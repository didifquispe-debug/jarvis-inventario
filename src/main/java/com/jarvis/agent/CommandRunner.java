package com.jarvis.agent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ÚNICO camino por el que JARVIS ejecuta comandos del sistema (lo usa {@link AgentTools}).
 *
 * <ul>
 *   <li>Bloquea comandos destructivos salvo que se permita explícitamente.</li>
 *   <li>En Windows lee la salida con la codificación de la consola (Cp850/Cp437…), no UTF-8:
 *       cmd.exe escribe en la página de códigos OEM y con UTF-8 las tildes salen rotas.</li>
 *   <li>Lee la salida en un hilo aparte. Si se leyera en el mismo hilo, un comando que se cuelga
 *       nunca llegaría al timeout; y si nadie lee, un comando con mucha salida llena el buffer
 *       del pipe (~4-64 KB) y se queda bloqueado para siempre.</li>
 * </ul>
 */
public class CommandRunner {

    private static final boolean WIN = System.getProperty("os.name").toLowerCase().contains("win");

    // Comandos que pueden destruir datos o apagar el equipo. Se buscan en cualquier parte del
    // comando (también dentro de "powershell -c ..." o tras "&&"), a propósito con criterio amplio.
    private static final Pattern PELIGROSO = Pattern.compile(
            // "format" sí, pero no "Format-Table"/"--format" (PowerShell/git, inofensivos)
            "(?i)(?<![-\\w])format(?![-\\w])" +
            "|\\b(mkfs(\\.\\w+)?|diskpart|fdisk|shutdown|reboot|halt|poweroff|" +
            "del|erase|rm|rmdir|rd|remove-item|ri|clear-content|" +
            "reg\\s+delete|cipher\\s+/w|sfc|bcdedit|vssadmin|wbadmin|takeown|icacls|cacls|" +
            "stop-computer|restart-computer)\\b" +
            "|\\bdd\\s+if=" +
            "|:\\s*\\(\\s*\\)\\s*\\{.*\\|.*&\\s*\\}");  // fork bomb  :(){ :|:& };:

    /** Resultado de un comando: código de salida (-1 si se canceló) y salida recortada. */
    public record Resultado(int codigo, String salida, boolean timeout, boolean bloqueado) {}

    private final boolean allowDangerous;
    private final int maxOutput;
    private final long timeoutMs;
    private final Charset charset;

    /**
     * @param consoleCharset codificación de la consola; vacío = detectarla (Windows) o UTF-8 (resto)
     */
    public CommandRunner(boolean allowDangerous, int maxOutput, long timeoutMs, String consoleCharset) {
        this.allowDangerous = allowDangerous;
        this.maxOutput = maxOutput;
        this.timeoutMs = timeoutMs;
        this.charset = consoleCharset == null || consoleCharset.isBlank() ? detectarCharset() : Charset.forName(consoleCharset);
    }

    public Charset charset() { return charset; }

    /** ¿El comando está prohibido? (siempre false si allow-dangerous=true) */
    public boolean esPeligroso(String comando) {
        return !allowDangerous && comando != null && PELIGROSO.matcher(comando).find();
    }

    /** Qué palabra hizo saltar el freno (para explicárselo al usuario y al modelo). */
    public static String motivo(String comando) {
        Matcher m = PELIGROSO.matcher(comando == null ? "" : comando);
        return m.find() ? m.group().trim() : "";
    }

    public Resultado ejecutar(String comando, Path workdir) throws Exception {
        if (comando == null || comando.isBlank()) return new Resultado(-1, "ERROR: comando vacío.", false, false);
        if (esPeligroso(comando))
            return new Resultado(-1, "BLOQUEADO por seguridad: el comando contiene \"" + motivo(comando)
                    + "\", que puede borrar o dañar datos (jarvis.agent.allow-dangerous=false).", false, true);

        ProcessBuilder pb = WIN ? new ProcessBuilder("cmd.exe", "/c", comando) : new ProcessBuilder("sh", "-c", comando);
        if (workdir != null) pb.directory(workdir.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        proc.getOutputStream().close();   // sin stdin: un comando que pide datos termina en vez de esperar

        CompletableFuture<byte[]> lector = CompletableFuture.supplyAsync(() -> leerAcotado(proc.getInputStream(), maxOutput * 4L));
        boolean fin = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!fin) {
            proc.descendants().forEach(ProcessHandle::destroyForcibly);   // ej. el ping que lanzó cmd.exe
            proc.destroyForcibly();
        }
        byte[] bytes;
        try { bytes = lector.get(5, TimeUnit.SECONDS); } catch (Exception e) { bytes = new byte[0]; }
        String out = clip(new String(bytes, charset));

        if (!fin) return new Resultado(-1, "ERROR: el comando tardó más de " + (timeoutMs / 1000) + " s y se canceló.\n" + out, true, false);
        return new Resultado(proc.exitValue(), out.isBlank() ? "(sin salida)" : out, false, false);
    }

    /**
     * Lee todo el stream pero solo guarda los primeros {@code limite} bytes: hay que seguir
     * leyendo (y descartando) para que el proceso no se bloquee con el pipe lleno.
     */
    static byte[] leerAcotado(InputStream in, long limite) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(b)) != -1) {
                long cabe = limite - buf.size();
                if (cabe > 0) buf.write(b, 0, (int) Math.min(n, cabe));
            }
        } catch (Exception ignored) { /* el proceso murió o se canceló: devolvemos lo leído */ }
        return buf.toByteArray();
    }

    private String clip(String s) {
        return s.length() <= maxOutput ? s : s.substring(0, maxOutput) + "\n…(salida recortada)";
    }

    /** En Windows pregunta a "chcp" la página de códigos OEM (850 en español, 437 en inglés…). */
    static Charset detectarCharset() {
        if (!WIN) return StandardCharsets.UTF_8;
        try {
            Process p = new ProcessBuilder("cmd.exe", "/c", "chcp").redirectErrorStream(true).start();
            String out = new String(leerAcotado(p.getInputStream(), 512), StandardCharsets.US_ASCII);
            p.waitFor(5, TimeUnit.SECONDS);
            Matcher m = Pattern.compile("(\\d{3,5})").matcher(out);
            if (m.find()) {
                String cp = m.group(1);
                if (cp.equals("65001")) return StandardCharsets.UTF_8;
                for (String nombre : new String[]{"Cp" + cp, "IBM" + cp, "x-IBM" + cp, "windows-" + cp})
                    if (Charset.isSupported(nombre)) return Charset.forName(nombre);
            }
        } catch (Exception ignored) { }
        return Charset.forName("Cp850");
    }
}
