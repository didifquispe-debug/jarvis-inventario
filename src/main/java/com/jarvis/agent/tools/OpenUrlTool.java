package com.jarvis.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.agent.Schemas;
import com.jarvis.agent.Tool;
import com.jarvis.system.LaptopActions;
import org.springframework.stereotype.Component;

@Component
public class OpenUrlTool implements Tool {

    private final LaptopActions laptop;

    public OpenUrlTool(LaptopActions laptop) { this.laptop = laptop; }

    @Override public String name() { return "abrir_web"; }
    @Override public String description() {
        return "Abre una página web o una búsqueda en el navegador de la laptop. Pasa una URL completa (https://...).";
    }
    @Override public ObjectNode inputSchema() {
        return Schemas.object(Schemas.props().set("url", Schemas.string("URL completa a abrir")), "url");
    }
    @Override public String humanize(JsonNode in) { return "Abrir web: " + in.path("url").asText(); }

    @Override public String run(JsonNode in) {
        String url = in.path("url").asText("");
        if (!url.startsWith("http")) url = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8);
        return laptop.openUrl(url) ? "Abierto: " + url : "No pude abrir el navegador.";
    }
}
