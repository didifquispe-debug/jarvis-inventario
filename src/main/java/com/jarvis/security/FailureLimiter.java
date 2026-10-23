package com.jarvis.security;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Cuenta los intentos fallidos de clave por IP en una ventana deslizante (p. ej. 10 por minuto).
 * Al llegar al límite, esa IP queda bloqueada hasta que sus fallos salgan de la ventana.
 */
public class FailureLimiter {

    private final int max;
    private final long windowMs;
    private final LongSupplier clock;
    private final Map<String, Deque<Long>> fallos = new ConcurrentHashMap<>();

    public FailureLimiter(int max, long windowMs) { this(max, windowMs, System::currentTimeMillis); }

    FailureLimiter(int max, long windowMs, LongSupplier clock) {
        this.max = max;
        this.windowMs = windowMs;
        this.clock = clock;
    }

    /** ¿Esta IP agotó sus intentos? */
    public boolean bloqueado(String ip) {
        Deque<Long> d = fallos.get(ip);
        if (d == null) return false;
        synchronized (d) {
            purgar(d);
            return d.size() >= max;
        }
    }

    /** Registra un intento fallido. */
    public void fallo(String ip) {
        Deque<Long> d = fallos.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (d) {
            purgar(d);
            d.addLast(clock.getAsLong());
        }
        if (fallos.size() > 10_000) limpiar();   // no dejar crecer el mapa con IPs viejas
    }

    /** Segundos hasta que la IP vuelva a tener intentos (para la cabecera Retry-After). */
    public long segundosParaReintentar(String ip) {
        Deque<Long> d = fallos.get(ip);
        if (d == null) return 0;
        synchronized (d) {
            Long primero = d.peekFirst();
            return primero == null ? 0 : Math.max(1, (primero + windowMs - clock.getAsLong() + 999) / 1000);
        }
    }

    private void purgar(Deque<Long> d) {
        long limite = clock.getAsLong() - windowMs;
        while (!d.isEmpty() && d.peekFirst() <= limite) d.pollFirst();
    }

    private void limpiar() {
        fallos.entrySet().removeIf(e -> { synchronized (e.getValue()) { purgar(e.getValue()); return e.getValue().isEmpty(); } });
    }
}
