/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PersistenciaTest {

    @TempDir Path dir;

    private Path json() { return dir.resolve("inv.json"); }

    private Path base() { return dir.resolve("inv.db"); }

    private InventoryService nuevo() { return new InventoryService(json().toString()); }

    private InventoryService conEjemplos() { return Ejemplos.sembrar(nuevo()); }

    private static BigDecimal stock(InventoryService inv, String prefijo) {
        return inv.productos().stream().filter(p -> p.nombre.startsWith(prefijo)).findFirst().orElseThrow().stock;
    }

    private List<Path> archivos(String patron) throws Exception {
        try (Stream<Path> s = Files.list(dir)) { return s.filter(p -> p.getFileName().toString().matches(patron)).sorted().toList(); }
    }

    private void borrarRespaldos() throws Exception {
        for (Path p : archivos("inv\\.db\\.respaldo-.*")) Files.delete(p);
    }

    @Test void inventarioNuevoEmpiezaVacio() {
        InventoryService inv = nuevo();
        assertEquals(0, inv.productos().size(), "sin productos de ejemplo");
        assertTrue(Files.exists(base()), "se crea la base de datos");
        assertFalse(Files.exists(json()), "ya no se usa el archivo JSON");
        assertEquals("El inventario está vacío, señor.", inv.inventario());
        assertEquals(0, nuevo().productos().size(), "y sigue vacío al reiniciar");
    }

    @Test void loGuardadoSeVuelveALeerIgual() {
        InventoryService inv = conEjemplos();
        inv.salida(new BigDecimal("2"), "cemento", false);
        inv.salida(new BigDecimal("2.5"), "cable", false);
        inv.cambiarPrecio("foco", new BigDecimal("8.10"));

        InventoryService releido = nuevo();
        assertEquals(new BigDecimal("48"), stock(releido, "Cemento"));
        assertEquals(new BigDecimal("47.5"), stock(releido, "Cable"), "las cantidades con decimales no pierden nada");
        Producto foco = releido.productos().stream().filter(p -> p.nombre.startsWith("Foco")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("8.10"), foco.precio);
        assertEquals(List.of("foco", "foco led", "led", "bombilla", "foco led 9w").stream().sorted().toList(),
                foco.alias.stream().sorted().toList(), "los otros nombres también");
        assertTrue(releido.salida(BigDecimal.ONE, "foco", false).contains("Boleta número 3"), "la numeración sigue");
        assertTrue(releido.ultimoComprobante().contains("número 3"));
    }

    @Test void anularYCorregirSeGuardan() {
        InventoryService inv = conEjemplos();
        inv.venderVarios(List.of(new BigDecimal("2"), new BigDecimal("3")), List.of("cemento", "foco"));
        assertTrue(inv.corregirUltimaVenta("foco", BigDecimal.ONE, false).ok());
        assertTrue(inv.anularUltimaVenta().ok());

        InventoryService releido = nuevo();
        assertEquals(new BigDecimal("50"), stock(releido, "Cemento"), "la anulación devolvió el stock");
        assertEquals(new BigDecimal("30"), stock(releido, "Foco"));
        assertTrue(releido.ultimoComprobante().contains("ANULADA"));
        assertEquals("Cierre del día: no se registraron ventas hoy, señor. Ventas anuladas: 1.", releido.cierre());
    }

    @Test void importaElInventarioJsonAntiguoUnaSolaVez() throws Exception {
        String ayer = LocalDate.now().minusDays(1).toString();
        Files.writeString(json(), """
                {"productos":[{"nombre":"Cemento","alias":["cemento"],"stock":10,"stockMin":2,"precio":28.0,"unidad":"bolsas"}],
                 "ventas":[{"numero":7,"fecha":"%s","hora":"10:00","producto":"Cemento","cantidad":1,"unidad":"bolsas",
                            "precioUnitario":28.0,"monto":28.0}]}""".formatted(ayer));
        InventoryService inv = nuevo();
        Producto p = inv.productos().get(0);
        assertEquals(new BigDecimal("10"), p.stock);
        assertEquals(new BigDecimal("28.00"), p.precio);
        assertFalse(Files.exists(json()), "el JSON queda renombrado para no volver a importarlo");
        assertTrue(Files.exists(dir.resolve("inv.json.migrado")), "pero no se borra");
        InventoryService.Opciones nota = new InventoryService.Opciones(TipoComprobante.NOTA_VENTA, null, null);
        assertTrue(inv.salida(BigDecimal.ONE, "cemento", false, nota).contains("Nota de venta número 8"),
                "las ventas antiguas eran notas de venta: su numeración sigue");
        assertTrue(inv.salida(BigDecimal.ONE, "cemento", false).contains("Boleta número 1"), "las boletas empiezan en 1");

        Files.copy(dir.resolve("inv.json.migrado"), json());
        assertEquals(new BigDecimal("8"), stock(nuevo(), "Cemento"), "…la base ya tiene datos y no se importa de nuevo");
    }

    @Test void siElJsonEstaDanadoImportaSuRespaldo() throws Exception {
        Files.writeString(json(), "{ \"productos\": [ {\"nombre\": \"Cem");
        Files.writeString(dir.resolve("inv.json.bak"), """
                {"productos":[{"nombre":"Cemento","alias":["cemento"],"stock":45,"stockMin":2,"precio":28,"unidad":"bolsas"}]}""");
        assertEquals(new BigDecimal("45"), stock(nuevo(), "Cemento"));
    }

    @Test void importaElHistorialYLasVentasArchivadas() throws Exception {
        String viejo = LocalDate.now().minusDays(200).toString();
        Files.writeString(json(), """
                {"productos":[{"nombre":"Foco","alias":["foco"],"stock":10,"stockMin":1,"precio":7.5,"unidad":"unidades"}],
                 "ventas":[{"numero":42,"fecha":"%s","hora":"09:00","producto":"Foco","cantidad":2,"unidad":"unidades","precioUnitario":7.5,"monto":15}]}"""
                .formatted(LocalDate.now().minusDays(3)));
        Path historial = Files.createDirectories(dir.resolve("inv-historial"));
        Files.writeString(historial.resolve("ventas-" + viejo.substring(0, 7) + ".jsonl"),
                "{\"numero\":41,\"fecha\":\"" + viejo + "\",\"hora\":\"09:00\",\"producto\":\"Foco\",\"cantidad\":1,"
                        + "\"unidad\":\"unidades\",\"precioUnitario\":7.5,\"monto\":7.5}\n");
        Files.writeString(historial.resolve("movimientos-" + viejo.substring(0, 7) + ".jsonl"),
                "{\"fecha\":\"" + viejo + "T09:00:00\",\"tipo\":\"VENTA\",\"producto\":\"Foco\",\"cantidad\":1,"
                        + "\"detalle\":\"comprobante 41\",\"usuario\":\"local\"}\n{\"fecha\":\"a medias");

        InventoryService inv = nuevo();
        assertEquals("comprobante 41", inv.movimientos(10).get(0).detalle(), "el historial antiguo pasó a la base");
        assertTrue(inv.salida(BigDecimal.ONE, "foco", false, new InventoryService.Opciones(TipoComprobante.NOTA_VENTA, null, null))
                .contains("Nota de venta número 43"));
        assertTrue(Files.exists(historial.resolve("ventas-" + viejo.substring(0, 7) + ".jsonl")), "los archivos se conservan");
    }

    @Test void respaldoDiarioYRecuperacionDeUnaBaseDanada() throws Exception {
        conEjemplos().salida(new BigDecimal("5"), "cemento", false);
        borrarRespaldos();
        nuevo();
        assertEquals(1, archivos("inv\\.db\\.respaldo-\\d{8}").size());

        Files.writeString(base(), "esto ya no es una base de datos");
        InventoryService recuperado = nuevo();
        assertEquals(new BigDecimal("45"), stock(recuperado, "Cemento"), "vuelve al último respaldo");
        Path danado = archivos("inv\\.db\\.danado-.*").get(0);
        assertEquals("esto ya no es una base de datos", Files.readString(danado), "el dañado se conserva para revisarlo");
    }

    @Test void baseDanadaSinRespaldoArrancaVacia() throws Exception {
        Files.writeString(base(), "esto no es una base");
        assertTrue(nuevo().productos().isEmpty());
        assertEquals(1, archivos("inv\\.db\\.danado-.*").size());
    }

    @Test void seConservanSoloLosUltimosSieteRespaldos() throws Exception {
        nuevo();
        borrarRespaldos();
        for (int i = 1; i <= 9; i++) Files.writeString(dir.resolve("inv.db.respaldo-2020010" + i), "viejo");
        nuevo();
        List<Path> quedan = archivos("inv\\.db\\.respaldo-\\d{8}");
        assertEquals(BaseDatos.RESPALDOS, quedan.size());
        assertTrue(quedan.get(quedan.size() - 1).getFileName().toString().endsWith(LocalDate.now().toString().replace("-", "")),
                "el de hoy siempre queda");
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

    @Test void productoEliminadoYRenombradoNoRompenLasVentas() {
        InventoryService inv = conEjemplos();
        inv.salida(BigDecimal.ONE, "disco", false);
        assertTrue(inv.editarProducto("Foco LED 9W", true, new InventoryService.Cambios("Foco LED 10W", null, null, null, null, null)).ok());
        assertTrue(inv.eliminarProducto("Disco de corte 4 1/2", true).ok());
        InventoryService releido = nuevo();
        assertTrue(releido.productos().stream().noneMatch(p -> p.nombre.startsWith("Disco")));
        assertTrue(releido.productos().stream().anyMatch(p -> p.nombre.equals("Foco LED 10W")));
        assertTrue(releido.ultimoComprobante().contains("número 1"), "la venta del producto eliminado se conserva");
    }

    @Test void muchosProductosYVentasSiguenSiendoRapidos() {
        InventoryService inv = nuevo();
        List<ListaProductos.Fila> filas = new ArrayList<>();
        for (int i = 1; i <= 3000; i++)
            filas.add(new ListaProductos.Fila(i, "Producto de prueba " + i, new BigDecimal("2.50"), new BigDecimal("100"),
                    "unidades", null, Producto.Ficha.VACIA));
        long t0 = System.nanoTime();
        assertEquals(3000, inv.crearProductos(filas, List.of()).agregados());
        long t1 = System.nanoTime();
        for (int i = 1; i <= 200; i++) inv.salida(BigDecimal.ONE, "Producto de prueba " + i, false);
        long t2 = System.nanoTime();
        assertEquals(new BigDecimal("99"), stock(nuevo(), "Producto de prueba 200"));
        long msAltas = (t1 - t0) / 1_000_000, msVentas = (t2 - t1) / 1_000_000;
        System.out.println("Volumen: 3000 altas en " + msAltas + " ms; 200 ventas en " + msVentas + " ms ("
                + msVentas / 200 + " ms por venta)");
        assertTrue(msAltas + msVentas < 60_000, "3000 altas y 200 ventas tardaron " + (msAltas + msVentas) + " ms");
    }
}
