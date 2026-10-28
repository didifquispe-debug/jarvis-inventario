package com.jarvis.voz;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import com.jarvis.inventory.Palabras;
import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Reconocimiento de voz sin Internet con Vosk. El audio (PCM de 16 bits, mono, 16 kHz) llega del micrófono de esta
 * computadora ({@link EscuchaLocal}) o de un navegador; el modelo en español vive en la carpeta del usuario
 * ({@code jarvis.voz.vosk-model}).
 *
 * <p>El modelo se carga en segundo plano al arrancar, para no demorar la ventana. Si no está o la librería
 * nativa no carga, {@link #listo()} es falso y el navegador sigue usando su propio reconocimiento.</p>
 */
@Component
public class VozLocal {

    private static final Logger log = LoggerFactory.getLogger(VozLocal.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        // Vosk habla UTF-8, pero JNA usa la codificación de Windows (windows-1252) si no se le dice otra cosa:
        // "qué" llegaría como "quÃ©". Debe fijarse antes de que JNA se cargue.
        if (System.getProperty("jna.encoding") == null) System.setProperty("jna.encoding", "UTF-8");
    }

    /** Frecuencia del audio que manda el navegador. */
    public static final int HZ = 16_000;
    /** Tope por frase: 30 segundos de audio (2 bytes por muestra). */
    public static final int MAX_BYTES = HZ * 2 * 30;

    /** Qué motor de voz prefiere la pantalla: auto (el del navegador con Internet, este sin él), local o navegador. */
    public enum Motor { AUTO, LOCAL, NAVEGADOR }

    private final boolean habilitado;
    private final Path dir;
    private final Motor motor;
    private volatile Model modelo;
    private volatile boolean cargando;

    public VozLocal(@Value("${jarvis.voz.local.enabled:true}") boolean habilitado,
                    // jarvis.voz.modelo-dir es el nombre antiguo de la clave
                    @Value("${jarvis.voz.vosk-model:${jarvis.voz.modelo-dir:${user.home}/jarvis-voz/vosk/vosk-model-small-es-0.42}}") String dir,
                    @Value("${jarvis.voz.motor:auto}") String motor) {
        this.habilitado = habilitado;
        this.dir = Paths.get(dir);
        this.motor = leerMotor(motor);
    }

    static Motor leerMotor(String s) {
        try { return Motor.valueOf(s.trim().toUpperCase()); } catch (RuntimeException e) { return Motor.AUTO; }
    }

    @PostConstruct
    public void iniciar() {
        if (!habilitado) { log.info("Voz sin Internet desactivada (jarvis.voz.local.enabled=false)."); return; }
        if (!Files.isDirectory(dir.resolve("am"))) {
            log.info("Voz sin Internet no disponible: no hay modelo en {}. El micrófono usará el servicio del navegador.", dir);
            return;
        }
        cargando = true;
        Thread t = new Thread(this::cargar, "jarvis-voz-local");
        t.setDaemon(true);
        t.start();
    }

    private void cargar() {
        try {
            long t0 = System.currentTimeMillis();
            LibVosk.vosk_set_log_level(-2);   // solo errores: no avisar de cada palabra del vocabulario que no conoce
            modelo = new Model(dir.toString());
            log.info("Voz sin Internet lista ({} ms, modelo {}).", System.currentTimeMillis() - t0, dir.getFileName());
        } catch (Throwable e) {   // UnsatisfiedLinkError si la librería nativa no carga en este equipo
            log.warn("No pude cargar la voz sin Internet ({}): el micrófono usará el servicio del navegador.", e.toString());
        } finally {
            cargando = false;
        }
    }

    /** ¿Hay modelo cargado? */
    public boolean listo() { return modelo != null; }

    /** ¿Todavía cargando el modelo? (los primeros segundos tras arrancar) */
    public boolean cargando() { return cargando; }

    public Motor motor() { return motor; }

    public Path carpetaModelo() { return dir; }

    /** Un reconocedor continuo (modelo general) para escuchar el micrófono; null si no hay modelo. Quien lo pide lo cierra. */
    Recognizer reconocedorContinuo() throws IOException {
        Model m = modelo;
        return m == null ? null : new Recognizer(m, HZ);
    }

    /**
     * Lo que viene después de "Jarvis" (o de cómo lo suele oír el modelo: "harvis", "yarvis"…) en una frase:
     * "jarvis vendí dos cementos" → "vendí dos cementos"; "oye jarvis" → "". Vacío si no se dijo "Jarvis".
     */
    public static java.util.Optional<String> trasJarvis(String texto) {
        if (texto == null || texto.isBlank()) return java.util.Optional.empty();
        String[] w = texto.trim().split("\\s+");
        for (int i = 0; i < w.length; i++) {
            if (COMO_SUENA_JARVIS.contains(Palabras.norm(w[i]).replaceAll("[^a-z]", ""))) {
                return java.util.Optional.of(String.join(" ", java.util.Arrays.copyOfRange(w, i + 1, w.length))
                        .replaceFirst("^[,.:;\\s]+", "").trim());
            }
        }
        return java.util.Optional.empty();
    }

    /** Pasa a texto con el modelo general (sin vocabulario). */
    public String reconocer(byte[] pcm) {
        return reconocer(pcm, Set.of());
    }

    /**
     * Pasa a texto una frase grabada (PCM 16 bits little-endian, mono, 16 kHz). Devuelve "" si no se entendió nada.
     *
     * <p>Dos pasadas sobre el mismo audio: una solo con las palabras que JARVIS espera ({@code vocabulario}), que
     * acierta mucho más en órdenes y nombres de productos, y otra con el modelo general; {@link #elegir} decide.</p>
     * @throws IllegalStateException si el modelo no está listo
     */
    public String reconocer(byte[] pcm, Set<String> vocabulario) {
        Model m = modelo;
        if (m == null) throw new IllegalStateException("La voz sin Internet no está disponible.");
        int largo = pcm.length - pcm.length % 2;
        try {
            List<Alternativa> guiado = List.of();
            if (!vocabulario.isEmpty()) {
                Guiado g = guiados.poll();
                if (g == null) {
                    g = new Guiado(new Recognizer(m, HZ, gramatica(vocabulario)), vocabulario);
                    g.r.setMaxAlternatives(ALTERNATIVAS);
                } else if (g.vocabulario != vocabulario && !g.vocabulario.equals(vocabulario)) {
                    g.r.setGrammar(gramatica(vocabulario));
                    g = new Guiado(g.r, vocabulario);
                }
                try {
                    guiado = alternativas(pasar(g.r, pcm, largo));
                } finally {
                    devolver(guiados, g, g.r);
                }
            }
            Recognizer r = libres.poll();
            if (r == null) r = new Recognizer(m, HZ);
            String libre;
            try {
                libre = JSON.readTree(pasar(r, pcm, largo)).path("text").asText("").trim();
            } finally {
                devolver(libres, r, r);
            }
            return elegir(guiado, libre, vocabulario);
        } catch (IOException e) {
            throw new IllegalStateException("No pude reconocer el audio.", e);
        }
    }

    private static String pasar(Recognizer r, byte[] pcm, int largo) {
        r.acceptWaveForm(pcm, largo);
        String json = r.getFinalResult();
        r.reset();
        return json;
    }

    private static final int ALTERNATIVAS = 10;

    /** Una lectura posible de la frase con su puntaje (más alto = más probable). */
    public record Alternativa(double puntaje, String texto) {}

    private static List<Alternativa> alternativas(String json) throws IOException {
        List<Alternativa> lista = new ArrayList<>();
        for (var a : JSON.readTree(json).path("alternatives")) {
            lista.add(new Alternativa(a.path("confidence").asDouble(), a.path("text").asText("").trim()));
        }
        return lista;
    }

    /** "jarvis" u "oye jarvis" al inicio, y luego un número de veinte y algo: "veintitrés", "veintidós". */
    private static final java.util.regex.Pattern VEINTI = java.util.regex.Pattern.compile(
            "^((?:(?:oye|hola)\\s+)?jarvis\\s+)?veinti(uno|una|ún|dós|trés|cuatro|cinco|séis|seis|siete|ocho|nueve)\\b(.*)$");
    /** Lo que el modelo general suele oír cuando se dice "Jarvis" (no cuentan como palabras ajenas). */
    private static final Set<String> COMO_SUENA_JARVIS = Set.of("harville", "harvest", "harvey", "harvis", "harbis", "jarvi",
            "jarbi", "jarbis", "yarvis", "yarbis", "garbis", "charvis", "arvis", "jarvis");

    /** El vocabulario sin tildes, para comparar "cuanto" con "cuánto"; se guarda el último. */
    private static volatile Object[] ultimoNormalizado;

    private static Set<String> normalizadas(Set<String> vocabulario) {
        Object[] u = ultimoNormalizado;
        if (u != null && u[0] == vocabulario) {
            @SuppressWarnings("unchecked") Set<String> s = (Set<String>) u[1];
            return s;
        }
        Set<String> s = new java.util.HashSet<>();
        for (String w : vocabulario) s.add(Palabras.norm(w));
        ultimoNormalizado = new Object[]{vocabulario, s};
        return s;
    }

    /** Cuánto más probable debe ser "veintitrés" que "vendí tres" para creerle (lo dicen igual). */
    static final double MARGEN_VENDI = 2.5;

    /**
     * Elige el texto final.
     * <ul>
     *   <li>Si el modelo general oyó dos o más palabras que JARVIS no conoce ("capital de Francia"), la frase no
     *       era una orden: gana el modelo general (el vocabulario la habría forzado a palabras conocidas).</li>
     *   <li>Si no, gana la pasada con vocabulario, salvo que deje palabras desconocidas.</li>
     *   <li>"vendí tres focos" y "veintitrés focos" suenan casi igual. Si la mejor lectura empieza con "veinti…" y
     *       entre las alternativas está la misma frase con "vendí …" a poca distancia, se cree que fue "vendí".</li>
     * </ul>
     */
    public static String elegir(List<Alternativa> guiado, String libre, Set<String> vocabulario) {
        Set<String> conocidas = normalizadas(vocabulario);
        long desconocidas = libre.isEmpty() ? 0 : java.util.Arrays.stream(libre.split("\\s+"))
                .map(Palabras::norm)
                .filter(w -> !conocidas.contains(w) && !COMO_SUENA_JARVIS.contains(w)).count();
        if (guiado.isEmpty() || desconocidas >= 2) return libre;
        Alternativa mejor = guiado.get(0);
        if (mejor.texto().isEmpty() || mejor.texto().contains("[unk]")) return libre;
        java.util.regex.Matcher m = VEINTI.matcher(mejor.texto());
        if (m.matches()) {
            String prefijo = m.group(1) == null ? "" : m.group(1);
            String unidad = switch (m.group(2)) {
                case "ún" -> "un"; case "dós" -> "dos"; case "trés" -> "tres"; case "séis" -> "seis"; default -> m.group(2);
            };
            for (Alternativa a : guiado) {
                if (mejor.puntaje() - a.puntaje() > MARGEN_VENDI) break;
                String t = a.texto();
                for (String verbo : new String[]{"vendí", "vende", "vendo"}) {
                    if (t.equals(prefijo + verbo + " " + unidad + m.group(3))) return t;
                }
            }
        }
        return mejor.texto();
    }

    /** Vuelve al grupo para la próxima frase (si el modelo se cerró mientras tanto, se libera). */
    private <T> void devolver(Queue<T> cola, T item, Recognizer r) {
        if (modelo == null) r.close();
        else cola.add(item);
    }

    /** ["vende","dos",...,"[unk]"]; se guarda la última para no rearmarla en cada frase. */
    private String gramatica(Set<String> vocabulario) throws IOException {
        Object[] ultima = ultimaGramatica;
        if (ultima != null && ultima[0] == vocabulario) return (String) ultima[1];
        List<String> lista = new ArrayList<>(vocabulario);
        lista.add("[unk]");
        String json = JSON.writeValueAsString(lista);
        ultimaGramatica = new Object[]{vocabulario, json};
        return json;
    }

    private record Guiado(Recognizer r, Set<String> vocabulario) {}

    private final Queue<Guiado> guiados = new ConcurrentLinkedQueue<>();
    private final Queue<Recognizer> libres = new ConcurrentLinkedQueue<>();
    private volatile Object[] ultimaGramatica;

    @PreDestroy
    public void cerrar() {
        Model m = modelo;
        modelo = null;
        for (Guiado g; (g = guiados.poll()) != null; ) g.r.close();
        for (Recognizer r; (r = libres.poll()) != null; ) r.close();
        if (m != null) m.close();
    }
}
