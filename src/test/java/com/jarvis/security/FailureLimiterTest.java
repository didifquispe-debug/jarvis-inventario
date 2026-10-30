/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.security;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class FailureLimiterTest {

    @Test void bloqueaAlLlegarAlLimiteYSeLiberaConElTiempo() {
        AtomicLong ahora = new AtomicLong(1_000_000);
        FailureLimiter l = new FailureLimiter(3, 60_000, ahora::get);
        l.fallo("ip"); l.fallo("ip");
        assertFalse(l.bloqueado("ip"));
        l.fallo("ip");
        assertTrue(l.bloqueado("ip"));
        assertEquals(60, l.segundosParaReintentar("ip"));
        ahora.addAndGet(60_001);
        assertFalse(l.bloqueado("ip"));
    }

    @Test void ventanaDeslizante() {
        AtomicLong ahora = new AtomicLong(0);
        FailureLimiter l = new FailureLimiter(2, 60_000, ahora::get);
        l.fallo("ip");
        ahora.set(50_000);
        l.fallo("ip");
        assertTrue(l.bloqueado("ip"));
        ahora.set(60_001);
        assertFalse(l.bloqueado("ip"));
    }

    @Test void comparacionDeClaves() {
        assertTrue(AccessToken.constantTimeEquals("abc", "abc"));
        assertFalse(AccessToken.constantTimeEquals("abc", "abd"));
        assertFalse(AccessToken.constantTimeEquals("abc", "abcd"));
        assertFalse(AccessToken.constantTimeEquals("abc", null));
        String archivo = System.getProperty("java.io.tmpdir") + "/jarvis-test-clave-" + java.util.UUID.randomUUID();
        assertFalse(new AccessToken("", archivo).matches(""), "una clave vacía nunca da acceso");
    }
}
