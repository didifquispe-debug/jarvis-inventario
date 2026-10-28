/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.voz;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Component
public class HablaLocal {

    private static final Logger log = LoggerFactory.getLogger(HablaLocal.class);
    private static final long MAX_GENERAR_S = 60;

    public enum Modo { PIPER, WINDOWS, NAVEGADOR }

    private final Path piperExe;
    private final Path piperVoz;
    private final Modo pedido;
    private final Modo modo;
    private final String motivo;

    private final Object turno = new Object();
    private volatile long frase;
    private volatile Clip sonando;
    private volatile Process generando;

    public HablaLocal(@Value("${jarvis.voz.piper-exe:${user.home}/jarvis-voz/piper/piper.exe}") String piperExe,
                      @Value("${jarvis.voz.piper-voz:${user.home}/jarvis-voz/piper/voces/es_ES-davefx-medium.onnx}") String piperVoz,
                      @Value("${jarvis.voz.hablar:piper}") String hablar) {
        this.piperExe = Path.of(piperExe);
        this.piperVoz = Path.of(piperVoz);
        this.pedido = leerModo(hablar);
        String m = "";
        Modo efectivo = pedido;
        if (efectivo == Modo.PIPER && !piperListo()) {
            m = !Files.isRegularFile(this.piperExe) ? "No encuentro Piper en " + this.piperExe + "."
                    : "No encuentro la voz de Piper " + this.piperVoz + " (con su .onnx.json).";
            efectivo = Modo.WINDOWS;
        }
        if (efectivo == Modo.WINDOWS && !esWindows()) efectivo = Modo.NAVEGADOR;
        this.modo = efectivo;
        this.motivo = m;
        switch (modo) {
            case PIPER -> log.info("Voz de JARVIS: Piper ({}).", this.piperVoz.getFileName());
            case WINDOWS -> log.info("Voz de JARVIS: voces de Windows.{}", m.isEmpty() ? "" : " " + m);
            case NAVEGADOR -> log.info("Voz de JARVIS: la del navegador.{}", m.isEmpty() ? "" : " " + m);
        }
    }

    static Modo leerModo(String s) {
        try { return Modo.valueOf(s.trim().toUpperCase(Locale.ROOT)); } catch (RuntimeException e) { return Modo.PIPER; }
    }

    private boolean piperListo() {
        return Files.isRegularFile(piperExe) && Files.isRegularFile(piperVoz)
                && Files.isRegularFile(Path.of(piperVoz + ".json"));
    }

    static boolean esWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    public Modo modo() { return modo; }

    public String motivo() { return motivo; }

    public Modo pedido() { return pedido; }

    public boolean hablaAqui() { return modo != Modo.NAVEGADOR; }

    static String paraLeer(String texto) {
        return texto == null ? "" : texto.replaceAll("([,.:;!?])[\\s•]*[•\\r\\n][\\s•]*", "$1 ")
                .replaceAll("[•\\r\\n]+", ". ").replaceAll("\\.\\s*\\.", ".")
                .replaceAll("\\s+", " ").replaceFirst("^[.\\s]+", "").trim();
    }

    public boolean hablar(String texto) throws IOException, InterruptedException {
        String t = paraLeer(texto);
        if (t.isEmpty() || !hablaAqui()) return true;
        long mia = ++frase;
        callar(false);
        synchronized (turno) {
            if (mia != frase) return false;
            Path wav = Files.createTempFile("jarvis-voz-", ".wav");
            try {
                generar(t, wav);
                if (mia != frase) return false;
                return reproducir(wav, mia);
            } finally {
                Files.deleteIfExists(wav);
            }
        }
    }

    public void callar() {
        frase++;
        callar(true);
    }

    private void callar(boolean tambienGenerando) {
        Clip c = sonando;
        if (c != null) c.stop();
        Process p = generando;
        if (tambienGenerando && p != null) p.destroy();
    }

    void generar(String texto, Path wav) throws IOException, InterruptedException {
        List<String> cmd;
        Path dir;
        if (modo == Modo.PIPER) {
            cmd = List.of(piperExe.toString(), "-q", "-m", piperVoz.toString(), "-f", wav.toString());
            dir = piperExe.getParent();
        } else {
            String ps = "[Console]::InputEncoding=[Text.Encoding]::UTF8;"
                    + "Add-Type -AssemblyName System.Speech;"
                    + "$s=New-Object System.Speech.Synthesis.SpeechSynthesizer;"
                    + "$v=$s.GetInstalledVoices()|?{$_.Enabled -and $_.VoiceInfo.Culture.Name -like 'es*'}|select -First 1;"
                    + "if($v){$s.SelectVoice($v.VoiceInfo.Name)};"
                    + "$s.SetOutputToWaveFile('" + wav.toString().replace("'", "''") + "');"
                    + "$s.Speak([Console]::In.ReadToEnd());$s.Dispose()";
            cmd = List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps);
            dir = null;
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        if (dir != null) pb.directory(dir.toFile());
        Process p = pb.start();
        generando = p;
        try {
            try (OutputStream in = p.getOutputStream()) {
                in.write((texto + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!p.waitFor(MAX_GENERAR_S, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("La voz tardó demasiado en generar el audio.");
            }
            if (p.exitValue() != 0 && Files.size(wav) == 0) throw new IOException("La voz terminó con error " + p.exitValue() + ".");
            if (Files.size(wav) < 100) throw new IOException("La voz no generó audio.");
        } finally {
            generando = null;
        }
    }

    private boolean reproducir(Path wav, long mia) throws IOException, InterruptedException {
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(wav.toFile());
             Clip clip = AudioSystem.getClip()) {
            CountDownLatch fin = new CountDownLatch(1);
            clip.addLineListener(e -> { if (e.getType() == LineEvent.Type.STOP) fin.countDown(); });
            clip.open(audio);
            sonando = clip;
            if (mia != frase) return false;
            clip.start();
            long ms = clip.getMicrosecondLength() / 1000 + 2000;
            fin.await(ms, TimeUnit.MILLISECONDS);
            return mia == frase;
        } catch (javax.sound.sampled.UnsupportedAudioFileException | javax.sound.sampled.LineUnavailableException e) {
            throw new IOException("No pude reproducir el audio: " + e.getMessage(), e);
        } finally {
            sonando = null;
        }
    }

    @PreDestroy
    public void cerrar() { callar(); }
}
