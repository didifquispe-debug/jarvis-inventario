package com.jarvis;

import com.jarvis.inventory.Ejemplos;
import com.jarvis.inventory.InventoryService;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** API de proveedores y compras. Contexto propio. */
@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false", "jarvis.token=clave-compras",
        "jarvis.inventory.file=${java.io.tmpdir}/jarvis-test-compras-${random.uuid}.json"})
@AutoConfigureMockMvc
class ComprasApiTest {

    private static final String CLAVE = "clave-compras";

    @Autowired MockMvc mvc;
    @Autowired InventoryService inv;

    @BeforeEach void preparar() { Ejemplos.sembrar(inv); }

    private ResultActions json(MockHttpServletRequestBuilder req, String cuerpo) throws Exception {
        return mvc.perform(req.header("X-Jarvis-Token", CLAVE).contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private BigDecimal stock(String prefijo) {
        return inv.productos().stream().filter(p -> p.nombre.startsWith(prefijo)).findFirst().orElseThrow().stock;
    }

    @Test void registrarListarVerYAnular() throws Exception {
        BigDecimal antes = stock("Clavo");
        json(post("/api/compras"), """
                {"ruc":"20100047218","razonSocial":"Distribuidora Andina SAC","tipoDocumento":"factura","numeroDocumento":"F001-900",
                 "items":[{"producto":"Clavo con cabeza 2 pulgadas","cantidad":500,"costo":0.059}]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(startsWith("Compra CP-")))
                .andExpect(jsonPath("$.compra.total").value(29.5))
                .andExpect(jsonPath("$.compra.detalles[0].costo").value(0.059))
                .andExpect(jsonPath("$.compra.id").doesNotExist());
        assertEquals(antes.add(new BigDecimal("500")), stock("Clavo"));

        String lista = mvc.perform(get("/api/compras").param("q", "F001-900").header("X-Jarvis-Token", CLAVE))
                .andExpect(jsonPath("$.compras[0].proveedor").value("Distribuidora Andina SAC"))
                .andExpect(jsonPath("$.totales.compras").value(1))
                .andReturn().getResponse().getContentAsString();
        String codigo = lista.replaceAll("(?s).*\"codigo\":\"(CP-\\d+)\".*", "$1");
        mvc.perform(get("/api/compras/" + codigo).header("X-Jarvis-Token", CLAVE)).andExpect(jsonPath("$.detalles.length()").value(1));

        json(post("/api/compras"), """
                {"ruc":"20100047218","numeroDocumento":"F001-900","items":[{"producto":"Clavo con cabeza 2 pulgadas","cantidad":1,"costo":1}]}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reply").value(containsString("Ya se registró")));

        mvc.perform(post("/api/compras/" + codigo + "/anular").header("X-Jarvis-Token", CLAVE)).andExpect(status().isOk());
        assertEquals(antes, stock("Clavo"));
        mvc.perform(post("/api/compras/" + codigo + "/anular").header("X-Jarvis-Token", CLAVE)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/compras/CP-99999999").header("X-Jarvis-Token", CLAVE)).andExpect(status().isNotFound());
    }

    @Test void productoInexistenteEs404() throws Exception {
        json(post("/api/compras"), """
                {"ruc":"20100047218","razonSocial":"X","numeroDocumento":"F9","items":[{"producto":"no existe","cantidad":1,"costo":1}]}""")
                .andExpect(status().isNotFound());
    }

    @Test void apiDeProveedores() throws Exception {
        json(post("/api/proveedores"), "{\"ruc\":\"20555555551\",\"razonSocial\":\"Textiles del Sur SAC\",\"telefono\":\"054223344\"}")
                .andExpect(status().isOk());
        json(post("/api/proveedores"), "{\"ruc\":\"20555555551\",\"razonSocial\":\"Otra\"}").andExpect(status().isBadRequest());
        mvc.perform(get("/api/proveedores").param("q", "textiles").header("X-Jarvis-Token", CLAVE))
                .andExpect(jsonPath("$[0].ruc").value("20555555551"));
        json(patch("/api/proveedores/20555555551"), "{\"direccion\":\"Av. Ejército 101, Arequipa\"}").andExpect(status().isOk());
        mvc.perform(get("/api/proveedores/20555555551").header("X-Jarvis-Token", CLAVE))
                .andExpect(jsonPath("$.direccion").value("Av. Ejército 101, Arequipa"));
        mvc.perform(delete("/api/proveedores/20555555551").header("X-Jarvis-Token", CLAVE)).andExpect(status().isOk());
        mvc.perform(get("/api/proveedores/20555555551").header("X-Jarvis-Token", CLAVE)).andExpect(status().isNotFound());
        mvc.perform(get("/api/compras")).andExpect(status().isUnauthorized());
    }
}
