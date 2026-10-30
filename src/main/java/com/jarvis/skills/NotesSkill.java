/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.skills;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Component
@Order(-1)
public class NotesSkill implements Skill {

    private final Path file = Paths.get(System.getProperty("user.home"), "jarvis-notas.txt");

    @Override public String name() { return "Notas"; }
    @Override public String example() { return "Anota comprar leche / Lee mis notas / Borra mis notas"; }

    @Override
    public Optional<String> handle(String cmd, String original) {
        try {
            if (cmd.startsWith("anota ") || cmd.startsWith("recuerda que ") || cmd.startsWith("toma nota ")) {
                String nota = original.replaceFirst("(?i)^(anota|recuerda que|toma nota de|toma nota)\\s*", "").trim();
                String linea = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM HH:mm")) + " - " + nota;
                Files.writeString(file, linea + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return Optional.of("Anotado: " + nota);
            }
            if (cmd.contains("mis notas") && (cmd.contains("lee") || cmd.contains("muestra") || cmd.contains("dime"))) {
                if (!Files.exists(file)) return Optional.of("No tiene notas guardadas.");
                List<String> notas = Files.readAllLines(file, StandardCharsets.UTF_8);
                if (notas.isEmpty()) return Optional.of("No tiene notas guardadas.");
                return Optional.of("Sus notas:\n" + String.join("\n", notas));
            }
            if (cmd.contains("borra") && cmd.contains("notas")) {
                Files.deleteIfExists(file);
                return Optional.of("Notas borradas.");
            }
        } catch (IOException e) {
            return Optional.of("Error con las notas: " + e.getMessage());
        }
        return Optional.empty();
    }
}
