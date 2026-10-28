package com.jarvis;

import com.jarvis.voz.VozLocal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Voz sin Internet (Vosk). La API se prueba siempre (sin modelo responde 503 y la pantalla usa el navegador).
 * El reconocimiento real solo corre si el modelo está instalado (ver MODELO); los audios de
 * src/test/resources/voz los dijo la voz española de Windows (Helena) a 16 kHz.
 */
@SpringBootTest(properties = {"jarvis.intent.enabled=false", "jarvis.token=clave-voz"})
@AutoConfigureMockMvc
class VozSinInternetTest {

    /** Donde está instalado el modelo (la carpeta de jarvis.voz.vosk-model, o la antigua ~/jarvis-models/vosk-es). */
    private static final Path MODELO = java.util.stream.Stream.of(
                    Path.of(System.getProperty("user.home"), "jarvis-voz", "vosk", "vosk-model-small-es-0.42"),
                    Path.of(System.getProperty("user.home"), "jarvis-models", "vosk-es"))
            .filter(p -> Files.isDirectory(p.resolve("am"))).findFirst()
            .orElse(Path.of(System.getProperty("user.home"), "jarvis-voz", "vosk", "vosk-model-small-es-0.42"));

    @Autowired MockMvc mvc;

    @Test void sinModeloLaApiLoDiceYPideLaClave() throws Exception {
        mvc.perform(get("/api/voz/estado").header("X-Jarvis-Token", "clave-voz"))
                .andExpect(jsonPath("$.local").value(false))
                .andExpect(jsonPath("$.motor").value("auto"));
        mvc.perform(post("/api/voz/reconocer").header("X-Jarvis-Token", "clave-voz")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[3200]))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reply").value("La voz sin Internet no está instalada en este equipo."));
        mvc.perform(get("/api/voz/estado")).andExpect(status().isUnauthorized());
    }

    @Test void motorDesdeLaConfiguracion() {
        assertEquals(VozLocal.Motor.LOCAL, new VozLocal(false, "x", "Local").motor());
        assertEquals(VozLocal.Motor.AUTO, new VozLocal(false, "x", "cualquiera").motor());
    }

    @Test void reconoceFrasesEnEspanol() throws Exception {
        assumeTrue(Files.isDirectory(MODELO.resolve("am")), "modelo de voz no instalado en " + MODELO);
        VozLocal voz = new VozLocal(true, MODELO.toString(), "auto");
        voz.iniciar();
        for (int i = 0; i < 600 && voz.cargando(); i++) Thread.sleep(50);
        assertTrue(voz.listo(), "el modelo debió cargar");
        try {
            String venta = voz.reconocer(pcm("venta.wav"));
            assertTrue(venta.contains("dos") && venta.contains("cemento"), venta);
            String consulta = voz.reconocer(pcm("consulta.wav"));
            assertTrue(consulta.contains("focos"), consulta);
            assertEquals("", voz.reconocer(new byte[16000]), "el silencio no es una orden");
            System.out.println("OIDO1=" + venta + " OIDO2=" + consulta + " OIDO3=" + voz.reconocer(pcm("jarvis.wav")));
        } finally {
            voz.cerrar();
        }
    }

    /** Muestras de un WAV PCM: busca el bloque "data" (Windows añade otros bloques antes). */
    static byte[] pcm(String nombre) throws Exception {
        byte[] wav;
        try (InputStream in = VozSinInternetTest.class.getResourceAsStream("/voz/" + nombre)) {
            wav = in.readAllBytes();
        }
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int p = 12;
        while (p + 8 <= wav.length) {
            String id = new String(wav, p, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int largo = b.getInt(p + 4);
            if (id.equals("data")) return Arrays.copyOfRange(wav, p + 8, Math.min(wav.length, p + 8 + largo));
            p += 8 + largo + (largo & 1);
        }
        throw new IllegalArgumentException("WAV sin datos: " + nombre);
    }
}
