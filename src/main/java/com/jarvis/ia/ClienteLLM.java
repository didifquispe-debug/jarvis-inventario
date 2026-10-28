package com.jarvis.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Cliente de un servidor de modelos de lenguaje compatible con la API de OpenAI (Ollama, LM Studio, llama.cpp…)
 * que corre en esta computadora o en la red local. Nunca sale a Internet: rechaza cualquier otra dirección.
 */
public class ClienteLLM {

    /** Un mensaje de la conversación: role = system | user | assistant. */
    public record Mensaje(String role, String content) {}

    /** El servidor respondió con un código de error (404 = no tiene el modelo). */
    public static class ErrorServidor extends IOException {
        public final int status;

        public ErrorServidor(int status, String mensaje) {
            super("HTTP " + status + (mensaje == null || mensaje.isBlank() ? "" : ": " + mensaje));
            this.status = status;
        }
    }

    /** localhost, ::1, 127.x, 10.x, 192.168.x y 172.16-31.x: se compara el texto, sin consultar ningún DNS. */
    private static final Pattern LOCAL = Pattern.compile(
            "^(localhost|\\[?::1]?|127(\\.\\d{1,3}){3}|10(\\.\\d{1,3}){3}|192\\.168(\\.\\d{1,3}){2}"
            + "|172\\.(1[6-9]|2\\d|3[01])(\\.\\d{1,3}){2})$");

    private final ObjectMapper json = new ObjectMapper();
    private final String baseUrl;
    private final String modelo;
    private final Duration timeout;
    private final HttpClient http;

    /** @throws IllegalArgumentException si la dirección no es de esta PC o de la red local */
    public ClienteLLM(String baseUrl, String modelo, Duration timeout) {
        if (baseUrl == null || baseUrl.isBlank()) throw new IllegalArgumentException("falta jarvis.ia.base-url");
        if (modelo == null || modelo.isBlank()) throw new IllegalArgumentException("falta jarvis.ia.model");
        URI u;
        try {
            u = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("la dirección " + baseUrl + " no es válida");
        }
        if (u.getHost() == null || !("http".equals(u.getScheme()) || "https".equals(u.getScheme()))
                || !LOCAL.matcher(u.getHost().toLowerCase()).matches())
            throw new IllegalArgumentException("la dirección " + baseUrl + " no es de esta computadora ni de la red local");
        this.baseUrl = baseUrl.trim().replaceAll("/+$", "");
        this.modelo = modelo.trim();
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public String baseUrl() { return baseUrl; }

    public String modelo() { return modelo; }

    /** Los modelos que tiene el servidor (GET /models). */
    public List<String> modelos() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/models")).timeout(timeout).GET().build();
        JsonNode r = enviar(req);
        List<String> lista = new ArrayList<>();
        for (JsonNode m : r.path("data")) lista.add(m.path("id").asText());
        return lista;
    }

    /**
     * Pide una respuesta (POST /chat/completions) y devuelve su texto.
     *
     * @param soloJson   pide que conteste con un objeto JSON
     * @param maxTokens  largo máximo de la respuesta
     * @param espera     tiempo máximo para esta llamada; null = el de la configuración
     */
    public String chat(List<Mensaje> mensajes, boolean soloJson, int maxTokens, Duration espera)
            throws IOException, InterruptedException {
        ObjectNode cuerpo = json.createObjectNode();
        cuerpo.put("model", modelo);
        cuerpo.put("stream", false);
        cuerpo.put("temperature", 0.2);
        cuerpo.put("max_tokens", maxTokens);
        ArrayNode ms = cuerpo.putArray("messages");
        for (Mensaje m : mensajes) ms.addObject().put("role", m.role()).put("content", m.content());
        if (soloJson) cuerpo.putObject("response_format").put("type", "json_object");
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .timeout(espera == null ? timeout : espera)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(cuerpo), StandardCharsets.UTF_8))
                .build();
        JsonNode r = enviar(req);
        return r.path("choices").path(0).path("message").path("content").asText("");
    }

    private JsonNode enviar(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() >= 400) {
            String cuerpo = res.body() == null ? "" : res.body();
            throw new ErrorServidor(res.statusCode(), cuerpo.length() > 200 ? cuerpo.substring(0, 200) : cuerpo);
        }
        return json.readTree(res.body());
    }
}
