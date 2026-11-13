/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AccessTokenTest {

    @TempDir Path dir;

    @Test void claveDeFabricaNuncaSeUsa() throws Exception {
        Path archivo = dir.resolve("clave");
        ApazaAccessToken t = new ApazaAccessToken(ApazaAccessToken.CLAVE_DE_FABRICA, archivo.toString());
        assertTrue(t.generada());
        assertNotEquals(ApazaAccessToken.CLAVE_DE_FABRICA, t.value());
        assertFalse(t.matches(ApazaAccessToken.CLAVE_DE_FABRICA), "la clave de fábrica no debe abrir la puerta");
        assertTrue(t.value().matches("[a-z2-9]{4}(-[a-z2-9]{4}){3}"), t.value());
        assertEquals(t.value(), Files.readString(archivo).trim(), "se guarda para el próximo arranque");
    }

    @Test void claveVaciaTambienGenera() {
        ApazaAccessToken t = new ApazaAccessToken("  ", dir.resolve("clave").toString());
        assertTrue(t.generada());
        assertTrue(t.matches(t.value()));
    }

    @Test void segundoArranqueReutilizaLaGuardadaSinMostrarla() {
        String archivo = dir.resolve("clave").toString();
        ApazaAccessToken primero = new ApazaAccessToken(ApazaAccessToken.CLAVE_DE_FABRICA, archivo);
        ApazaAccessToken segundo = new ApazaAccessToken(ApazaAccessToken.CLAVE_DE_FABRICA, archivo);
        assertEquals(primero.value(), segundo.value());
        assertFalse(segundo.generada(), "solo se muestra cuando se crea");
        assertEquals(primero.value(), ApazaAccessToken.resolverSinGenerar(ApazaAccessToken.CLAVE_DE_FABRICA, archivo));
    }

    @Test void clavePropiaTieneprioridad() {
        ApazaAccessToken t = new ApazaAccessToken("mi-clave-larga-123", dir.resolve("clave").toString());
        assertFalse(t.generada());
        assertEquals("mi-clave-larga-123", t.value());
        assertFalse(Files.exists(dir.resolve("clave")), "no crea archivo si hay clave propia");
    }

    @Test void clavesAleatoriasDistintas() {
        assertNotEquals(ApazaAccessToken.generar(), ApazaAccessToken.generar());
    }

    @Test void comparacionEnTiempoConstanteEsCorrecta() {
        assertTrue(ApazaAccessToken.constantTimeEquals("abc", "abc"));
        assertFalse(ApazaAccessToken.constantTimeEquals("abc", "abd"));
        assertFalse(ApazaAccessToken.constantTimeEquals("abc", null));
        assertFalse(new ApazaAccessToken("x-clave-propia", dir.resolve("c").toString()).matches(""));
    }
}
