package com.jarvis.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.agent.Schemas;
import com.jarvis.agent.Tool;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class ReadFileTool implements Tool {

    private static final int MAX = 12_000;   // caracteres

    @Override public String name() { return "leer_archivo"; }
    @Override public String description() {
        return "Lee el contenido de un archivo de texto (txt, java, md, csv, código...). Da la ruta completa.";
    }
    @Override public ObjectNode inputSchema() {
        return Schemas.object(Schemas.props().set("ruta", Schemas.string("Ruta completa del archivo")), "ruta");
    }
    @Override public String humanize(JsonNode in) { return "Leer archivo: " + in.path("ruta").asText(); }

    @Override public String run(JsonNode in) throws Exception {
        Path p = Path.of(in.path("ruta").asText(""));
        if (!Files.isRegularFile(p)) return "No existe el archivo: " + p;
        if (Files.size(p) > 2_000_000) return "El archivo es muy grande para leerlo aquí.";
        String text = Files.readString(p, StandardCharsets.UTF_8);
        return text.length() > MAX ? text.substring(0, MAX) + "\n…(recortado)" : text;
    }
}
