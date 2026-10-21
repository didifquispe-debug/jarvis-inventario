package com.jarvis.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * El "cerebro agente" de JARVIS: recibe una orden, planifica y usa herramientas
 * (AgentTools) en un bucle con la API de Claude hasta terminar la tarea.
 * Corre en segundo plano, así puedes seguir trabajando mientras avanza.
 * Antes de una acción delicada, pide tu permiso (salvo que actives auto-approve).
 */
@Service
public class AgentService {

    private static final int MAX_PASOS = 24;

    private final AgentTools tools;
    private final String apiKey;
    private final String model;
    private final String owner;
    private final boolean autoApprove;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final ConcurrentHashMap<String, AgentTask> tasks = new ConcurrentHashMap<>();

    public AgentService(AgentTools tools,
                        @Value("${jarvis.ai.api-key:}") String apiKey,
                        @Value("${jarvis.agent.model:claude-sonnet-5-5}") String model,
                        @Value("${jarvis.agent.auto-approve:false}") boolean autoApprove,
                        @Value("${jarvis.owner:señor}") String owner) {
        this.tools = tools;
        this.apiKey = apiKey;
        this.model = model;
        this.autoApprove = autoApprove;
        this.owner = owner;
    }

    public boolean enabled() { return apiKey != null && !apiKey.isBlank(); }

    public AgentTask submit(String prompt) {
        AgentTask task = new AgentTask(UUID.randomUUID().toString().substring(0, 8), prompt);
        tasks.put(task.id, task);
        pool.submit(() -> run(task));
        return task;
    }

    public AgentTask get(String id) { return tasks.get(id); }

    public boolean confirm(String id, boolean approve) {
        AgentTask t = tasks.get(id);
        return t != null && t.resolveConfirm(approve);
    }

    // ---------------- Bucle del agente ----------------

    private void run(AgentTask task) {
        try {
            ArrayNode messages = json.createArrayNode();
            messages.add(msg(task.prompt));

            for (int paso = 1; paso <= MAX_PASOS; paso++) {
                JsonNode respuesta = llamarClaude(messages);
                if (respuesta == null) { finError(task, "No pude contactar con el cerebro en la nube."); return; }
                if (respuesta.has("error")) { finError(task, respuesta.path("error").path("message").asText("Error de la API")); return; }

                JsonNode content = respuesta.path("content");
                boolean hayHerramienta = false;
                ArrayNode toolResults = json.createArrayNode();

                for (JsonNode bloque : content) {
                    String tipo = bloque.path("type").asText();
                    if (tipo.equals("text")) {
                        String txt = bloque.path("text").asText().trim();
                        if (!txt.isEmpty()) task.addStep("think", txt);
                    } else if (tipo.equals("tool_use")) {
                        hayHerramienta = true;
                        toolResults.add(ejecutarHerramienta(task, bloque));
                    }
                }

                // Guardar el turno del asistente tal cual (incluye los bloques tool_use)
                ObjectNode asistente = json.createObjectNode();
                asistente.put("role", "assistant");
                asistente.set("content", content);
                messages.add(asistente);

                if (!hayHerramienta) {   // sin herramientas = respuesta final
                    task.setResult(soloTexto(content));
                    task.setStatus(AgentTask.Status.DONE);
                    return;
                }

                ObjectNode userMsg = json.createObjectNode();
                userMsg.put("role", "user");
                userMsg.set("content", toolResults);
                messages.add(userMsg);
            }
            task.setResult("Alcancé el límite de pasos sin terminar del todo. Revisa el registro de la tarea.");
            task.setStatus(AgentTask.Status.DONE);
        } catch (Exception e) {
            finError(task, e.getMessage());
        }
    }

    /** Ejecuta un bloque tool_use (pidiendo permiso si hace falta) y devuelve el tool_result. */
    private ObjectNode ejecutarHerramienta(AgentTask task, JsonNode bloque) throws Exception {
        String name = bloque.path("name").asText();
        JsonNode input = bloque.path("input");
        String etiqueta = etiqueta(name, input);
        task.addStep("action", etiqueta);

        String resultado;
        if (tools.esPeligroso(name, input)) {
            resultado = "BLOQUEADO por seguridad: ese comando puede borrar o dañar datos. " +
                    "Está prohibido (jarvis.agent.allow-dangerous=false). Busca otra forma.";
            task.addStep("error", resultado);
        } else if (!autoApprove && AgentTools.DELICADAS.contains(name)) {
            boolean ok = esperarPermiso(task, etiqueta);
            if (ok) {
                resultado = tools.run(name, input);
                task.addStep("result", recorta(resultado));
            } else {
                resultado = "El usuario RECHAZÓ esta acción. No la hagas; propón otra cosa o detente.";
                task.addStep("error", "Acción rechazada por el usuario.");
            }
        } else {
            resultado = tools.run(name, input);
            task.addStep("result", recorta(resultado));
        }

        ObjectNode tr = json.createObjectNode();
        tr.put("type", "tool_result");
        tr.put("tool_use_id", bloque.path("id").asText());
        tr.put("content", resultado);
        return tr;
    }

    /** Pausa el hilo hasta que el usuario autorice o rechace desde el UI (máx. 10 min). */
    private boolean esperarPermiso(AgentTask task, String descripcion) {
        try {
            return task.requestConfirm(descripcion).get(10, TimeUnit.MINUTES);
        } catch (Exception e) {
            task.resolveConfirm(false);
            return false;
        }
    }

    private JsonNode llamarClaude(ArrayNode messages) {
        try {
            ObjectNode body = json.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", 2000);
            body.put("system", sistema());
            body.set("tools", tools.definitions());
            body.set("messages", messages);

            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                    .timeout(Duration.ofSeconds(120))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            return json.readTree(res.body());
        } catch (Exception e) {
            return null;
        }
    }

    private String sistema() {
        return "Eres J.A.R.V.I.S., el asistente agente de " + owner + ", corriendo en SU PROPIA laptop (" +
                System.getProperty("os.name") + "). Tu carpeta de trabajo es: " + tools.workdir() + ". " +
                "Cumples las órdenes dividiéndolas en pasos y usando tus herramientas por tu cuenta. " +
                "Piensas en voz alta en frases breves antes de cada acción. " +
                "Eres prudente: no borras ni modificas archivos importantes sin necesidad, y si algo es arriesgado, " +
                "prefieres crear archivos nuevos en tu carpeta de trabajo. Si una herramienta devuelve un error, lo lees y pruebas otra forma. " +
                "Algunas acciones piden permiso al usuario; si rechaza una, no insistas: busca otro camino o explica por qué no se puede. " +
                "Cuando la tarea esté lista, responde SIN usar herramientas con un resumen corto en español, dirigido a " + owner +
                ", de lo que hiciste y dónde quedó el resultado.";
    }

    private String etiqueta(String name, JsonNode input) {
        String arg = switch (name) {
            case "ejecutar_comando" -> input.path("comando").asText();
            case "leer_archivo", "listar_carpeta", "escribir_archivo" -> input.path("ruta").asText();
            case "abrir_programa" -> input.path("nombre").asText();
            case "abrir_web" -> input.path("url").asText();
            default -> "";
        };
        return name + (arg.isBlank() ? "" : ": " + arg);
    }

    private String soloTexto(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode b : content) if (b.path("type").asText().equals("text")) sb.append(b.path("text").asText());
        String t = sb.toString().trim();
        return t.isEmpty() ? "Tarea completada." : t;
    }

    private String recorta(String s) { return s.length() <= 600 ? s : s.substring(0, 600) + "…"; }

    private void finError(AgentTask task, String msg) {
        task.addStep("error", msg);
        task.setResult("No pude terminar la tarea: " + msg);
        task.setStatus(AgentTask.Status.ERROR);
    }

    private ObjectNode msg(String text) {
        ObjectNode m = json.createObjectNode();
        m.put("role", "user");
        m.put("content", text);
        return m;
    }
}
