/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.core;

import com.jarvis.inventory.DYSAInventoryService;
import com.jarvis.security.ClientSession;
import com.jarvis.security.Rol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ImpresionTest {

    @TempDir Path dir;

    private DYSAInventoryService inv() { return new DYSAInventoryService(dir.resolve("inv.json").toString()); }

    @Test void porDefectoTicketDe80SinImprimirSolo() {
        assertEquals(new DidierImpresion.Config(80, false, false), new DidierImpresion(inv()).config());
    }

    @Test void seGuardaYSeRecuerdaAlReiniciar() {
        DidierImpresion imp = new DidierImpresion(inv());
        DYSAInventoryService.Resultado r = imp.guardar(new DidierImpresion.Config(58, true, true));
        assertTrue(r.ok(), r.mensaje());
        assertTrue(r.mensaje().contains("volver a abrir"), "avisa que la impresión directa necesita reabrir");
        assertEquals(new DidierImpresion.Config(58, true, true), new DidierImpresion(inv()).config());

        r = imp.guardar(new DidierImpresion.Config(80, true, true));
        assertFalse(r.mensaje().contains("volver a abrir"), "si no cambia la impresión directa, no lo repite");
    }

    @Test void soloPapelDe58u80() {
        DidierImpresion imp = new DidierImpresion(inv());
        assertFalse(imp.guardar(new DidierImpresion.Config(70, false, false)).ok());
        assertFalse(imp.guardar(null).ok());
        assertEquals(80, imp.config().ancho());
    }

    @Test void soloElAdministradorLaCambia() {
        DidierImpresion imp = new DidierImpresion(inv());
        DYSAInventoryService.Resultado r = ClientSession.como("c", "c@test", Rol.CAJERO, () -> imp.guardar(new DidierImpresion.Config(58, true, false)));
        assertEquals(DYSAInventoryService.Estado.PROHIBIDO, r.estado());
        assertEquals(80, imp.config().ancho());
    }
}
