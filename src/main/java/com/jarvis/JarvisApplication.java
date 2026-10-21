package com.jarvis;

import com.jarvis.desktop.AppWindow;
import com.jarvis.desktop.DesktopShell;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Properties;

@SpringBootApplication
public class JarvisApplication {

    public static void main(String[] args) {
        Properties cfg = loadConfig();
        int port = Integer.parseInt(cfg.getProperty("server.port", "8080").trim());
        String token = cfg.getProperty("jarvis.token", "").trim();

        // Navegador en el que se abre la ventana de escritorio (chrome | edge | auto)
        AppWindow.setPreferred(cfg.getProperty("jarvis.browser", "chrome"));

        // Si JARVIS ya está encendido (por ejemplo, le diste doble clic otra vez),
        // solo abre su ventana en lugar de fallar con "Port 8080 was already in use".
        if (alreadyRunning(port)) {
            AppWindow.open(DesktopShell.localUrl(port, token));
            System.exit(0);
        }

        new SpringApplicationBuilder(JarvisApplication.class)
                .headless(false)   // necesario para el ícono de bandeja y abrir programas
                .run(args);
    }

    private static Properties loadConfig() {
        Properties p = new Properties();
        try (InputStream in = JarvisApplication.class.getResourceAsStream("/application.properties")) {
            if (in != null) p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
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
