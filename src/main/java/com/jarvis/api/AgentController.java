package com.jarvis.api;

import com.jarvis.agent.AgentService;
import com.jarvis.agent.AgentTask;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Endpoints del modo agente.
 *   POST /api/agent              { "text": "..." }     -> { "id": "ab12cd34" }
 *   GET  /api/agent/{id}                               -> { status, steps[], result, pending }
 *   POST /api/agent/{id}/confirm { "approve": true }   -> autoriza/rechaza una acción
 * El UI consulta el id cada segundo para mostrar el avance.
 */
@RestController
@RequestMapping("/api/agent")
@CrossOrigin
public class AgentController {

    private final AgentService agent;
    private final String token;

    public AgentController(AgentService agent, @Value("${jarvis.token}") String token) {
        this.agent = agent;
        this.token = token;
    }

    public record TaskRequest(String text) {}
    public record ConfirmRequest(boolean approve) {}

    @PostMapping
    public ResponseEntity<Map<String, String>> start(@RequestHeader(value = "X-Jarvis-Token", required = false) String t,
                                                     @RequestBody TaskRequest req) {
        if (!token.equals(t)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (!agent.enabled())
            return ResponseEntity.ok(Map.of("error",
                    "El modo agente necesita la variable ANTHROPIC_API_KEY. Mira el README, sección 7."));
        if (req.text() == null || req.text().isBlank())
            return ResponseEntity.ok(Map.of("error", "No recibí ninguna tarea."));
        AgentTask task = agent.submit(req.text().trim());
        return ResponseEntity.ok(Map.of("id", task.id));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> poll(@RequestHeader(value = "X-Jarvis-Token", required = false) String t,
                                                    @PathVariable String id) {
        if (!token.equals(t)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        AgentTask task = agent.get(id);
        if (task == null) return ResponseEntity.notFound().build();
        Map<String, Object> out = new HashMap<>();
        out.put("status", task.status().name());      // RUNNING | WAITING | DONE | ERROR
        out.put("steps", task.steps);
        out.put("result", task.result());
        out.put("pending", task.pending());           // {description} o null
        return ResponseEntity.ok(out);
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<Map<String, Object>> confirm(@RequestHeader(value = "X-Jarvis-Token", required = false) String t,
                                                       @PathVariable String id, @RequestBody ConfirmRequest req) {
        if (!token.equals(t)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        boolean ok = agent.confirm(id, req.approve());
        return ResponseEntity.ok(Map.of("ok", ok));
    }
}
