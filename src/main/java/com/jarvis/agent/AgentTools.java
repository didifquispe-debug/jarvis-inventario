package com.jarvis.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.system.LaptopActions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Las "manos" del agente: las herramientas que puede usar sobre tu laptop.
 * Aquí viven también los frenos de seguridad.
 */
@Component
public class  AgentTools {

    /** Herramientas que modifican el sistema: piden tu permiso salvo que actives auto-approve. */
    public static final Set<String> DELICADAS = Set.of("ejecutar_comando", "escribir_archivo");

    private final ObjectMapper json = new ObjectMapper();
    private final LaptopActions laptop;

    private final boolean allowDangerous;
    private final int maxOutput;
    private final Path workdir;

    // Comandos que pueden destruir datos: bloqueados salvo que actives allow-dangerous.
    private static final Pattern PELIGROSO = Pattern.compile(
            "(?i)\\b(format|mkfs|diskpart|fdisk|shutdown|reboot|rmdir\\s+/s|rd\\s+/s|" +
            "del\\s+/[sfq]|rm\\s+-\\w*[rf]|reg\\s+delete|cipher\\s+/w|sfc|bcdedit)\\b" +
            "|:\\s*\\)\\s*\\{.*\\|.*&\\s*\\}");  // fork bomb

    public AgentTools(LaptopActions laptop,
                      @Value("${jarvis.agent.allow-dangerous:false}") boolean allowDangerous,
                      @Value("${jarvis.agent.max-output:6000}") int maxOutput,
                      @Value("${jarvis.agent.workdir:}") String workdir) {
        this.laptop = laptop;
        this.allowDangerous = allowDangerous;
        this.maxOutput = maxOutput;
        String home = System.getProperty("user.home");
        this.workdir = Paths.get(workdir == null || workdir.isBlank() ? home : workdir);
        try { Files.createDirectories(this.workdir); } catch (Exception ignored) { }
    }

    public Path workdir() { return workdir; }

    /** Definición de las herramientas que se le envían a Claude. */
    public ArrayNode definitions() {
        ArrayNode tools = json.createArrayNode();
        tools.add(tool("ejecutar_comando",
                "Ejecuta un comando en la terminal de la laptop (CMD en Windows) y devuelve su salida. " +
                "Úsalo para listar o mover archivos, abrir programas con 'start', consultar el sistema, instalar cosas, etc.",
                "comando", "El comando completo a ejecutar."));
        tools.add(tool("leer_archivo", "Lee el contenido de un archivo de texto.",
                "ruta", "Ruta del archivo. Puede ser relativa a la carpeta de trabajo."));
        tools.add(toolWrite());
        tools.add(tool("listar_carpeta", "Lista los archivos y subcarpetas de una carpeta.",
                "ruta", "Ruta de la carpeta. Vacío o '.' = carpeta de trabajo."));
        tools.add(tool("abrir_programa", "Abre un programa conocido (calculadora, bloc de notas, explorador, etc.).",
                "nombre", "Nombre hablado del programa."));
        tools.add(tool("abrir_web", "Abre una dirección o búsqueda en el navegador de la laptop.",
                "url", "La URL completa (https://...)."));
        return tools;
    }

    private ObjectNode tool(String name, String desc, String prop, String propDesc) {
        ObjectNode t = json.createObjectNode();
        t.put("name", name);
        t.put("description", desc);
        ObjectNode schema = t.putObject("input_schema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject(prop).put("type", "string").put("description", propDesc);
        schema.putArray("required").add(prop);
        return t;
    }

    private ObjectNode toolWrite() {
        ObjectNode t = json.createObjectNode();
        t.put("name", "escribir_archivo");
        t.put("description", "Crea o sobrescribe un archivo de texto con el contenido dado.");
        ObjectNode schema = t.putObject("input_schema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("ruta").put("type", "string").put("description", "Ruta del archivo a escribir.");
        props.putObject("contenido").put("type", "string").put("description", "Contenido completo del archivo.");
        ArrayNode req = schema.putArray("required"); req.add("ruta"); req.add("contenido");
        return t;
    }

    /** ¿Este comando está prohibido por ser destructivo? */
    public boolean esPeligroso(String name, JsonNode input) {
        if (allowDangerous) return false;
        if (!name.equals("ejecutar_comando")) return false;
        return PELIGROSO.matcher(input.path("comando").asText("")).find();
    }

    /** Ejecuta una herramienta y devuelve el texto de resultado (lo que ve Claude). */
    public String run(String name, JsonNode input) {
        try {
            return switch (name) {
                case "ejecutar_comando" -> ejecutar(input.path("comando").asText(""));
                case "leer_archivo" -> leer(input.path("ruta").asText(""));
                case "escribir_archivo" -> escribir(input.path("ruta").asText(""), input.path("contenido").asText(""));
                case "listar_carpeta" -> listar(input.path("ruta").asText(""));
                case "abrir_programa" -> laptop.openApp(input.path("nombre").asText("").toLowerCase())
                        .map(n -> "Abrí " + n + ".").orElse("No conozco ese programa.");
                case "abrir_web" -> laptop.openUrl(input.path("url").asText("")) ? "Abrí la página." : "No pude abrir la página.";
                default -> "Herramienta desconocida: " + name;
            };
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private Path resolve(String ruta) {
        Path p = Paths.get(ruta);
        return p.isAbsolute() ? p : workdir.resolve(ruta);
    }

    private String ejecutar(String comando) throws Exception {
        if (comando.isBlank()) return "ERROR: comando vacío.";
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder pb = win
                ? new ProcessBuilder("cmd.exe", "/c", comando)
                : new ProcessBuilder("sh", "-c", comando);
        pb.directory(workdir.toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes(),
                win ? java.nio.charset.Charset.forName("Cp850") : StandardCharsets.UTF_8);
        boolean fin = proc.waitFor(90, TimeUnit.SECONDS);
        if (!fin) { proc.destroyForcibly(); return "ERROR: el comando tardó demasiado (más de 90 s) y se canceló.\n" + clip(out); }
        return "Código de salida: " + proc.exitValue() + "\n" + clip(out.isBlank() ? "(sin salida)" : out);
    }

    private String leer(String ruta) throws Exception {
        Path p = resolve(ruta);
        if (!Files.exists(p)) return "ERROR: no existe " + p;
        if (Files.size(p) > 2_000_000) return "ERROR: el archivo es demasiado grande para leerlo completo.";
        return clip(Files.readString(p, StandardCharsets.UTF_8));
    }

    private String escribir(String ruta, String contenido) throws Exception {
        Path p = resolve(ruta);
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, contenido, StandardCharsets.UTF_8);
        return "Escribí " + contenido.length() + " caracteres en " + p;
    }

    private String listar(String ruta) {
        File dir = (ruta == null || ruta.isBlank() || ruta.equals(".")) ? workdir.toFile() : resolve(ruta).toFile();
        File[] fs = dir.listFiles();
        if (fs == null) return "ERROR: no es una carpeta válida: " + dir;
        StringBuilder sb = new StringBuilder(dir + ":\n");
        for (File f : fs) sb.append(f.isDirectory() ? "[carpeta] " : "          ").append(f.getName()).append("\n");
        return clip(sb.toString());
    }

    private String clip(String s) {
        return s.length() <= maxOutput ? s : s.substring(0, maxOutput) + "\n…(salida recortada)";
    }
}
