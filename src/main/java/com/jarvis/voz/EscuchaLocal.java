/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.voz;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.function.Function;

@Component
public class EscuchaLocal {

    private static final Logger log = LoggerFactory.getLogger(EscuchaLocal.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final AudioFormat FORMATO = new AudioFormat(VozLocal.HZ, 16, 1, true, false);
    private static final int BLOQUE = VozLocal.HZ * 2 / 10;
    static final long ESPERA_ORDEN_MS = 8_000;
    private static final long UNA_VEZ_MS = 10_000;
    private static final long PAUSA_MAX_MS = 30_000;
    private static final int MAX_EVENTOS = 50;

    public record Evento(long id, String tipo, String texto) {}

    private final VozLocal voz;
    private final boolean habilitado;
    private final Function<byte[], String> preciso;
    private final Deque<Evento> eventos = new ArrayDeque<>();
    private long ultimoId;

    private volatile boolean manosLibres;
    private volatile long unaVezHasta;
    private volatile long ordenHasta;
    private volatile long pausadoHasta;
    private volatile boolean reiniciar;
    private volatile String error;
    private volatile String ultimoParcial = "";
    private Thread hilo;
    private volatile TargetDataLine linea;

    @Autowired
    public EscuchaLocal(VozLocal voz, VocabularioVoz vocabulario, @Value("${jarvis.voz.escuchar:true}") boolean habilitado) {
        this(voz, habilitado, pcm -> voz.reconocer(pcm, vocabulario.palabras()));
    }

    EscuchaLocal(VozLocal voz, boolean habilitado, Function<byte[], String> preciso) {
        this.voz = voz;
        this.habilitado = habilitado;
        this.preciso = preciso;
    }

    public boolean disponible() {
        return habilitado && voz.listo() && error == null && hayMicrofono();
    }

    static boolean hayMicrofono() {
        try {
            return AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, FORMATO));
        } catch (Throwable e) {
            return false;
        }
    }

    public Map<String, Object> estado() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("disponible", disponible());
        m.put("cargando", habilitado && voz.cargando());
        m.put("manosLibres", manosLibres);
        m.put("escuchando", hilo != null);
        m.put("ultimo", ultimoId());
        String motivo = !habilitado ? "Desactivado (jarvis.voz.escuchar=false)."
                : voz.cargando() ? "Cargando el modelo de voz…"
                : !voz.listo() ? "No hay modelo de voz en " + voz.carpetaModelo() + "."
                : error != null ? error
                : !hayMicrofono() ? "No encuentro un micrófono en esta computadora." : "";
        m.put("motivo", motivo);
        return m;
    }

    public void manosLibres(boolean si) {
        if (si) error = null;
        manosLibres = si;
        if (!si) { ordenHasta = 0; unaVezHasta = 0; }
        asegurarHilo();
    }

    public void escucharUnaVez() {
        error = null;
        pausadoHasta = 0;
        reiniciar = true;
        unaVezHasta = System.currentTimeMillis() + UNA_VEZ_MS;
        asegurarHilo();
    }

    public void pausar() {
        pausadoHasta = System.currentTimeMillis() + PAUSA_MAX_MS;
    }

    public void seguir(boolean esperarOrden) {
        if (esperarOrden) ordenHasta = System.currentTimeMillis() + ESPERA_ORDEN_MS;
        pausadoHasta = 0;
        reiniciar = true;
        asegurarHilo();
    }

    public void cancelar() {
        unaVezHasta = 0;
        ordenHasta = 0;
        reiniciar = true;
    }

    public synchronized List<Evento> eventos(long desde, long esperaMs) throws InterruptedException {
        long fin = System.currentTimeMillis() + esperaMs;
        while (ultimoId <= desde) {
            long falta = fin - System.currentTimeMillis();
            if (falta <= 0) return List.of();
            wait(falta);
        }
        return eventos.stream().filter(e -> e.id() > desde).toList();
    }

    public synchronized long ultimoId() { return ultimoId; }

    private synchronized void emitir(String tipo, String texto) {
        eventos.addLast(new Evento(++ultimoId, tipo, texto));
        while (eventos.size() > MAX_EVENTOS) eventos.removeFirst();
        notifyAll();
    }

    boolean esperandoOrden() {
        long ahora = System.currentTimeMillis();
        return ahora < unaVezHasta || ahora < ordenHasta;
    }

    void alTerminarFrase(String libre, byte[] pcm) {
        if (libre == null || libre.isBlank() || ruido(libre)) return;
        if (esperandoOrden()) {
            String t = preciso(pcm, libre);
            t = VozLocal.trasJarvis(t).orElse(t);
            unaVezHasta = 0;
            ordenHasta = 0;
            if (t.isBlank()) { escucharDeNuevoTrasJarvis(); return; }
            ordenLista(t);
            return;
        }
        if (!manosLibres) return;
        Optional<String> tras = VozLocal.trasJarvis(libre);
        if (tras.isEmpty()) return;
        String p = preciso(pcm, libre);
        String orden = VozLocal.trasJarvis(p).orElse(tras.get());
        if (orden.length() <= 1) escucharDeNuevoTrasJarvis();
        else ordenLista(orden);
    }

    private static final Set<String> CORTAS_VALIDAS = Set.of("si", "no", "ok");

    static boolean ruido(String texto) {
        String t = com.jarvis.inventory.Palabras.norm(texto).replaceAll("[^a-z ]", "").trim();
        return !t.contains(" ") && t.length() <= 3 && !CORTAS_VALIDAS.contains(t);
    }

    private String preciso(byte[] pcm, String libre) {
        try {
            String t = preciso.apply(pcm);
            return t == null || t.isBlank() ? libre : t;
        } catch (RuntimeException e) {
            return libre;
        }
    }

    private void escucharDeNuevoTrasJarvis() {
        pausar();
        emitir("activado", "");
    }

    private void ordenLista(String texto) {
        log.debug("Voz: orden \"{}\"", texto);
        pausar();
        ultimoParcial = "";
        emitir("orden", texto);
    }

    private boolean hayQueEscuchar() {
        return manosLibres || esperandoOrden();
    }

    private synchronized void asegurarHilo() {
        if (!hayQueEscuchar() || !disponible() || (hilo != null && hilo.isAlive())) return;
        hilo = new Thread(this::escuchar, "jarvis-microfono");
        hilo.setDaemon(true);
        hilo.start();
    }

    private void escuchar() {
        Recognizer r = null;
        TargetDataLine l = null;
        boolean unaVezAvisada = false;
        try {
            r = voz.reconocedorContinuo();
            if (r == null) return;
            l = AudioSystem.getTargetDataLine(FORMATO);
            l.open(FORMATO, BLOQUE * 4);
            l.start();
            linea = l;
            log.info("Micrófono de la computadora abierto (voz sin Internet).");
            byte[] buf = new byte[BLOQUE];
            ByteArrayOutputStream frase = new ByteArrayOutputStream();
            while (hayQueEscuchar()) {
                int n = l.read(buf, 0, buf.length);
                if (n <= 0) continue;
                long ahora = System.currentTimeMillis();
                if (unaVezHasta != 0 && ahora >= unaVezHasta && !unaVezAvisada && ordenHasta < ahora) {
                    unaVezAvisada = true;
                    emitir("silencio", "");
                }
                if (ahora < pausadoHasta) { reiniciar = true; continue; }
                if (reiniciar) {
                    reiniciar = false;
                    unaVezAvisada = false;
                    r.reset();
                    frase.reset();
                }
                frase.write(buf, 0, n);
                if (frase.size() > VozLocal.MAX_BYTES) {
                    r.reset();
                    frase.reset();
                    continue;
                }
                if (r.acceptWaveForm(buf, n)) {
                    String libre = JSON.readTree(r.getResult()).path("text").asText("").trim();
                    byte[] pcm = frase.toByteArray();
                    frase.reset();
                    alTerminarFrase(libre, pcm);
                } else {
                    String parcial = JSON.readTree(r.getPartialResult()).path("partial").asText("").trim();
                    if (!parcial.equals(ultimoParcial)) {
                        ultimoParcial = parcial;
                        if (!parcial.isEmpty() && (esperandoOrden() || VozLocal.trasJarvis(parcial).isPresent()))
                            emitir("parcial", parcial);
                    }
                }
            }
        } catch (Throwable e) {
            if (hayQueEscuchar()) {
                error = "No pude usar el micrófono de la computadora (" + e.getMessage() + ").";
                log.warn("{} Se usará el reconocimiento del navegador.", error);
                emitir("error", error);
            }
        } finally {
            linea = null;
            if (l != null) l.close();
            if (r != null) r.close();
            synchronized (this) { hilo = null; }
            log.debug("Micrófono de la computadora cerrado.");
            if (hayQueEscuchar() && error == null) asegurarHilo();
        }
    }

    @PreDestroy
    public void cerrar() {
        manosLibres = false;
        unaVezHasta = 0;
        ordenHasta = 0;
        TargetDataLine l = linea;
        if (l != null) l.close();
    }
}
