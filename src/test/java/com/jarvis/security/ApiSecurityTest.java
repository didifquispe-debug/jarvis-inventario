package com.jarvis.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false",
        "jarvis.token=clave-de-prueba-123", "jarvis.security.max-failures-per-minute=10",
        "jarvis.inventory.file=${java.io.tmpdir}/jarvis-test-${random.uuid}.json"})
@AutoConfigureMockMvc
class ApiSecurityTest {

    @Autowired MockMvc mvc;

    private MockHttpServletRequestBuilder comando(String ip, String clave) {
        MockHttpServletRequestBuilder b = post("/api/command").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"que hora es\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; });
        return clave == null ? b : b.header("X-Jarvis-Token", clave);
    }

    @Test void sinClaveEs401() throws Exception {
        mvc.perform(comando("10.0.0.1", null)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.reply").value("Acceso denegado. Clave incorrecta."));
    }

    @Test void conClaveCorrectaResponde() throws Exception {
        mvc.perform(comando("10.0.0.2", "clave-de-prueba-123")).andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value(org.hamcrest.Matchers.startsWith("Son las")));
    }

    @Test void todosLosEndpointsEstanProtegidos() throws Exception {
        for (String url : new String[]{"/api/system", "/api/inventory", "/api/inventory/venta", "/api/inventory/movimientos"})
            mvc.perform(get(url).with(r -> { r.setRemoteAddr("10.0.0.3"); return r; })).andExpect(status().isUnauthorized());
        for (String url : new String[]{"/api/inventory/productos", "/api/inventory/productos/ajuste"})
            mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"x\",\"precio\":1,\"unidad\":\"u\"}")
                    .with(r -> { r.setRemoteAddr("10.0.0.3"); return r; })).andExpect(status().isUnauthorized());
    }

    @Test void statusEsPublico() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("online"));
    }

    @Test void fuerzaBrutaDevuelve429() throws Exception {
        String ip = "10.0.0.66";
        for (int i = 0; i < 10; i++) mvc.perform(comando(ip, "intento-" + i)).andExpect(status().isUnauthorized());
        // el intento 11 ya no se evalúa, ni siquiera con la clave buena
        mvc.perform(comando(ip, "clave-de-prueba-123")).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        // otra IP no se ve afectada
        mvc.perform(comando("10.0.0.67", "clave-de-prueba-123")).andExpect(status().isOk());
    }
}
