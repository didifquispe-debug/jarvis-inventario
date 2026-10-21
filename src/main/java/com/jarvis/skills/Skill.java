package com.jarvis.skills;

import java.util.Optional;

/**
 * Una "habilidad" de JARVIS. Para añadir una nueva, crea una clase que implemente
 * esta interfaz y márcala con @Component: se registra sola.
 */
public interface Skill {

    /** Nombre corto para la lista de ayuda. */
    String name();

    /** Ejemplo de frase que activa la habilidad. */
    String example();

    /**
     * Intenta manejar el comando (ya en minúsculas y sin tildes).
     * Devuelve la respuesta, o vacío si este comando no le corresponde.
     */
    Optional<String> handle(String command, String original);
}
