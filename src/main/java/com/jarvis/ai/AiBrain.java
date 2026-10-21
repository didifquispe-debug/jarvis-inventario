package com.jarvis.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.inventory.Palabras;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Cerebro de JARVIS con dos partes:
 * <ul>
 *   <li>Local y offline: {@link #interpretar(String)} reconoce la intención con DJL
 *       ({@link IntentClassifier}) y extrae las entidades ({@link EntityExtractor}).</li>
 *   <li>Opcional en la nube (API de Claude): {@link #ask(String)} responde lo que ninguna
 *       habilidad entiende y guarda las últimas frases como contexto.</li>
 * </ul>
 */
@Component
public class AiBrain {

    private static final int MAX_HISTORY = 10;

    private final String apiKey;
    private final String model;
    private final String owner;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final Deque<ObjectNode> history = new ArrayDeque<>();
    private final IntentClassifier classifier;

    public AiBrain(@Value("${jarvis.ai.api-key:}") String apiKey,
                   @Value("${jarvis.ai.model:claude-sonnet-5-5}") String model,
                   @Value("${jarvis.owner:señor}") String owner,
                   IntentClassifier classifier) {
        this.apiKey = apiKey;
        this.model = model;
        this.owner = owner;
        this.classifier = classifier;
    }

    public boolean enabled() { return apiKey != null && !apiKey.isBlank(); }

    /** ¿Está cargado el modelo local de intenciones? */
    public boolean intencionesLocales() { return classifier.listo(); }

    /**
     * Pipeline local: clasifica la intención con DJL y, si es de inventario, extrae cantidad,
     * producto y precio. Recibe el comando ya normalizado (minúsculas, sin tildes).
     */
    public IntentResult interpretar(String cmd) {
        String texto = Palabras.norm(cmd);
        IntentClassifier.Clasificacion c = classifier.clasificar(texto);
        if (c.intent() == Intent.DESCONOCIDO) return new IntentResult(Intent.DESCONOCIDO, c.confianza(), -1, "", null);
        EntityExtractor.Entidades e = EntityExtractor.extraer(texto);
        return new IntentResult(c.intent(), c.confianza(), e.cantidad(), e.producto(), e.precio());
    }

    public synchronized String ask(String userText) {
        try {
            addMessage("user", userText);

            ObjectNode body = json.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", 400);
            body.put("system", "Eres J.A.R.V.I.S., un asistente personal elegante, leal y con un toque de humor británico. "
                    + "Te diriges al usuario como '" + owner + "'. Respondes en español, breve y claro, "
                    + "porque tus respuestas se leen en voz alta. Nada de markdown ni listas largas.");
            ArrayNode msgs = body.putArray("messages");
            history.forEach(msgs::add);

            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                    .timeout(Duration.ofSeconds(60))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = json.readTree(res.body());
            if (res.statusCode() != 200) {
                history.pollLast();
                return "Mi conexión neuronal falló (" + res.statusCode() + "): " + root.path("error").path("message").asText();
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) sb.append(block.path("text").asText());
            }
            String answer = sb.toString().trim();
            addMessage("assistant", answer);
            return answer;
        } catch (Exception e) {
            history.pollLast();
            return "No pude contactar con mi cerebro en la nube: " + e.getMessage();
        }
    }

    private void addMessage(String role, String text) {
        ObjectNode m = json.createObjectNode();
        m.put("role", role);
        m.put("content", text);
        history.addLast(m);
        while (history.size() > MAX_HISTORY) history.pollFirst();
        // La API exige que la conversación empiece con "user"
        while (!history.isEmpty() && !"user".equals(history.peekFirst().path("role").asText())) history.pollFirst();
    }
}
