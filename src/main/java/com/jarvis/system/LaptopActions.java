/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.system;

import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class LaptopActions {

    private static final String OS = System.getProperty("os.name").toLowerCase();
    private static final boolean WIN = OS.contains("win");
    private static final boolean MAC = OS.contains("mac");

    private static final Map<String, String[]> APPS = Map.of(
            "calculadora",    new String[]{"calc",     "Calculator",  "gnome-calculator"},
            "bloc de notas",  new String[]{"notepad",  "TextEdit",    "gedit"},
            "explorador",     new String[]{"explorer", "Finder",      "nautilus"},
            "terminal",       new String[]{"cmd",      "Terminal",    "gnome-terminal"},
            "paint",          new String[]{"mspaint",  "Preview",     "gimp"},
            "configuracion",  new String[]{"ms-settings:", "System Settings", "gnome-control-center"},
            "intellij",       new String[]{"idea64",   "IntelliJ IDEA", "idea"}
    );

    public boolean openUrl(String url) {
        URI uri = urlSegura(url);
        if (uri == null) return false;
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(uri);
                return true;
            }
            String u = uri.toASCIIString();
            run(WIN ? List.of("rundll32", "url.dll,FileProtocolHandler", u)
                    : MAC ? List.of("open", u) : List.of("xdg-open", u));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static URI urlSegura(String url) {
        if (url == null || url.isBlank() || url.chars().anyMatch(c -> c < 0x21 || "\"<>^`{|}\\".indexOf(c) >= 0)) return null;
        try {
            URI uri = new URI(url.trim());
            String esquema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!(esquema.equals("http") || esquema.equals("https")) || uri.getHost() == null) return null;
            return uri;
        } catch (Exception e) {
            return null;
        }
    }

    public Optional<String> openApp(String spoken) {
        for (var e : APPS.entrySet()) {
            if (spoken.contains(e.getKey())) {
                String[] c = e.getValue();
                try {
                    if (WIN) run(List.of("cmd", "/c", "start", "", c[0]));
                    else if (MAC) run(List.of("open", "-a", c[1]));
                    else run(List.of(c[2]));
                    return Optional.of(e.getKey());
                } catch (Exception ex) {
                    return Optional.of(e.getKey() + " (falló: " + ex.getMessage() + ")");
                }
            }
        }
        return Optional.empty();
    }

    private void run(List<String> cmd) throws Exception {
        new ProcessBuilder(cmd).inheritIO().start();
    }
}
