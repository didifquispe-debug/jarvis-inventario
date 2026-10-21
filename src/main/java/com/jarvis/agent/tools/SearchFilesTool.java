package com.jarvis.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.agent.Schemas;
import com.jarvis.agent.Tool;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

@Component
public class SearchFilesTool implements Tool {

    @Override public String name() { return "buscar_archivos"; }
    @Override public String description() {
        return "Busca archivos cuyo nombre contenga un texto, dentro de una carpeta (y subcarpetas). Útil para encontrar documentos.";
    }
    @Override public ObjectNode inputSchema() {
        ObjectNode p = Schemas.props();
        p.set("texto", Schemas.string("Parte del nombre a buscar"));
        p.set("carpeta", Schemas.string("Carpeta donde buscar (opcional, por defecto la del usuario)"));
        return Schemas.object(p, "texto");
    }
    @Override public String humanize(JsonNode in) { return "Buscar archivos: " + in.path("texto").asText(); }

    @Override public String run(JsonNode in) throws Exception {
        String texto = in.path("texto").asText("").toLowerCase();
        String carpeta = in.path("carpeta").asText("");
        Path root = Path.of(carpeta.isBlank() ? System.getProperty("user.home") : carpeta);
        if (!Files.isDirectory(root)) return "No es una carpeta: " + root;

        List<String> found = new ArrayList<>();
        try (var stream = Files.walk(root, 6)) {
            stream.filter(p -> p.getFileName().toString().toLowerCase().contains(texto))
                  .limit(50)
                  .forEach(p -> found.add(p.toString()));
        } catch (Exception e) {
            return "Error buscando: " + e.getMessage();
        }
        return found.isEmpty() ? "No encontré archivos con \"" + texto + "\"." : String.join("\n", found);
    }
}
