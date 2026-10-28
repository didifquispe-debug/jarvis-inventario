/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.api;

import com.jarvis.voz.EscuchaLocal;
import com.jarvis.voz.HablaLocal;
import com.jarvis.voz.VocabularioVoz;
import com.jarvis.voz.VozLocal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/api/voz")
@CrossOrigin
public class VozController {

    private final VozLocal voz;
    private final VocabularioVoz vocabulario;
    private final EscuchaLocal escucha;
    private final HablaLocal habla;
    private final Semaphore turnos = new Semaphore(2);

    public VozController(VozLocal voz, VocabularioVoz vocabulario, EscuchaLocal escucha, HablaLocal habla) {
        this.voz = voz;
        this.vocabulario = vocabulario;
        this.escucha = escucha;
        this.habla = habla;
    }

    public record Texto(String texto) {}

    @GetMapping("/habla")
    public ResponseEntity<Map<String, Object>> habla(HttpServletRequest req) {
        if (!estaPc(req)) return ResponseEntity.ok(Map.of("modo", "navegador", "pedido", habla.pedido().name().toLowerCase(),
                "motivo", "Desde otro equipo se usa la voz del navegador."));
        return ResponseEntity.ok(Map.of("modo", habla.modo().name().toLowerCase(), "pedido", habla.pedido().name().toLowerCase(),
                "motivo", habla.motivo()));
    }

    @PostMapping("/hablar")
    public ResponseEntity<Map<String, Object>> hablar(@RequestBody Texto t, HttpServletRequest req) throws InterruptedException {
        if (!estaPc(req)) return error(HttpStatus.FORBIDDEN, "Los parlantes de la computadora solo se usan desde ella misma.");
        if (!habla.hablaAqui()) return error(HttpStatus.SERVICE_UNAVAILABLE, "Esta computadora usa la voz del navegador.");
        try {
            boolean completa = habla.hablar(t == null ? "" : t.texto());
            return ResponseEntity.ok(Map.of("ok", true, "interrumpida", !completa));
        } catch (java.io.IOException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    @PostMapping("/callar")
    public ResponseEntity<Map<String, Object>> callar(HttpServletRequest req) {
        if (!estaPc(req)) return error(HttpStatus.FORBIDDEN, "Los parlantes de la computadora solo se usan desde ella misma.");
        habla.callar();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    public record Accion(String accion) {}

    @GetMapping("/escucha")
    public ResponseEntity<Map<String, Object>> escucha(HttpServletRequest req) {
        if (!estaPc(req)) return error(HttpStatus.FORBIDDEN, "El micrófono de la computadora solo se usa desde ella misma.");
        return ResponseEntity.ok(escucha.estado());
    }

    @PostMapping("/escucha")
    public ResponseEntity<Map<String, Object>> mandar(@RequestBody Accion a, HttpServletRequest req) {
        if (!estaPc(req)) return error(HttpStatus.FORBIDDEN, "El micrófono de la computadora solo se usa desde ella misma.");
        switch (a == null || a.accion() == null ? "" : a.accion()) {
            case "manos-libres" -> escucha.manosLibres(true);
            case "sin-manos-libres" -> escucha.manosLibres(false);
            case "una-vez" -> escucha.escucharUnaVez();
            case "pausa" -> escucha.pausar();
            case "sigue" -> escucha.seguir(false);
            case "sigue-y-espera" -> escucha.seguir(true);
            case "cancelar" -> escucha.cancelar();
            default -> { return error(HttpStatus.BAD_REQUEST, "Acción desconocida."); }
        }
        return ResponseEntity.ok(escucha.estado());
    }

    @GetMapping("/escucha/eventos")
    public ResponseEntity<?> eventos(@RequestParam(defaultValue = "0") long desde, HttpServletRequest req) throws InterruptedException {
        if (!estaPc(req)) return error(HttpStatus.FORBIDDEN, "El micrófono de la computadora solo se usa desde ella misma.");
        List<EscuchaLocal.Evento> ev = escucha.eventos(desde, 25_000);
        return ResponseEntity.ok(ev);
    }

    static boolean estaPc(HttpServletRequest req) {
        try {
            return InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
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
