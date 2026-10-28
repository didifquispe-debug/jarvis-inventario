/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis;

import com.jarvis.core.JarvisService;
import com.jarvis.inventory.Ejemplos;
import com.jarvis.inventory.InventoryService;
import com.jarvis.skills.InventorySkill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false", "jarvis.token=clave-mic",
        "jarvis.inventory.file=${java.io.tmpdir}/jarvis-test-${random.uuid}.json"})
@AutoConfigureMockMvc
class ConfirmacionPorVozTest {

    @Autowired JarvisService jarvis;
    @Autowired InventoryService inv;
    @Autowired InventorySkill reglas;
    @Autowired MockMvc mvc;

    @BeforeEach void limpiar() {
        Ejemplos.sembrar(inv);
        inv.cancelarVenta();
        inv.tomarComprobanteNuevo();
        jarvis.process("no");
    }

    private int stockCemento() {
        return inv.productos().stream().filter(p -> p.nombre.startsWith("Cemento")).findFirst().orElseThrow().stock.intValueExact();
    }

    @Test void unaVentaDictadaSeRepiteYSoloSeHaceConUnSi() {
        int antes = stockCemento();
        String r = jarvis.process("Jarvis, vendí 2 bolsas de cemento", true);
        assertEquals("Entendí: vendí 2 bolsas de cemento. ¿Lo hago, señor?", r);
        assertEquals(antes, stockCemento(), "todavía no se vendió");

        String hecho = jarvis.process("sí", true);
        assertTrue(hecho.startsWith("Venta registrada"), hecho);
        assertEquals(antes - 2, stockCemento());
    }

    @Test void conUnNoNoSeVende() {
        int antes = stockCemento();
        jarvis.process("vendí 2 bolsas de cemento", true);
        assertEquals("Bien, señor, no hice nada.", jarvis.process("no", true));
        assertEquals(antes, stockCemento());
    }

    @Test void escritaNoPregunta() {
        int antes = stockCemento();
        assertTrue(jarvis.process("vendí 2 bolsas de cemento", false).startsWith("Venta registrada"));
        assertEquals(antes - 2, stockCemento());
    }

    @Test void lasConsultasDictadasNoPreguntan() {
        String r = jarvis.process("cuántas bolsas de cemento quedan", true);
        assertFalse(r.contains("¿Lo hago"), r);
    }

    @Test void conElCarritoAbiertoLosProductosNoSePreguntanUnoAUno() {
        jarvis.process("nueva venta", true);
        String r = jarvis.process("2 bolsas de cemento", true);
        assertFalse(r.contains("¿Lo hago"), r);
        inv.cancelarVenta();
    }

    @ParameterizedTest
    @ValueSource(strings = {"vendí 2 bolsas de cemento", "agrega 10 focos a 5 soles", "cobra 2 bolsas de cemento",
            "corrige la última venta, eran 3", "cambia el precio del cemento a 30", "stock mínimo del cemento en 5",
            "véndeme tres focos"})
    void delicadas(String cmd) { assertTrue(reglas.delicadaPorVoz(cmd), cmd); }

    @ParameterizedTest
    @ValueSource(strings = {"cuántas bolsas de cemento quedan", "lista de precios", "nueva venta", "cobrar", "cierre de caja",
            "último comprobante", "anula la última venta", "elimina el producto cemento", "nuevo inventario",
            "cuánto es 25 más 4", "qué hora es", "inventario"})
    void noDelicadas(String cmd) { assertFalse(reglas.delicadaPorVoz(cmd), cmd); }

    @Test void elMicrofonoDeLaPcSoloSeManejaDesdeEllaYPideLaClave() throws Exception {
        mvc.perform(get("/api/voz/escucha").header("X-Jarvis-Token", "clave-mic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disponible").value(false))
                .andExpect(jsonPath("$.motivo").isNotEmpty());
        mvc.perform(post("/api/voz/escucha").header("X-Jarvis-Token", "clave-mic")
                        .contentType("application/json").content("{\"accion\":\"manos-libres\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/voz/escucha").header("X-Jarvis-Token", "clave-mic")
                        .contentType("application/json").content("{\"accion\":\"bailar\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/voz/escucha").header("X-Jarvis-Token", "clave-mic")
                        .with(r -> { r.setRemoteAddr("192.168.1.30"); return r; }))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/voz/escucha")).andExpect(status().isUnauthorized());
    }

    @Test void laVozDeLaPcPorLaApi() throws Exception {
        mvc.perform(get("/api/voz/habla").header("X-Jarvis-Token", "clave-mic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modo").value("navegador"));
        mvc.perform(post("/api/voz/hablar").header("X-Jarvis-Token", "clave-mic").contentType("application/json")
                        .content("{\"texto\":\"hola\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/voz/hablar").header("X-Jarvis-Token", "clave-mic").contentType("application/json")
                        .content("{\"texto\":\"hola\"}").with(r -> { r.setRemoteAddr("192.168.1.30"); return r; }))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/voz/habla").header("X-Jarvis-Token", "clave-mic")
                        .with(r -> { r.setRemoteAddr("192.168.1.30"); return r; }))
                .andExpect(jsonPath("$.modo").value("navegador"));
        mvc.perform(post("/api/voz/callar").header("X-Jarvis-Token", "clave-mic")).andExpect(status().isOk());
    }

    @Test void laOrdenDictadaLlegaPorLaApiMarcadaComoVoz() throws Exception {
        mvc.perform(post("/api/command").header("X-Jarvis-Token", "clave-mic").contentType("application/json")
                        .content("{\"text\":\"vendí 2 bolsas de cemento\",\"voz\":true}"))
                .andExpect(jsonPath("$.reply").value("Entendí: vendí 2 bolsas de cemento. ¿Lo hago, señor?"));
        mvc.perform(post("/api/command").header("X-Jarvis-Token", "clave-mic").contentType("application/json")
                        .content("{\"text\":\"no\",\"voz\":true}"))
                .andExpect(jsonPath("$.reply").value("Bien, señor, no hice nada."));
    }
}
