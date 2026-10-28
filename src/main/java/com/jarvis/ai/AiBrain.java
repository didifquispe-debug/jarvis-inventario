/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.ai;

import com.jarvis.inventory.Palabras;
import org.springframework.stereotype.Component;

@Component
public class AiBrain {

    private final IntentClassifier classifier;

    public AiBrain(IntentClassifier classifier) {
        this.classifier = classifier;
    }

    public boolean intencionesLocales() { return classifier.listo(); }

    public IntentResult interpretar(String cmd) {
        String texto = Palabras.norm(cmd);
        IntentClassifier.Clasificacion c = classifier.clasificar(texto);
        if (c.intent() == Intent.DESCONOCIDO) return new IntentResult(Intent.DESCONOCIDO, c.confianza(), null, "", null);
        EntityExtractor.Entidades e = EntityExtractor.extraer(texto);
        return new IntentResult(c.intent(), c.confianza(), e.cantidad(), e.producto(), e.precio(), e.enPares());
    }
}
