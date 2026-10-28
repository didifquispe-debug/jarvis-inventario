/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.voz;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class EscuchaLocalTest {

    private String precisa;
    private final EscuchaLocal escucha = new EscuchaLocal(new VozLocal(false, "x", "auto"), true, pcm -> precisa);

    private List<EscuchaLocal.Evento> nuevos(long desde) throws InterruptedException {
        return escucha.eventos(desde, 0);
    }

    private String oir(String libre, String preciso) throws InterruptedException {
        long antes = escucha.ultimoId();
        precisa = preciso;
        escucha.alTerminarFrase(libre, new byte[0]);
        List<EscuchaLocal.Evento> ev = nuevos(antes);
        return ev.isEmpty() ? "(nada)" : ev.get(ev.size() - 1).tipo() + ":" + ev.get(ev.size() - 1).texto();
    }

    @Test void sinManosLibresNoAtiendeNada() throws Exception {
        assertEquals("(nada)", oir("jarvis vendí dos cementos", null));
    }

    @Test void conManosLibresSoloAtiendeLoQueEmpiezaConJarvis() throws Exception {
        escucha.manosLibres(true);
        assertEquals("(nada)", oir("cuál es la capital de francia", null));
        assertEquals("orden:vendí dos cementos", oir("harvey vendí dos cementos", "jarvis vendí dos cementos"));
        assertEquals("orden:cuántos focos quedan", oir("oye jarvis cuántos focos quedan", null));
    }

    @Test void jarvisSoloEsperaLaOrdenEnLaFraseSiguiente() throws Exception {
        escucha.manosLibres(true);
        assertEquals("activado:", oir("jarvis", null));
        escucha.seguir(true);
        assertTrue(escucha.esperandoOrden());
        assertEquals("orden:vendí tres focos", oir("vendí tres focos", null));
        assertFalse(escucha.esperandoOrden());
        assertEquals("(nada)", oir("vendí tres focos", null), "la siguiente ya necesita \"Jarvis\"");
    }

    @Test void trasUnaPreguntaElSiNoNecesitaJarvis() throws Exception {
        escucha.manosLibres(true);
        escucha.seguir(true);
        assertEquals("orden:sí", oir("sí", null));
    }

    @Test void elBotonDelMicrofonoTomaLaFraseComoOrden() throws Exception {
        escucha.escucharUnaVez();
        assertEquals("orden:cuántos focos quedan", oir("cuantos foco quedan", "cuántos focos quedan"));
        assertEquals("(nada)", oir("otra frase", null), "solo una vez");
    }

    @Test void siLaPasadaPrecisaFallaUsaLoQueOyo() throws Exception {
        EscuchaLocal e = new EscuchaLocal(new VozLocal(false, "x", "auto"), true, pcm -> { throw new IllegalStateException(); });
        e.escucharUnaVez();
        e.alTerminarFrase("cierre de caja", new byte[0]);
        assertEquals("cierre de caja", e.eventos(0, 0).get(0).texto());
    }

    @Test void elSilencioNoEsUnaOrden() throws Exception {
        escucha.escucharUnaVez();
        assertEquals("(nada)", oir("", null));
        assertEquals("(nada)", oir("los", null), "un ruido que el modelo oyó como palabra");
        assertEquals("(nada)", oir("eh", null));
        assertTrue(escucha.esperandoOrden(), "sigue esperando la orden");
    }

    @Test void losEventosEsperanYNoSeRepiten() throws Exception {
        long t = System.currentTimeMillis();
        assertEquals(List.of(), escucha.eventos(0, 300));
        assertTrue(System.currentTimeMillis() - t >= 250, "espera antes de responder vacío");
        escucha.escucharUnaVez();
        escucha.alTerminarFrase("inventario", new byte[0]);
        List<EscuchaLocal.Evento> ev = escucha.eventos(0, 1000);
        assertEquals(1, ev.size());
        assertEquals(List.of(), escucha.eventos(ev.get(0).id(), 0));
    }

    @Test void sinModeloNoEstaDisponibleYDiceElMotivo() {
        assertFalse(escucha.disponible());
        assertTrue(String.valueOf(escucha.estado().get("motivo")).startsWith("No hay modelo de voz"));
        EscuchaLocal apagada = new EscuchaLocal(new VozLocal(false, "x", "auto"), false, pcm -> "");
        assertEquals("Desactivado (jarvis.voz.escuchar=false).", apagada.estado().get("motivo"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "jarvis vendí dos cementos|vendí dos cementos",
            "Jarvis, ¿cuántos focos quedan?|¿cuántos focos quedan?",
            "oye harvis cierre de caja|cierre de caja",
            "jarvis|''"})
    void loQueVieneTrasJarvis(String dicho, String orden) {
        assertEquals(Optional.of(orden), VozLocal.trasJarvis(dicho));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sí", "no", "ok", "cobrar", "inventario", "dos focos"})
    void palabrasCortasQueSiSonOrdenes(String dicho) { assertFalse(EscuchaLocal.ruido(dicho), dicho); }

    @ParameterizedTest
    @ValueSource(strings = {"vendí dos cementos", "jardín", "", "har vis"})
    void sinJarvis(String dicho) { assertEquals(Optional.empty(), VozLocal.trasJarvis(dicho)); }
}
