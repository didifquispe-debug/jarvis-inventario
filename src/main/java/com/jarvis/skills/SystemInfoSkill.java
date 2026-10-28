/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.skills;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.Optional;

@Component
@Order(6)
public class SystemInfoSkill implements Skill {

    @Override public String name() { return "Estado del sistema"; }
    @Override public String example() { return "Estado del sistema / Diagnóstico"; }

    @Override
    public Optional<String> handle(String cmd, String original) {
        if (!(cmd.contains("estado del sistema") || cmd.contains("diagnostico"))) return Optional.empty();
        var os = ManagementFactory.getOperatingSystemMXBean();
        File root = File.listRoots()[0];
        long libreGb = root.getUsableSpace() / (1024L * 1024 * 1024);
        long totalGb = root.getTotalSpace() / (1024L * 1024 * 1024);
        String carga = os.getSystemLoadAverage() >= 0 ? String.format("%.2f", os.getSystemLoadAverage()) : "no disponible";
        return Optional.of(String.format(
                "Diagnóstico completo. Sistema: %s %s. Procesadores: %d. Carga: %s. Disco: %d GB libres de %d GB.",
                os.getName(), os.getVersion(), os.getAvailableProcessors(), carga, libreGb, totalGb));
    }
}
