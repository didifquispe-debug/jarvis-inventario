/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.security;

import java.util.function.Supplier;

public final class ClientSession {

    public static final String CABECERA = "X-Jarvis-Session";
    public static final String LOCAL = "local";

    /** {@code rol} null: todavía no hay usuarios creados y todo está permitido, como antes de los roles. */
    private record Actual(String id, String usuario, Rol rol) {}

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

    public static Rol rol() {
        Actual a = ACTUAL.get();
        return a == null ? null : a.rol();
    }

    public static boolean puede(Rol.Permiso p) {
        Rol r = rol();
        return r == null || r.permisos.contains(p);
    }

    /** Mensaje para quien no tiene el permiso, o null si lo tiene. */
    public static String sinPermiso(Rol.Permiso p) {
        if (puede(p)) return null;
        return "Su usuario (" + rol().descripcion.toLowerCase() + ") no tiene permiso para " + p.accion + ", señor.";
    }

    public static <T> T como(String id, Supplier<T> accion) {
        return como(id, id, null, accion);
    }

    public static <T> T como(String id, String usuario, Rol rol, Supplier<T> accion) {
        Actual antes = ACTUAL.get();
        ACTUAL.set(new Actual(id, usuario, rol));
        try {
            return accion.get();
        } finally {
            if (antes == null) ACTUAL.remove(); else ACTUAL.set(antes);
        }
    }

    static void abrir(String cabecera, String ip) {
        abrir(cabecera, ip, null, null);
    }

    static void abrir(String cabecera, String ip, String nombre, Rol rol) {
        String limpio = limpiar(cabecera);
        String id = limpio != null ? limpio : "ip-" + ip;
        String quien = nombre != null ? nombre : limpio != null ? limpio.substring(0, Math.min(8, limpio.length())) : "sin-sesion";
        ACTUAL.set(new Actual(id, quien + "@" + ip, rol));
    }

    static void cerrar() { ACTUAL.remove(); }

    static String limpiar(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 64 || !s.matches("[A-Za-z0-9_-]+")) return null;
        return s;
    }
}
