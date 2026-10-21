package com.jarvis.desktop;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Abre la interfaz de JARVIS en su propia ventana (sin barra de direcciones ni pestañas),
 * usando el "modo aplicación" de Chrome o Edge. Así funcionan el micrófono y la voz.
 *
 * El navegador se elige con la propiedad jarvis.browser (chrome | edge | auto).
 * Si el elegido no está, usa el otro; si no hay ninguno, abre el navegador por defecto.
 */
public final class AppWindow {

    private static final String OS = System.getProperty("os.name").toLowerCase();

    /** Preferencia leída de application.properties al arrancar. Por defecto: chrome. */
    private static volatile String preferred = "chrome";

    private AppWindow() {}

    public static void setPreferred(String browser) {
        if (browser != null && !browser.isBlank()) preferred = browser.trim().toLowerCase();
    }

    public static void open(String url) {
        String exe = findBrowser(preferred);
        try {
            if (exe != null) {
                List<String> cmd = new ArrayList<>();
                if (OS.contains("mac")) {
                    cmd.addAll(List.of("open", "-na", exe, "--args"));
                } else {
                    cmd.add(exe);
                }
                cmd.add("--app=" + url);
                cmd.add("--window-size=1280,820");
                cmd.add("--start-maximized");
                new ProcessBuilder(cmd).start();
                return;
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception e) {
            System.err.println("No pude abrir la ventana de JARVIS: " + e.getMessage());
        }
    }

    /** Devuelve la ruta del navegador elegido; si no está, prueba el otro. */
    private static String findBrowser(String pref) {
        boolean edgeFirst = "edge".equals(pref);
        String chrome = chromePath();
        String edge = edgePath();
        if (edgeFirst) {
            if (edge != null) return edge;
            return chrome;      // si pidió Edge pero no está, usa Chrome
        }
        // "chrome" o "auto": Chrome primero
        if (chrome != null) return chrome;
        return edge;
    }

    private static String chromePath() {
        if (OS.contains("win")) {
            return firstExisting(
                    env("ProgramFiles") + "\\Google\\Chrome\\Application\\chrome.exe",
                    env("ProgramFiles(x86)") + "\\Google\\Chrome\\Application\\chrome.exe",
                    env("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe");
        }
        if (OS.contains("mac")) {
            return new File("/Applications/Google Chrome.app").exists() ? "Google Chrome" : null;
        }
        return firstExisting("/usr/bin/google-chrome", "/usr/bin/google-chrome-stable",
                "/usr/bin/chromium", "/usr/bin/chromium-browser");
    }

    private static String edgePath() {
        if (OS.contains("win")) {
            return firstExisting(
                    env("ProgramFiles(x86)") + "\\Microsoft\\Edge\\Application\\msedge.exe",
                    env("ProgramFiles") + "\\Microsoft\\Edge\\Application\\msedge.exe");
        }
        if (OS.contains("mac")) {
            return new File("/Applications/Microsoft Edge.app").exists() ? "Microsoft Edge" : null;
        }
        return firstExisting("/usr/bin/microsoft-edge", "/usr/bin/microsoft-edge-stable");
    }

    private static String env(String k) {
        String v = System.getenv(k);
        return v == null ? "" : v;
    }

    private static String firstExisting(String... paths) {
        for (String p : paths) {
            if (p != null && !p.isBlank() && new File(p).isFile()) return p;
        }
        return null;
    }
}
