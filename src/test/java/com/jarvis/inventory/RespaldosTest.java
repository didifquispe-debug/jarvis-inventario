/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import com.jarvis.security.ClientSession;
import com.jarvis.security.Rol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RespaldosTest {

    @TempDir Path dir;
    @TempDir Path usb;

    private DYSAInventoryService nuevo() { return new DYSAInventoryService(dir.resolve("inv.json").toString()); }

    private static BigDecimal stock(DYSAInventoryService inv, String prefijo) {
        return inv.productos().stream().filter(p -> p.nombre.startsWith(prefijo)).findFirst().orElseThrow().stock;
    }

    private static String manual(DYSAInventoryService inv) throws Exception {
        return inv.respaldos().stream().filter(r -> r.tipo().equals("MANUAL")).findFirst().orElseThrow().nombre();
    }

    @Test void listaElRespaldoDiarioYHaceUnoManual() throws Exception {
        DYSAInventoryService inv = Ejemplos.sembrar(nuevo());
        assertTrue(inv.respaldos().stream().anyMatch(r -> r.tipo().equals("DIARIO")), "el diario se hace solo");
        DYSAInventoryService.Resultado r = inv.respaldarAhora();
        assertTrue(r.ok(), r.mensaje());
        String nombre = manual(inv);
        assertTrue(nombre.matches("inv\\.db\\.respaldo-manual-\\d{8}-\\d{6}"), nombre);
        assertTrue(Files.size(inv.archivoRespaldo(nombre)) > 0);
    }

    @Test void restaurarDevuelveElInventarioDeLaCopiaYGuardaElActual() throws Exception {
        DYSAInventoryService inv = Ejemplos.sembrar(nuevo());
        BigDecimal antes = stock(inv, "Cemento");
        assertTrue(inv.respaldarAhora().ok());
        String copia = manual(inv);

        inv.salida(new BigDecimal("3"), "cemento", false);
        assertEquals(antes.subtract(new BigDecimal("3")), stock(inv, "Cemento"));

        DYSAInventoryService.Resultado r = inv.restaurar(copia);
        assertTrue(r.ok(), r.mensaje());
        assertEquals(antes, stock(inv, "Cemento"), "vuelve al stock de la copia");
        assertEquals(antes, stock(nuevo(), "Cemento"), "y queda guardado en la base");

        List<DYSAInventoryService.Respaldo> lista = inv.respaldos();
        DYSAInventoryService.Respaldo previo = lista.stream().filter(x -> x.tipo().equals("ANTES_DE_RESTAURAR")).findFirst().orElseThrow();
        assertTrue(r.mensaje().contains(previo.nombre()));
        assertTrue(inv.restaurar(previo.nombre()).ok(), "se puede deshacer");
        assertEquals(antes.subtract(new BigDecimal("3")), stock(inv, "Cemento"));

        inv.salida(new BigDecimal("1"), "cemento", false);
        assertEquals(antes.subtract(new BigDecimal("4")), stock(nuevo(), "Cemento"), "después de restaurar se sigue guardando bien");
    }

    @Test void unRespaldoDanadoNoTocaElInventario() throws Exception {
        DYSAInventoryService inv = Ejemplos.sembrar(nuevo());
        assertTrue(inv.respaldarAhora().ok());
        String copia = manual(inv);
        Files.writeString(inv.archivoRespaldo(copia), "esto no es una base de datos");
        BigDecimal antes = stock(inv, "Cemento");

        DYSAInventoryService.Resultado r = inv.restaurar(copia);
        assertFalse(r.ok());
        assertTrue(r.mensaje().contains("sigue como estaba"), r.mensaje());
        assertEquals(antes, stock(nuevo(), "Cemento"));
        assertTrue(inv.respaldos().stream().noneMatch(x -> x.tipo().equals("ANTES_DE_RESTAURAR")));
    }

    @Test void soloSeRestauranArchivosDeLaLista() throws Exception {
        DYSAInventoryService inv = Ejemplos.sembrar(nuevo());
        assertEquals(DYSAInventoryService.Estado.NO_ENCONTRADO, inv.restaurar("../../etc/passwd").estado());
        assertEquals(DYSAInventoryService.Estado.NO_ENCONTRADO, inv.restaurar("inv.db").estado());
        assertNull(inv.archivoRespaldo("inv.db"));
        assertNull(inv.archivoRespaldo(null));
    }

    @Test void soloElAdministradorManejaLosRespaldos() throws Exception {
        DYSAInventoryService inv = Ejemplos.sembrar(nuevo());
        assertTrue(inv.respaldarAhora().ok());
        String copia = manual(inv);
        for (Rol rol : new Rol[]{Rol.CAJERO, Rol.ALMACEN}) {
            ClientSession.como("p", "p@test", rol, () -> {
                assertEquals(DYSAInventoryService.Estado.PROHIBIDO, inv.respaldarAhora().estado());
                assertEquals(DYSAInventoryService.Estado.PROHIBIDO, inv.restaurar(copia).estado());
                assertEquals(DYSAInventoryService.Estado.PROHIBIDO, inv.usarCarpetaExtra(usb.toString()).estado());
                return null;
            });
        }
    }

    @Test void carpetaExtraRecibeCopiasYSeRecuerda() throws Exception {
        DYSAInventoryService inv = Ejemplos.sembrar(nuevo());
        Path destino = usb.resolve("JARVIS");
        DYSAInventoryService.Resultado r = inv.usarCarpetaExtra(destino.toString());
        assertTrue(r.ok(), r.mensaje());
        assertTrue(inv.respaldarAhora().ok());
        try (Stream<Path> s = Files.list(destino)) {
            assertTrue(s.anyMatch(p -> p.getFileName().toString().matches("jarvis-respaldo-\\d{8}-\\d{6}\\.db")));
        }
        assertEquals(destino.toString(), nuevo().carpetaExtra(), "se recuerda al reiniciar");

        assertTrue(inv.usarCarpetaExtra("").ok());
        assertEquals("", inv.carpetaExtra());
    }

    @Test void carpetaExtraInvalidaSeRechaza() {
        DYSAInventoryService inv = nuevo();
        assertFalse(inv.usarCarpetaExtra("relativa/sin/unidad").ok());
        assertFalse(inv.usarCarpetaExtra(dir.toString()).ok(), "no la misma carpeta del inventario");
        assertEquals("", inv.carpetaExtra());
    }
}
