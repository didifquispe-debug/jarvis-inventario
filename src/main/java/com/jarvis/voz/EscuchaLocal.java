package com.jarvis.voz;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.vosk.Recognizer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * El micrófono de ESTA computadora escuchado con Vosk, sin Internet y sin el navegador.
 *
 * <p>Modos:</p>
 * <ul>
 *   <li><b>Manos libres</b>: escucha todo el tiempo y solo reacciona a "Jarvis …". "Jarvis, vendí dos cementos"
 *       manda la orden; "Jarvis" a secas avisa {@code activado} y la pantalla contesta "¿Sí, señor?".</li>
 *   <li><b>Una vez</b> (botón del micrófono) o tras una pregunta ("¿Lo hago?"): la próxima frase es la orden, sin
 *       decir "Jarvis". Si en {@value #ESPERA_ORDEN_MS} ms no se dice nada, avisa {@code silencio}.</li>
 * </ul>
 *
 * <p>Mientras JARVIS habla la pantalla lo pausa, para que no se oiga a sí mismo. El micrófono solo se abre cuando
 * hace falta y se suelta al terminar. La pantalla recoge los avisos con {@link #eventos} (espera larga).</p>
 */
@Component
public class EscuchaLocal {

    private static final Logger log = LoggerFactory.getLogger(EscuchaLocal.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final long ESPERA_ORDEN_MS = 8_000;
    private static final int MAX_EVENTOS = 50;
    private static final AudioFormat FORMATO = new AudioFormat(VozLocal.HZ, 16, 1, true, false);

    /** Un aviso para la pantalla. tipo: parcial | orden | activado | silencio | error */
    public record Evento(long id, String tipo, String texto) {}

    private enum Modo { APAGADO, ESPERA_JARVIS, ORDEN }

    private final VozLocal voz;
    private final VocabularioVoz vocabulario;
    private final boolean habilitado;

    private volatile boolean manosLibres;
    private volatile boolean pausado;
    private volatile Modo modo = Modo.APAGADO;
    private volatile long ordenHasta;
    private volatile String falla;
    private volatile boolean escuchando;
    private Boolean hayMicrofono;   // se averigua una vez, cuando hace falta
    private Thread hilo;

    private final Deque<Evento> eventos = new ArrayDeque<>();
    /** Empieza en la hora actual: tras reiniciar JARVIS, la pantalla que pide "desde" un id viejo igual recibe los nuevos. */
    private long ultimoId = System.currentTimeMillis();

    public EscuchaLocal(VozLocal voz, VocabularioVoz vocabulario,
                        @Value("${jarvis.voz.escuchar:true}") boolean habilitado) {
        this.voz = voz;
        this.vocabulario = vocabulario;
        this.habilitado = habilitado;
    }

    // ---------------------------------------------------------------- estado

    public Map<String, Object> estado() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("disponible", disponible());
        m.put("cargando", habilitado && voz.cargando());
        m.put("manosLibres", manosLibres);
        m.put("escuchando", escuchando && !pausado && modo != Modo.APAGADO);
        synchronized (eventos) { m.put("ultimo", ultimoId); }
        m.put("motivo", motivo());
        return m;
    }

    /** ¿Se puede escuchar con el micrófono de esta computadora? */
    public boolean disponible() {
        return habilitado && voz.listo() && falla == null && microfono();
    }

    private String motivo() {
        if (!habilitado) return "Escuchar con el micrófono de la computadora está desactivado (jarvis.voz.escuchar=false).";
        if (voz.cargando()) return "Cargando el modelo de voz…";
        if (!voz.listo()) return "No hay modelo de voz sin Internet en " + voz.carpetaModelo() + ".";
        if (falla != null) return falla;
        if (!microfono()) return "No encontré un micrófono en esta computadora.";
        return "";
    }

    private synchronized boolean microfono() {
        if (hayMicrofono == null) {
            try {
                hayMicrofono = AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, FORMATO));
            } catch (RuntimeException e) {
                hayMicrofono = false;
            }
        }
        return hayMicrofono;
    }

    // ---------------------------------------------------------------- órdenes de la pantalla

    public synchronized void manosLibres(boolean si) {
        manosLibres = si;
        if (si) {
            if (modo == Modo.APAGADO) modo = Modo.ESPERA_JARVIS;
            pausado = false;
            arrancar();
        } else if (modo == Modo.ESPERA_JARVIS) {
            modo = Modo.APAGADO;
        }
    }

    /** La próxima frase es la orden (botón del micrófono). */
    public synchronized void escucharUnaVez() {
        esperarOrden();
    }

    /** JARVIS va a hablar: no escuchar hasta {@link #seguir}. */
    public void pausar() {
        pausado = true;
    }

    /** JARVIS terminó de hablar. {@code esperaRespuesta}: lo último fue una pregunta, se escucha sin "Jarvis". */
    public synchronized void seguir(boolean esperaRespuesta) {
        if (esperaRespuesta) {
            esperarOrden();
            return;
        }
        pausado = false;
        if (modo == Modo.ORDEN || modo == Modo.APAGADO) modo = manosLibres ? Modo.ESPERA_JARVIS : Modo.APAGADO;
        if (modo != Modo.APAGADO) arrancar();
    }

    public synchronized void cancelar() {
        pausado = false;
        modo = manosLibres ? Modo.ESPERA_JARVIS : Modo.APAGADO;
    }

    private void esperarOrden() {
        ordenHasta = System.currentTimeMillis() + ESPERA_ORDEN_MS;
        modo = Modo.ORDEN;
        pausado = false;
        arrancar();
    }

    /**
     * Los avisos con id mayor que {@code desde}. Si no hay ninguno, espera hasta {@code esperaMs} a que llegue alguno.
     */
    public List<Evento> eventos(long desde, long esperaMs) throws InterruptedException {
        long fin = System.currentTimeMillis() + esperaMs;
        synchronized (eventos) {
            while (ultimoId <= desde) {
                long falta = fin - System.currentTimeMillis();
                if (falta <= 0) return List.of();
                eventos.wait(falta);
            }
            List<Evento> lista = new ArrayList<>();
            for (Evento e : eventos) if (e.id() > desde) lista.add(e);
            return lista;
        }
    }

    private void emitir(String tipo, String texto) {
        synchronized (eventos) {
            eventos.addLast(new Evento(++ultimoId, tipo, texto == null ? "" : texto));
            while (eventos.size() > MAX_EVENTOS) eventos.removeFirst();
            eventos.notifyAll();
        }
    }

    // ---------------------------------------------------------------- el hilo que escucha

    /** Abre el micrófono si hace falta y no está abierto. Se llama con el candado de this. */
    private void arrancar() {
        if (hilo != null || !disponible()) return;
        hilo = new Thread(this::escuchar, "jarvis-microfono");
        hilo.setDaemon(true);
        hilo.start();
    }

    /** ¿Seguir escuchando? Si no, el hilo se da de baja (con el candado, para que arrancar() no lo pierda). */
    private synchronized boolean seguirEscuchando() {
        if (modo != Modo.APAGADO) return true;
        hilo = null;
        return false;
    }

    private void escuchar() {
        TargetDataLine linea = null;
        Recognizer rec = null;
        try {
            linea = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, FORMATO));
            linea.open(FORMATO);
            linea.start();
            rec = voz.reconocedorContinuo();
            if (rec == null) throw new IllegalStateException("el modelo de voz no está cargado");
            escuchando = true;
            log.info("Micrófono de la computadora abierto (voz sin Internet).");
            bucle(linea, rec);
        } catch (Exception | UnsatisfiedLinkError e) {
            log.warn("No pude escuchar con el micrófono de la computadora: {}", e.toString());
            falla = "No pude usar el micrófono de la computadora.";
            emitir("error", falla);
            synchronized (this) { hilo = null; }
        } finally {
            escuchando = false;
            if (rec != null) rec.close();
            if (linea != null) linea.close();
        }
    }

    private void bucle(TargetDataLine linea, Recognizer rec) throws Exception {
        byte[] buf = new byte[3200];   // 100 ms
        ByteArrayOutputStream frase = new ByteArrayOutputStream();
        String ultimoParcial = "";
        boolean estabaPausado = false;
        while (seguirEscuchando()) {
            int n = linea.read(buf, 0, buf.length);
            if (n <= 0) continue;
            if (pausado) {   // se sigue leyendo (si no, el audio viejo quedaría en cola) pero no se usa
                if (!estabaPausado) { rec.reset(); frase.reset(); ultimoParcial = ""; estabaPausado = true; }
                continue;
            }
            estabaPausado = false;
            Modo m = modo;
            if (m == Modo.ORDEN && ultimoParcial.isEmpty() && System.currentTimeMillis() > ordenHasta) {
                rec.reset(); frase.reset();
                synchronized (this) { if (modo == Modo.ORDEN) modo = manosLibres ? Modo.ESPERA_JARVIS : Modo.APAGADO; }
                emitir("silencio", "");
                continue;
            }
            if (frase.size() + n > VozLocal.MAX_BYTES) frase.reset();   // una "frase" de más de 30 s es ruido de fondo
            frase.write(buf, 0, n);
            if (rec.acceptWaveForm(buf, n)) {
                String texto = JSON.readTree(rec.getResult()).path("text").asText("").trim();
                byte[] pcm = frase.toByteArray();
                frase.reset();
                ultimoParcial = "";
                if (!texto.isEmpty()) alTerminarFrase(m, texto, pcm);
            } else {
                String parcial = JSON.readTree(rec.getPartialResult()).path("partial").asText("").trim();
                if (!parcial.isEmpty() && !parcial.equals(ultimoParcial)) {
                    ultimoParcial = parcial;
                    if (m == Modo.ORDEN || VozLocal.trasJarvis(parcial).isPresent()) emitir("parcial", parcial);
                } else if (parcial.isEmpty() && !ultimoParcial.isEmpty()) {
                    ultimoParcial = "";
                }
            }
        }
    }

    private void alTerminarFrase(Modo m, String texto, byte[] pcm) {
        if (m == Modo.ORDEN) {
            String orden = mejorLectura(pcm, texto);
            orden = VozLocal.trasJarvis(orden).orElse(orden);   // si igual dijo "Jarvis, …"
            if (orden.isBlank()) return;
            ordenDicha(orden);
            return;
        }
        Optional<String> tras = VozLocal.trasJarvis(texto);
        if (tras.isEmpty()) return;   // manos libres: lo que no empieza con "Jarvis" no es para él
        if (tras.get().isBlank()) {
            pausado = true;   // la pantalla dice "¿Sí, señor?" y luego pide sigue-y-espera
            emitir("activado", "");
            return;
        }
        String guiada = VozLocal.trasJarvis(mejorLectura(pcm, texto)).filter(s -> !s.isBlank()).orElse(tras.get());
        ordenDicha(guiada);
    }

    /** Manda la orden y espera a que JARVIS conteste (la pantalla lo pausa y luego pide seguir). */
    private synchronized void ordenDicha(String orden) {
        pausado = true;
        if (modo == Modo.ORDEN) modo = manosLibres ? Modo.ESPERA_JARVIS : Modo.APAGADO;
        emitir("orden", orden);
    }

    /** Vuelve a leer la frase con el vocabulario de JARVIS (acierta más en productos y números). */
    private String mejorLectura(byte[] pcm, String libre) {
        try {
            String guiado = voz.reconocer(pcm, vocabulario.palabras());
            return guiado == null || guiado.isBlank() ? libre : guiado;
        } catch (RuntimeException e) {
            return libre;
        }
    }

    @PreDestroy
    public synchronized void cerrar() {
        manosLibres = false;
        modo = Modo.APAGADO;
    }
}
