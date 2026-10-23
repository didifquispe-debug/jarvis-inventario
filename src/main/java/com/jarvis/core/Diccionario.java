package com.jarvis.core;

import com.jarvis.inventory.Palabras;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Diccionario de sinónimos: cambia lo que el usuario dice por palabras que JARVIS ya entiende
 * ("chompa" → "polera", "talla eme" → "talla m") ANTES de que lo lean las habilidades.
 * <ul>
 *   <li>De fábrica: {@code src/main/resources/diccionario-es.txt}.</li>
 *   <li>Del negocio: {@code ~/jarvis-diccionario.txt} (o jarvis.diccionario.file). Manda sobre el de fábrica,
 *       se puede editar con el Bloc de notas y se vuelve a leer solo al guardarlo, sin reiniciar.</li>
 * </ul>
 * Formato: una línea por palabra, {@code lo que se dice = lo que JARVIS entiende}; {@code #} = comentario.
 * Se reemplazan palabras o frases completas (nunca pedazos de otra palabra) en una sola pasada,
 * empezando por las frases más largas.
 */
@Component
public class Diccionario {

    private static final Logger log = LoggerFactory.getLogger(Diccionario.class);

    /** Palabras que no se dejan redefinir: sin ellas no se podría confirmar ni pedir ayuda. */
    private static final Set<String> RESERVADAS = Set.of("si", "no", "ayuda", "aprende", "olvida", "cancela", "cancelar", "jarvis");
    private static final int MAX_LARGO = 40;
    /** Tope de pasadas al encadenar sinónimos ("zapas = tenis", "tenis = zapatillas"). */
    private static final int MAX_PASADAS = 10;

    private static final String PLANTILLA = """
            # Diccionario de JARVIS: las palabras propias de su negocio.
            # Escriba una línea por palabra:   lo que usted dice = lo que JARVIS ya entiende
            # Ejemplos (quite el # del inicio para activarlos):
            # chompa = polera
            # zapas = zapatillas
            # tenis = zapatillas
            #
            # También puede enseñarle por voz: "Jarvis, aprende que chompa significa polera".
            # Los cambios se aplican solos al guardar este archivo (no hace falta reiniciar).
            """;

    private final Path archivo;
    private final Map<String, String> fabrica = new LinkedHashMap<>();
    private Map<String, String> propias = new LinkedHashMap<>();
    private Map<String, String> todas = Map.of();
    private Pattern patron;
    private FileTime leido;

    public Diccionario(@Value("${jarvis.diccionario.file:${user.home}/jarvis-diccionario.txt}") String archivo) {
        this.archivo = Paths.get(archivo);
        try (InputStream in = Diccionario.class.getResourceAsStream("/diccionario-es.txt")) {
            if (in != null) leer(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList(), fabrica);
        } catch (IOException e) {
            log.warn("No pude leer el diccionario de fábrica: {}", e.getMessage());
        }
        if (!Files.exists(this.archivo)) {
            try {
                if (this.archivo.getParent() != null) Files.createDirectories(this.archivo.getParent());
                Files.writeString(this.archivo, PLANTILLA, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("No pude crear el diccionario {}: {}", this.archivo, e.getMessage());
            }
        }
        recargarSiCambio();
    }

    /**
     * Cambia las palabras del diccionario por las que JARVIS entiende. Recibe el comando ya normalizado.
     * Encadena sinónimos ("zapas = tenis" y "tenis = zapatillas": zapas → zapatillas) repitiendo la pasada
     * hasta que nada cambie. Si dos palabras se apuntan entre sí ("a = b", "b = a") se detiene al ver un
     * texto repetido, y nunca hace más de {@value #MAX_PASADAS} pasadas.
     */
    public synchronized String aplicar(String cmd) {
        recargarSiCambio();
        if (patron == null || cmd == null || cmd.isEmpty()) return cmd;
        Set<String> vistos = new HashSet<>(List.of(cmd));
        String actual = cmd;
        for (int i = 0; i < MAX_PASADAS; i++) {
            String siguiente = unaPasada(actual);
            if (!vistos.add(siguiente)) break;   // sin cambios, o un ciclo que vuelve a un texto ya visto
            actual = siguiente;
        }
        return actual;
    }

    private String unaPasada(String cmd) {
        Matcher m = patron.matcher(cmd);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(todas.get(m.group())));
        m.appendTail(sb);
        return sb.toString();
    }

    /** Aprende una palabra nueva y la guarda en el archivo del negocio. Devuelve la respuesta para el usuario. */
    public synchronized String aprender(String dicho, String significa) {
        recargarSiCambio();
        String k = limpiar(dicho), v = limpiar(significa);
        if (k.isEmpty() || v.isEmpty()) return "Dígame así, señor: aprende que chompa significa polera.";
        if (k.length() > MAX_LARGO || v.length() > MAX_LARGO) return "Son palabras muy largas, señor. Use frases cortas.";
        if (k.equals(v)) return "Esas dos palabras ya son iguales, señor.";
        if (RESERVADAS.contains(k)) return "No puedo cambiar el significado de \"" + k + "\", señor: la necesito para funcionar.";
        String antes = propias.get(k);
        List<String> lineas = lineasDelArchivo();
        lineas.removeIf(l -> clave(l).equals(k));   // si ya existía, la nueva reemplaza a la vieja
        lineas.add(k + " = " + v);
        if (!escribir(lineas)) return "No pude guardar el diccionario en " + archivo + ", señor.";
        recargarSiCambio(true);
        return antes == null
                ? "Aprendido, señor: cuando diga \"" + k + "\" entenderé \"" + v + "\"."
                : "Actualizado, señor: \"" + k + "\" ahora significa \"" + v + "\" (antes \"" + antes + "\").";
    }

    /** Olvida una palabra del diccionario del negocio. */
    public synchronized String olvidar(String dicho) {
        recargarSiCambio();
        String k = limpiar(dicho);
        if (!propias.containsKey(k)) {
            return fabrica.containsKey(k)
                    ? "\"" + k + "\" viene de fábrica, señor; para cambiarla enséñeme otro significado."
                    : "No tengo la palabra \"" + k + "\" en su diccionario, señor.";
        }
        List<String> lineas = lineasDelArchivo();
        lineas.removeIf(l -> clave(l).equals(k));
        if (!escribir(lineas)) return "No pude guardar el diccionario en " + archivo + ", señor.";
        recargarSiCambio(true);
        return "Listo, señor: olvidé la palabra \"" + k + "\".";
    }

    /** Las palabras que enseñó el negocio (sin las de fábrica). */
    public synchronized Map<String, String> propias() {
        recargarSiCambio();
        return new LinkedHashMap<>(propias);
    }

    public Path archivo() { return archivo; }

    // ---------------------------------------------------------------- lectura

    private void recargarSiCambio() { recargarSiCambio(false); }

    private void recargarSiCambio(boolean forzar) {
        FileTime t;
        try { t = Files.exists(archivo) ? Files.getLastModifiedTime(archivo) : null; } catch (IOException e) { t = null; }
        if (!forzar && patron != null && Objects.equals(t, leido)) return;
        leido = t;
        Map<String, String> nuevas = new LinkedHashMap<>();
        if (t != null) leer(lineasDelArchivo(), nuevas);
        propias = nuevas;
        Map<String, String> union = new LinkedHashMap<>(fabrica);
        union.putAll(propias);
        // plurales automáticos de las palabras sueltas: "chompas" también es "polera" (la búsqueda de productos ya acepta plurales)
        for (Map.Entry<String, String> e : new ArrayList<>(union.entrySet())) {
            String k = e.getKey();
            if (k.contains(" ") || k.length() < 3) continue;
            union.putIfAbsent(plural(k), e.getValue());
        }
        todas = union;
        patron = union.isEmpty() ? null : Pattern.compile(union.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())   // primero "talla equis ele", luego "ele"
                .map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b)
                .map(alt -> "(?<![\\p{L}\\p{N}])(?:" + alt + ")(?![\\p{L}\\p{N}])")
                .orElseThrow());
    }

    /** Plural de una palabra ya normalizada: lapiz → lapices, polera → poleras, pantalon → pantalones. */
    static String plural(String k) {
        if (k.endsWith("z")) return k.substring(0, k.length() - 1) + "ces";
        return k + (k.matches(".*[aeiou]$") ? "s" : "es");
    }

    private static void leer(List<String> lineas, Map<String, String> destino) {
        for (String l : lineas) {
            String k = clave(l);
            if (k.isEmpty()) continue;
            String v = limpiar(sinComentario(l).substring(sinComentario(l).indexOf('=') + 1));
            if (!v.isEmpty() && !k.equals(v) && !RESERVADAS.contains(k)) destino.put(k, v);
        }
    }

    /** La palabra dicha de una línea "x = y", ya normalizada, o "" si la línea no es una entrada. */
    private static String clave(String linea) {
        String l = sinComentario(linea);
        int i = l.indexOf('=');
        return i <= 0 ? "" : limpiar(l.substring(0, i));
    }

    private static String sinComentario(String l) {
        int i = l.indexOf('#');
        return (i >= 0 ? l.substring(0, i) : l).trim();
    }

    /** Minúsculas, sin tildes ni comillas, igual que llegan los comandos. */
    private static String limpiar(String s) {
        return Palabras.norm(s == null ? "" : s.replaceAll("[\"'“”«»]", " "));
    }

    private List<String> lineasDelArchivo() {
        try {
            if (!Files.exists(archivo)) return new ArrayList<>();
            try {
                return new ArrayList<>(Files.readAllLines(archivo, StandardCharsets.UTF_8));
            } catch (java.nio.charset.CharacterCodingException e) {
                // guardado como "ANSI" desde un Bloc de notas antiguo: las tildes vienen en Windows-1252
                return new ArrayList<>(Files.readAllLines(archivo, java.nio.charset.Charset.forName("windows-1252")));
            }
        } catch (IOException e) {
            log.warn("No pude leer el diccionario {}: {}", archivo, e.getMessage());
            return new ArrayList<>();
        }
    }

    private boolean escribir(List<String> lineas) {
        try {
            if (archivo.getParent() != null) Files.createDirectories(archivo.getParent());
            Files.write(archivo, lineas, StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            log.error("No pude guardar el diccionario {}: {}", archivo, e.toString());
            return false;
        }
    }
}
