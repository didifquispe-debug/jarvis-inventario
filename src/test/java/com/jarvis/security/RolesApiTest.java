/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false", "jarvis.ia.enabled=false",
        "jarvis.token=clave-de-prueba-123", "jarvis.usuarios.laptop-admin=true",
        "jarvis.inventory.file=${java.io.tmpdir}/jarvis-roles-${random.uuid}.json"})
@AutoConfigureMockMvc
class RolesApiTest {

    private static final String LAPTOP = "127.0.0.1", CELULAR = "192.168.1.40";

    @Autowired MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    private MockHttpServletRequestBuilder desde(MockHttpServletRequestBuilder b, String ip, String usuario) {
        b.header("X-Jarvis-Token", "clave-de-prueba-123").with(r -> { r.setRemoteAddr(ip); return r; });
        if (usuario != null) b.header(ApiAuthFilter.CABECERA_USUARIO, usuario);
        return b.contentType(MediaType.APPLICATION_JSON);
    }

    private String ingresar(String nombre, String pin) throws Exception {
        String r = mvc.perform(desde(post("/api/sesion"), CELULAR, null).content("{\"usuario\":\"" + nombre + "\",\"pin\":\"" + pin + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(r).get("token").asText();
    }

    /** Un solo recorrido: los usuarios y sesiones viven en el mismo servidor de prueba. */
    @Test void recorridoCompletoDeRoles() throws Exception {
        // Sin usuarios: el celular trabaja como siempre, pero no puede crear el primer administrador.
        mvc.perform(desde(get("/api/inventory"), CELULAR, null)).andExpect(status().isOk());
        mvc.perform(desde(get("/api/sesion"), CELULAR, null)).andExpect(jsonPath("$.usuarios").value(false));
        mvc.perform(desde(post("/api/usuarios"), CELULAR, null).content("{\"nombre\":\"Intruso\",\"rol\":\"admin\",\"pin\":\"2580\"}"))
                .andExpect(status().isForbidden());

        // La laptop crea los usuarios y un producto con costo.
        mvc.perform(desde(post("/api/usuarios"), LAPTOP, null).content("{\"nombre\":\"Didier\",\"rol\":\"admin\",\"pin\":\"2580\"}"))
                .andExpect(status().isOk());
        mvc.perform(desde(post("/api/usuarios"), LAPTOP, null).content("{\"nombre\":\"Yoel\",\"rol\":\"cajero\",\"pin\":\"4826\"}"))
                .andExpect(status().isOk());
        mvc.perform(desde(post("/api/usuarios"), LAPTOP, null).content("{\"nombre\":\"Sumi\",\"rol\":\"almacen\",\"pin\":\"7391\"}"))
                .andExpect(status().isOk());
        mvc.perform(desde(post("/api/inventory/productos"), LAPTOP, null)
                        .content("{\"nombre\":\"Polera azul\",\"precio\":59,\"stock\":10,\"unidad\":\"unidades\",\"costo\":30}"))
                .andExpect(status().isOk());

        // Ahora el celular debe iniciar sesión; la clave del aparato no se borra (cabecera X-Jarvis-Login).
        mvc.perform(desde(get("/api/inventory"), CELULAR, null)).andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Jarvis-Login", "requerido"));
        mvc.perform(desde(get("/api/sesion"), CELULAR, null)).andExpect(jsonPath("$.usuarios").value(true))
                .andExpect(jsonPath("$.nombres.length()").value(3)).andExpect(jsonPath("$.yo").doesNotExist());
        mvc.perform(desde(post("/api/sesion"), CELULAR, null).content("{\"usuario\":\"Yoel\",\"pin\":\"0000\"}"))
                .andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("X-Jarvis-Login"));
        mvc.perform(desde(get("/api/sesion"), LAPTOP, null)).andExpect(jsonPath("$.yo.rol").value("ADMIN"));

        // Cajero: vende y ve precios, pero no costos, ganancias, compras ni cambios de inventario.
        String cajero = ingresar("Yoel", "4826");
        mvc.perform(desde(get("/api/sesion"), CELULAR, cajero)).andExpect(jsonPath("$.yo.nombre").value("Yoel"))
                .andExpect(jsonPath("$.yo.rol").value("CAJERO"));
        String lista = mvc.perform(desde(get("/api/inventory"), CELULAR, cajero)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(lista.contains("costo"), lista);
        assertTrue(lista.contains("Polera azul"));
        String venta = mvc.perform(desde(post("/api/inventory/ventas"), CELULAR, cajero)
                        .content("{\"items\":[{\"nombre\":\"Polera azul\",\"cantidad\":1}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(venta.contains("B001"), venta);
        assertFalse(venta.contains("costoUnitario"), "el comprobante no lleva el costo: " + venta);
        mvc.perform(desde(get("/api/inventory/resumen"), CELULAR, cajero)).andExpect(status().isOk())
                .andExpect(jsonPath("$.valorCosto").doesNotExist());
        for (String url : new String[]{"/api/reportes/panel", "/api/inventory/valorizado", "/api/compras", "/api/proveedores",
                "/api/usuarios", "/api/respaldos", "/api/inventory/movimientos", "/api/reportes/inventario.xlsx"})
            mvc.perform(desde(get(url), CELULAR, cajero)).andExpect(status().isForbidden());
        mvc.perform(desde(post("/api/inventory/productos"), CELULAR, cajero).content("{\"nombre\":\"Gorra\",\"precio\":20,\"unidad\":\"unidades\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.reply").value(containsString("cajero")));
        mvc.perform(desde(post("/api/inventory/ventas/ultima/anular"), CELULAR, cajero)).andExpect(status().isForbidden());
        mvc.perform(desde(post("/api/respaldos/restaurar"), CELULAR, cajero).content("{\"nombre\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(desde(post("/api/command"), CELULAR, cajero).content("{\"text\":\"nuevo inventario\"}"))
                .andExpect(jsonPath("$.reply").value(containsString("no tiene permiso")));
        mvc.perform(desde(post("/api/command"), CELULAR, cajero).content("{\"text\":\"abre la calculadora\"}"))
                .andExpect(jsonPath("$.reply").value(containsString("no tiene permiso")));
        mvc.perform(desde(get("/api/clientes"), CELULAR, cajero)).andExpect(status().isOk());

        // Almacén: ve costos y compras, no vende ni ve clientes.
        String almacen = ingresar("Sumi", "7391");
        JsonNode valorizado = json.readTree(mvc.perform(desde(get("/api/inventory/valorizado"), CELULAR, almacen))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(30.0, valorizado.get("filas").get(0).get("costo").asDouble());
        mvc.perform(desde(get("/api/compras"), CELULAR, almacen)).andExpect(status().isOk());
        mvc.perform(desde(post("/api/inventory/ventas"), CELULAR, almacen)
                .content("{\"items\":[{\"nombre\":\"Polera azul\",\"cantidad\":1}]}")).andExpect(status().isForbidden());
        mvc.perform(desde(get("/api/clientes"), CELULAR, almacen)).andExpect(status().isForbidden());
        mvc.perform(desde(get("/api/reportes/panel"), CELULAR, almacen)).andExpect(status().isForbidden());

        // Administrador desde el celular: todo, incluidas las ganancias.
        String admin = ingresar("Didier", "2580");
        mvc.perform(desde(get("/api/reportes/panel"), CELULAR, admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.deHoy.ganancia").value(20.0));
        mvc.perform(desde(get("/api/usuarios"), CELULAR, admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$[0].pinHash").doesNotExist());
        mvc.perform(desde(get("/api/respaldos"), CELULAR, admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.respaldos[0].tipo").value("DIARIO"));

        // Cerrar sesión invalida el token.
        mvc.perform(desde(delete("/api/sesion"), CELULAR, cajero)).andExpect(status().isOk());
        mvc.perform(desde(get("/api/inventory"), CELULAR, cajero)).andExpect(status().isUnauthorized());
    }

    @Test void mapaDePermisos() {
        assertEquals(Rol.Permiso.VENDER, ApiAuthFilter.permisoPara("POST", "/api/inventory/ventas"));
        assertEquals(Rol.Permiso.VENDER, ApiAuthFilter.permisoPara("PUT", "/api/inventory/venta/opciones"));
        assertNull(ApiAuthFilter.permisoPara("GET", "/api/inventory/venta"), "ver el carrito propio no pide permiso");
        assertEquals(Rol.Permiso.ANULAR_VENTAS, ApiAuthFilter.permisoPara("PUT", "/api/inventory/ventas/ultima"));
        assertEquals(Rol.Permiso.INVENTARIO, ApiAuthFilter.permisoPara("DELETE", "/api/inventory/productos"));
        assertEquals(Rol.Permiso.VER_COSTOS, ApiAuthFilter.permisoPara("GET", "/api/reportes/kardex.xlsx"));
        assertEquals(Rol.Permiso.COMPRAS, ApiAuthFilter.permisoPara("GET", "/api/reportes/compras.xlsx"));
        assertEquals(Rol.Permiso.REPORTES, ApiAuthFilter.permisoPara("GET", "/api/reportes/ventas.xlsx"));
        assertNull(ApiAuthFilter.permisoPara("POST", "/api/command"));
        assertNull(ApiAuthFilter.permisoPara("GET", "/api/inventory"));
        assertNull(ApiAuthFilter.permisoPara("GET", "/api/negocio"));
        assertEquals(Rol.Permiso.ADMINISTRAR, ApiAuthFilter.permisoPara("PUT", "/api/negocio"));
    }
}
