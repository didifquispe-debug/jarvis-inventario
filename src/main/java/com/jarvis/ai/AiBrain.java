package com.jarvis.ai;

import com.jarvis.inventory.Palabras;
import org.springframework.stereotype.Component;

/**
 * Cerebro de JARVIS, 100% local y offline (sin Internet ni clave de API):
 * {@link #interpretar(String)} reconoce la intención con DJL ({@link IntentClassifier})
 * y extrae las entidades ({@link EntityExtractor}).
 */
@Component
public class AiBrain {

    private final IntentClassifier classifier;

    public AiBrain(IntentClassifier classifier) {
        this.classifier = classifier;
    }

    /** ¿Está cargado el modelo local de intenciones? */
    public boolean intencionesLocales() { return classifier.listo(); }

    /**
     * Pipeline local: clasifica la intención con DJL y, si es de inventario, extrae cantidad,
     * producto y precio. Recibe el comando ya normalizado (minúsculas, sin tildes).
     */
    public IntentResult interpretar(String cmd) {
        String texto = Palabras.norm(cmd);
        IntentClassifier.Clasificacion c = classifier.clasificar(texto);
        if (c.intent() == Intent.DESCONOCIDO) return new IntentResult(Intent.DESCONOCIDO, c.confianza(), null, "", null);
        EntityExtractor.Entidades e = EntityExtractor.extraer(texto);
        return new IntentResult(c.intent(), c.confianza(), e.cantidad(), e.producto(), e.precio(), e.enPares());
    }
}
