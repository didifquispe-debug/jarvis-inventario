package com.jarvis.ai;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba con el modelo ONNX real. Solo corre si existe la carpeta del modelo
 * (por defecto ~/jarvis-models/intent, o -Djarvis.intent.model-dir=...).
 */
@EnabledIf("hayModelo")
class IntentClassifierModelTest {

    static final String DIR = System.getProperty("jarvis.intent.model-dir",
            Paths.get(System.getProperty("user.home"), "jarvis-models", "intent").toString());
    static IntentClassifier clf;
    static AiBrain brain;

    static boolean hayModelo() { return Files.exists(Path.of(DIR, "model.onnx")); }

    @BeforeAll static void cargar() {
        clf = new IntentClassifier(true, DIR, "intents-es.json", true, 0.62, 0.04);
        clf.init();
        brain = new AiBrain("", "", "señor", clf);
        assertTrue(clf.listo(), "el modelo debería cargar");
    }

    @AfterAll static void cerrar() { if (clf != null) clf.cerrar(); }

    // Frases que NO están en intents-es.json
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "me dejaron 25 bolsas de cemento        | REGISTRAR_ENTRADA",
            "hoy entraron 40 focos nuevos            | REGISTRAR_ENTRADA",
            "le vendi 3 discos de corte a un cliente | REGISTRAR_SALIDA",
            "se fueron 12 metros de cable            | REGISTRAR_SALIDA",
            "cuantos clavos nos quedan               | CONSULTAR_STOCK",
            "todavia tengo cemento                   | CONSULTAR_STOCK",
            "como cerramos la caja hoy               | REPORTE_DIARIO",
            "que hora es                             | DESCONOCIDO",
            "abre spotify                            | DESCONOCIDO",
            "cuanto es 7 por 8                       | DESCONOCIDO"})
    void clasifica(String frase, Intent esperado) {
        IntentResult r = brain.interpretar(frase.trim());
        System.out.printf("%-42s -> %-18s %.2f  cant=%d prod='%s'%n", frase.trim(), r.intent(), r.confianza(), r.cantidad(), r.producto());
        assertEquals(esperado, r.intent());
    }
}
