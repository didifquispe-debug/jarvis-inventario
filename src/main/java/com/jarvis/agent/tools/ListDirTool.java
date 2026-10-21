package com.jarvis.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.agent.Schemas;
import com.jarvis.agent.Tool;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Arrays;
import java.util.stream.Collectors;

@Component
public class ListDirTool implements Tool {

    @Override public String name() { return "listar_carpeta"; }
    @Override public String description() {
        return "Lista archivos y carpetas dentro de una ruta. Si no das ruta, usa la carpeta del usuario.";
    }
    @Override public ObjectNode inputSchema() {
        return Schemas.object(Schemas.props().set("ruta", Schemas.string("Ruta de la carpeta (opcional)")));
    }
    @Override public String humanize(JsonNode in) { return "Listar carpeta: " + ruta(in); }

    @Override public String run(JsonNode in) {
        File dir = new File(ruta(in));
        if (!dir.isDirectory()) return "No es una carpeta: " + dir;
        File[] fs = dir.listFiles();
        if (fs == null) return "No pude leer la carpeta.";
        return Arrays.stream(fs).limit(200)
                .map(f -> (f.isDirectory() ? "[carpeta] " : "          ") + f.getName())
                .collect(Collectors.joining("\n"));
    }

    private String ruta(JsonNode in) {
        String r = in.path("ruta").asText("");
        return r.isBlank() ? System.getProperty("user.home") : r;
    }
}
