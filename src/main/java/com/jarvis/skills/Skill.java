/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.skills;

import java.util.Optional;

public interface Skill {

    String name();

    String example();

    Optional<String> handle(String command, String original);
}
