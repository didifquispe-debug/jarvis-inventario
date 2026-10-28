/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.voz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HablaLocalTest {

    private static final Path PIPER = Path.of(System.getProperty("user.home"), "jarvis-voz", "piper", "piper.exe");
    private static final Path VOZ = PIPER.resolveSibling("voces").resolve("es_ES-davefx-medium.onnx");

    @TempDir Path dir;

    private HablaLocal.Modo esperadoSinPiper() {
        return HablaLocal.esWindows() ? HablaLocal.Modo.WINDOWS : HablaLocal.Modo.NAVEGADOR;
    }

    @Test void conPiperYSuVozUsaPiper() throws Exception {
        Path exe = Files.createFile(dir.resolve("piper.exe"));
        Path voz = Files.createFile(dir.resolve("voz.onnx"));
        Files.createFile(dir.resolve("voz.onnx.json"));
        HablaLocal h = new HablaLocal(exe.toString(), voz.toString(), "piper");
        assertEquals(HablaLocal.Modo.PIPER, h.modo());
        assertEquals("", h.motivo());
        assertTrue(h.hablaAqui());
    }

    @Test void sinPiperUsaLasVocesDeWindowsYDiceElMotivo() {
        HablaLocal h = new HablaLocal(dir.resolve("no-esta.exe").toString(), dir.resolve("voz.onnx").toString(), "piper");
        assertEquals(esperadoSinPiper(), h.modo());
        assertTrue(h.motivo().startsWith("No encuentro Piper"), h.motivo());
    }

    @Test void sinElJsonDeLaVozTampocoUsaPiper() throws Exception {
        Path exe = Files.createFile(dir.resolve("piper.exe"));
        Path voz = Files.createFile(dir.resolve("voz.onnx"));
        HablaLocal h = new HablaLocal(exe.toString(), voz.toString(), "piper");
        assertEquals(esperadoSinPiper(), h.modo());
        assertTrue(h.motivo().contains(".onnx.json"), h.motivo());
    }

    @Test void navegadorNoHablaAqui() throws Exception {
        HablaLocal h = new HablaLocal("x", "y", "navegador");
        assertEquals(HablaLocal.Modo.NAVEGADOR, h.modo());
        assertFalse(h.hablaAqui());
        assertTrue(h.hablar("hola"), "no hace nada y no falla");
    }

    @Test void modoDesdeLaConfiguracion() {
        assertEquals(HablaLocal.Modo.WINDOWS, HablaLocal.leerModo(" Windows "));
        assertEquals(HablaLocal.Modo.PIPER, HablaLocal.leerModo("cualquiera"));
    }

    @Test void loQueSeLeeVaEnUnaLinea() {
        assertEquals("Puedo hacer esto: Vender. Consultar", HablaLocal.paraLeer("Puedo hacer esto:\n• Vender\n• Consultar"));
        assertEquals("Stock bajo. Polera. Gorra", HablaLocal.paraLeer("Stock bajo\n• Polera\n• Gorra"));
        assertEquals("Hola, señor.", HablaLocal.paraLeer("  Hola,\r\n señor.  "));
        assertEquals("", HablaLocal.paraLeer(null));
    }

    @Test void piperGeneraUnWavQueJavaPuedeReproducir() throws Exception {
        assumeTrue(Files.isRegularFile(PIPER) && Files.isRegularFile(VOZ), "Piper no instalado en " + PIPER);
        HablaLocal h = new HablaLocal(PIPER.toString(), VOZ.toString(), "piper");
        assertEquals(HablaLocal.Modo.PIPER, h.modo());
        Path wav = dir.resolve("piper.wav");
        h.generar("Venta registrada: dos bolsas de cemento. ¿Lo hago, señor?", wav);
        assertWav(wav);
    }

    @Test void lasVocesDeWindowsGeneranUnWav() throws Exception {
        assumeTrue(HablaLocal.esWindows(), "solo en Windows");
        HablaLocal h = new HablaLocal("x", "y", "windows");
        Path wav = dir.resolve("windows.wav");
        h.generar("Hola, señor.", wav);
        assertWav(wav);
    }

    private static void assertWav(Path wav) throws Exception {
        assertTrue(Files.size(wav) > 10_000, "audio muy corto: " + Files.size(wav));
        AudioFileFormat f = AudioSystem.getAudioFileFormat(wav.toFile());
        assertEquals(AudioFileFormat.Type.WAVE, f.getType());
        assertTrue(f.getFrameLength() > f.getFormat().getFrameRate() / 2, "debe durar más de medio segundo");
    }
}
