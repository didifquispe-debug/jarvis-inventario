package com.jarvis;

import com.jarvis.core.OrdenesPorConfirmar;
import com.jarvis.security.ClientSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** El "¿Lo hago?" de las órdenes de la IA y de las ventas dictadas por voz. */
class OrdenesPorConfirmarTest {

    private final OrdenesPorConfirmar p = new OrdenesPorConfirmar();
    private final List<String> ejecutadas = new ArrayList<>();
    private final Function<String, String> reglas = o -> { ejecutadas.add(o); return "hecho: " + o; };

    @Test
    void preguntaAntesDeHacerla() {
        assertEquals("Entendí: vendí 2 polera roja M. ¿Lo hago?", p.proponer("vendí 2 polera roja M"));
        assertTrue(p.hayPendiente());
        assertTrue(ejecutadas.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"sí", "Sí, hazlo", "dale", "ok", "claro que sí", "Jarvis, sí por favor", "¡Correcto!"})
    void conSiLaHacen(String respuesta) {
        p.proponer("vendí 2 polera roja M");
        assertEquals(Optional.of("hecho: vendí 2 polera roja M"), p.resolver(respuesta, reglas));
        assertEquals(List.of("vendí 2 polera roja M"), ejecutadas);
        assertFalse(p.hayPendiente());
    }

    @ParameterizedTest
    @ValueSource(strings = {"no", "No, gracias", "cancela", "mejor no", "déjalo"})
    void conNoSeDescarta(String respuesta) {
        p.proponer("vendí 2 polera roja M");
        assertEquals(Optional.of("Muy bien, no lo hago."), p.resolver(respuesta, reglas));
        assertTrue(ejecutadas.isEmpty());
        assertFalse(p.hayPendiente());
    }

    @Test
    void otraFraseLaDescartaYSigueComoOrdenNueva() {
        p.proponer("vendí 2 polera roja M");
        assertEquals(Optional.empty(), p.resolver("cuántas tengo de polera roja M", reglas));
        assertFalse(p.hayPendiente());
        assertEquals(Optional.empty(), p.resolver("sí", reglas));   // ya no queda nada que confirmar
        assertTrue(ejecutadas.isEmpty());
    }

    @Test
    void cadaDispositivoTieneLaSuya() {
        ClientSession.como("laptop", () -> p.proponer("vendí 2 polera roja M"));
        assertEquals(Optional.empty(), ClientSession.como("celular", () -> p.resolver("sí", reglas)));
        assertEquals(Optional.of("hecho: vendí 2 polera roja M"), ClientSession.como("laptop", () -> p.resolver("sí", reglas)));
    }
}
