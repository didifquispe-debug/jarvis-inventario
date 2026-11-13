/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.ai;

import com.jarvis.inventory.Palabras;
import org.springframework.stereotype.Component;

/** El cerebro de JARVIS: entiende la orden y saca sus datos. Lleva el nombre del equipo: Didier, Yoel, Sumi y Apaza. */
@Component
public class DYSA {

    private final YoelIntentClassifier classifier;

    public DYSA(YoelIntentClassifier classifier) {
        this.classifier = classifier;
    }

    public boolean intencionesLocales() { return classifier.listo(); }

    public IntentResult interpretar(String cmd) {
        String texto = Palabras.norm(cmd);
        YoelIntentClassifier.Clasificacion c = classifier.clasificar(texto);
        if (c.intent() == Intent.DESCONOCIDO) return new IntentResult(Intent.DESCONOCIDO, c.confianza(), null, "", null);
        YoelEntityExtractor.Entidades e = YoelEntityExtractor.extraer(texto);
        return new IntentResult(c.intent(), c.confianza(), e.cantidad(), e.producto(), e.precio(), e.enPares());
    }
}
