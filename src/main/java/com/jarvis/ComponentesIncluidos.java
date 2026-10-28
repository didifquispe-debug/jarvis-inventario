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
        if (dir == null) return;
        String vosk = cfg.getProperty("jarvis.voz.vosk-model", "");
        usarSiExiste("jarvis.voz.vosk-model", dir.resolve("voz/vosk").resolve(Path.of(vosk.isBlank() ? "vosk-model-small-es-0.42" : vosk).getFileName()));
        Path piper = dir.resolve("voz/piper");
        if (Files.isRegularFile(piper.resolve("piper.exe"))) {
            usarSiExiste("jarvis.voz.piper-exe", piper.resolve("piper.exe"));
            String voz = cfg.getProperty("jarvis.voz.piper-voz", "");
            usarSiExiste("jarvis.voz.piper-voz", piper.resolve("voces").resolve(Path.of(voz.isBlank() ? "es_ES-davefx-medium.onnx" : voz).getFileName()));
        }
        if (!"false".equalsIgnoreCase(cfg.getProperty("jarvis.ia.enabled", "true").trim())) {
            arrancarOllama(dir.resolve("ia"), cfg.getProperty("jarvis.ia.base-url", "http://localhost:11434/v1"));
        }
    }

    private static void usarSiExiste(String propiedad, Path ruta) {
        if (System.getProperty(propiedad) == null && Files.exists(ruta)) System.setProperty(propiedad, ruta.toString());
    }

    private static void arrancarOllama(Path ia, String baseUrl) {
        Path exe = ia.resolve("ollama/ollama.exe");
        if (!Files.isRegularFile(exe)) return;
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
            pb.environment().put("OLLAMA_MODELS", ia.resolve("modelos").toString());
            ollama = pb.start();
            puertoOllama = puerto;
            Runtime.getRuntime().addShutdownHook(new Thread(ComponentesIncluidos::detenerOllama, "jarvis-ollama-cierre"));
        } catch (Exception e) {
            System.err.println("No pude iniciar el Ollama incluido: " + e);
        }
    }

    /** Espera a que el Ollama incluido acepte conexiones; vuelve enseguida si JARVIS no lo inició. */
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
