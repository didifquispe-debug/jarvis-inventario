/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.ai;

import ai.djl.huggingface.translator.TextEmbeddingTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.inventory.Palabras;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    public record Clasificacion(Intent intent, double confianza) {}

    private final boolean habilitado;
    private final Path modelDir;
    private final String ejemplosRecurso;
    private final boolean includeTokenTypes;
    private final double umbral;
    private final double margen;

    private ZooModel<String, float[]> model;
    private Predictor<String, float[]> predictor;
    private final Map<Intent, List<float[]>> prototipos = new EnumMap<>(Intent.class);
    private volatile boolean listo;

    public IntentClassifier(@Value("${jarvis.intent.enabled:true}") boolean habilitado,
                            @Value("${jarvis.intent.model-dir:${user.home}/jarvis-models/intent}") String modelDir,
                            @Value("${jarvis.intent.examples:intents-es.json}") String ejemplosRecurso,
                            @Value("${jarvis.intent.include-token-types:true}") boolean includeTokenTypes,
                            @Value("${jarvis.intent.threshold:0.62}") double umbral,
                            @Value("${jarvis.intent.margin:0.04}") double margen) {
        this.habilitado = habilitado;
        this.modelDir = Paths.get(modelDir);
        this.ejemplosRecurso = ejemplosRecurso;
        this.includeTokenTypes = includeTokenTypes;
        this.umbral = umbral;
        this.margen = margen;
    }

    public boolean listo() { return listo; }

    @PostConstruct
    void init() {
        if (!habilitado) { log.info("Clasificador de intenciones desactivado (jarvis.intent.enabled=false)."); return; }
        if (!Files.exists(modelDir.resolve("model.onnx")) || !Files.exists(modelDir.resolve("tokenizer.json"))) {
            log.warn("Clasificador de intenciones sin modelo: faltan model.onnx/tokenizer.json en {}. Se usarán las reglas clásicas.", modelDir);
            return;
        }
        long t0 = System.currentTimeMillis();
        Map<Intent, List<String>> ejemplos;
        try {
            ejemplos = cargarEjemplos();
        } catch (Exception e) {
            log.error("No pude leer los ejemplos de intenciones ({}): {}", ejemplosRecurso, e.getMessage());
            return;
        }
        for (boolean tokenTypes : new boolean[]{includeTokenTypes, !includeTokenTypes}) {
            try {
                cargarModelo(tokenTypes);
                calcularPrototipos(ejemplos);
                listo = true;
                log.info("Clasificador de intenciones listo en {} ms ({} ejemplos, token_type_ids={}).",
                        System.currentTimeMillis() - t0, prototipos.values().stream().mapToInt(List::size).sum(), tokenTypes);
                return;
            } catch (Exception e) {
                log.warn("Fallo al cargar el modelo de intenciones (token_type_ids={}): {}", tokenTypes, e.getMessage());
                cerrar();
            }
        }
        log.error("Clasificador de intenciones no disponible. Se usarán las reglas clásicas.");
    }

    private void cargarModelo(boolean tokenTypes) throws Exception {
        Criteria<String, float[]> criteria = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelPath(modelDir)
                .optModelName("model")
                .optEngine("OnnxRuntime")
                .optTranslatorFactory(new TextEmbeddingTranslatorFactory())
                .optArgument("pooling", "mean")
                .optArgument("normalize", "true")
                .optArgument("includeTokenTypes", String.valueOf(tokenTypes))
                .build();
        model = criteria.loadModel();
        predictor = model.newPredictor();
    }

    private Map<Intent, List<String>> cargarEjemplos() throws Exception {
        try (InputStream in = new ClassPathResource(ejemplosRecurso).getInputStream()) {
            Map<String, List<String>> raw = new ObjectMapper().readValue(in, new TypeReference<>() {});
            Map<Intent, List<String>> out = new EnumMap<>(Intent.class);
            raw.forEach((k, v) -> out.put(Intent.valueOf(k), v));
            return out;
        }
    }

    private void calcularPrototipos(Map<Intent, List<String>> ejemplos) throws Exception {
        prototipos.clear();
        for (Map.Entry<Intent, List<String>> e : ejemplos.entrySet()) {
            List<float[]> vs = new ArrayList<>();
            for (String frase : e.getValue()) vs.add(predictor.predict(Palabras.norm(frase)));
            prototipos.put(e.getKey(), vs);
        }
    }

    @PreDestroy
    void cerrar() {
        listo = false;
        if (predictor != null) { predictor.close(); predictor = null; }
        if (model != null) { model.close(); model = null; }
    }

    public synchronized Clasificacion clasificar(String texto) {
        if (!listo || texto == null || texto.isBlank()) return new Clasificacion(Intent.DESCONOCIDO, 0);
        try {
            float[] v = predictor.predict(texto);
            Map<Intent, Double> puntajes = new EnumMap<>(Intent.class);
            prototipos.forEach((intent, vs) -> {
                double best = -1;
                for (float[] p : vs) best = Math.max(best, coseno(v, p));
                puntajes.put(intent, best);
            });
            return decidir(puntajes, umbral, margen);
        } catch (Exception e) {
            log.warn("Error clasificando \"{}\": {}", texto, e.getMessage());
            return new Clasificacion(Intent.DESCONOCIDO, 0);
        }
    }

    static Clasificacion decidir(Map<Intent, Double> puntajes, double umbral, double margen) {
        Intent mejor = Intent.DESCONOCIDO;
        double s1 = -1, s2 = -1;
        for (Map.Entry<Intent, Double> e : puntajes.entrySet()) {
            double s = e.getValue();
            if (s > s1) { s2 = s1; s1 = s; mejor = e.getKey(); }
            else if (s > s2) { s2 = s; }
        }
        if (mejor == Intent.DESCONOCIDO || s1 < umbral || s1 - s2 < margen)
            return new Clasificacion(Intent.DESCONOCIDO, Math.max(s1, 0));
        return new Clasificacion(mejor, s1);
    }

    static double coseno(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
