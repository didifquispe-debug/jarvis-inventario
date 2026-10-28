package com.jarvis.core;

import com.jarvis.inventory.Palabras;
import com.jarvis.security.ClientSession;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Órdenes que esperan un "sí" antes de ejecutarse: las que traduce la IA local y las ventas o cantidades dictadas por
 * voz (el reconocimiento puede confundir "vendí tres" con "veintitrés").
 *
 * <p>{@link #proponer} guarda la orden y pregunta "Entendí: … ¿Lo hago?". La siguiente frase de esa misma sesión
 * la resuelve en {@link #resolver}: "sí" la pasa a las reglas, "no" la descarta, y cualquier otra cosa también la
 * descarta y sigue como una orden nueva. Cada dispositivo tiene la suya ({@link ClientSession}).</p>
 */
@Component
public class OrdenesPorConfirmar {

    /** Dos minutos sin contestar: la pregunta se olvida. */
    private static final long VIGENCIA_MS = 2 * 60 * 1000L;

    private static final Pattern SI = Pattern.compile(
            "^(?:(?:si|sip|sii|dale|hazlo|hagalo|ok|okay|claro|correcto|confirmo|confirmado|adelante|de acuerdo|afirmativo"
            + "|exacto|eso|asi es|claro que|por favor|senor|jarvis|ya)\\s*)+$");
    private static final Pattern NO = Pattern.compile(
            "^(?:(?:no|nop|cancela|cancelalo|cancelar|mejor no|dejalo|olvidalo|negativo|no lo hagas|detente|para"
            + "|gracias|senor|jarvis)\\s*)+$");

    private record Pendiente(String orden, long hasta) {}

    private final Map<String, Pendiente> pendientes = new HashMap<>();

    /** Guarda la orden de esta sesión y devuelve la pregunta para el usuario. */
    public synchronized String proponer(String orden) {
        String o = orden == null ? "" : orden.strip();
        pendientes.put(ClientSession.id(), new Pendiente(o, System.currentTimeMillis() + VIGENCIA_MS));
        return "Entendí: " + o + ". ¿Lo hago?";
    }

    /** ¿Esta sesión tiene una orden esperando su "sí"? */
    public synchronized boolean hayPendiente() {
        Pendiente p = pendientes.get(ClientSession.id());
        return p != null && p.hasta >= System.currentTimeMillis();
    }

    /**
     * Si hay una orden pendiente y {@code texto} es la respuesta ("sí" / "no"), la resuelve. Vacío si no había
     * nada pendiente o si el usuario dijo otra cosa (la pendiente se descarta y la frase sigue como orden nueva).
     *
     * @param reglas quien ejecuta la orden confirmada
     */
    public Optional<String> resolver(String texto, Function<String, String> reglas) {
        Pendiente p;
        synchronized (this) {
            p = pendientes.remove(ClientSession.id());
        }
        if (p == null || p.hasta < System.currentTimeMillis()) return Optional.empty();
        String t = Palabras.norm(texto).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        if (SI.matcher(t).matches()) return Optional.of(reglas.apply(p.orden));
        if (NO.matcher(t).matches()) return Optional.of("Muy bien, no lo hago.");
        return Optional.empty();
    }
}
