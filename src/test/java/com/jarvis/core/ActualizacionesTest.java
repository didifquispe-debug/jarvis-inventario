/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActualizacionesTest {

    private static final String REPO = "didifquispe-debug/jarvis-inventario";

    private static String release(String tag, String url) {
        return "{\"tag_name\":\"" + tag + "\",\"html_url\":\"" + url + "\",\"draft\":false,\"prerelease\":false}";
    }

    @Test void comparaVersionesPorNumero() {
        assertTrue(Actualizaciones.comparar("0.10.0", "0.9.9") > 0);
        assertTrue(Actualizaciones.comparar("1.0", "1.0.1") < 0);
        assertEquals(0, Actualizaciones.comparar("1.2.0", "1.2"));
    }

    @Test void avisaSoloSiEsMasNueva() {
        String url = "https://github.com/" + REPO + "/releases/tag/v0.2.0";
        Actualizaciones.Nueva n = Actualizaciones.deRelease(release("v0.2.0", url), "0.1.0", REPO);
        assertNotNull(n);
        assertEquals("0.2.0", n.version());
        assertEquals(url, n.url());
        assertNull(Actualizaciones.deRelease(release("v0.1.0", url), "0.1.0", REPO));
        assertNull(Actualizaciones.deRelease(release("v0.0.9", url), "0.1.0", REPO));
    }

    @Test void ignoraBorradoresEtiquetasRarasYEnlacesDeOtroSitio() {
        assertNull(Actualizaciones.deRelease("{\"tag_name\":\"v9.0.0\",\"draft\":true}", "0.1.0", REPO));
        assertNull(Actualizaciones.deRelease("{\"tag_name\":\"v9.0.0\",\"prerelease\":true}", "0.1.0", REPO));
        assertNull(Actualizaciones.deRelease(release("ultima", "x"), "0.1.0", REPO));
        assertNull(Actualizaciones.deRelease("no es json", "0.1.0", REPO));
        Actualizaciones.Nueva n = Actualizaciones.deRelease(release("v1.0.0", "https://malicioso.example/descarga"), "0.1.0", REPO);
        assertEquals("https://github.com/" + REPO + "/releases/", n.url(), "solo enlaces del repositorio en GitHub");
    }

    @Test void sinVersionEmpaquetadaNoRevisa() {
        Actualizaciones a = new Actualizaciones(true, REPO);
        assertEquals("desarrollo", a.versionActual(), "en pruebas no hay manifiesto con versión");
        a.alArrancar();
        assertNull(a.estado().get("nueva"));
    }
}
