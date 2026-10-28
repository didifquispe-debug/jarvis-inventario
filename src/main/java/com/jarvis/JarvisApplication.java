/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis;

import com.jarvis.desktop.AppWindow;
import com.jarvis.desktop.DesktopShell;
import com.jarvis.security.AccessToken;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

@SpringBootApplication
public class JarvisApplication {

    public static final int PUERTO_POR_DEFECTO = 8081;

    public static void main(String[] args) {
        Properties cfg = loadConfig();
        int port = Integer.parseInt(cfg.getProperty("server.port", String.valueOf(PUERTO_POR_DEFECTO)).trim());
        String archivoClave = cfg.getProperty("jarvis.token-file", AccessToken.ARCHIVO_POR_DEFECTO)
                .replace("${user.home}", System.getProperty("user.home"));
        String token = AccessToken.resolverSinGenerar(cfg.getProperty("jarvis.token", ""), archivoClave);

        AppWindow.setPreferred(cfg.getProperty("jarvis.browser", "chrome"));

        if (alreadyRunning(port)) {
            AppWindow.open(DesktopShell.localUrl(port, token));
            System.exit(0);
        }

        new SpringApplicationBuilder(JarvisApplication.class)
                .headless(false)
                .run(args);
    }

    static Properties loadConfig() {
        Properties p = new Properties();
        try (InputStream in = JarvisApplication.class.getResourceAsStream("/application.properties")) {
            if (in != null) p.load(new java.io.InputStreamReader(in, StandardCharsets.ISO_8859_1));
        } catch (Exception ignored) { }
        return p;
    }

    private static boolean alreadyRunning(int port) {
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/status"))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return res.statusCode() == 200 && res.body().contains("online");
        } catch (Exception e) {
            return false;
        }
    }
}
