package com.jarvis.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.agent.Schemas;
import com.jarvis.agent.Tool;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * RIESGOSA: ejecuta un comando del sistema (CMD en Windows, shell en Mac/Linux).
 * Es la herramienta más poderosa y la más peligrosa, por eso SIEMPRE pide confirmación.
 */
@Component
public class RunCommandTool implements Tool {

    private static final String OS = System.getProperty("os.name").toLowerCase();

    @Override public String name() { return "ejecutar_comando"; }
    @Override public String description() {
        return "Ejecuta un comando del sistema operativo y devuelve su salida. "
             + "Úsalo solo si ninguna otra herramienta sirve. En Windows es CMD.";
    }
    @Override public boolean risky() { return true; }
    @Override public ObjectNode inputSchema() {
        return Schemas.object(Schemas.props().set("comando", Schemas.string("Comando a ejecutar")), "comando");
    }
    @Override public String humanize(JsonNode in) {
        return "Ejecutar comando: " + in.path("comando").asText();
    }

    @Override public String run(JsonNode in) throws Exception {
        String comando = in.path("comando").asText("");
        if (comando.isBlank()) return "Comando vacío.";

        ProcessBuilder pb = OS.contains("win")
                ? new ProcessBuilder("cmd", "/c", comando)
                : new ProcessBuilder("sh", "-c", comando);
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line; int lines = 0;
            while ((line = r.readLine()) != null && lines++ < 200) out.append(line).append('\n');
        }
        boolean ok = proc.waitFor(60, TimeUnit.SECONDS);
        if (!ok) { proc.destroyForcibly(); return "El comando tardó demasiado y se detuvo.\n" + out; }
        return "Código de salida: " + proc.exitValue() + "\n" + (out.length() == 0 ? "(sin salida)" : out);
    }
}
