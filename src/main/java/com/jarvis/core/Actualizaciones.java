/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.JarvisApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Avisa si en GitHub hay una versión más nueva del instalador. Solo lee la última "release" pública del repositorio
 * (no envía datos del negocio) y no descarga nada: el administrador decide si la instala.
 * Se apaga con jarvis.actualizaciones.enabled=false.
 */
@Component
public class Actualizaciones {

    private static final Logger log = LoggerFactory.getLogger(Actualizaciones.class);

    public record Nueva(String version, String url) {}

    private final boolean enabled;
    private final String repo;
    private final String actual;
    private volatile Nueva nueva;

    public Actualizaciones(@Value("${jarvis.actualizaciones.enabled:true}") boolean enabled,
                           @Value("${jarvis.actualizaciones.repo:didifquispe-debug/jarvis-inventario}") String repo) {
        this.enabled = enabled;
        this.repo = repo == null ? "" : repo.trim();
        String v = JarvisApplication.class.getPackage().getImplementationVersion();
        this.actual = v == null || v.isBlank() ? "desarrollo" : v;
    }

    public String versionActual() { return actual; }

    public Map<String, Object> estado() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actual", actual);
        Nueva n = nueva;
        if (n != null) { m.put("nueva", n.version()); m.put("url", n.url()); }
        return m;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void alArrancar() {
        if (!enabled || !repo.matches("[\\w.-]+/[\\w.-]+") || actual.equals("desarrollo")) return;
        Thread t = new Thread(() -> {
            try {
                while (true) {
                    revisar();
                    Thread.sleep(Duration.ofHours(24).toMillis());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "jarvis-actualizaciones");
        t.setDaemon(true);
        t.start();
    }

    void revisar() {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + repo + "/releases/latest"))
                    .timeout(Duration.ofSeconds(10)).header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "jarvis-inventario").GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return;
            nueva = deRelease(res.body(), actual, repo);
            if (nueva != null) log.info("Hay una versión nueva de JARVIS: {} (tiene la {}). {}", nueva.version(), actual, nueva.url());
        } catch (Exception e) {
            log.debug("No pude revisar si hay versión nueva: {}", e.toString());
        }
    }

    /** La release si es más nueva que {@code actual}; null si no lo es o si no parece válida. */
    static Nueva deRelease(String json, String actual, String repo) {
        try {
            JsonNode r = new ObjectMapper().readTree(json);
            if (r.path("draft").asBoolean(false) || r.path("prerelease").asBoolean(false)) return null;
            String tag = r.path("tag_name").asText("").trim().replaceFirst("^[vV]", "");
            if (!tag.matches("\\d+(\\.\\d+){0,3}") || comparar(tag, actual) <= 0) return null;
            String url = r.path("html_url").asText("");
            String esperado = "https://github.com/" + repo + "/releases/";
            if (!url.startsWith(esperado)) url = esperado;
            return new Nueva(tag, url);
        } catch (Exception e) {
            return null;
        }
    }

    /** Compara versiones tipo 1.2.3; lo que no es número cuenta como 0. */
    static int comparar(String a, String b) {
        String[] x = Objects.requireNonNullElse(a, "").split("[.-]"), y = Objects.requireNonNullElse(b, "").split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int c = Integer.compare(num(x, i), num(y, i));
            if (c != 0) return c;
        }
        return 0;
    }

    private static int num(String[] p, int i) {
        if (i >= p.length) return 0;
        try { return Integer.parseInt(p[i]); } catch (NumberFormatException e) { return 0; }
    }
}
