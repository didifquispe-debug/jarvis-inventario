/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.core;

import com.jarvis.inventory.DYSAInventoryService;
import com.jarvis.inventory.Moneda;
import com.jarvis.inventory.NumeroALetras;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DatosNegocioTest {

    @TempDir Path dir;

    private DYSAInventoryService inv() { return new DYSAInventoryService(dir.resolve("inv.json").toString()); }

    private static DatosNegocio con(DYSAInventoryService inv, String nombre) {
        return new DatosNegocio(inv, nombre, "", "20601234567", "Av. Grau 123", "", "", "S/", "soles", "sol");
    }

    @AfterEach void volverASoles() { Moneda.usar("S/", "soles", "sol"); }

    @Test void sinCambiosUsaApplicationProperties() {
        DatosNegocio d = con(inv(), "Ferretería El Clavo");
        assertEquals("Ferretería El Clavo", d.nombre());
        assertEquals("20601234567", d.datos().ruc());
        assertEquals("soles", Moneda.nombre());
        assertEquals("Mi Negocio", con(inv(), "").paraRecibo().get("nombre"));
    }

    @Test void guardaYSeConservaAlReiniciar() {
        DYSAInventoryService inv = inv();
        DatosNegocio d = con(inv, "Ferretería El Clavo");
        assertTrue(d.guardar(new DatosNegocio.Datos("Bodega Doña Rosa", "", "Jr. Lima 45", "987 654 321", "rosa@correo.pe",
                "US$", "Dólares", "dólar")).ok());
        assertEquals("dólares", Moneda.nombre(), "se guarda en minúsculas");
        assertEquals("US$", Moneda.simbolo());
        assertEquals("Jr. Lima 45", d.emisor().direccion());
        Moneda.usar("S/", "soles", "sol");
        DatosNegocio otra = con(new DYSAInventoryService(dir.resolve("inv.json").toString()), "Ferretería El Clavo");
        assertEquals("Bodega Doña Rosa", otra.nombre(), "lo guardado manda sobre application.properties");
        assertEquals("dólares", Moneda.nombre());
        assertEquals("CIEN CON 00/100 DÓLARES", NumeroALetras.importe(new BigDecimal("100")));
    }

    @Test void losMensajesUsanLaMoneda() {
        DYSAInventoryService inv = inv();
        con(inv, "").guardar(new DatosNegocio.Datos("", "", "", "", "", "$", "dólares", "dólar"));
        assertTrue(inv.crearProducto("Gorra", new BigDecimal("12.5"), BigDecimal.TEN, "unidades", null, null).ok());
        assertEquals("Gorra cuesta 12.50 dólares. Quedan 10 unidades.", inv.precio("gorra"));
    }

    @Test void datosInvalidos() {
        DatosNegocio d = con(inv(), "X");
        DatosNegocio.Datos base = new DatosNegocio.Datos("Tienda", "", "", "", "", "S/", "soles", "sol");
        assertFalse(d.guardar(new DatosNegocio.Datos("Tienda", "123", "", "", "", "S/", "soles", "sol")).ok(), "RUC corto");
        assertFalse(d.guardar(new DatosNegocio.Datos("Tienda", "", "", "", "no-es-correo", "S/", "soles", "sol")).ok());
        assertFalse(d.guardar(new DatosNegocio.Datos("Tienda", "", "", "", "", "", "soles", "sol")).ok(), "sin símbolo");
        assertFalse(d.guardar(new DatosNegocio.Datos("Tienda", "", "", "", "", "S/", "50%", "sol")).ok(), "la moneda son letras");
        assertFalse(d.guardar(new DatosNegocio.Datos("Tienda", "", "", "", "", "<b>", "soles", "sol")).ok());
        assertTrue(d.guardar(base).ok());
        assertEquals("Tienda", d.nombre());
    }
}
