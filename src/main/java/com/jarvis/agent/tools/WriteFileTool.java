package com.jarvis.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.agent.Schemas;
import com.jarvis.agent.Tool;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** RIESGOSA: crea o sobrescribe un archivo. JARVIS pedirá confirmación. */
@Component
public class WriteFileTool implements Tool {

    @Override public String name() { return "escribir_archivo"; }
    @Override public String description() {
        return "Crea o sobrescribe un archivo de texto con el contenido dado. Da la ruta completa.";
    }
    @Override public boolean risky() { return true; }
    @Override public ObjectNode inputSchema() {
        ObjectNode p = Schemas.props();
        p.set("ruta", Schemas.string("Ruta completa del archivo a escribir"));
        p.set("contenido", Schemas.string("Contenido de texto a guardar"));
        return Schemas.object(p, "ruta", "contenido");
    }
    @Override public String humanize(JsonNode in) {
        return "Escribir archivo: " + in.path("ruta").asText();
    }

    @Override public String run(JsonNode in) throws Exception {
        Path p = Path.of(in.path("ruta").asText(""));
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, in.path("contenido").asText(""), StandardCharsets.UTF_8);
        return "Archivo guardado: " + p;
    }
}
