/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.core.DYSAJarvisService;
import com.jarvis.inventory.Tiendas;
import com.jarvis.security.ApazaApiAuthFilter;
import com.jarvis.security.ClientSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false", "jarvis.ia.enabled=false",
        "jarvis.token=clave-de-prueba-123", "jarvis.usuarios.laptop-admin=true",
        "jarvis.inventory.file=${java.io.tmpdir}/jarvis-tiendas-${random.uuid}/inv.json"})
@AutoConfigureMockMvc
class TiendasApiTest {

    private static final String LAPTOP = "127.0.0.1", CELULAR = "192.168.1.41";

    @Autowired MockMvc mvc;
    @Autowired DYSAJarvisService jarvis;
    @Autowired Tiendas tiendas;
    private final ObjectMapper json = new ObjectMapper();

    private MockHttpServletRequestBuilder desde(MockHttpServletRequestBuilder b, String ip, String usuario, Integer tienda) {
        b.header("X-Jarvis-Token", "clave-de-prueba-123").with(r -> { r.setRemoteAddr(ip); return r; });
        if (usuario != null) b.header(ApazaApiAuthFilter.CABECERA_USUARIO, usuario);
        if (tienda != null) b.header(ApazaApiAuthFilter.CABECERA_TIENDA, String.valueOf(tienda));
        return b.contentType(MediaType.APPLICATION_JSON);
    }

    private JsonNode leer(MockHttpServletRequestBuilder b) throws Exception {
        return json.readTree(mvc.perform(b).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private int productos(String usuario, Integer tienda, String ip) throws Exception {
        return leer(desde(get("/api/inventory"), ip, usuario, tienda)).size();
    }

    /** Un recorrido: las tiendas viven en el mismo servidor de prueba. */
    @Test void cadaTiendaTieneSuPropioInventarioYSusUsuarios() throws Exception {
        // Al principio solo está la tienda principal.
        JsonNode s = leer(desde(get("/api/sesion"), LAPTOP, null, null));
        assertEquals(1, s.get("tienda").get("id").asInt());
        assertEquals("Tienda principal", s.get("tiendas").get(0).get("nombre").asText());

        mvc.perform(desde(post("/api/tiendas"), LAPTOP, null, null).content("{\"nombre\":\"Tienda Centro\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(containsString("Tienda creada: Tienda Centro")));
        mvc.perform(desde(post("/api/tiendas"), LAPTOP, null, null).content("{\"nombre\":\"tienda centro\"}"))
                .andExpect(status().isBadRequest());
        int centro = leer(desde(get("/api/tiendas"), LAPTOP, null, null)).get("tiendas").get(1).get("id").asInt();
        assertTrue(Files.exists(tiendas.principal().archivoBaseDatos().resolveSibling("inv-tienda-" + centro + ".db")), "la tienda tiene su propia base");

        // Un producto en la principal y otro en Centro: cada uno solo en su tienda.
        mvc.perform(desde(post("/api/inventory/productos"), LAPTOP, null, 1)
                .content("{\"nombre\":\"Arroz Costeno 5kg\",\"precio\":22,\"stock\":10,\"unidad\":\"bolsas\"}")).andExpect(status().isOk());
        mvc.perform(desde(post("/api/inventory/productos"), LAPTOP, null, centro)
                .content("{\"nombre\":\"Leche Gloria\",\"precio\":4.5,\"stock\":30,\"unidad\":\"latas\"}")).andExpect(status().isOk());
        assertEquals(1, productos(null, 1, LAPTOP));
        assertEquals(1, productos(null, centro, LAPTOP));
        JsonNode inv = leer(desde(get("/api/inventory"), LAPTOP, null, centro));
        assertEquals("Leche Gloria", inv.get(0).get("nombre").asText());

        // Vender en Centro no toca la principal, y la boleta empieza su propia numeración.
        mvc.perform(desde(post("/api/inventory/ventas"), LAPTOP, null, centro)
                .content("{\"items\":[{\"nombre\":\"Leche Gloria\",\"cantidad\":2}]}")).andExpect(status().isOk());
        assertEquals(28, leer(desde(get("/api/inventory"), LAPTOP, null, centro)).get(0).get("stock").asInt());
        assertEquals(0, leer(desde(get("/api/inventory/resumen"), LAPTOP, null, 1)).get("ventasHoy").asInt());

        // Usuarios: el admin y una cajera de Centro. La cajera siempre trabaja en Centro aunque pida otra tienda.
        mvc.perform(desde(post("/api/usuarios"), LAPTOP, null, null).content("{\"nombre\":\"Didier\",\"rol\":\"admin\",\"pin\":\"2580\"}"))
                .andExpect(status().isOk());
        mvc.perform(desde(post("/api/usuarios"), LAPTOP, null, null)
                .content("{\"nombre\":\"Rosa\",\"rol\":\"cajero\",\"pin\":\"4826\",\"tienda\":" + centro + "}")).andExpect(status().isOk());
        mvc.perform(desde(post("/api/usuarios"), LAPTOP, null, null)
                .content("{\"nombre\":\"Nadie\",\"rol\":\"cajero\",\"pin\":\"4826\",\"tienda\":99}")).andExpect(status().isBadRequest());
        String rosa = json.readTree(mvc.perform(desde(post("/api/sesion"), CELULAR, null, null)
                .content("{\"usuario\":\"Rosa\",\"pin\":\"4826\"}")).andReturn().getResponse().getContentAsString()).get("token").asText();
        JsonNode suyo = leer(desde(get("/api/inventory"), CELULAR, rosa, 1));
        assertEquals("Leche Gloria", suyo.get(0).get("nombre").asText(), "la cajera no puede cambiar de tienda");
        JsonNode sesion = leer(desde(get("/api/sesion"), CELULAR, rosa, null));
        assertEquals("Tienda Centro", sesion.get("tienda").get("nombre").asText());
        assertEquals(0, sesion.get("tiendas").size(), "la cajera no ve el selector de tiendas");
        mvc.perform(desde(post("/api/tiendas"), CELULAR, rosa, null).content("{\"nombre\":\"Otra\"}")).andExpect(status().isForbidden());

        // El administrador puede renombrar; una tienda inexistente cae en la principal.
        mvc.perform(desde(patch("/api/tiendas/" + centro), LAPTOP, null, null).content("{\"nombre\":\"Centro Lima\"}"))
                .andExpect(status().isOk());
        assertEquals("Arroz Costeno 5kg", leer(desde(get("/api/inventory"), LAPTOP, null, 77)).get(0).get("nombre").asText());

        // La voz y el chat también trabajan en la tienda elegida.
        String r = ClientSession.enTienda(centro, () -> jarvis.process("cuánta leche gloria hay"));
        assertTrue(r.contains("28"), r);
        String p = ClientSession.enTienda(1, () -> jarvis.process("cuánta leche gloria hay"));
        assertFalse(p.contains("28"), "en la principal no hay leche: " + p);
    }
}
