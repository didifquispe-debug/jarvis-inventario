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
        AccessToken t = new AccessToken(AccessToken.CLAVE_DE_FABRICA, archivo.toString());
        assertTrue(t.generada());
        assertNotEquals(AccessToken.CLAVE_DE_FABRICA, t.value());
        assertFalse(t.matches(AccessToken.CLAVE_DE_FABRICA), "la clave de fábrica no debe abrir la puerta");
        assertTrue(t.value().matches("[a-z2-9]{4}(-[a-z2-9]{4}){3}"), t.value());
        assertEquals(t.value(), Files.readString(archivo).trim(), "se guarda para el próximo arranque");
    }

    @Test void claveVaciaTambienGenera() {
        AccessToken t = new AccessToken("  ", dir.resolve("clave").toString());
        assertTrue(t.generada());
        assertTrue(t.matches(t.value()));
    }

    @Test void segundoArranqueReutilizaLaGuardadaSinMostrarla() {
        String archivo = dir.resolve("clave").toString();
        AccessToken primero = new AccessToken(AccessToken.CLAVE_DE_FABRICA, archivo);
        AccessToken segundo = new AccessToken(AccessToken.CLAVE_DE_FABRICA, archivo);
        assertEquals(primero.value(), segundo.value());
        assertFalse(segundo.generada(), "solo se muestra cuando se crea");
        assertEquals(primero.value(), AccessToken.resolverSinGenerar(AccessToken.CLAVE_DE_FABRICA, archivo));
    }

    @Test void clavePropiaTieneprioridad() {
        AccessToken t = new AccessToken("mi-clave-larga-123", dir.resolve("clave").toString());
        assertFalse(t.generada());
        assertEquals("mi-clave-larga-123", t.value());
        assertFalse(Files.exists(dir.resolve("clave")), "no crea archivo si hay clave propia");
    }

    @Test void clavesAleatoriasDistintas() {
        assertNotEquals(AccessToken.generar(), AccessToken.generar());
    }

    @Test void comparacionEnTiempoConstanteEsCorrecta() {
        assertTrue(AccessToken.constantTimeEquals("abc", "abc"));
        assertFalse(AccessToken.constantTimeEquals("abc", "abd"));
        assertFalse(AccessToken.constantTimeEquals("abc", null));
        assertFalse(new AccessToken("x-clave-propia", dir.resolve("c").toString()).matches(""));
    }
}
