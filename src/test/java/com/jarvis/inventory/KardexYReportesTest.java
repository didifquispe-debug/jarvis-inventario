package com.jarvis.inventory;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Kardex (entradas, salidas y saldo de cada movimiento), inventario valorizado y reportes en Excel. */
class KardexYReportesTest {

    @TempDir Path dir;

    private InventoryService nuevo() { return new InventoryService(dir.resolve("inv.json").toString()); }

    private static BigDecimal n(String s) { return new BigDecimal(s); }

    private static Compras.NuevaCompra compra(String doc, String producto, String cantidad, String costo) {
        return new Compras.NuevaCompra("20100047218", "Distribuidora Andina SAC", "factura", doc, null, "exonerado", null,
                List.of(new Compras.LineaCompra(producto, n(cantidad), n(costo))));
    }

    /** Polera con stock 10 a costo 5, y luego compra, venta, ajuste, corrección, anulación, edición y anulación de compra. */
    private InventoryService conMovimientos() {
        InventoryService inv = nuevo();
        assertTrue(inv.crearProducto("Polera roja M", n("45"), n("10"), "unidades", n("5"), null,
                new Producto.Ficha("Poleras", null, null, n("5"))).ok());
        assertTrue(inv.registrarCompra(compra("F001-1", "Polera roja M", "20", "6")).ok());           // 30
        assertTrue(inv.salida(n("4"), "polera roja m", false).startsWith("Venta registrada"));         // 26
        assertTrue(inv.ajustarStock("Polera roja M", n("-1")).ok());                                    // 25
        assertTrue(inv.corregirUltimaVenta("Polera roja M", n("2"), true).ok());                         // 27
        assertTrue(inv.anularUltimaVenta().ok());                                                        // 29
        assertTrue(inv.editarProducto("Polera roja M", true,
                new InventoryService.Cambios(null, null, n("40"), null, null, null)).ok());             // 40
        assertTrue(inv.anularCompra("CP-00000001").ok());                                                // 20
        return inv;
    }

    @Test void cadaMovimientoTieneSuEntradaSalidaYSaldo() {
        conMovimientos();
        InventoryService.Kardex k = nuevo().kardex("Polera roja M", 100);   // releído de la base
        assertEquals(List.of("ENTRADA", "COMPRA", "VENTA", "AJUSTE", "CORRECCION_VENTA", "ANULACION", "AJUSTE", "ANULACION_COMPRA"),
                k.filas().stream().map(FilaKardex::tipo).toList());
        assertEquals(List.of(n("10"), n("20"), n("0"), n("0"), n("2"), n("2"), n("11"), n("0")),
                k.filas().stream().map(FilaKardex::entrada).toList());
        assertEquals(List.of(n("0"), n("0"), n("4"), n("1"), n("0"), n("0"), n("0"), n("20")),
                k.filas().stream().map(FilaKardex::salida).toList());
        assertEquals(List.of(n("10"), n("30"), n("26"), n("25"), n("27"), n("29"), n("40"), n("20")),
                k.filas().stream().map(FilaKardex::saldo).toList(), "el saldo es el stock que quedó tras cada movimiento");
        assertEquals(n("20"), k.stock(), "el último saldo coincide con el stock");
        assertEquals(n("5.67"), k.filas().get(1).costo(), "costo promedio tras la compra: (10×5 + 20×6) / 30");
    }

    @Test void losMovimientosQueNoSonDeStockNoEntranAlKardex() {
        InventoryService inv = conMovimientos();
        inv.cambiarPrecio("polera roja m", n("49"));
        assertEquals(8, inv.kardex("Polera roja M", 100).filas().size());
        assertNull(inv.kardex("No existe", 10));
    }

    @Test void movimientosAntiguosSinSaldo() throws Exception {
        InventoryService inv = nuevo();
        assertTrue(inv.crearProducto("Vela", n("2"), n("10"), "unidades", null, null).ok());
        try (Connection cx = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("inv.db").toAbsolutePath())) {
            cx.createStatement().execute("INSERT INTO movimiento (fecha, tipo, producto, cantidad_mil) VALUES ('2026-01-05T10:00:00', 'VENTA', 'Vela', 3000)");
        }
        FilaKardex antigua = nuevo().kardex("Vela", 10).filas().get(1);
        assertEquals(n("3"), antigua.salida(), "la salida sale del tipo y la cantidad");
        assertNull(antigua.saldo(), "el saldo no se inventa");
    }

    @Test void inventarioValorizado() {
        InventoryService inv = conMovimientos();
        assertTrue(inv.crearProducto("Gorra negra", n("15"), n("0"), "unidades", n("2"), null).ok());   // sin stock, sin costo
        InventoryService.Valorizado v = inv.valorizado();
        assertEquals(2, v.productos());
        assertEquals(1, v.sinCosto());
        assertEquals(1, v.sinStock());
        InventoryService.FilaValorizada polera = v.filas().stream().filter(f -> f.producto().startsWith("Polera")).findFirst().orElseThrow();
        assertEquals(n("113.40"), polera.valorCosto(), "20 × 5.67");
        assertEquals(n("900.00"), polera.valorVenta(), "20 × 45");
        assertEquals(n("786.60"), v.margen());
        assertEquals("SIN_STOCK", v.filas().stream().filter(f -> f.producto().startsWith("Gorra")).findFirst().orElseThrow().estado());
    }

    @Test void excelDeInventarioYKardex() throws Exception {
        InventoryService inv = conMovimientos();
        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(ReportesExcel.inventario(inv.valorizado(), inv.kardexCompleto(), "Tienda X")))) {
            Sheet hoja = libro.getSheet("Inventario");
            assertEquals("Inventario valorizado", hoja.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Producto", hoja.getRow(3).getCell(0).getStringCellValue());
            Row polera = hoja.getRow(4);
            assertEquals("Polera roja M", polera.getCell(0).getStringCellValue());
            assertEquals(113.40, polera.getCell(8).getNumericCellValue(), 0.001);
            assertEquals("SUBTOTAL(109,I5:I5)", hoja.getRow(5).getCell(8).getCellFormula(), "totales que respetan el filtro");
            Sheet kardex = libro.getSheet("Kardex");
            assertEquals(3 + 8, kardex.getLastRowNum(), "cabecera en la fila 4 y 8 movimientos (sin fila de totales)");
            assertEquals("Compra", kardex.getRow(5).getCell(2).getStringCellValue());
        }
        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(ReportesExcel.kardex(inv.kardex("Polera roja M", 100), "")))) {
            Sheet k = libro.getSheet("Kardex");
            assertEquals(20, k.getRow(11).getCell(5).getNumericCellValue(), 0.001, "saldo final");
        }
    }

    @Test void excelDeVentasConGananciaSinIgv() throws Exception {
        InventoryService inv = Ejemplos.sembrar(nuevo());
        inv.editarProducto("Foco LED 9W", true, new InventoryService.Cambios(null, null, null, null, null, null,
                new Producto.Ficha(null, null, null, n("5")), false));
        inv.salida(n("2"), "foco", false);                                    // 15.00 con IGV incluido
        inv.salida(n("1"), "cemento", false);                                 // sin costo
        inv.salida(n("1"), "foco", false);
        inv.anularUltimaVenta();
        String hoy = LocalDate.now().toString();
        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(ReportesExcel.ventas(inv.ventasPeriodo(hoy, hoy), hoy, hoy, "")))) {
            Sheet ventas = libro.getSheet("Ventas");
            assertEquals(3, ventas.getLastRowNum() - 4, "3 comprobantes y la fila de totales");
            assertEquals(0, ventas.getRow(6).getCell(8).getNumericCellValue(), 0.001, "el anulado no suma");
            assertTrue(ventas.getRow(6).getCell(9).getStringCellValue().startsWith("ANULADO"));
            Sheet det = libro.getSheet("Detalle");
            Row foco = det.getRow(4);
            assertEquals("Foco LED 9W", foco.getCell(3).getStringCellValue());
            assertEquals(12.71, foco.getCell(8).getNumericCellValue(), 0.001, "15 / 1.18");
            assertEquals(10.00, foco.getCell(10).getNumericCellValue(), 0.001);
            assertEquals(2.71, foco.getCell(11).getNumericCellValue(), 0.001, "ganancia sin IGV");
            assertEquals("sin costo", det.getRow(5).getCell(9).getStringCellValue());
            assertEquals(6, det.getLastRowNum(), "sin las líneas del comprobante anulado");
        }
        assertTrue(inv.ventasPeriodo(LocalDate.now().plusDays(1).toString(), "").isEmpty());
    }

    @Test void excelDeCompras() throws Exception {
        InventoryService inv = Ejemplos.sembrar(nuevo());
        inv.registrarCompra(compra("F001-1", "Foco LED 9W", "10", "4.5"));
        inv.registrarCompra(compra("F001-2", "Foco LED 9W", "3", "4.5"));
        inv.anularCompra("CP-00000002");
        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(
                ReportesExcel.compras(inv.compras().listarConDetalle("", "", "", 100), "", "", "")))) {
            Sheet compras = libro.getSheet("Compras");
            assertEquals("CP-00000001", compras.getRow(4).getCell(1).getStringCellValue(), "de la más antigua a la más reciente");
            assertEquals(45, compras.getRow(4).getCell(9).getNumericCellValue(), 0.001);
            assertEquals(0, compras.getRow(5).getCell(9).getNumericCellValue(), 0.001, "la anulada no suma");
            assertEquals(1, libro.getSheet("Detalle").getLastRowNum() - 4, "una línea y la fila de totales");
        }
    }
}
