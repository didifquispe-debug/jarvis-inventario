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

    /** Puerto por defecto si application.properties no dice otro (debe coincidir con server.port). */
    public static final int PUERTO_POR_DEFECTO = 8081;

    public static void main(String[] args) {
        Properties cfg = loadConfig();
        int port = Integer.parseInt(cfg.getProperty("server.port", String.valueOf(PUERTO_POR_DEFECTO)).trim());
        String archivoClave = cfg.getProperty("jarvis.token-file", AccessToken.ARCHIVO_POR_DEFECTO)
                .replace("${user.home}", System.getProperty("user.home"));
        String token = AccessToken.resolverSinGenerar(cfg.getProperty("jarvis.token", ""), archivoClave);

        // Navegador en el que se abre la ventana de escritorio (chrome | edge | auto)
        AppWindow.setPreferred(cfg.getProperty("jarvis.browser", "chrome"));

        // Si JARVIS ya está encendido (por ejemplo, le diste doble clic otra vez),
        // solo abre su ventana en lugar de fallar con "Port ... was already in use".
        if (alreadyRunning(port)) {
            AppWindow.open(DesktopShell.localUrl(port, token));
            System.exit(0);
        }

        new SpringApplicationBuilder(JarvisApplication.class)
                .headless(false)   // necesario para el ícono de bandeja y abrir programas
                .run(args);
    }

    /**
     * Lee application.properties igual que Spring: como ISO-8859-1 (por eso la ñ va como ñ).
     * Si se leyera como UTF-8, una ñ escrita a mano valdría distinto aquí que en Spring.
     */
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
