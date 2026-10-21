package com.jarvis.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/** Una tarea que el agente ejecuta en segundo plano. El UI la consulta por su id. */
public class AgentTask {

    public enum Status { RUNNING, WAITING, DONE, ERROR }

    public final String id;
    public final String prompt;

    private volatile Status status = Status.RUNNING;
    private volatile String result = "";
    private volatile Map<String, String> pending;          // acción que espera tu permiso
    private volatile CompletableFuture<Boolean> gate;      // se completa cuando autorizas/rechazas

    /** Cada paso: kind = think | action | result | error ; text = el detalle */
    public final List<Map<String, String>> steps = new CopyOnWriteArrayList<>();

    public AgentTask(String id, String prompt) {
        this.id = id;
        this.prompt = prompt;
    }

    public void addStep(String kind, String text) {
        steps.add(Map.of("kind", kind, "text", text == null ? "" : text));
    }

    /** Llamado por el hilo del agente: publica la acción y espera la respuesta del UI. */
    public CompletableFuture<Boolean> requestConfirm(String description) {
        CompletableFuture<Boolean> f = new CompletableFuture<>();
        this.gate = f;
        this.pending = Map.of("description", description);
        this.status = Status.WAITING;
        return f;
    }

    /** Llamado por el endpoint /confirm. */
    public synchronized boolean resolveConfirm(boolean approve) {
        if (gate == null || status != Status.WAITING) return false;
        pending = null;
        status = Status.RUNNING;
        CompletableFuture<Boolean> g = gate;
        gate = null;
        g.complete(approve);
        return true;
    }

    public Status status() { return status; }
    public void setStatus(Status s) { this.status = s; }
    public String result() { return result; }
    public void setResult(String r) { this.result = r; }
    public Map<String, String> pending() { return pending; }
}
