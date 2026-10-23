package com.jarvis.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Guardado atómico con respaldo, recuperación de archivos dañados, formato viejo y archivo de ventas. */
class PersistenciaTest {

    @TempDir Path dir;

    private Path archivo() { return dir.resolve("inv.json"); }

    private InventoryService nuevo() { return new InventoryService(archivo().toString(), 90); }

    /** Inventario con los productos de prueba (la primera vez; después ya están en el archivo). */
    private InventoryService conEjemplos() { return Ejemplos.sembrar(nuevo()); }

    private static BigDecimal stock(InventoryService inv, String prefijo) {
        return inv.productos().stream().filter(p -> p.nombre.startsWith(prefijo)).findFirst().orElseThrow().stock;
    }

    private List<Path> archivos(String patron) throws Exception {
        try (Stream<Path> s = Files.list(dir)) { return s.filter(p -> p.getFileName().toString().matches(patron)).toList(); }
    }

    @Test void inventarioNuevoEmpiezaVacio() throws Exception {
        InventoryService inv = nuevo();
        assertEquals(0, inv.productos().size(), "sin productos de ejemplo");
        assertTrue(Files.exists(archivo()), "el archivo se crea vacío");
        assertEquals("El inventario está vacío, señor.", inv.inventario());
        assertEquals(0, nuevo().productos().size(), "y sigue vacío al reiniciar");
    }

    @Test void guardaConRespaldoYSinDejarTemporales() throws Exception {
        InventoryService inv = conEjemplos();
        String antes = Files.readString(archivo());
        inv.salida(new BigDecimal("2"), "cemento", false);
        assertEquals(antes, Files.readString(dir.resolve("inv.json.bak")), ".bak = versión anterior");
        assertFalse(Files.exists(dir.resolve("inv.json.tmp")), "el .tmp se reemplaza atómicamente");
        assertEquals(new BigDecimal("48"), stock(nuevo(), "Cemento"), "lo guardado se vuelve a leer igual");
    }

    @Test void archivoDanadoSeRecuperaDelRespaldo() throws Exception {
        InventoryService inv = conEjemplos();
        inv.salida(new BigDecimal("5"), "cemento", false);   // .bak = 50, archivo = 45
        inv.salida(new BigDecimal("1"), "cemento", false);   // .bak = 45, archivo = 44
        Files.writeString(archivo(), "{ \"productos\": [ {\"nombre\": \"Cem");   // corte de luz a mitad (sin guardado atómico)

        InventoryService recuperado = nuevo();
        assertEquals(new BigDecimal("45"), stock(recuperado, "Cemento"), "vuelve a la última versión sana");
        assertEquals(1, archivos("inv\\.json\\.danado-.*").size(), "el dañado se conserva para revisarlo");
        assertEquals(new BigDecimal("45"), stock(nuevo(), "Cemento"), "el archivo principal quedó reparado");
        assertTrue(Files.readString(dir.resolve("inv.json.bak")).contains("\"stock\" : 45"), "el respaldo sano no se pisa con el dañado");
    }

    @Test void archivoVacioTambienSeRecupera() throws Exception {
        conEjemplos().salida(new BigDecimal("3"), "foco", false);   // .bak = 30, archivo = 27
        Files.write(archivo(), new byte[0]);
        assertEquals(new BigDecimal("30"), stock(nuevo(), "Foco"));
    }

    @Test void danadoSinRespaldoNoSePierde() throws Exception {
        Files.writeString(archivo(), "esto no es json");
        InventoryService inv = nuevo();
        assertTrue(inv.productos().isEmpty(), "arranca vacío");
        Path danado = archivos("inv\\.json\\.danado-.*").get(0);
        assertEquals("esto no es json", Files.readString(danado));
    }

    @Test void leeElFormatoViejoConEnterosYDoubles() throws Exception {
        String ayer = LocalDate.now().minusDays(1).toString();
        Files.writeString(archivo(), """
                {"productos":[{"nombre":"Cemento","alias":["cemento"],"stock":10,"stockMin":2,"precio":28.0,"unidad":"bolsas"}],
                 "ventas":[{"numero":7,"fecha":"%s","hora":"10:00","producto":"Cemento","cantidad":1,"unidad":"bolsas",
                            "precioUnitario":28.0,"monto":28.0}]}""".formatted(ayer));
        InventoryService inv = nuevo();
        Producto p = inv.productos().get(0);
        assertEquals(new BigDecimal("10"), p.stock);
        assertEquals(new BigDecimal("28.00"), p.precio);
        assertTrue(inv.salida(BigDecimal.ONE, "cemento", false).contains("Comprobante número 8"), "la numeración sigue");
    }

    @Test void ventasAntiguasSeArchivanYLaNumeracionSigue() throws Exception {
        String viejo = LocalDate.now().minusDays(200).toString();
        String reciente = LocalDate.now().minusDays(3).toString();
        Files.writeString(archivo(), """
                {"productos":[{"nombre":"Foco","alias":["foco"],"stock":10,"stockMin":1,"precio":7.5,"unidad":"unidades"}],
                 "ventas":[{"numero":41,"fecha":"%s","hora":"09:00","producto":"Foco","cantidad":1,"unidad":"unidades","precioUnitario":7.5,"monto":7.5},
                           {"numero":42,"fecha":"%s","hora":"09:00","producto":"Foco","cantidad":2,"unidad":"unidades","precioUnitario":7.5,"monto":15}]}"""
                .formatted(viejo, reciente));
        InventoryService inv = nuevo();

        Path archivoMes = inv.carpetaHistorial().resolve("ventas-" + viejo.substring(0, 7) + ".jsonl");
        assertTrue(Files.readString(archivoMes).contains("\"numero\":41"), "la venta vieja pasó al archivo del mes");
        String principal = Files.readString(archivo());
        assertFalse(principal.contains("\"numero\" : 41"), "y salió del archivo principal");
        assertTrue(principal.contains("\"numero\" : 42"), "las recientes se quedan");
        assertTrue(inv.salida(BigDecimal.ONE, "foco", false).contains("Comprobante número 43"));
    }

    @Test void historialDeMovimientos() {
        InventoryService inv = conEjemplos();
        inv.entrada(new BigDecimal("5"), "cemento", null, null, false);
        inv.salida(new BigDecimal("2"), "foco", false);
        inv.cambiarPrecio("foco", new BigDecimal("8"));
        List<Movimiento> m = inv.movimientos(3);
        assertEquals(List.of("PRECIO", "VENTA", "ENTRADA"), m.stream().map(Movimiento::tipo).toList(), "del más reciente al más antiguo");
        assertEquals("Foco LED 9W", m.get(1).producto());
        assertEquals(new BigDecimal("2"), m.get(1).cantidad());
        assertEquals("local", m.get(1).usuario());
        assertEquals("7.50 -> 8.00", m.get(0).detalle());
        assertEquals(2, nuevo().movimientos(2).size(), "el historial sobrevive al reinicio");
    }
}
