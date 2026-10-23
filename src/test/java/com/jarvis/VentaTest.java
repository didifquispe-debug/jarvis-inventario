package com.jarvis;

import com.jarvis.core.JarvisService;
import com.jarvis.inventory.Ejemplos;
import com.jarvis.inventory.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/** Ventas con un inventario temporal (no toca ~/jarvis-inventario.json). */
@SpringBootTest(properties = {"jarvis.desktop.enabled=false",
        "jarvis.inventory.file=${java.io.tmpdir}/jarvis-test-${random.uuid}.json"})
class VentaTest {

    @Autowired JarvisService jarvis;
    @Autowired InventoryService inv;

    @BeforeEach void limpiar() { Ejemplos.sembrar(inv); inv.cancelarVenta(); inv.tomarComprobanteNuevo(); }

    private int stockCemento() {
        return inv.productos().stream().filter(p -> p.nombre.startsWith("Cemento")).findFirst().orElseThrow().stock.intValueExact();
    }

    @ParameterizedTest
    @ValueSource(strings = {"vende 2 bolsas de cemento", "véndeme 2 bolsas de cemento", "vendí 2 bolsas de cemento",
            "venta de 2 bolsas de cemento", "vender 2 bolsas de cemento", "quiero vender 2 bolsas de cemento",
            "vendo 2 bolsas de cemento", "el cliente compró 2 bolsas de cemento", "me compraron 2 bolsas de cemento",
            "registra una venta de 2 bolsas de cemento", "2 bolsas de cemento vendidas", "cobra 2 bolsas de cemento",
            "Jarvis, se llevaron 2 bolsas de cemento"})
    void ventaRestaYEmiteComprobante(String frase) {
        int antes = stockCemento();
        String r = jarvis.process(frase);
        assertTrue(r.startsWith("Venta registrada"), frase + " -> " + r);
        assertEquals(antes - 2, stockCemento(), frase);
        InventoryService.Comprobante c = inv.tomarComprobanteNuevo();
        assertNotNull(c, "debe emitir comprobante");
        assertEquals(1, c.items.size());
        assertEquals(new java.math.BigDecimal("2"), c.items.get(0).cantidad);
        assertEquals(new java.math.BigDecimal("56.00"), c.total);   // 2 x 28 soles
    }

    @Test void entradaSigueSumando() {
        int antes = stockCemento();
        assertTrue(jarvis.process("llegaron 5 bolsas de cemento").startsWith("Entrada registrada"));
        assertEquals(antes + 5, stockCemento());
        assertNull(inv.tomarComprobanteNuevo(), "una entrada no emite comprobante");
    }

    @Test void numeracionCorrelativa() {
        jarvis.process("vendí 1 foco");
        int n1 = inv.tomarComprobanteNuevo().numero;
        jarvis.process("vendí 1 foco");
        assertEquals(n1 + 1, inv.tomarComprobanteNuevo().numero);
        assertTrue(jarvis.process("muestra el último comprobante").contains(String.valueOf(n1 + 1)));
        assertEquals(n1 + 1, inv.tomarComprobanteNuevo().numero);
    }

    private int stock(String prefijo) {
        return inv.productos().stream().filter(p -> p.nombre.startsWith(prefijo)).findFirst().orElseThrow().stock.intValueExact();
    }

    @Test void variosProductosEnUnaFrase() {
        int cemento = stock("Cemento"), focos = stock("Foco");
        String r = jarvis.process("vendí 2 bolsas de cemento y 3 focos");
        assertTrue(r.startsWith("Venta registrada: 2 productos"), r);
        assertEquals(cemento - 2, stock("Cemento"));
        assertEquals(focos - 3, stock("Foco"));
        InventoryService.Comprobante c = inv.tomarComprobanteNuevo();
        assertEquals(2, c.items.size());
        assertEquals(new java.math.BigDecimal("78.50"), c.total);   // 2 x 28 + 3 x 7.50
        assertTrue(c.items.stream().allMatch(v -> v.numero == c.numero), "todas las líneas, un comprobante");
    }

    @Test void treintaYCincoNoSeCorta() {
        int clavos = stock("Clavo");
        assertTrue(jarvis.process("vendí treinta y cinco clavos").startsWith("Venta registrada: 35"));
        assertEquals(clavos - 35, stock("Clavo"));
    }

    @Test void carritoCompleto() {
        int cemento = stock("Cemento"), cable = stock("Cable"), focos = stock("Foco");
        assertTrue(jarvis.process("nueva venta").startsWith("Venta nueva"));
        assertTrue(jarvis.process("2 bolsas de cemento").startsWith("Agregado"));
        assertTrue(jarvis.process("agrega 10 metros de cable").startsWith("Agregado"));
        assertTrue(jarvis.process("también 4 focos").startsWith("Agregado"));
        assertTrue(jarvis.process("quita los focos").startsWith("Quitado"));
        assertEquals(cemento, stock("Cemento"), "nada se descuenta antes de cobrar");
        assertEquals(2, inv.carritoActual().items.size());
        assertTrue(jarvis.process("qué hora es").startsWith("Son las"), "otras órdenes siguen funcionando");
        assertTrue(jarvis.process("cuánto va").contains("91.00"), "2x28 + 10x3.5");
        String r = jarvis.process("cobrar");
        assertTrue(r.startsWith("Venta registrada: 2 productos. Total: 91.00"), r);
        assertEquals(cemento - 2, stock("Cemento"));
        assertEquals(cable - 10, stock("Cable"));
        assertEquals(focos, stock("Foco"));
        assertNull(inv.carritoActual(), "la venta se cierra al cobrar");
        assertEquals(2, inv.tomarComprobanteNuevo().items.size());
    }

    @Test void carritoSinStockNoDescuentaNada() {
        int discos = stock("Disco");
        jarvis.process("nueva venta");
        assertTrue(jarvis.process((discos + 1) + " discos de corte").contains("stock insuficiente"));
        assertTrue(jarvis.process("cancelar venta").startsWith("Venta cancelada"));
        assertEquals(discos, stock("Disco"));
    }

    @Test void precios() {
        assertTrue(jarvis.process("lista de precios").contains("Cemento Yura 42.5kg: 28.00 soles"));
        assertTrue(jarvis.process("cuánto cuesta el cable").startsWith("Cable Vulcanizado cuesta 3.50 soles"));
        assertTrue(jarvis.process("cambia el precio del foco a 8.50 soles").contains("ahora cuesta 8.50"));
        assertTrue(jarvis.process("precio del foco").startsWith("Foco LED 9W cuesta 8.50"));
        jarvis.process("cambia el precio del foco a 7.50 soles");   // dejarlo como estaba
    }
}
