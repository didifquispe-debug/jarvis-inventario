/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.ia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.core.JarvisService;
import com.jarvis.core.OrdenesPorConfirmar;
import com.jarvis.inventory.InventoryService;
import com.jarvis.skills.InventorySkill;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class AsistenteIATest {

    @TempDir Path dir;

    private HttpServer servidor;
    private volatile String contestar;
    private final List<String> peticiones = new CopyOnWriteArrayList<>();
    private final List<String> ordenes = new CopyOnWriteArrayList<>();
    private final Function<String, String> reglas = o -> { ordenes.add(o); return "reglas: " + o; };
    private InventoryService inv;
    private final OrdenesPorConfirmar confirmar = new OrdenesPorConfirmar("señor");

    @BeforeEach
    void preparar() throws Exception {
        inv = new InventoryService(dir.resolve("inv.json").toString());
        assertTrue(inv.crearProducto("Polera roja M", new BigDecimal("25"), new BigDecimal("10"), "unidades", null, null).ok());
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v1/models", x -> responder(x, "{\"object\":\"list\",\"data\":[{\"id\":\"llama3.2:3b\"}]}"));
        servidor.createContext("/v1/chat/completions", x -> {
            String cuerpo = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!cuerpo.contains("\"max_tokens\":1,")) peticiones.add(cuerpo);
            String c = new ObjectMapper().writeValueAsString(contestar == null ? "" : contestar);
            responder(x, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + c + "}}]}");
        });
        servidor.start();
    }

    private static void responder(com.sun.net.httpserver.HttpExchange x, String json) throws java.io.IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(200, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    @AfterEach
    void cerrar() { servidor.stop(0); }

    private AsistenteIA asistente(String baseUrl) {
        return new AsistenteIA(inv, new InventorySkill(inv), confirmar, true, baseUrl, "llama3.2:3b", 5, "señor", "Mi Tienda");
    }

    private AsistenteIA asistente() {
        return asistente("http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1");
    }

    @Test void conversaConPalabrasSinTocarLasReglas() {
        contestar = "{\"respuesta\": \"Le conviene reponer la polera roja M, señor.\"}";
        assertEquals(Optional.of("Le conviene reponer la polera roja M, señor."), asistente().responder("qué me conviene reponer", reglas));
        assertTrue(ordenes.isEmpty());
    }

    @Test void mandaElModeloYElInventarioAlServidorLocal() {
        contestar = "{\"respuesta\": \"Hola\"}";
        asistente().responder("qué tal", reglas);
        assertEquals(1, peticiones.size());
        String p = peticiones.get(0);
        assertTrue(p.contains("\"model\":\"llama3.2:3b\""), p);
        assertTrue(p.contains("json_object"), p);
        assertTrue(p.contains("Polera roja M | 10 unidades"), p);
        assertTrue(p.contains("Mi Tienda"), p);
    }

    @Test void lasConsultasPasanDirectoALasReglas() {
        contestar = "{\"orden\": \"cuántas tengo de polera roja M\"}";
        assertEquals(Optional.of("reglas: cuántas tengo de polera roja M"), asistente().responder("me fijas las poleras", reglas));
        assertEquals(List.of("cuántas tengo de polera roja M"), ordenes);
    }

    @Test void unaVentaPideElSiAntesDeHacerse() {
        AsistenteIA a = asistente();
        contestar = "{\"orden\": \"vendí 2 polera roja M\"}";
        String r = a.responder("despacha dos poleras rojas", reglas).orElseThrow();
        assertTrue(r.contains("vendí 2 polera roja M") && r.endsWith("¿Lo hago, señor?"), r);
        assertTrue(ordenes.isEmpty(), "el modelo no ejecuta nada por su cuenta");

        assertEquals(Optional.of("reglas: vendí 2 polera roja M"), confirmar.resolver("sí, hazlo", reglas));
        assertEquals(List.of("vendí 2 polera roja M"), ordenes);
        assertEquals(Optional.empty(), confirmar.resolver("sí", reglas), "el sí vale una sola vez");
    }

    @Test void conUnNoNoSeHaceNada() {
        AsistenteIA a = asistente();
        contestar = "{\"orden\": \"nuevo inventario\"}";
        a.responder("borra todo y empecemos", reglas);
        assertEquals(Optional.of("Bien, señor, no hice nada."), confirmar.resolver("no", reglas));
        assertTrue(ordenes.isEmpty());
    }

    @Test void otraFraseDescartaLaOrdenPendiente() {
        AsistenteIA a = asistente();
        contestar = "{\"orden\": \"vendí 2 polera roja M\"}";
        a.responder("despacha dos poleras", reglas);
        assertEquals(Optional.empty(), confirmar.resolver("qué hora es", reglas));
        assertEquals(Optional.empty(), confirmar.resolver("sí", reglas));
        assertTrue(ordenes.isEmpty());
    }

    @Test void nuncaDiceQueHizoAlgoQueNoHizo() {
        AsistenteIA a = asistente();
        contestar = "{\"respuesta\": \"Se han enviado dos poleras rojas talla M al cliente.\"}";
        assertEquals(Optional.of(AsistenteIA.NO_PUDE), a.responder("despacha dos poleras rojas", reglas));
        contestar = "Listo, señor: vendí 2 poleras.";
        assertEquals(Optional.of(AsistenteIA.NO_PUDE), a.responder("despacha dos poleras", reglas));
        assertTrue(ordenes.isEmpty());

        contestar = "{\"respuesta\": \"Hola\"}";
        a.responder("hola", reglas);
        assertFalse(peticiones.get(peticiones.size() - 1).contains("Se han enviado"), "lo descartado no se recuerda");
    }

    @Test void entiendeElJsonAunqueVengaConAdornos() {
        contestar = "Claro:\n```json\n{\"respuesta\": \"Buenos días, señor.\"}\n```";
        assertEquals(Optional.of("Buenos días, señor."), asistente().responder("buen día", reglas));
    }

    @Test void siOlvidaElJsonUsaSusPalabras() {
        contestar = "Buenos días, señor.";
        assertEquals(Optional.of("Buenos días, señor."), asistente().responder("buen día", reglas));
    }

    @Test void unaConsultaQueLasReglasNoEntiendenNoDevuelveElNoEntendi() {
        contestar = "{\"orden\": \"cuántas tengo de cosa rara\"}";
        AsistenteIA a = asistente();
        assertEquals(Optional.of(AsistenteIA.NO_PUDE), a.responder("y las cosas raras", o -> JarvisService.NO_ENTENDI));
    }

    @Test void sinServidorSeApagaYUsaLasReglas() throws Exception {
        int libre;
        try (ServerSocket s = new ServerSocket(0)) { libre = s.getLocalPort(); }
        AsistenteIA a = asistente("http://127.0.0.1:" + libre + "/v1");
        assertEquals(Optional.empty(), a.responder("qué me conviene reponer", reglas));
        assertFalse(a.disponible(), "tras el fallo espera un minuto antes de reintentar");
        assertTrue(ordenes.isEmpty());
    }

    @Test void apagadoNoLlamaAlServidor() {
        AsistenteIA a = new AsistenteIA(inv, new InventorySkill(inv), confirmar, false,
                "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1", "llama3.2:3b", 5, "señor", "");
        assertFalse(a.disponible());
        assertEquals(Optional.empty(), a.responder("hola", reglas));
        assertTrue(peticiones.isEmpty());
    }

    @Test void seNiegaAUsarUnServidorDeInternet() {
        AsistenteIA a = asistente("https://api.example.com/v1");
        assertFalse(a.disponible());
        assertEquals(Optional.empty(), a.responder("hola", reglas));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:11434/v1", "http://127.0.0.1:11434/v1", "http://192.168.1.20:11434/v1",
            "http://10.0.0.5:8080/v1", "http://[::1]:11434/v1"})
    void direccionesLocales(String url) { assertTrue(ClienteLLM.esLocal(URI.create(url)), url); }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.anthropic.com/v1", "https://api.openai.com/v1", "http://8.8.8.8/v1",
            "http://mi-servidor.com/v1", "ftp://localhost/v1"})
    void direccionesDeInternet(String url) { assertFalse(ClienteLLM.esLocal(URI.create(url)), url); }

    @ParameterizedTest
    @ValueSource(strings = {"sí", "si", "Sí, hazlo", "claro", "claro que sí", "dale", "confirmo", "de acuerdo", "ok", "Jarvis, sí"})
    void reconoceElSi(String dicho) { assertTrue(OrdenesPorConfirmar.esSi(dicho), dicho); }

    @ParameterizedTest
    @ValueSource(strings = {"no", "no, mejor no", "no, sí lo pensé mejor", "elimina la polera", "", "simplemente no"})
    void noConfundeOtraCosaConUnSi(String dicho) { assertFalse(OrdenesPorConfirmar.esSi(dicho), dicho); }

    @Test void soloLasConsultasSeHacenSinPreguntar() {
        InventorySkill s = new InventorySkill(inv);
        for (String c : List.of("cuántas tengo de polera roja M", "cuánto cuesta la polera roja M", "lista de precios", "inventario"))
            assertTrue(s.soloConsulta(c), c);
        for (String c : List.of("vendí 2 polera roja M", "agrega 10 polera roja M a 25 soles", "nuevo inventario",
                "elimina el producto polera roja M", "cambia el precio de la polera roja M a 30", "anula la última venta"))
            assertFalse(s.soloConsulta(c), c);
    }
}
