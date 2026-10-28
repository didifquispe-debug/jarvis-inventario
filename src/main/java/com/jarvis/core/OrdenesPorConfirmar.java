/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.core;

import com.jarvis.inventory.Palabras;
import com.jarvis.security.ClientSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

@Component
public class OrdenesPorConfirmar {

    private static final long VIGENCIA_MS = 2 * 60 * 1000L;
    private static final Set<String> SI = Set.of("si", "sip", "claro", "confirmo", "correcto", "dale", "afirmativo",
            "exacto", "ok", "okay", "confirmado", "hazlo", "adelante", "de acuerdo", "por supuesto", "si hazlo", "si claro");
    private static final Set<String> NO = Set.of("no", "nop", "cancela", "cancelar", "mejor no", "dejalo", "olvidalo", "negativo");

    private record Pendiente(String orden, long desde) {}

    private final String owner;
    private final Map<String, Pendiente> pendientes = new HashMap<>();

    public OrdenesPorConfirmar(@Value("${jarvis.owner:señor}") String owner) {
        this.owner = owner == null || owner.isBlank() ? "señor" : owner.trim();
    }

    public static boolean esSi(String texto) {
        String t = limpio(texto);
        if (t.isEmpty() || t.startsWith("no ") || t.equals("no")) return false;
        return SI.contains(t) || SI.stream().anyMatch(s -> t.startsWith(s + " ")) || t.endsWith(" que si");
    }

    public static boolean esNo(String texto) {
        String t = limpio(texto);
        return NO.contains(t) || NO.stream().anyMatch(s -> t.startsWith(s + " "));
    }

    private static String limpio(String texto) {
        return Palabras.norm(texto == null ? "" : texto).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ")
                .replaceFirst("^(oye |hey )?jarvis ", "").trim();
    }

    public synchronized String proponer(String orden) {
        pendientes.put(ClientSession.id(), new Pendiente(orden.trim(), System.currentTimeMillis()));
        return "Entendí: " + orden.trim() + ". ¿Lo hago, " + owner + "?";
    }

    public Optional<String> resolver(String texto, Function<String, String> reglas) {
        Pendiente p;
        synchronized (this) {
            p = pendientes.remove(ClientSession.id());
        }
        if (p == null || System.currentTimeMillis() - p.desde() > VIGENCIA_MS) return Optional.empty();
        if (esSi(texto)) return Optional.of(reglas.apply(p.orden()));
        if (esNo(texto)) return Optional.of("Bien, " + owner + ", no hice nada.");
        return Optional.empty();
    }
}
