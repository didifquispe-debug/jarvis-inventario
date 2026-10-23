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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Comprobantes y clientes por voz y por la API. Contexto propio: inventario y clientes de esta clase. */
@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false", "jarvis.token=clave-comprobantes",
        "jarvis.negocio=Ferretería El Clavo", "jarvis.negocio.ruc=20601234567",
        "jarvis.inventory.file=${java.io.tmpdir}/jarvis-test-comprobantes-${random.uuid}.json"})
@AutoConfigureMockMvc
class ComprobantesVozYApiTest {

    private static final String CLAVE = "clave-comprobantes";

    @Autowired MockMvc mvc;
    @Autowired JarvisService jarvis;
    @Autowired InventoryService inv;

    @BeforeEach
    void preparar() {
        Ejemplos.sembrar(inv);
        if (inv.clientes().porDocumento("12345678").isEmpty())
            inv.clientes().crear(new com.jarvis.inventory.Clientes.Datos("12345678", "María Quispe Rojas", null, null, null));
        jarvis.process("cancelar venta");
    }

    private ResultActions json(MockHttpServletRequestBuilder req, String cuerpo) throws Exception {
        return mvc.perform(req.header("X-Jarvis-Token", CLAVE).contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private BigDecimal stock(String prefijo) {
        return inv.productos().stream().filter(p -> p.nombre.startsWith(prefijo)).findFirst().map(p -> p.stock).orElseThrow();
    }

    // ---------------------------------------------------------------- voz

    @Test void ventaConBoletaYDniDichos() {
        BigDecimal antes = stock("Cemento");
        String r = jarvis.process("vendí 2 bolsas de cemento con boleta para el DNI 12345678");
        assertTrue(r.contains("Boleta número") && r.contains("Cliente: María Quispe Rojas."), r);
        assertEquals(antes.subtract(new BigDecimal("2")), stock("Cemento"), "el DNI no se leyó como cantidad");
        assertEquals("12345678", inv.tomarComprobanteNuevo().cliente.documento());
    }

    @Test void ventaConNotaDeVenta() {
        String r = jarvis.process("vendí 1 foco con nota de venta");
        assertTrue(r.contains("Nota de venta número"), r);
    }

    @Test void dniNoRegistradoNoVende() {
        BigDecimal antes = stock("Foco");
        String r = jarvis.process("vendí 1 foco para el dni 99999999");
        assertTrue(r.startsWith("No tengo registrado al cliente con DNI 99999999"), r);
        assertEquals(antes, stock("Foco"));
    }

    @Test void ventaEnCursoConClienteDichoDespues() {
        assertTrue(jarvis.process("nueva venta con nota de venta").contains("La venta será nota de venta"));
        jarvis.process("2 focos");
        assertEquals("La venta será nota de venta para María Quispe Rojas.", jarvis.process("cliente dni 12345678"));
        String r = jarvis.process("cobrar");
        assertTrue(r.contains("Nota de venta número") && r.contains("María Quispe Rojas"), r);
    }

    @Test void laUltimaBoletaSigueSiendoUnaConsulta() {
        jarvis.process("vendí 1 disco");
        assertTrue(jarvis.process("muéstrame la última boleta").startsWith("Mostrando la boleta número"));
    }

    // ---------------------------------------------------------------- API de clientes

    @Test void apiDeClientes() throws Exception {
        json(post("/api/clientes"), "{\"documento\":\"20100047218\",\"nombre\":\"Comercial Andina SAC\",\"telefono\":\"014215500\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(startsWith("Cliente registrado")));
        json(post("/api/clientes"), "{\"documento\":\"20100047218\",\"nombre\":\"Otra\"}").andExpect(status().isBadRequest());
        json(post("/api/clientes"), "{\"documento\":\"123\",\"nombre\":\"Corto\"}").andExpect(status().isBadRequest());
        mvc.perform(get("/api/clientes").param("q", "andina").header("X-Jarvis-Token", CLAVE))
                .andExpect(jsonPath("$[0].documento").value("20100047218"))
                .andExpect(jsonPath("$[0].tipoDocumento").value("RUC"));
        json(patch("/api/clientes/20100047218"), "{\"email\":\"ventas@andina.pe\"}").andExpect(status().isOk());
        mvc.perform(get("/api/clientes/20100047218").header("X-Jarvis-Token", CLAVE))
                .andExpect(jsonPath("$.email").value("ventas@andina.pe"));
        mvc.perform(delete("/api/clientes/20100047218").header("X-Jarvis-Token", CLAVE)).andExpect(status().isOk());
        mvc.perform(get("/api/clientes/20100047218").header("X-Jarvis-Token", CLAVE)).andExpect(status().isNotFound());
        mvc.perform(get("/api/clientes")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- API de ventas y comprobantes

    @Test void ventaPorApiYSuPdf() throws Exception {
        String r = json(post("/api/inventory/ventas"), """
                {"items":[{"nombre":"Foco LED 9W","cantidad":2},{"nombre":"Cable Vulcanizado","cantidad":1.5}],
                 "tipo":"boleta","cliente":"12345678"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recibo.comprobante.codigo").value(startsWith("B001-")))
                .andExpect(jsonPath("$.recibo.comprobante.cliente.nombre").value("María Quispe Rojas"))
                .andExpect(jsonPath("$.recibo.comprobante.total").value(20.25))
                .andExpect(jsonPath("$.recibo.comprobante.igv").value(3.09))
                .andExpect(jsonPath("$.recibo.comprobante.totalEnLetras").value("VEINTE CON 25/100 SOLES"))
                .andExpect(jsonPath("$.recibo.negocio.ruc").value("20601234567"))
                .andReturn().getResponse().getContentAsString();
        int numero = Integer.parseInt(r.replaceAll("(?s).*\"numero\":(\\d+).*", "$1"));

        mvc.perform(get("/api/inventory/comprobantes/" + numero + "/pdf").header("X-Jarvis-Token", CLAVE))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", containsString("B001-")));
        mvc.perform(get("/api/inventory/comprobantes/999999/pdf").header("X-Jarvis-Token", CLAVE)).andExpect(status().isNotFound());
        mvc.perform(get("/api/inventory/comprobantes/" + numero)).andExpect(status().isUnauthorized());
    }

    @Test void ventaPorApiConErrores() throws Exception {
        json(post("/api/inventory/ventas"), "{\"items\":[{\"nombre\":\"foco\",\"cantidad\":1}]}").andExpect(status().isNotFound());
        json(post("/api/inventory/ventas"), "{\"items\":[]}").andExpect(status().isBadRequest());
        json(post("/api/inventory/ventas"), "{\"items\":[{\"nombre\":\"Foco LED 9W\",\"cantidad\":1}],\"tipo\":\"factura\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reply").value(containsString("boleta o nota de venta")));
        json(post("/api/inventory/ventas"), "{\"items\":[{\"nombre\":\"Foco LED 9W\",\"cantidad\":1}],\"cliente\":\"11112222\"}")
                .andExpect(status().isBadRequest());
    }

    @Test void opcionesDeLaVentaEnCursoPorApi() throws Exception {
        json(put("/api/inventory/venta/opciones"), "{\"tipo\":\"nota de venta\",\"cliente\":\"12345678\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value("La venta será nota de venta para María Quispe Rojas."));
        mvc.perform(get("/api/inventory/venta").header("X-Jarvis-Token", CLAVE))
                .andExpect(jsonPath("$.tipo").value("NOTA_VENTA"))
                .andExpect(jsonPath("$.cliente.documento").value("12345678"));
        json(put("/api/inventory/venta/opciones"), "{\"cliente\":\"11112222\"}").andExpect(status().isBadRequest());
    }

    @Test void losProductosNoMuestranSuIdInterno() throws Exception {
        mvc.perform(get("/api/inventory").header("X-Jarvis-Token", CLAVE)).andExpect(jsonPath("$[0].id").doesNotExist());
        assertNotNull(inv.productos().stream().map((Producto p) -> p.nombre).findFirst().orElse(null));
    }
}
