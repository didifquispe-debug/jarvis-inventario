/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

public class ClienteLLM {

    public record Mensaje(String role, String content) {}

    public static class ErrorServidor extends IOException {
        public final int status;
        ErrorServidor(int status, String cuerpo) {
            super("HTTP " + status + (cuerpo == null || cuerpo.isBlank() ? "" : ": " + cuerpo.strip()));
            this.status = status;
        }
    }

    private static final Duration CONEXION = Duration.ofSeconds(2);

    private final URI base;
    private final String modelo;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public ClienteLLM(String baseUrl, String modelo, Duration timeout) {
        String b = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.base = URI.create(b);
        if (!esLocal(base))
            throw new IllegalArgumentException("La dirección del modelo debe ser de esta computadora o de la red local: " + b);
        this.modelo = modelo;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(CONEXION).version(HttpClient.Version.HTTP_1_1).build();
    }

    static boolean esLocal(URI u) {
        if (u == null || u.getHost() == null || !("http".equals(u.getScheme()) || "https".equals(u.getScheme()))) return false;
        String host = u.getHost().replaceAll("^\\[|]$", "");
        if (host.equalsIgnoreCase("localhost")) return true;
        if (!host.matches("[0-9.]+") && !host.contains(":")) return false;
        try {
            InetAddress a = InetAddress.getByName(host);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress();
        } catch (Exception e) {
            return false;
        }
    }

    public String modelo() { return modelo; }

    public String baseUrl() { return base.toString(); }

    public List<String> modelos() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/models")).timeout(CONEXION.multipliedBy(2)).GET().build();
        JsonNode r = json.readTree(enviar(req));
        return r.path("data").findValuesAsText("id");
    }

    public String chat(List<Mensaje> mensajes, boolean soloJson, int maxTokens, Duration espera)
            throws IOException, InterruptedException {
        ObjectNode cuerpo = json.createObjectNode();
        cuerpo.put("model", modelo);
        cuerpo.put("stream", false);
        cuerpo.put("temperature", 0.2);
        cuerpo.put("max_tokens", maxTokens);
        if (soloJson) cuerpo.putObject("response_format").put("type", "json_object");
        ArrayNode ms = cuerpo.putArray("messages");
        for (Mensaje m : mensajes) ms.addObject().put("role", m.role()).put("content", m.content());

        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .timeout(espera == null ? timeout : espera)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(cuerpo), StandardCharsets.UTF_8))
                .build();
        JsonNode r = json.readTree(enviar(req));
        JsonNode texto = r.path("choices").path(0).path("message").path("content");
        if (texto.isMissingNode() || texto.isNull()) throw new IOException("Respuesta sin texto: " + r);
        return texto.asText();
    }

    private String enviar(HttpRequest req) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() / 100 != 2) {
            String c = r.body();
            throw new ErrorServidor(r.statusCode(), c != null && c.length() > 300 ? c.substring(0, 300) : c);
        }
        return r.body();
    }
}
