package com.jarvis.skills;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Optional;

@Component
@Order(0)
public class GreetingSkill implements Skill {

    private final String owner;

    public GreetingSkill(@Value("${jarvis.owner:señor}") String owner) {
        this.owner = owner;
    }

    @Override public String name() { return "Saludo"; }
    @Override public String example() { return "Hola JARVIS"; }

    @Override
    public Optional<String> handle(String cmd, String original) {
        if (cmd.matches(".*\\b(hola|buenos dias|buenas tardes|buenas noches|hey jarvis)\\b.*")
                && cmd.split("\\s+").length <= 4) {
            int h = LocalTime.now().getHour();
            String parte = h < 12 ? "Buenos días" : h < 19 ? "Buenas tardes" : "Buenas noches";
            return Optional.of(parte + ", " + owner + ". Todos los sistemas en línea. ¿En qué le ayudo?");
        }
        return Optional.empty();
    }
}
