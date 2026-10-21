package com.jarvis.skills;

import com.jarvis.system.LaptopActions;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Abre programas instalados en la laptop. */
@Component
@Order(3)
public class AppSkill implements Skill {

    private final LaptopActions laptop;

    public AppSkill(LaptopActions laptop) { this.laptop = laptop; }

    @Override public String name() { return "Aplicaciones"; }
    @Override public String example() { return "Abre la calculadora / Abre el bloc de notas / Abre el explorador"; }

    @Override
    public Optional<String> handle(String cmd, String original) {
        if (!cmd.startsWith("abre ")) return Optional.empty();
        String app = cmd.substring(5).replaceFirst("^(el|la|los|las) ", "").trim();
        return laptop.openApp(app)
                .map(nombre -> Optional.of("Abriendo " + nombre + "."))
                .orElse(Optional.empty());
    }
}
