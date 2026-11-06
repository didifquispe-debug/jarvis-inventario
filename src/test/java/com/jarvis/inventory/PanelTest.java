/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PanelTest {

    @TempDir Path dir;

    private static BigDecimal n(String s) { return new BigDecimal(s); }

    private InventoryService conVentas() {
        InventoryService inv = new InventoryService(dir.resolve("inv.json").toString());
        assertTrue(inv.crearProducto("Polera azul", n("59"), n("10"), "unidades", n("3"), null,
                new Producto.Ficha("Poleras", null, null, n("30"))).ok());
        assertTrue(inv.crearProducto("Gorra", n("20"), n("6"), "unidades", n("5"), null).ok());
        assertTrue(inv.crearProducto("Vela", n("2"), n("0"), "unidades", n("2"), null).ok());
        assertTrue(inv.crearProducto("Taza", n("15"), n("8"), "unidades", n("2"), null).ok());
        assertTrue(inv.salida(n("2"), "polera azul", false).startsWith("Venta registrada"));
        assertTrue(inv.salida(n("1"), "gorra", false).startsWith("Venta registrada"));
        return inv;
    }

    @Test void gananciaSinIgvContraElCosto() {
        InventoryService.Panel p = conVentas().panel(30);
        InventoryService.Periodo hoy = p.deHoy();
        assertEquals(2, hoy.ventas());
        assertEquals(n("138.00"), hoy.total(), "118 de poleras + 20 de la gorra, con IGV");
        assertEquals(n("60.00"), hoy.costo(), "2 poleras a 30");
        assertEquals(n("40.00"), hoy.ganancia(), "118 / 1.18 = 100 de venta sin IGV, menos 60 de costo");
        assertEquals(1, hoy.lineasSinCosto(), "la gorra no tiene costo: no se inventa ganancia");
        assertEquals(hoy.total(), p.semana().total());
        assertEquals(hoy.total(), p.mes().total());
    }

    @Test void lasVentasAnuladasNoCuentan() {
        InventoryService inv = conVentas();
        assertTrue(inv.anularUltimaVenta().ok());
        InventoryService.Periodo hoy = inv.panel(30).deHoy();
        assertEquals(1, hoy.ventas());
        assertEquals(1, hoy.anuladas());
        assertEquals(n("118.00"), hoy.total());
        assertEquals(0, hoy.lineasSinCosto());
    }

    @Test void ventasPorDiaYMasVendidos() {
        InventoryService.Panel p = conVentas().panel(7);
        assertEquals(7, p.porDia().size(), "un punto por día, también los días sin ventas");
        InventoryService.DiaPanel ultimo = p.porDia().get(6);
        assertEquals(LocalDate.now().toString(), ultimo.fecha());
        assertEquals(2, ultimo.ventas());
        assertEquals(n("138.00"), ultimo.total());
        assertEquals(n("0.00"), p.porDia().get(0).total());
        assertEquals(List.of("Polera azul", "Gorra"), p.masVendidos().stream().map(InventoryService.MasVendido::producto).toList(),
                "ordenados por lo vendido en soles");
        assertEquals(n("2"), p.masVendidos().get(0).cantidad());
        assertEquals(n("40.00"), p.masVendidos().get(0).ganancia());
        assertNull(p.masVendidos().get(1).ganancia(), "la gorra no tiene costo: su ganancia es desconocida, no cero");
    }

    @Test void alertasDeStockYProductosSinMovimiento() {
        InventoryService.Panel p = conVentas().panel(30);
        assertEquals(List.of("Vela", "Gorra"), p.alertas().stream().map(InventoryService.Alerta::producto).toList(),
                "primero lo que no tiene stock");
        assertEquals("SIN_STOCK", p.alertas().get(0).estado());
        assertEquals(List.of("Taza"), p.sinMovimiento().stream().map(InventoryService.SinMovimiento::producto).toList(),
                "la vela no tiene stock: no es mercadería parada");
        assertNull(p.sinMovimiento().get(0).ultimaVenta());
    }

    @Test void loVendidoHaceMasDeUnMesQuedaSinMovimiento() {
        InventoryService inv = conVentas();
        LocalDate despues = LocalDate.now().plusDays(InventoryService.DIAS_SIN_MOVIMIENTO + 5L);
        InventoryService.Panel p = inv.panel(despues, 7);
        assertEquals(0, p.deHoy().ventas());
        assertEquals(n("0.00"), p.rango().total());
        List<String> quietos = p.sinMovimiento().stream().map(InventoryService.SinMovimiento::producto).toList();
        assertEquals(List.of("Polera azul", "Taza", "Gorra"), quietos, "de mayor a menor valor parado");
        assertEquals(LocalDate.now().toString(), p.sinMovimiento().get(0).ultimaVenta());
    }

    @Test void elRangoSeLimita() {
        InventoryService inv = conVentas();
        assertEquals(7, inv.panel(1).porDia().size());
        assertEquals(366, inv.panel(10_000).porDia().size());
    }
}
