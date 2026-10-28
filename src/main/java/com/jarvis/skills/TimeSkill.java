/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.skills;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

@Component
@Order(1)
public class TimeSkill implements Skill {

    private static final Locale ES = Locale.forLanguageTag("es");

    @Override public String name() { return "Hora y fecha"; }
    @Override public String example() { return "¿Qué hora es? / ¿Qué día es hoy?"; }

    @Override
    public Optional<String> handle(String cmd, String original) {
        if (cmd.matches(".*\\bhora\\b.*")) {
            String h = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a", ES));
            return Optional.of("Son las " + h + (h.endsWith(".") ? "" : "."));
        }
        if (cmd.contains("fecha") || cmd.contains("que dia")) {
            String d = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' yyyy", ES));
            return Optional.of("Hoy es " + d + ".");
        }
        return Optional.empty();
    }
}
