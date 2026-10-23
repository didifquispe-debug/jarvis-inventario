package com.jarvis.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Proveedores y compras: stock, costo promedio ponderado, IGV, documentos repetidos y anulación. */
class ComprasTest {

    @TempDir Path dir;

    private static final String RUC = "20100047218";

    private InventoryService nuevo() { return new InventoryService(dir.resolve("inv.json").toString()); }

    private static Producto producto(InventoryService inv, String prefijo) {
        return inv.productos().stream().filter(p -> p.nombre.startsWith(prefijo)).findFirst().orElseThrow();
    }

    private static Compras.NuevaCompra compra(String doc, String igv, Compras.LineaCompra... items) {
        return new Compras.NuevaCompra(RUC, "Distribuidora Andina SAC", "factura", doc, null, igv, null, List.of(items));
    }

    private static Compras.LineaCompra linea(String producto, String cantidad, String costo) {
        return new Compras.LineaCompra(producto, new BigDecimal(cantidad), new BigDecimal(costo));
    }

    @Test void compraSumaStockYCalculaElCostoSinIgv() {
        InventoryService inv = Ejemplos.sembrar(nuevo());   // Foco LED 9W: 30 unidades, sin costo
        InventoryService.Resultado r = inv.registrarCompra(compra("F001-452", null, linea("Foco LED 9W", "20", "5.90")));
        assertTrue(r.ok(), r.mensaje());
        assertTrue(r.mensaje().startsWith("Compra CP-00000001 registrada: factura F001-452 de Distribuidora Andina SAC por 118.00 soles"),
                r.mensaje());
        Producto foco = producto(nuevo(), "Foco");
        assertEquals(new BigDecimal("50"), foco.stock);
        assertEquals(new BigDecimal("5.00"), foco.costo, "5.90 incluye IGV: al inventario entra a 5.00");
        Compras.Compra c = inv.compras().obtener("CP-00000001").orElseThrow();
        assertEquals(new BigDecimal("100.00"), c.base());
        assertEquals(new BigDecimal("18.00"), c.igv());
        assertEquals(new BigDecimal("118.00"), c.total());
        assertEquals("FACTURA", c.tipoDocumento());
        assertEquals(LocalDate.now().toString(), c.fechaDocumento(), "sin fecha = hoy");
        assertEquals("COMPRA", inv.movimientos(1).get(0).tipo());
        assertTrue(inv.compras().proveedor(RUC).isPresent(), "el proveedor nuevo se registró solo");
    }

    @Test void costoPromedioPonderado() {
        InventoryService inv = nuevo();
        assertTrue(inv.crearProducto("Polera roja M", new BigDecimal("45"), new BigDecimal("10"), "unidades", null, null,
                new Producto.Ficha(null, null, null, new BigDecimal("20"))).ok());
        assertTrue(inv.registrarCompra(compra("F001-1", "exonerado", linea("Polera roja M", "30", "24"))).ok());
        assertEquals(new BigDecimal("23.00"), producto(inv, "Polera").costo, "(10×20 + 30×24) / 40");
        inv.salida(BigDecimal.ONE, "polera roja m", false);
        assertEquals(new BigDecimal("23.00"), inv.tomarComprobanteNuevo().items.get(0).costoUnitario, "la venta guarda ese costo");
    }

    @Test void igvAgregadoYCantidadesConDecimales() {
        InventoryService inv = Ejemplos.sembrar(nuevo());   // Cable Vulcanizado en metros
        InventoryService.Resultado r = inv.registrarCompra(compra("F002-9", "agregado", linea("Cable Vulcanizado", "12.5", "2")));
        assertTrue(r.ok(), r.mensaje());
        Compras.Compra c = inv.compras().obtener("1").orElseThrow();
        assertEquals(new BigDecimal("25.00"), c.base());
        assertEquals(new BigDecimal("4.50"), c.igv());
        assertEquals(new BigDecimal("29.50"), c.total());
        assertEquals(new BigDecimal("62.5"), producto(inv, "Cable").stock);
        assertEquals(new BigDecimal("12.5"), c.detalles().get(0).cantidad());
    }

    @Test void validaAntesDeTocarNada() {
        InventoryService inv = Ejemplos.sembrar(nuevo());
        BigDecimal antes = producto(inv, "Foco").stock;
        assertFalse(inv.registrarCompra(new Compras.NuevaCompra("1234", "X", "factura", "F1", null, null, null,
                List.of(linea("Foco LED 9W", "1", "5")))).ok(), "RUC inválido");
        assertFalse(inv.registrarCompra(new Compras.NuevaCompra("20111111111", "", "factura", "F1", null, null, null,
                List.of(linea("Foco LED 9W", "1", "5")))).ok(), "RUC nuevo sin razón social");
        assertFalse(inv.registrarCompra(compra("", null, linea("Foco LED 9W", "1", "5"))).ok(), "sin número de documento");
        assertFalse(inv.registrarCompra(new Compras.NuevaCompra(RUC, "D", "factura", "F1", LocalDate.now().plusDays(1).toString(),
                null, null, List.of(linea("Foco LED 9W", "1", "5")))).ok(), "fecha futura");
        assertFalse(inv.registrarCompra(new Compras.NuevaCompra(RUC, "D", "recibo", "F1", null, null, null,
                List.of(linea("Foco LED 9W", "1", "5")))).ok(), "tipo de documento desconocido");
        assertFalse(inv.registrarCompra(compra("F1", null)).ok(), "sin productos");
        assertFalse(inv.registrarCompra(compra("F1", null, linea("Foco LED 9W", "1", "0"))).ok(), "costo cero");
        assertFalse(inv.registrarCompra(compra("F1", null, linea("Foco LED 9W", "1.5", "5"))).ok(), "focos no se fraccionan");
        assertEquals(InventoryService.Estado.NO_ENCONTRADO,
                inv.registrarCompra(compra("F1", null, linea("Foco LED 9W", "2", "5"), linea("Producto que no existe", "1", "5"))).estado());
        assertEquals(antes, producto(nuevo(), "Foco").stock, "ninguna compra inválida tocó el stock");
        assertTrue(inv.compras().listar("", "", "", 10).isEmpty());
    }

    @Test void noSeRegistraDosVecesElMismoDocumento() {
        InventoryService inv = Ejemplos.sembrar(nuevo());
        assertTrue(inv.registrarCompra(compra("F001-452", null, linea("Foco LED 9W", "1", "5"))).ok());
        InventoryService.Resultado r = inv.registrarCompra(compra("f001-452", null, linea("Foco LED 9W", "1", "5")));
        assertFalse(r.ok(), "sin importar mayúsculas");
        assertTrue(r.mensaje().contains("Ya se registró la factura F001-452"), r.mensaje());
        assertTrue(inv.registrarCompra(new Compras.NuevaCompra(RUC, null, "guía", "F001-452", null, null, null,
                List.of(linea("Foco LED 9W", "1", "5")))).ok(), "otro tipo de documento con el mismo número sí");
    }

    @Test void anularDevuelveElStockSoloSiNoSeVendio() {
        InventoryService inv = Ejemplos.sembrar(nuevo());   // Disco de corte: 8
        assertTrue(inv.registrarCompra(compra("F001-1", null, linea("Disco de corte 4 1/2", "10", "3"))).ok());
        inv.salida(new BigDecimal("15"), "disco", false);    // quedan 3: no alcanza para retirar 10
        InventoryService.Resultado r = inv.anularCompra("CP-00000001");
        assertFalse(r.ok());
        assertTrue(r.mensaje().contains("parte de la mercadería ya se vendió"), r.mensaje());
        assertEquals(new BigDecimal("3"), producto(inv, "Disco").stock, "no se tocó");

        assertTrue(inv.registrarCompra(compra("F001-2", null, linea("Foco LED 9W", "5", "5"))).ok());
        assertTrue(inv.anularCompra("CP-00000002").ok());
        assertEquals(new BigDecimal("30"), producto(nuevo(), "Foco").stock);
        assertTrue(nuevo().compras().obtener("CP-00000002").orElseThrow().anulada(), "queda guardado");
        assertFalse(inv.anularCompra("CP-00000002").ok(), "ya estaba anulada");
        assertEquals(InventoryService.Estado.NO_ENCONTRADO, inv.anularCompra("CP-99").estado());
        assertTrue(inv.registrarCompra(compra("F001-2", null, linea("Foco LED 9W", "5", "5"))).ok(),
                "el documento de una compra anulada se puede volver a registrar");
    }

    @Test void listaFiltrosYTotales() {
        InventoryService inv = Ejemplos.sembrar(nuevo());
        inv.registrarCompra(compra("F001-1", null, linea("Foco LED 9W", "10", "11.80")));
        inv.registrarCompra(new Compras.NuevaCompra("20601234567", "Ferretera Lima SAC", "boleta", "B001-77", null, "exonerado", null,
                List.of(linea("Cemento Yura 42.5kg", "4", "25"))));
        assertEquals(List.of("CP-00000002", "CP-00000001"),
                inv.compras().listar("", "", "", 10).stream().map(Compras.Compra::codigo).toList(), "de la más reciente a la más antigua");
        assertEquals(1, inv.compras().listar("ferretera", "", "", 10).size(), "por proveedor");
        assertEquals(1, inv.compras().listar("f001", "", "", 10).size(), "por número de documento");
        Compras.Totales t = inv.compras().totalizar("", "", "");
        assertEquals(2, t.compras());
        assertEquals(new BigDecimal("218.00"), t.total());
        inv.anularCompra("CP-00000002");
        assertEquals(new BigDecimal("118.00"), inv.compras().totalizar("", "", "").total(), "las anuladas no suman");
        assertTrue(inv.compras().listar("", LocalDate.now().plusDays(1).toString(), "", 10).isEmpty(), "filtro de fechas");
    }

    @Test void proveedores() {
        Compras c = nuevo().compras();
        assertTrue(c.crearProveedor(new Compras.DatosProveedor(RUC, " Distribuidora  Andina SAC ", "Av. Lima 1", "014215500")).ok());
        assertFalse(c.crearProveedor(new Compras.DatosProveedor(RUC, "Otra", null, null)).ok(), "RUC repetido");
        assertFalse(c.crearProveedor(new Compras.DatosProveedor("30100047218", "Mala", null, null)).ok(), "RUC inválido");
        assertEquals("Distribuidora Andina SAC", c.proveedor(RUC).orElseThrow().razonSocial());
        assertEquals(1, c.buscarProveedores("andina", 10).size());
        assertTrue(c.editarProveedor(RUC, new Compras.DatosProveedor(null, null, "", "987654321")).ok());
        assertEquals("", c.proveedor(RUC).orElseThrow().direccion());
        assertTrue(c.eliminarProveedor(RUC).ok());
        assertEquals(0, c.contarProveedores());
    }

    @Test void proveedorConComprasNoSeElimina() {
        InventoryService inv = Ejemplos.sembrar(nuevo());
        inv.registrarCompra(compra("F001-1", null, linea("Foco LED 9W", "1", "5")));
        assertFalse(inv.compras().eliminarProveedor(RUC).ok());
    }
}
