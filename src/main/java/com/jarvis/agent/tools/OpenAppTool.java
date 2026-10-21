package com.jarvis.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.agent.Schemas;
import com.jarvis.agent.Tool;
import com.jarvis.system.LaptopActions;
import org.springframework.stereotype.Component;

@Component
public class OpenAppTool implements Tool {

    private final LaptopActions laptop;

    public OpenAppTool(LaptopActions laptop) { this.laptop = laptop; }

    @Override public String name() { return "abrir_app"; }
    @Override public String description() {
        return "Abre un programa instalado en la laptop (calculadora, bloc de notas, explorador, terminal, intellij, etc.).";
    }
    @Override public ObjectNode inputSchema() {
        return Schemas.object(Schemas.props().set("app", Schemas.string("Nombre del programa a abrir")), "app");
    }
    @Override public String humanize(JsonNode in) { return "Abrir programa: " + in.path("app").asText(); }

    @Override public String run(JsonNode in) {
        String app = in.path("app").asText("");
        return laptop.openApp(app)
                .map(n -> "Abierto: " + n)
                .orElse("No conozco ese programa: " + app);
    }
}
