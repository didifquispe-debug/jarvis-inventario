/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis;

import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.Producto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false", "jarvis.token=clave-pantalla",
        "jarvis.negocio=Tienda Prueba", "jarvis.inventory.file=${java.io.tmpdir}/jarvis-test-pantalla-${random.uuid}.json"})
@AutoConfigureMockMvc
class ProductosPantallaTest {

    private static final String CLAVE = "clave-pantalla";

    @Autowired MockMvc mvc;
    @Autowired InventoryService inv;

    private ResultActions crear(String json) throws Exception {
        return mvc.perform(post("/api/inventory/productos").header("X-Jarvis-Token", CLAVE)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions editar(String json) throws Exception {
        return mvc.perform(patch("/api/inventory/productos").header("X-Jarvis-Token", CLAVE)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions ajustar(String nombre, String cantidad) throws Exception {
        return mvc.perform(post("/api/inventory/productos/ajuste").header("X-Jarvis-Token", CLAVE)
                .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"" + nombre + "\",\"cantidad\":" + cantidad + "}"));
    }

    private Producto producto(String nombre) {
        return inv.productos().stream().filter(p -> p.nombre.equals(nombre)).findFirst().orElse(null);
    }

    @Test void creaConTodosLosDatos() throws Exception {
        crear("{\"nombre\":\"  Casaca  jean azul L \",\"precio\":89.9,\"stock\":4,\"unidad\":\"Unidades\",\"stockMin\":1,\"alias\":[\"casaca jean\"]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.reply").value(containsString("Producto registrado: Casaca jean azul L")));
        Producto p = producto("Casaca jean azul L");
        assertNotNull(p, "quita espacios de más");
        assertEquals(new BigDecimal("89.90"), p.precio);
        assertEquals(new BigDecimal("4"), p.stock);
        assertEquals(new BigDecimal("1"), p.stockMin);
        assertEquals("unidades", p.unidad);
        assertTrue(p.alias.contains("casaca jean") && p.alias.contains("casaca jean azul l"), p.alias.toString());
        assertEquals("NUEVO_PRODUCTO", inv.movimientos(2).get(1).tipo());
        assertEquals("ENTRADA", inv.movimientos(1).get(0).tipo(), "el stock inicial queda en el historial");
    }

    @Test void stockYMinimoSonOpcionales() throws Exception {
        crear("{\"nombre\":\"Gorra negra\",\"precio\":15,\"unidad\":\"unidades\"}").andExpect(status().isOk());
        assertEquals(BigDecimal.ZERO, producto("Gorra negra").stock.stripTrailingZeros());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"precio\":10,\"unidad\":\"unidades\"}",
            "{\"nombre\":\"   \",\"precio\":10,\"unidad\":\"unidades\"}",
            "{\"nombre\":\"Media\",\"unidad\":\"pares\"}",
            "{\"nombre\":\"Media\",\"precio\":3}",
            "{\"nombre\":\"Media\",\"precio\":3,\"unidad\":\" \"}",
            "{\"nombre\":\"Media\",\"precio\":-3,\"unidad\":\"pares\"}",
            "{\"nombre\":\"Media\",\"precio\":3,\"stock\":-1,\"unidad\":\"pares\"}",
            "{\"nombre\":\"Media\",\"precio\":3,\"stock\":1.5,\"unidad\":\"pares\"}",
            "{\"nombre\":\"Media\",\"precio\":\"tres\",\"unidad\":\"pares\"}",
            "{}"})
    void rechazaDatosIncompletosOInvalidos(String json) throws Exception {
        int antes = inv.productos().size();
        crear(json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.reply").isNotEmpty());
        assertEquals(antes, inv.productos().size(), "no se crea nada");
    }

    @Test void noPermiteNombresRepetidos() throws Exception {
        crear("{\"nombre\":\"Pantalón Drill 32\",\"precio\":60,\"unidad\":\"unidades\"}").andExpect(status().isOk());
        crear("{\"nombre\":\"pantalon drill 32\",\"precio\":50,\"unidad\":\"unidades\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reply").value(containsString("Ya existe")));
        crear("{\"nombre\":\"PANTALÓN  DRILL 32\",\"precio\":50,\"unidad\":\"unidades\"}").andExpect(status().isBadRequest());
        assertEquals(1, inv.productos().stream().filter(p -> p.nombre.startsWith("Pantal")).count());
    }

    @Test void ajusteRapidoDeStock() throws Exception {
        crear("{\"nombre\":\"Correa cuero\",\"precio\":35,\"stock\":2,\"unidad\":\"unidades\"}").andExpect(status().isOk());
        ajustar("Correa cuero", "1").andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(containsString("3 unidades")));
        ajustar("Correa cuero", "-3").andExpect(status().isOk());
        assertEquals(0, producto("Correa cuero").stock.signum());
        ajustar("Correa cuero", "-1").andExpect(status().isBadRequest());
        assertEquals(0, producto("Correa cuero").stock.signum(), "nunca queda negativo");
        ajustar("Correa cuero", "0.5").andExpect(status().isBadRequest());
        ajustar("No existe", "1").andExpect(status().isNotFound());
        assertNull(inv.tomarComprobanteNuevo(), "un ajuste no es una venta");
        assertEquals("AJUSTE", inv.movimientos(1).get(0).tipo());
    }

    @Test void editarNombreStockYAlias() throws Exception {
        crear("{\"nombre\":\"Polo básico\",\"precio\":20,\"stock\":5,\"unidad\":\"unidades\"}").andExpect(status().isOk());
        crear("{\"nombre\":\"Polo estampado\",\"precio\":30,\"unidad\":\"unidades\"}").andExpect(status().isOk());
        editar("{\"nombre\":\"Polo básico\",\"nuevoNombre\":\"Polo básico blanco M\",\"stock\":8,\"precio\":22,\"alias\":[\"polo blanco\"]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(containsString("ahora se llama Polo básico blanco M")));
        Producto p = producto("Polo básico blanco M");
        assertNotNull(p);
        assertNull(producto("Polo básico"));
        assertEquals(new BigDecimal("8"), p.stock);
        assertEquals(new BigDecimal("22.00"), p.precio);
        assertTrue(p.alias.contains("polo blanco"));
        assertFalse(p.alias.contains("polo basico"), "el alias del nombre viejo se va");
        editar("{\"nombre\":\"Polo básico blanco M\",\"nuevoNombre\":\"polo ESTAMPADO\"}").andExpect(status().isBadRequest());
        editar("{\"nombre\":\"Polo básico blanco M\",\"stock\":-2}").andExpect(status().isBadRequest());
        editar("{\"nombre\":\"Polo básico blanco M\"}").andExpect(status().isBadRequest());
    }

    @Test void negocioEnElSaludoYEnLaPantalla() throws Exception {
        mvc.perform(post("/api/command").header("X-Jarvis-Token", CLAVE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hola jarvis\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reply").value(containsString("Bienvenido a Tienda Prueba")));
        mvc.perform(get("/api/system").header("X-Jarvis-Token", CLAVE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.negocio").value("Tienda Prueba"));
    }

    @Test void ordenQueNadieEntiende() throws Exception {
        mvc.perform(post("/api/command").header("X-Jarvis-Token", CLAVE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"escribe un poema sobre el mar\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reply").value("No entendí la orden. Diga 'ayuda' para ver lo que puedo hacer."));
    }
}
