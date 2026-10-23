package com.jarvis.inventory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Boletas y notas de venta: series, correlativos, IGV, clientes, importe en letras y PDF. */
class ComprobantesTest {

    @TempDir Path dir;

    private InventoryService nuevo() { return new InventoryService(dir.resolve("inv.json").toString()); }

    private InventoryService nuevo(String tipo, String igv) {
        return new InventoryService(dir.resolve("inv.json").toString(), tipo, igv);
    }

    private static InventoryService.Opciones opciones(TipoComprobante tipo, String dni) {
        return new InventoryService.Opciones(tipo, dni, null);
    }

    private static BigDecimal stock(InventoryService inv, String prefijo) {
        return inv.productos().stream().filter(p -> p.nombre.startsWith(prefijo)).findFirst().orElseThrow().stock;
    }

    private static Clientes.Datos cliente(String doc, String nombre) {
        return new Clientes.Datos(doc, nombre, null, null, null);
    }

    // ---------------------------------------------------------------- IGV e importe en letras (del sistema de referencia)

    private static final List<BigDecimal> IMPORTES = List.of(new BigDecimal("100.00"), new BigDecimal("18.00"));

    @Test void igvIncluidoExtraeLaBase() {
        Igv.Totales t = Igv.calcular(IMPORTES, Igv.Modo.INCLUIDO);
        assertEquals(new BigDecimal("118.00"), t.total());
        assertEquals(new BigDecimal("100.00"), t.base());
        assertEquals(new BigDecimal("18.00"), t.igv());
    }

    @Test void igvAgregadoSeSumaEncima() {
        Igv.Totales t = Igv.calcular(IMPORTES, Igv.Modo.AGREGADO);
        assertEquals(new BigDecimal("118.00"), t.base());
        assertEquals(new BigDecimal("21.24"), t.igv());
        assertEquals(new BigDecimal("139.24"), t.total());
    }

    @Test void exoneradoNoTieneIgv() {
        Igv.Totales t = Igv.calcular(IMPORTES, Igv.Modo.EXONERADO);
        assertEquals(new BigDecimal("118.00"), t.base());
        assertEquals(0, t.igv().signum());
        assertEquals(new BigDecimal("118.00"), t.total());
        assertEquals(0, t.tasa().signum());
    }

    @Test void baseMasIgvSiempreCuadraConElTotal() {
        for (int centimos = 1; centimos < 5000; centimos += 7) {
            Igv.Totales t = Igv.calcular(List.of(BigDecimal.valueOf(centimos, 2)), Igv.Modo.INCLUIDO);
            assertEquals(0, t.base().add(t.igv()).compareTo(t.total()), "al céntimo con " + centimos);
        }
    }

    @Test void importeEnLetras() {
        assertEquals("CERO CON 50/100 SOLES", NumeroALetras.importe(new BigDecimal("0.50")));
        assertEquals("UNO CON 00/100 SOLES", NumeroALetras.importe(new BigDecimal("1")));
        assertEquals("CIEN CON 00/100 SOLES", NumeroALetras.importe(new BigDecimal("100")));
        assertEquals("CIENTO DIECIOCHO CON 45/100 SOLES", NumeroALetras.importe(new BigDecimal("118.45")));
        assertEquals("MIL DOSCIENTOS CINCUENTA CON 50/100 SOLES", NumeroALetras.importe(new BigDecimal("1250.5")));
        assertEquals("VEINTIUN MIL CON 00/100 SOLES", NumeroALetras.importe(new BigDecimal("21000")));
        assertEquals("TREINTA Y UN MIL NOVECIENTOS NOVENTA Y NUEVE CON 99/100 SOLES", NumeroALetras.importe(new BigDecimal("31999.99")));
        assertEquals("UN MILLON CON 00/100 SOLES", NumeroALetras.importe(new BigDecimal("1000000")));
        assertEquals("DOS MILLONES QUINIENTOS MIL CIEN CON 00/100 SOLES", NumeroALetras.importe(new BigDecimal("2500100")));
    }

    // ---------------------------------------------------------------- series y correlativos

    @Test void porDefectoBoletaConIgvIncluido() {
        InventoryService inv = Ejemplos.sembrar(nuevo());
        assertTrue(inv.salida(new BigDecimal("2"), "cemento", false).contains("Boleta número 1"));
        InventoryService.Comprobante c = inv.tomarComprobanteNuevo();
        assertEquals("B001-00000001", c.codigo);
        assertEquals("Boleta de venta electrónica", c.tipoDescripcion);
        assertEquals(new BigDecimal("56.00"), c.total, "el precio ya incluye IGV: el total no cambia");
        assertEquals(new BigDecimal("47.46"), c.base);
        assertEquals(new BigDecimal("8.54"), c.igv);
        assertEquals("CINCUENTA Y SEIS CON 00/100 SOLES", c.totalEnLetras);
        assertNull(c.cliente, "sin cliente = clientes varios");
    }

    @Test void cadaSerieLlevaSuPropioCorrelativo() {
        InventoryService inv = Ejemplos.sembrar(nuevo());
        InventoryService.Opciones nota = opciones(TipoComprobante.NOTA_VENTA, null);
        inv.salida(BigDecimal.ONE, "foco", false);
        inv.salida(BigDecimal.ONE, "foco", false, nota);
        inv.salida(BigDecimal.ONE, "foco", false);
        assertTrue(inv.salida(BigDecimal.ONE, "foco", false, nota).contains("Nota de venta número 2"));
        assertEquals("NV01-00000002", inv.tomarComprobanteNuevo().codigo);
        assertTrue(nuevo().salida(BigDecimal.ONE, "foco", false).contains("Boleta número 3"), "siguen al reiniciar");
    }

    @Test void losNumerosNoSeRepitenNiConUnInventarioNuevo() {
        InventoryService inv = Ejemplos.sembrar(nuevo());
        inv.salida(BigDecimal.ONE, "foco", false);
        assertTrue(inv.empezarDeCero().ok());
        Ejemplos.sembrar(inv);
        assertTrue(inv.salida(BigDecimal.ONE, "foco", false).contains("Boleta número 2"));
        assertTrue(Ejemplos.sembrar(nuevo()).salida(BigDecimal.ONE, "foco", false).contains("Boleta número 3"));
    }

    @Test void tipoEIgvConfigurables() {
        InventoryService inv = Ejemplos.sembrar(nuevo("nota de venta", "agregado"));
        assertTrue(inv.salida(new BigDecimal("2"), "cemento", false).contains("Total: 66.08 soles. Nota de venta número 1"),
                "56 + 18% de IGV");
        InventoryService.Comprobante c = inv.tomarComprobanteNuevo();
        assertEquals(new BigDecimal("56.00"), c.base);
        assertEquals(new BigDecimal("10.08"), c.igv);
        assertTrue(inv.cierre().contains("por un total de 66.08 soles"), "el cierre de caja suma el total con IGV");
    }

    @Test void anularYCorregirMantienenSerieEIgv() {
        InventoryService inv = Ejemplos.sembrar(nuevo());
        inv.venderVarios(List.of(new BigDecimal("2"), BigDecimal.ONE), List.of("cemento", "foco"));
        assertTrue(inv.corregirUltimaVenta("foco", new BigDecimal("2"), false).mensaje().startsWith("Corregí la boleta número 1"));
        InventoryService.Comprobante c = inv.tomarComprobanteNuevo();
        assertEquals(new BigDecimal("71.00"), c.total);
        assertEquals(new BigDecimal("60.17"), c.base, "71 / 1.18");
        assertTrue(inv.anularUltimaVenta().mensaje().startsWith("Venta anulada: boleta número 1"));
        String ultimo = nuevo().ultimoComprobante();
        assertTrue(ultimo.contains("boleta número 1 (ANULADA)"), ultimo);
    }

    // ---------------------------------------------------------------- clientes

    @Test void registraYBuscaClientes() {
        Clientes cl = nuevo().clientes();
        assertTrue(cl.crear(cliente("12345678", "  María   Quispe Rojas ")).ok());
        assertTrue(cl.crear(new Clientes.Datos("20601234567", "Comercial Andina S.A.C.", "ventas@andina.pe", "014215500", null)).ok());
        assertEquals("María Quispe Rojas", cl.porDocumento("12345678").orElseThrow().nombre(), "sin espacios de más");
        assertEquals("RUC", cl.porDocumento("20601234567").orElseThrow().tipoDocumento());
        assertEquals(List.of("María Quispe Rojas"), cl.buscar("quispe maria", 10).stream().map(Clientes.Cliente::nombre).toList(),
                "palabras en cualquier orden y sin tildes");
        assertEquals(1, cl.buscar("1234", 10).size(), "por documento que empiece así");
        assertEquals(2, nuevo().clientes().contar(), "quedan guardados");
    }

    @Test void validaLosDatosDelCliente() {
        Clientes cl = nuevo().clientes();
        assertFalse(cl.crear(cliente("1234567", "Corto")).ok(), "DNI de 7 dígitos");
        assertFalse(cl.crear(cliente("30123456789", "Ruc raro")).ok(), "RUC debe empezar con 10, 15, 17 o 20");
        assertFalse(cl.crear(cliente("12345678", "")).ok(), "sin nombre");
        assertFalse(cl.crear(new Clientes.Datos("12345678", "Ana", "ana@", null, null)).ok(), "correo inválido");
        assertFalse(cl.crear(new Clientes.Datos("12345678", "Ana", null, "12345", null)).ok(), "teléfono corto");
        assertTrue(cl.crear(cliente("12345678", "Ana")).ok());
        assertFalse(cl.crear(cliente("12345678", "Otra Ana")).ok(), "DNI repetido");
        assertEquals(1, cl.contar());
    }

    @Test void ventaConClienteYLuegoNoSeBorra() {
        InventoryService inv = Ejemplos.sembrar(nuevo());
        inv.clientes().crear(cliente("12345678", "María Quispe Rojas"));
        String r = inv.salida(BigDecimal.ONE, "foco", false, opciones(null, "12345678"));
        assertTrue(r.contains("Cliente: María Quispe Rojas."), r);
        InventoryService.Comprobante c = inv.tomarComprobanteNuevo();
        assertEquals("12345678", c.cliente.documento());
        assertEquals("DNI", c.cliente.tipoDocumento());

        assertTrue(inv.clientes().editar("12345678", cliente(null, "María Q. Rojas")).ok());
        assertEquals("María Quispe Rojas", nuevo().comprobantePorNumero(c.numero).cliente.nombre(),
                "el comprobante conserva el nombre con que se emitió");
        assertFalse(inv.clientes().eliminar("12345678").ok(), "tiene comprobantes");
    }

    @Test void clienteNoRegistradoNoVende() {
        InventoryService inv = Ejemplos.sembrar(nuevo());
        String r = inv.salida(BigDecimal.ONE, "foco", false, opciones(null, "87654321"));
        assertTrue(r.startsWith("No tengo registrado al cliente con DNI 87654321"), r);
        assertEquals(new BigDecimal("30"), stock(inv, "Foco"), "no se descontó nada");
    }

    @Test void boletaGrandeSinDniAvisa() {
        InventoryService inv = Ejemplos.sembrar(nuevo());
        assertTrue(inv.salida(new BigDecimal("25"), "cemento", false).contains("se debe identificar al cliente"), "700 soles");
        assertFalse(inv.salida(new BigDecimal("25"), "cemento", false, opciones(TipoComprobante.NOTA_VENTA, null))
                .contains("identificar"), "la nota de venta no lo exige");
    }

    @Test void ventaEnCursoConTipoYCliente() {
        InventoryService inv = Ejemplos.sembrar(nuevo());
        inv.clientes().crear(cliente("12345678", "María Quispe Rojas"));
        assertTrue(inv.abrirVenta(opciones(TipoComprobante.NOTA_VENTA, "12345678")).endsWith("La venta será nota de venta para María Quispe Rojas."));
        inv.agregarAlCarrito(new BigDecimal("2"), "foco");
        assertEquals("NOTA_VENTA", inv.carritoActual().tipo);
        assertEquals("María Quispe Rojas", inv.carritoActual().cliente.nombre());
        assertTrue(inv.cobrar().contains("Nota de venta número 1"));
        inv.abrirVenta();
        assertEquals("BOLETA", inv.carritoActual().tipo, "la venta siguiente vuelve a lo de siempre");
    }

    @Test void ventaPorApiConNombresExactos() {
        InventoryService inv = Ejemplos.sembrar(nuevo());
        assertEquals(InventoryService.Estado.NO_ENCONTRADO,
                inv.vender(List.of(new InventoryService.Pedido("foco", BigDecimal.ONE)), null).estado(), "no busca parecidos");
        assertFalse(inv.vender(List.of(new InventoryService.Pedido("Foco LED 9W", new BigDecimal("99"))), null).ok(), "sin stock");
        assertTrue(inv.vender(List.of(new InventoryService.Pedido("Foco LED 9W", new BigDecimal("2")),
                new InventoryService.Pedido("Cemento Yura 42.5kg", BigDecimal.ONE)), null).ok());
        assertEquals(2, inv.tomarComprobanteNuevo().items.size());
    }

    // ---------------------------------------------------------------- PDF

    @Test void generaElPdf() {
        InventoryService inv = Ejemplos.sembrar(nuevo());
        inv.clientes().crear(cliente("12345678", "María Quispe Rojas"));
        inv.salida(new BigDecimal("2.5"), "cable", false, opciones(null, "12345678"));
        InventoryService.Comprobante c = inv.tomarComprobanteNuevo();
        byte[] pdf = ComprobantePdf.generar(c, new ComprobantePdf.Emisor("Ferretería El Clavo", "20601234567", "Av. Lima 123", "", ""));
        assertTrue(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1).startsWith("%PDF-"), "es un PDF");
        assertTrue(pdf.length > 1500, "con contenido: " + pdf.length + " bytes");
        inv.anularUltimaVenta();
        assertTrue(ComprobantePdf.generar(inv.comprobantePorNumero(c.numero), new ComprobantePdf.Emisor("", "", "", "", "")).length > 1500,
                "también anulado y sin datos del negocio");
    }

    // ---------------------------------------------------------------- base de la versión anterior

    @Test void lasVentasDeLaVersionAnteriorQuedanComoNotasDeVenta() throws Exception {
        try (Connection cx = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("inv.db").toAbsolutePath());
             Statement st = cx.createStatement()) {
            st.execute("CREATE TABLE producto (id INTEGER PRIMARY KEY, nombre TEXT NOT NULL, alias TEXT NOT NULL DEFAULT '[]', "
                    + "stock_mil INTEGER NOT NULL DEFAULT 0, stock_min_mil INTEGER NOT NULL DEFAULT 0, precio_cent INTEGER NOT NULL DEFAULT 0, "
                    + "unidad TEXT NOT NULL DEFAULT 'unidades', categoria TEXT NOT NULL DEFAULT '', codigo TEXT NOT NULL DEFAULT '', "
                    + "codigo_barras TEXT NOT NULL DEFAULT '', costo_dm INTEGER)");
            st.execute("CREATE TABLE comprobante (id INTEGER PRIMARY KEY, numero INTEGER UNIQUE, fecha TEXT NOT NULL, "
                    + "hora TEXT NOT NULL DEFAULT '', anulado INTEGER NOT NULL DEFAULT 0, total_cent INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE venta_detalle (id INTEGER PRIMARY KEY, comprobante_id INTEGER NOT NULL REFERENCES comprobante(id) "
                    + "ON DELETE CASCADE, item INTEGER NOT NULL, producto_id INTEGER, producto TEXT NOT NULL, cantidad_mil INTEGER NOT NULL, "
                    + "unidad TEXT NOT NULL, precio_cent INTEGER NOT NULL, monto_cent INTEGER NOT NULL, costo_dm INTEGER, "
                    + "anulada INTEGER NOT NULL DEFAULT 0)");
            st.execute("CREATE TABLE movimiento (id INTEGER PRIMARY KEY, fecha TEXT NOT NULL, tipo TEXT NOT NULL, producto TEXT NOT NULL, "
                    + "producto_id INTEGER, cantidad_mil INTEGER, detalle TEXT NOT NULL DEFAULT '', usuario TEXT NOT NULL DEFAULT '')");
            st.execute("CREATE TABLE contador (nombre TEXT PRIMARY KEY, valor INTEGER NOT NULL)");
            st.execute("INSERT INTO producto (nombre, alias, stock_mil, precio_cent) VALUES ('Vela', '[\"vela\"]', 10000, 200)");
            st.execute("INSERT INTO comprobante (numero, fecha, hora, total_cent) VALUES (5, '" + LocalDate.now() + "', '10:00', 400)");
            st.execute("INSERT INTO venta_detalle (comprobante_id, item, producto, cantidad_mil, unidad, precio_cent, monto_cent) "
                    + "VALUES (1, 1, 'Vela', 2000, 'unidades', 200, 400)");
            st.execute("INSERT INTO contador VALUES ('ultimo_comprobante', 5)");
            st.execute("PRAGMA user_version = 1");
        }
        InventoryService inv = nuevo();
        InventoryService.Comprobante antigua = inv.comprobantePorNumero(5);
        assertEquals("NV01-00000005", antigua.codigo);
        assertEquals("EXONERADO", antigua.modoIgv, "no se inventa IGV para lo que ya se vendió");
        assertEquals(new BigDecimal("4.00"), antigua.total);
        assertTrue(inv.salida(BigDecimal.ONE, "vela", false, opciones(TipoComprobante.NOTA_VENTA, null)).contains("Nota de venta número 6"));
        assertTrue(inv.salida(BigDecimal.ONE, "vela", false).contains("Boleta número 1"));
        assertTrue(inv.cierre().contains("se registraron 3 ventas por un total de 8.00 soles"), inv.cierre());
    }
}
