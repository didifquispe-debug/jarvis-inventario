package com.jarvis.voz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * La voz de JARVIS por los parlantes de esta computadora, sin Internet.
 *
 * <ul>
 *   <li>{@code piper}: piper.exe con una voz en español ({@code jarvis.voz.piper-exe}, {@code jarvis.voz.piper-voz}).</li>
 *   <li>{@code windows}: las voces instaladas en Windows (System.Speech, por PowerShell).</li>
 *   <li>{@code navegador}: no habla aquí; la pantalla usa la voz del navegador.</li>
 * </ul>
 *
 * <p>Si Piper no está instalado se usan las voces de Windows. En los dos casos se genera un .wav temporal y se
 * reproduce desde Java, así {@link #callar()} lo corta al instante.</p>
 */
@Component
public class HablaLocal {

    private static final Logger log = LoggerFactory.getLogger(HablaLocal.class);

    /** Una respuesta muy larga (una lista de precios) se corta: nadie escucha diez minutos. */
    private static final int MAX_CARACTERES = 1500;
    private static final long MAX_GENERAR_S = 60;

    public enum Modo { PIPER, WINDOWS, NAVEGADOR }

    private final Modo pedido;
    private final Path piperExe;
    private final Path piperVoz;
    private final boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");

    /** Sube con cada frase nueva y con callar(): la frase que ve otro número sabe que debe parar. */
    private volatile long turno;
    private volatile Process proceso;
    private volatile SourceDataLine linea;

    public HablaLocal(@Value("${jarvis.voz.hablar:piper}") String hablar,
                      @Value("${jarvis.voz.piper-exe:${user.home}/jarvis-voz/piper/piper.exe}") String piperExe,
                      @Value("${jarvis.voz.piper-voz:${user.home}/jarvis-voz/piper/voces/es_ES-davefx-medium.onnx}") String piperVoz) {
        this.pedido = leerModo(hablar);
        this.piperExe = Paths.get(piperExe);
        this.piperVoz = Paths.get(piperVoz);
        log.info("Voz de JARVIS: pedida {}, se usa {} ({}).", pedido, modo(), motivo());
    }

    static Modo leerModo(String s) {
        try { return Modo.valueOf(s.trim().toUpperCase()); } catch (RuntimeException e) { return Modo.PIPER; }
    }

    /** Lo que dice la configuración (jarvis.voz.hablar). */
    public Modo pedido() { return pedido; }

    /** Lo que de verdad se usa: Piper solo si está instalado, si no las voces de Windows. */
    public Modo modo() {
        if (pedido == Modo.NAVEGADOR) return Modo.NAVEGADOR;
        if (pedido == Modo.PIPER && piperListo()) return Modo.PIPER;
        return windows ? Modo.WINDOWS : Modo.NAVEGADOR;
    }

    public boolean hablaAqui() { return modo() != Modo.NAVEGADOR; }

    public String motivo() {
        return switch (modo()) {
            case PIPER -> "Voz de Piper (" + nombreVoz() + "), sin Internet.";
            case WINDOWS -> pedido == Modo.PIPER
                    ? "Piper no está instalado en " + piperExe.getParent() + ": uso las voces de Windows."
                    : "Voces de Windows, sin Internet.";
            case NAVEGADOR -> pedido == Modo.NAVEGADOR ? "Configurado para usar la voz del navegador (jarvis.voz.hablar)."
                    : "Este sistema no es Windows y Piper no está instalado: uso la voz del navegador.";
        };
    }

    private boolean piperListo() {
        return Files.isRegularFile(piperExe) && Files.isRegularFile(piperVoz);
    }

    private String nombreVoz() {
        return piperVoz.getFileName().toString().replaceFirst("\\.onnx$", "");
    }

    /**
     * Dice el texto y vuelve al terminar.
     *
     * @return true si lo dijo completo, false si lo cortó {@link #callar()} u otra frase
     * @throws IOException si no pudo (la pantalla usa entonces la voz del navegador)
     */
    public boolean hablar(String texto) throws IOException, InterruptedException {
        String t = limpiar(texto);
        callar();   // una frase nueva reemplaza a la anterior
        synchronized (this) {
            long mio = ++turno;
            if (t.isEmpty()) return true;
            Modo m = modo();
            if (m == Modo.NAVEGADOR) throw new IOException("Esta computadora usa la voz del navegador.");
            Path wav = Files.createTempFile("jarvis-voz-", ".wav");
            try {
                if (m == Modo.PIPER) generarPiper(t, wav, mio);
                else generarWindows(t, wav, mio);
                if (mio != turno) return false;
                return reproducir(wav, mio);
            } finally {
                Files.deleteIfExists(wav);
            }
        }
    }

    /** Calla lo que se esté diciendo (o generando). */
    public void callar() {
        turno++;
        Process p = proceso;
        if (p != null) p.destroyForcibly();
        SourceDataLine l = linea;
        if (l != null) { l.stop(); l.flush(); }   // flush suelta un write() bloqueado
    }

    static String limpiar(String texto) {
        if (texto == null) return "";
        String t = texto.replaceAll("[•\\r\\n]+", ". ").replaceAll("[*_#`]", "").replaceAll("\\s+", " ").trim();
        return t.length() > MAX_CARACTERES ? t.substring(0, MAX_CARACTERES) : t;
    }

    private void generarPiper(String texto, Path wav, long mio) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(piperExe.toString(), "--model", piperVoz.toString(), "--output_file", wav.toString())
                .directory(piperExe.getParent().toFile())   // piper busca espeak-ng-data junto a él
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        ejecutar(pb, texto, mio);
    }

    private void generarWindows(String texto, Path wav, long mio) throws IOException, InterruptedException {
        // El texto va en Base64: así ninguna comilla ni signo del usuario puede romper (o inyectar en) el script
        String b64 = Base64.getEncoder().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
        String salida = wav.toString().replace("'", "''");
        String script = "Add-Type -AssemblyName System.Speech;"
                + "$s = New-Object System.Speech.Synthesis.SpeechSynthesizer;"
                + "$v = $s.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -like 'es*' } | Select-Object -First 1;"
                + "if ($v) { $s.SelectVoice($v.VoiceInfo.Name) };"
                + "$s.SetOutputToWaveFile('" + salida + "');"
                + "$s.Speak([Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + b64 + "')));"
                + "$s.Dispose()";
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        ejecutar(pb, null, mio);
    }

    private void ejecutar(ProcessBuilder pb, String entrada, long mio) throws IOException, InterruptedException {
        Process p = pb.start();
        proceso = p;
        try {
            try (OutputStream in = p.getOutputStream()) {
                if (entrada != null) in.write((entrada + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!p.waitFor(MAX_GENERAR_S, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("La voz tardó demasiado en generarse.");
            }
            if (mio != turno) return;   // la callaron mientras se generaba
            if (p.exitValue() != 0) throw new IOException("El generador de voz terminó con error " + p.exitValue() + ".");
        } finally {
            proceso = null;
        }
    }

    private boolean reproducir(Path wav, long mio) throws IOException {
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(wav.toFile())) {
            AudioFormat f = audio.getFormat();
            SourceDataLine l = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, f));
            l.open(f);
            linea = l;
            try {
                l.start();
                byte[] buf = new byte[4096];
                for (int n; (n = audio.read(buf)) > 0; ) {
                    if (mio != turno) return false;
                    l.write(buf, 0, n);
                }
                if (mio != turno) return false;
                l.drain();
                return mio == turno;
            } finally {
                linea = null;
                l.close();
            }
        } catch (javax.sound.sampled.UnsupportedAudioFileException | javax.sound.sampled.LineUnavailableException
                 | IllegalArgumentException e) {
            throw new IOException("No pude reproducir la voz: " + e.getMessage(), e);
        }
    }
}
