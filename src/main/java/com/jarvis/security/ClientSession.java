package com.jarvis.security;

import java.util.function.Supplier;

/**
 * Quién está hablando con JARVIS en este momento (por hilo de la petición).
 *
 * <p>El panel web manda la cabecera {@value #CABECERA} con un id aleatorio por pestaña. Con él,
 * el "sí/no" pendiente, la venta en curso y el comprobante por mostrar son de
 * cada dispositivo: el "sí" del celular ya no confirma lo que preguntó la laptop. Si un cliente
 * no manda la cabecera (otro programa, versión vieja del panel), su sesión es su IP.</p>
 *
 * <p>Lo fija {@link ApiAuthFilter} al aceptar la clave y lo borra al terminar la petición.
 * Fuera de una petición (pruebas, arranque) la sesión es {@value #LOCAL}.</p>
 */
public final class ClientSession {

    public static final String CABECERA = "X-Jarvis-Session";
    public static final String LOCAL = "local";

    private record Actual(String id, String usuario) {}

    private static final ThreadLocal<Actual> ACTUAL = new ThreadLocal<>();

    private ClientSession() {}

    /** Id de la sesión que hace la petición actual. */
    public static String id() {
        Actual a = ACTUAL.get();
        return a == null ? LOCAL : a.id();
    }

    /** Quién hizo el movimiento, para la auditoría: "sesión@ip". */
    public static String usuario() {
        Actual a = ACTUAL.get();
        return a == null ? LOCAL : a.usuario();
    }

    /** Ejecuta {@code accion} como si viniera de la sesión {@code id} (pruebas y tareas internas). */
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

    /** Solo letras, números, - y _ (máx. 64): el id viene del cliente y no debe poder inyectar nada. */
    static String limpiar(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.length() > 64 || !s.matches("[A-Za-z0-9_-]+")) return null;
        return s;
    }
}
