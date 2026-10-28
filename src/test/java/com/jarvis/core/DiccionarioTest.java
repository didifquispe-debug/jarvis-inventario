/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class DiccionarioTest {

    @TempDir Path dir;

    private Diccionario con(String lineas) throws Exception {
        Path archivo = dir.resolve("diccionario.txt");
        Files.writeString(archivo, lineas, StandardCharsets.UTF_8);
        return new Diccionario(archivo.toString());
    }

    @Test void encadenaSinonimos() throws Exception {
        Diccionario d = con("zapas = tenis\ntenis = zapatillas\n");
        assertEquals("vendi 2 zapatillas", d.aplicar("vendi 2 zapas"));
        assertEquals("vendi 2 zapatillas", d.aplicar("vendi 2 tenis"));
    }

    @Test void cicloNoSeCuelga() throws Exception {
        Diccionario d = con("a = b\nb = a\n");
        String r = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> d.aplicar("a"));
        assertNotNull(r);
        assertTrue(r.equals("a") || r.equals("b"), r);
    }

    @Test void sinonimoQueSeContieneASiMismoTermina() throws Exception {
        Diccionario d = con("polo = polo rojo\n");
        assertNotNull(assertTimeoutPreemptively(Duration.ofSeconds(2), () -> d.aplicar("vendi 1 polo")));
    }

    @Test void encadenadoRespetaLimitesDePalabra() throws Exception {
        Diccionario d = con("zapas = tenis\ntenis = zapatillas\n");
        assertEquals("zapasos tenista", d.aplicar("zapasos tenista"));
    }

    @Test void pluralDePalabraTerminadaEnZ() throws Exception {
        Diccionario d = con("");
        assertTrue(d.aprender("lapiz", "unidades").startsWith("Aprendido"));
        assertEquals("tengo 3 unidades", d.aplicar("tengo 3 lapices"));
        assertEquals("tengo 1 unidades", d.aplicar("tengo 1 lapiz"));
        assertEquals("tengo 3 lapizes", d.aplicar("tengo 3 lapizes"), "ya no se genera \"lapizes\"");
    }

    @Test void reglasDePlural() {
        assertEquals("lapices", Diccionario.plural("lapiz"));
        assertEquals("luces", Diccionario.plural("luz"));
        assertEquals("poleras", Diccionario.plural("polera"));
        assertEquals("pantalones", Diccionario.plural("pantalon"));
    }
}
