/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.security;

import java.util.function.Supplier;

public final class ClientSession {

    public static final String CABECERA = "X-Jarvis-Session";
    public static final String LOCAL = "local";

    private record Actual(String id, String usuario) {}

    private static final ThreadLocal<Actual> ACTUAL = new ThreadLocal<>();

    private ClientSession() {}

    public static String id() {
        Actual a = ACTUAL.get();
        return a == null ? LOCAL : a.id();
    }

    public static String usuario() {
        Actual a = ACTUAL.get();
        return a == null ? LOCAL : a.usuario();
    }

    public static <T> T como(String id, Supplier<T> accion) {
        Actual antes = ACTUAL.get();
        ACTUAL.set(new Actual(id, id));
        try {
            return accion.get();
        } finally {
            if (antes == null) ACTUAL.remove(); else ACTUAL.set(antes);
        }
    }

    static void abrir(String cabecera, String ip) {
        String limpio = limpiar(cabecera);
        String id = limpio != null ? limpio : "ip-" + ip;
        ACTUAL.set(new Actual(id, (limpio != null ? limpio.substring(0, Math.min(8, limpio.length())) : "sin-sesion") + "@" + ip));
    }

    static void cerrar() { ACTUAL.remove(); }

    static String limpiar(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 64 || !s.matches("[A-Za-z0-9_-]+")) return null;
        return s;
    }
}
