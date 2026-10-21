package com.jarvis.ai;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pruebas del pipeline sin modelo: extracción de entidades y regla de decisión. */
class IntentPipelineTest {

    @Test void extraeCantidadProductoYPrecio() {
        EntityExtractor.Entidades e = EntityExtractor.extraer("llegaron 20 bolsas de cemento a 28 soles");
        assertEquals(20, e.cantidad());
        assertEquals("cemento", e.producto());
        assertEquals(28.0, e.precio());
    }

    @Test void cantidadEnPalabras() {
        EntityExtractor.Entidades e = EntityExtractor.extraer("vendi veinticinco metros de cable");
        assertEquals(25, e.cantidad());
        assertEquals("cable", e.producto());
        assertNull(e.precio());
    }

    @Test void articuloNoEsCantidad() {
        assertEquals(10, EntityExtractor.extraer("registra una entrada de 10 focos").cantidad());
        assertEquals(1, EntityExtractor.extraer("vendi una bolsa de cemento").cantidad());
    }

    @Test void sinCantidad() {
        EntityExtractor.Entidades e = EntityExtractor.extraer("cuantos focos quedan");
        assertEquals(-1, e.cantidad());
        assertEquals("focos", e.producto());
    }

    @Test void decideLaMejorIntencion() {
        assertEquals(Intent.REGISTRAR_SALIDA, IntentClassifier.decidir(puntajes(0.85, 0.60, 0.40), 0.62, 0.04).intent());
    }

    @Test void confianzaBajaEsDesconocido() {
        assertEquals(Intent.DESCONOCIDO, IntentClassifier.decidir(puntajes(0.55, 0.30, 0.20), 0.62, 0.04).intent());
    }

    @Test void empateEsDesconocido() {
        assertEquals(Intent.DESCONOCIDO, IntentClassifier.decidir(puntajes(0.80, 0.79, 0.20), 0.62, 0.04).intent());
    }

    /** salida, entrada, desconocido */
    private Map<Intent, Double> puntajes(double salida, double entrada, double desconocido) {
        Map<Intent, Double> m = new EnumMap<>(Intent.class);
        m.put(Intent.REGISTRAR_SALIDA, salida);
        m.put(Intent.REGISTRAR_ENTRADA, entrada);
        m.put(Intent.DESCONOCIDO, desconocido);
        return m;
    }
}
