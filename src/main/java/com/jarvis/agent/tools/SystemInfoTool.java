package com.jarvis.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jarvis.agent.Schemas;
import com.jarvis.agent.Tool;
import com.jarvis.system.SystemMonitor;
import org.springframework.stereotype.Component;

@Component
public class SystemInfoTool implements Tool {

    private final SystemMonitor monitor;

    public SystemInfoTool(SystemMonitor monitor) { this.monitor = monitor; }

    @Override public String name() { return "estado_sistema"; }
    @Override public String description() {
        return "Devuelve el estado de la laptop: CPU, RAM, disco, sistema operativo, IP en la red.";
    }
    @Override public ObjectNode inputSchema() { return Schemas.object(Schemas.props()); }
    @Override public String humanize(JsonNode in) { return "Consultar estado del sistema"; }

    @Override public String run(JsonNode in) { return monitor.snapshot().toString(); }
}
