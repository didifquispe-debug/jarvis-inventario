/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * DYSA: usa Vosk, Piper y Ollama cuando vienen dentro de la carpeta del programa instalado
 * (JARVIS\voz y JARVIS\ia). Si no están, todo sigue como antes: se leen las rutas de
 * application.properties (carpeta jarvis-voz del usuario y el Ollama que tenga instalado).
 * Si Ollama no está encendido, JARVIS lo enciende: el incluido, o si no hay, el instalado en Windows.
 *
 * <pre>
 * JARVIS\JARVIS.exe
 * JARVIS\voz\vosk\vosk-model-small-es-0.42\
 * JARVIS\voz\piper\piper.exe  y  voz\piper\voces\es_ES-davefx-medium.onnx (+ .onnx.json)
 * JARVIS\ia\ollama\ollama.exe y  ia\modelos\
 * </pre>
 */
public final class ComponentesIncluidos {

    private static volatile Process ollama;
    private static volatile int puertoOllama;

    private ComponentesIncluidos() { }

    /** Carpeta donde está JARVIS.exe, o null si se ejecuta desde el IDE o con java -jar. */
    static Path carpetaPrograma() {
        String exe = System.getProperty("jpackage.app-path");
        if (exe != null && !exe.isBlank()) return Path.of(exe).toAbsolutePath().getParent();
        try {
            Path jar = Path.of(JarvisApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path app = jar.getParent();
            if (Files.isRegularFile(jar) && app != null && app.getFileName().toString().equals("app")) return app.getParent();
        } catch (Exception ignored) { }
        return null;
    }

    static void preparar(Properties cfg) {
        Path dir = carpetaPrograma();
        boolean ia = !"false".equalsIgnoreCase(cfg.getProperty("jarvis.ia.enabled", "true").trim());
        String baseUrl = cfg.getProperty("jarvis.ia.base-url", "http://localhost:11434/v1");
        if (dir == null) {
            if (ia) arrancarOllama(null, ollamaInstalado(), baseUrl);
            return;
        }
        String vosk = cfg.getProperty("jarvis.voz.vosk-model", "");
        usarSiExiste("jarvis.voz.vosk-model", dir.resolve("voz/vosk").resolve(Path.of(vosk.isBlank() ? "vosk-model-small-es-0.42" : vosk).getFileName()));
        Path piper = dir.resolve("voz/piper");
        if (Files.isRegularFile(piper.resolve("piper.exe"))) {
            usarSiExiste("jarvis.voz.piper-exe", piper.resolve("piper.exe"));
            String voz = cfg.getProperty("jarvis.voz.piper-voz", "");
            usarSiExiste("jarvis.voz.piper-voz", piper.resolve("voces").resolve(Path.of(voz.isBlank() ? "es_ES-davefx-medium.onnx" : voz).getFileName()));
        }
        if (ia) {
            Path incluido = dir.resolve("ia/ollama/ollama.exe");
            if (Files.isRegularFile(incluido)) arrancarOllama(dir.resolve("ia/modelos"), incluido, baseUrl);
            else arrancarOllama(null, ollamaInstalado(), baseUrl);
        }
    }

    /** ollama.exe que instala OllamaSetup.exe (o el que esté en el PATH), o null si no hay. */
    static Path ollamaInstalado() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            Path exe = Path.of(local, "Programs", "Ollama", "ollama.exe");
            if (Files.isRegularFile(exe)) return exe;
        }
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String d : path.split(java.io.File.pathSeparator)) {
            for (String nombre : new String[] {"ollama.exe", "ollama"}) {
                try {
                    Path exe = Path.of(d.trim(), nombre);
                    if (Files.isRegularFile(exe)) return exe;
                } catch (Exception ignored) { }
            }
        }
        return null;
    }

    private static void usarSiExiste(String propiedad, Path ruta) {
        if (System.getProperty(propiedad) == null && Files.exists(ruta)) System.setProperty(propiedad, ruta.toString());
    }

    /** modelos = carpeta de modelos del Ollama incluido; null = la que use el Ollama instalado (~/.ollama). */
    private static void arrancarOllama(Path modelos, Path exe, String baseUrl) {
        if (exe == null || !Files.isRegularFile(exe)) return;
        URI uri;
        try { uri = URI.create(baseUrl.trim()); } catch (IllegalArgumentException e) { return; }
        String host = uri.getHost();
        if (host == null || !(host.equals("localhost") || host.equals("127.0.0.1"))) return;
        int puerto = uri.getPort() > 0 ? uri.getPort() : 11434;
        if (abierto(puerto)) return;
        try {
            ProcessBuilder pb = new ProcessBuilder(exe.toString(), "serve")
                    .directory(exe.getParent().toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(Path.of(System.getProperty("user.home"), "jarvis-ollama.log").toFile()));
            pb.environment().put("OLLAMA_HOST", "127.0.0.1:" + puerto);
            if (modelos != null) pb.environment().put("OLLAMA_MODELS", modelos.toString());
            ollama = pb.start();
            puertoOllama = puerto;
            Runtime.getRuntime().addShutdownHook(new Thread(ComponentesIncluidos::detenerOllama, "jarvis-ollama-cierre"));
        } catch (Exception e) {
            System.err.println("No pude iniciar Ollama (" + exe + "): " + e);
        }
    }

    /** Espera a que el Ollama que inició JARVIS acepte conexiones; vuelve enseguida si JARVIS no lo inició. */
    public static void esperarOllama(long maxMs) throws InterruptedException {
        Process p = ollama;
        long fin = System.currentTimeMillis() + maxMs;
        while (p != null && p.isAlive() && !abierto(puertoOllama) && System.currentTimeMillis() < fin) Thread.sleep(250);
    }

    private static void detenerOllama() {
        Process p = ollama;
        if (p == null) return;
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
    }

    private static boolean abierto(int puerto) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", puerto), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
