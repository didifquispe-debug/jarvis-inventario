package com.jarvis;

import com.jarvis.core.JarvisService;
import com.jarvis.inventory.Ejemplos;
import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.Producto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Operaciones de corrección por voz y por la API REST. */
@SpringBootTest(properties = {"jarvis.token=clave-de-prueba-123",
        "jarvis.inventory.file=${java.io.tmpdir}/jarvis-test-${random.uuid}.json"})
@AutoConfigureMockMvc
class CorreccionesTest {

    @Autowired JarvisService jarvis;
    @Autowired InventoryService inv;
    @Autowired MockMvc mvc;

    @BeforeEach void limpiar() { Ejemplos.sembrar(inv); inv.cancelarVenta(); inv.resolver(false); inv.tomarComprobanteNuevo(); }

    private Producto producto(String prefijo) {
        return inv.productos().stream().filter(p -> p.nombre.startsWith(prefijo)).findFirst().orElse(null);
    }

    private BigDecimal stock(String prefijo) { return producto(prefijo).stock; }

    // ---------------------------------------------------------------- voz

    @Test void anularLaUltimaVentaPideConfirmacionYDevuelveElStock() {
        BigDecimal antes = stock("Cemento");
        assertTrue(jarvis.process("vendí 2 bolsas de cemento").startsWith("Venta registrada"));
        String pregunta = jarvis.process("anula la última venta");
        assertTrue(pregunta.startsWith("¿Confirma anular la boleta número"), pregunta);
        assertEquals(antes.subtract(new BigDecimal("2")), stock("Cemento"), "aún no se anuló");
        String r = jarvis.process("sí");
        assertTrue(r.startsWith("Venta anulada"), r);
        assertEquals(antes, stock("Cemento"));
        assertTrue(jarvis.process("anula la última venta").contains("ya está anulada"));
        assertTrue(jarvis.process("último comprobante").contains("(ANULADA)"));
    }

    @Test void anularYDecirNoNoCambiaNada() {
        jarvis.process("vendí 1 foco");
        BigDecimal antes = stock("Foco");
        jarvis.process("deshaz la última venta");
        assertEquals("Operación cancelada, señor.", jarvis.process("no"));
        assertEquals(antes, stock("Foco"));
    }

    @Test void corregirLaUltimaVenta() {
        BigDecimal antes = stock("Foco");
        jarvis.process("vendí 2 focos");
        String r = jarvis.process("corrige la última venta, eran 3 focos");
        assertTrue(r.startsWith("Corregí la boleta número"), r);
        assertTrue(r.contains("Nuevo total: 22.50 soles"), r);
        assertEquals(antes.subtract(new BigDecimal("3")), stock("Foco"));
        InventoryService.Comprobante c = inv.tomarComprobanteNuevo();
        assertEquals(new BigDecimal("22.50"), c.total, "la pantalla recibe el comprobante corregido");
        // con un solo producto no hace falta repetirlo
        assertTrue(jarvis.process("corrige la última venta, fueron 1").contains("ahora es 1 unidades"));
        assertEquals(antes.subtract(BigDecimal.ONE), stock("Foco"));
    }

    @Test void corregirSinStockNoHaceNada() {
        jarvis.process("vendí 1 disco de corte");
        BigDecimal antes = stock("Disco");
        String r = jarvis.process("corrige la última venta, eran 500 discos de corte");
        assertTrue(r.contains("stock insuficiente"), r);
        assertEquals(antes, stock("Disco"));
    }

    @Test void stockMinimoYUnidad() {
        assertTrue(jarvis.process("pon el stock mínimo del cemento en 15").startsWith("Stock mínimo de Cemento Yura 42.5kg: 15"));
        assertEquals(new BigDecimal("15"), producto("Cemento").stockMin);
        assertTrue(jarvis.process("cuál es el stock mínimo del cemento").contains("es 15 bolsas"));
        String unidad = jarvis.process("cambia la unidad del cable a rollos");
        assertTrue(unidad.contains("ahora se maneja en rollos"), unidad);
        assertEquals("rollos", producto("Cable").unidad);
        assertTrue(jarvis.process("el cable se vende en metros").contains("ahora se maneja en metros"));
        assertEquals("metros", producto("Cable").unidad);
    }

    @Test void eliminarProductoConConfirmacion() {
        jarvis.process("llegaron 4 brochas");
        assertNotNull(producto("Brochas"));
        String pregunta = jarvis.process("elimina el producto brochas");
        assertTrue(pregunta.startsWith("¿Confirma eliminar Brochas del inventario?"), pregunta);
        assertTrue(jarvis.process("sí").startsWith("Eliminé Brochas"));
        assertNull(producto("Brochas"));
    }

    @Test void eliminarDuranteUnaVentaSoloQuitaDelCarrito() {
        jarvis.process("nueva venta");
        jarvis.process("2 focos");
        assertTrue(jarvis.process("elimina los focos").startsWith("Quitado"));
        assertNotNull(producto("Foco"), "no se borró del inventario");
        jarvis.process("cancelar venta");
    }

    @Test void cierreNoCuentaVentasAnuladas() {
        String antes = jarvis.process("cierre de caja");
        jarvis.process("vendí 1 foco");
        jarvis.process("anula la última venta");
        jarvis.process("sí");
        String despues = jarvis.process("cierre de caja");
        assertEquals(antes.replaceAll(" Ventas anuladas: \\d+\\.", ""), despues.replaceAll(" Ventas anuladas: \\d+\\.", ""));
        assertTrue(despues.contains("Ventas anuladas:"), despues);
    }

    // ---------------------------------------------------------------- REST

    private static final String CLAVE = "clave-de-prueba-123";

    @Test void restEditarProducto() throws Exception {
        mvc.perform(patch("/api/inventory/productos").header("X-Jarvis-Token", CLAVE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Disco de corte 4 1/2\",\"precio\":4.25,\"stockMin\":6}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        assertEquals(new BigDecimal("4.25"), producto("Disco").precio);
        assertEquals(new BigDecimal("6"), producto("Disco").stockMin);
        // nombre exacto: "disco" no basta por API (nada de "el más parecido")
        mvc.perform(patch("/api/inventory/productos").header("X-Jarvis-Token", CLAVE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"disco\",\"precio\":1}")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/inventory/productos").header("X-Jarvis-Token", CLAVE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nombre\":\"Disco de corte 4 1/2\",\"precio\":-1}")).andExpect(status().isBadRequest());
    }

    @Test void restAnularYCorregirLaUltimaVenta() throws Exception {
        jarvis.process("vendí 4 metros de cable");
        BigDecimal tras = stock("Cable");
        mvc.perform(put("/api/inventory/ventas/ultima").header("X-Jarvis-Token", CLAVE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cantidad\":2.5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(startsWith("Corregí la boleta")));
        assertEquals(tras.add(new BigDecimal("1.5")), stock("Cable"));
        mvc.perform(post("/api/inventory/ventas/ultima/anular").header("X-Jarvis-Token", CLAVE)).andExpect(status().isOk());
        assertEquals(tras.add(new BigDecimal("4")), stock("Cable"));
        mvc.perform(post("/api/inventory/ventas/ultima/anular").header("X-Jarvis-Token", CLAVE)).andExpect(status().isBadRequest());
    }

    @Test void restEliminarYMovimientos() throws Exception {
        jarvis.process("llegaron 3 rodillos");
        mvc.perform(delete("/api/inventory/productos").param("nombre", "Rodillos").header("X-Jarvis-Token", CLAVE))
                .andExpect(status().isOk());
        assertNull(producto("Rodillos"));
        mvc.perform(delete("/api/inventory/productos").param("nombre", "Rodillos").header("X-Jarvis-Token", CLAVE))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/inventory/movimientos").param("limite", "1").header("X-Jarvis-Token", CLAVE))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].tipo").value("ELIMINAR"))
                .andExpect(jsonPath("$[0].producto").value("Rodillos"));
    }

    @Test void correccionesExigenClave() throws Exception {
        mvc.perform(delete("/api/inventory/productos").param("nombre", "Foco LED 9W")).andExpect(status().isUnauthorized());
        assertNotNull(producto("Foco"));
    }
}
