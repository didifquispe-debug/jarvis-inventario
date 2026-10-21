package com.jarvis.system;

import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Todo lo que toca el sistema operativo de la laptop vive aquí.
 * Detecta Windows / macOS / Linux y usa el comando adecuado.
 */
@Component
public class LaptopActions {

    private static final String OS = System.getProperty("os.name").toLowerCase();
    private static final boolean WIN = OS.contains("win");
    private static final boolean MAC = OS.contains("mac");

    /** nombre hablado -> {windows, mac, linux} */
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
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                return true;
            }
            run(WIN ? List.of("cmd", "/c", "start", "", url)
                    : MAC ? List.of("open", url) : List.of("xdg-open", url));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Devuelve el nombre de la app abierta, o vacío si no la conoce. */
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
