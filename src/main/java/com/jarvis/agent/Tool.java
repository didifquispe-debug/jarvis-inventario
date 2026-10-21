package com.jarvis.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Una herramienta que la IA puede decidir usar por sí misma (function calling).
 * Distinta de Skill: Skill reacciona a palabras; Tool la elige el modelo según el objetivo.
 * Para añadir una, crea una clase @Component que implemente esta interfaz.
 */
public interface Tool {

    /** Nombre técnico (sin espacios) que ve el modelo, ej. "abrir_app". */
    String name();

    /** Qué hace, para que el modelo sepa cuándo usarla. */
    String description();

    /** Esquema JSON de los parámetros (propiedades de "input_schema"). */
    ObjectNode inputSchema();

    /** true si puede modificar/borrar/ejecutar: entonces JARVIS pide confirmación. */
    default boolean risky() { return false; }

    /** Frase corta para mostrar al pedir confirmación, ej. "Ejecutar: shutdown". */
    String humanize(JsonNode input);

    /** Ejecuta la acción y devuelve el resultado como texto para el modelo. */
    String run(JsonNode input) throws Exception;
}
