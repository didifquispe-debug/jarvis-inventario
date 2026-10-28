package com.jarvis.ia;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** El cliente del modelo local, contra un servidor falso (nunca el Ollama real). */
class ClienteLLMTest {

    private HttpServer servidor;

    @AfterEach
    void apagar() {
        if (servidor != null) servidor.stop(0);
    }

    private String levantar(int status, String respuesta, AtomicReference<String> recibido) throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", ex -> {
            recibido.set(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " "
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = respuesta.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        servidor.start();
        return "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1";
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.openai.com/v1", "http://8.8.8.8:11434/v1", "http://ollama.com/v1", "ftp://localhost/v1"})
    void soloAceptaDireccionesLocales(String url) {
        assertThrows(IllegalArgumentException.class, () -> new ClienteLLM(url, "llama3.2:3b", Duration.ofSeconds(5)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost:11434/v1", "http://127.0.0.1:11434/v1/", "http://192.168.1.20:11434/v1",
            "http://10.0.0.5:8080/v1", "http://172.20.0.2:11434/v1", "http://[::1]:11434/v1"})
    void aceptaEstaPcYLaRedLocal(String url) {
        ClienteLLM c = new ClienteLLM(url, "llama3.2:3b", Duration.ofSeconds(5));
        assertFalse(c.baseUrl().endsWith("/"));
    }

    @Test
    void chatMandaElModeloYDevuelveElTexto() throws Exception {
        AtomicReference<String> recibido = new AtomicReference<>();
        String url = levantar(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"respuesta\\\": \\\"Hola, señor\\\"}\"}}]}", recibido);
        ClienteLLM c = new ClienteLLM(url, "llama3.2:3b", Duration.ofSeconds(5));
        String r = c.chat(List.of(new ClienteLLM.Mensaje("user", "hola")), true, 50, null);
        assertEquals("{\"respuesta\": \"Hola, señor\"}", r);
        assertTrue(recibido.get().startsWith("POST /v1/chat/completions "));
        assertTrue(recibido.get().contains("\"model\":\"llama3.2:3b\""));
        assertTrue(recibido.get().contains("\"json_object\""));
    }

    @Test
    void listaLosModelos() throws Exception {
        String url = levantar(200, "{\"data\":[{\"id\":\"llama3.2:3b\"},{\"id\":\"qwen2.5:7b\"}]}", new AtomicReference<>());
        assertEquals(List.of("llama3.2:3b", "qwen2.5:7b"), new ClienteLLM(url, "x", Duration.ofSeconds(5)).modelos());
    }

    @Test
    void sinElModeloDaErrorConSuCodigo() throws Exception {
        String url = levantar(404, "{\"error\":\"model not found\"}", new AtomicReference<>());
        ClienteLLM c = new ClienteLLM(url, "llama3.2:3b", Duration.ofSeconds(5));
        ClienteLLM.ErrorServidor e = assertThrows(ClienteLLM.ErrorServidor.class,
                () -> c.chat(List.of(new ClienteLLM.Mensaje("user", "hola")), true, 50, null));
        assertEquals(404, e.status);
    }
}
