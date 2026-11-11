/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.desktop;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public final class AppWindow {

    private static final String OS = System.getProperty("os.name").toLowerCase();

    private static volatile String preferred = "chrome";

    private AppWindow() {}

    public static void setPreferred(String browser) {
        if (browser != null && !browser.isBlank()) preferred = browser.trim().toLowerCase();
    }

    /** Cómo quedó la ventana: en Chrome/Edge como aplicación, en el navegador predeterminado, o no se pudo abrir. */
    public enum Abierta { VENTANA, NAVEGADOR, NO }

    /** Perfil propio de la ventana: no se mezcla con el Chrome personal y permite la impresión directa. */
    static Path perfil() {
        return Paths.get(System.getProperty("user.home"), ".jarvis-ventana");
    }

    public static Abierta open(String url) { return open(url, false); }

    /** {@code impresionDirecta}: imprime en la impresora predeterminada sin mostrar el diálogo (--kiosk-printing). */
    public static Abierta open(String url, boolean impresionDirecta) {
        String exe = findBrowser(preferred);
        try {
            if (exe != null) {
                List<String> cmd = new ArrayList<>();
                if (OS.contains("mac")) {
                    cmd.addAll(List.of("open", "-na", exe, "--args"));
                } else {
                    cmd.add(exe);
                }
                cmd.addAll(argumentos(url, impresionDirecta, perfil()));
                new ProcessBuilder(cmd).start();
                return Abierta.VENTANA;
            }
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
                return Abierta.NAVEGADOR;
            }
        } catch (Exception e) {
            System.err.println("No pude abrir la ventana de JARVIS: " + e.getMessage());
        }
        return Abierta.NO;
    }

    static List<String> argumentos(String url, boolean impresionDirecta, Path perfil) {
        List<String> a = new ArrayList<>();
        a.add("--app=" + url);
        a.add("--user-data-dir=" + perfil.toAbsolutePath());
        a.add("--no-first-run");
        a.add("--no-default-browser-check");
        if (impresionDirecta) a.add("--kiosk-printing");
        a.add("--window-size=1280,820");
        a.add("--start-maximized");
        return a;
    }

    private static String findBrowser(String pref) {
        boolean edgeFirst = "edge".equals(pref);
        String chrome = chromePath();
        String edge = edgePath();
        if (edgeFirst) {
            if (edge != null) return edge;
            return chrome;
        }
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
