/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.desktop;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AppWindowTest {

    private final Path perfil = Path.of(System.getProperty("java.io.tmpdir"), "jarvis-ventana-prueba");

    @Test void abreComoAplicacionConPerfilPropio() {
        List<String> a = AppWindow.argumentos("http://localhost:7060/#t=abc", false, perfil);
        assertEquals("--app=http://localhost:7060/#t=abc", a.get(0));
        assertTrue(a.contains("--user-data-dir=" + perfil.toAbsolutePath()), a.toString());
        assertTrue(a.contains("--no-first-run"));
        assertFalse(a.contains("--kiosk-printing"), "sin impresión directa, Chrome muestra el diálogo");
    }

    @Test void impresionDirectaUsaKioskPrinting() {
        assertTrue(AppWindow.argumentos("http://localhost:7060/", true, perfil).contains("--kiosk-printing"));
    }

    @Test void elPerfilVaEnLaCarpetaDelUsuario() {
        assertEquals(Path.of(System.getProperty("user.home"), ".jarvis-ventana"), AppWindow.perfil());
    }
}
