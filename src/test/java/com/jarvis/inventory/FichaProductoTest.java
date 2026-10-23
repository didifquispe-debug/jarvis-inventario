package com.jarvis.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Categoría, código interno, código de barras y costo de cada producto. */
class FichaProductoTest {

    @TempDir Path dir;

    private InventoryService nuevo() { return new InventoryService(dir.resolve("inv.json").toString(), 90); }

    private static Producto producto(InventoryService inv, String nombre) {
        return inv.productos().stream().filter(p -> p.nombre.equals(nombre)).findFirst().orElse(null);
    }

    private static Producto.Ficha ficha(String categoria, String codigo, String barras, String costo) {
        return new Producto.Ficha(categoria, codigo, barras, costo == null ? null : new BigDecimal(costo));
    }

    private static InventoryService.Cambios cambios(Producto.Ficha f, boolean quitarCosto) {
        return new InventoryService.Cambios(null, null, null, null, null, null, f, quitarCosto);
    }

    /** Zapatilla 40 con todos los datos y un polo sin ficha. */
    private InventoryService conProductos() {
        InventoryService inv = nuevo();
        assertTrue(inv.crearProducto("Zapatilla Nike 40", new BigDecimal("180"), new BigDecimal("5"), "pares", null, null,
                ficha("  Calzado ", "ZAP-NK-40", "7750182000123", "110")).ok());
        assertTrue(inv.crearProducto("Polo blanco M", new BigDecimal("25"), new BigDecimal("10"), "unidades", null, null).ok());
        return inv;
    }

    @Test void guardaLaFichaYSeConservaAlReiniciar() {
        conProductos();
        Producto z = producto(nuevo(), "Zapatilla Nike 40");
        assertEquals("Calzado", z.categoria, "sin espacios de más");
        assertEquals("ZAP-NK-40", z.codigo);
        assertEquals("7750182000123", z.codigoBarras, "un código de barras es texto: no pierde ceros ni dígitos");
        assertEquals(new BigDecimal("110.00"), z.costo);
        Producto polo = producto(nuevo(), "Polo blanco M");
        assertEquals("", polo.categoria);
        assertEquals("", polo.codigo);
        assertNull(polo.costo, "sin costo = no registrado, no 0");
    }

    @Test void archivoViejoSinFichaSeLeeSinErrores() throws Exception {
        Files.writeString(dir.resolve("inv.json"), """
                {"productos": [{"nombre": "Vela", "alias": ["vela"], "stock": 3, "stockMin": 1, "precio": 2, "unidad": "unidades"}],
                 "ventas": [{"numero": 1, "fecha": "2026-10-01", "hora": "10:00", "producto": "Vela", "cantidad": 1,
                             "unidad": "unidades", "precioUnitario": 2, "monto": 2}],
                 "ultimoNumero": 1}""");
        Producto vela = producto(nuevo(), "Vela");
        assertEquals("", vela.categoria);
        assertEquals("", vela.codigoBarras);
        assertNull(vela.costo);
    }

    @Test void noDejaRepetirCodigos() {
        InventoryService inv = conProductos();
        InventoryService.Resultado r = inv.crearProducto("Zapatilla Nike 41", new BigDecimal("180"), null, "pares", null, null,
                ficha(null, "zap-nk-40", null, null));
        assertFalse(r.ok(), "sin importar mayúsculas");
        assertTrue(r.mensaje().contains("Zapatilla Nike 40"), r.mensaje());
        assertFalse(inv.crearProducto("Zapatilla Nike 41", new BigDecimal("180"), null, "pares", null, null,
                ficha(null, "7750182000123", null, null)).ok(), "un código interno no puede ser el de barras de otro");
        assertNull(producto(inv, "Zapatilla Nike 41"), "no se crea nada");
    }

    @Test void rechazaCostoNegativoYTextosLargos() {
        InventoryService inv = nuevo();
        assertFalse(inv.crearProducto("Gorra", BigDecimal.TEN, null, "unidades", null, null, ficha(null, null, null, "-1")).ok());
        assertFalse(inv.crearProducto("Gorra", BigDecimal.TEN, null, "unidades", null, null, ficha("x".repeat(41), null, null, null)).ok());
        assertFalse(inv.crearProducto("Gorra", BigDecimal.TEN, null, "unidades", null, null, ficha(null, "x".repeat(41), null, null)).ok());
        assertTrue(inv.productos().isEmpty());
    }

    @Test void avisaCuandoElCostoSuperaAlPrecio() {
        InventoryService inv = nuevo();
        InventoryService.Resultado r = inv.crearProducto("Gorra", new BigDecimal("10"), null, "unidades", null, null,
                ficha(null, null, null, "12"));
        assertTrue(r.ok(), "se permite (liquidaciones), pero se avisa");
        assertTrue(r.mensaje().contains("Atención: el costo"), r.mensaje());
    }

    @Test void editaYBorraLaFicha() {
        InventoryService inv = conProductos();
        InventoryService.Resultado r = inv.editarProducto("Polo blanco M", true, cambios(ficha("Polos", "POL-B-M", "123", "14.5"), false));
        assertTrue(r.ok(), r.mensaje());
        assertTrue(r.mensaje().contains("margen de 10.50 soles"), r.mensaje());
        Producto polo = producto(nuevo(), "Polo blanco M");
        assertEquals("Polos", polo.categoria);
        assertEquals("POL-B-M", polo.codigo);
        assertEquals(new BigDecimal("14.50"), polo.costo);
        assertEquals("COSTO", inv.movimientos(1).get(0).tipo(), "cada cambio queda en el historial");

        assertTrue(inv.editarProducto("Polo blanco M", true, cambios(ficha("", null, "", null), true)).ok());
        polo = producto(nuevo(), "Polo blanco M");
        assertEquals("", polo.categoria, "\"\" borra");
        assertEquals("POL-B-M", polo.codigo, "null no toca");
        assertEquals("", polo.codigoBarras);
        assertNull(polo.costo, "quitarCosto lo borra");
    }

    @Test void alEditarNoPuedeTomarElCodigoDeOtroPeroSiConservarElSuyo() {
        InventoryService inv = conProductos();
        assertFalse(inv.editarProducto("Polo blanco M", true, cambios(ficha(null, "ZAP-NK-40", null, null), false)).ok());
        assertTrue(inv.editarProducto("Zapatilla Nike 40", true, cambios(ficha(null, "ZAP-NK-40", null, null), false)).ok(),
                "volver a guardar su propio código no es un duplicado");
    }

    @Test void encuentraElProductoPorSuCodigo() {
        InventoryService inv = conProductos();
        assertTrue(inv.consulta("7750182000123").contains("Zapatilla Nike 40"), "código de barras (escáner)");
        assertTrue(inv.consulta("zap-nk-40").contains("Zapatilla Nike 40"), "código interno, sin importar mayúsculas");
    }

    @Test void laVentaGuardaElCostoDelMomento() {
        InventoryService inv = conProductos();
        inv.salida(new BigDecimal("2"), "7750182000123", false);
        InventoryService.Venta v = inv.tomarComprobanteNuevo().items.get(0);
        assertEquals("Zapatilla Nike 40", v.producto);
        assertEquals(new BigDecimal("110.00"), v.costoUnitario);

        inv.editarProducto("Zapatilla Nike 40", true, cambios(ficha(null, null, null, "120"), false));
        inv.salida(new BigDecimal("1"), "Zapatilla Nike 40", false);
        assertEquals(new BigDecimal("120.00"), inv.tomarComprobanteNuevo().items.get(0).costoUnitario,
                "el cambio de costo no altera las ventas pasadas, solo las nuevas");
    }
}
