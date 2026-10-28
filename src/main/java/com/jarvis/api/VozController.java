package com.jarvis.api;

import com.jarvis.voz.VocabularioVoz;
import com.jarvis.voz.VozLocal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * Voz sin Internet. La clave la comprueba ApiAuthFilter.
 *
 *   GET  /api/voz/estado     -> { local: true|false, cargando, motor: "auto"|"local"|"navegador" }
 *   POST /api/voz/reconocer  (application/octet-stream: PCM 16 bits, mono, 16 kHz; máximo 30 s)
 *                            -> { ok, texto }   (503 si no hay modelo, 413 si el audio es muy largo)
 */
@RestController
@RequestMapping("/api/voz")
@CrossOrigin
public class VozController {

    private final VozLocal voz;
    private final VocabularioVoz vocabulario;
    /** Reconocer usa bastante CPU: a lo más dos frases a la vez (laptop y celular). */
    private final Semaphore turnos = new Semaphore(2);

    public VozController(VozLocal voz, VocabularioVoz vocabulario) {
        this.voz = voz;
        this.vocabulario = vocabulario;
    }

    @GetMapping("/estado")
    public Map<String, Object> estado() {
        return Map.of("local", voz.listo(), "cargando", voz.cargando(), "motor", voz.motor().name().toLowerCase());
    }

    @PostMapping(value = "/reconocer", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<Map<String, Object>> reconocer(@RequestBody(required = false) byte[] pcm) {
        if (!voz.listo()) return error(HttpStatus.SERVICE_UNAVAILABLE, "La voz sin Internet no está instalada en este equipo.");
        if (pcm == null || pcm.length == 0) return ResponseEntity.ok(Map.of("ok", true, "texto", ""));
        if (pcm.length > VozLocal.MAX_BYTES) return error(HttpStatus.PAYLOAD_TOO_LARGE, "La frase es muy larga: máximo 30 segundos.");
        if (!turnos.tryAcquire()) return error(HttpStatus.TOO_MANY_REQUESTS, "Estoy reconociendo otra frase, intente de nuevo.");
        try {
            return ResponseEntity.ok(Map.of("ok", true, "texto", voz.reconocer(pcm, vocabulario.palabras())));
        } catch (IllegalStateException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        } finally {
            turnos.release();
        }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus s, String msg) {
        return ResponseEntity.status(s).body(Map.of("ok", false, "reply", msg));
    }
}
