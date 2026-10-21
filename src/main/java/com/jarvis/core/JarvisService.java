package com.jarvis.core;

import com.jarvis.ai.AiBrain;
import com.jarvis.ai.Intent;
import com.jarvis.ai.IntentResult;
import com.jarvis.skills.InventorySkill;
import com.jarvis.skills.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** El "núcleo": recibe un comando de cualquier dispositivo y decide quién lo responde. */
@Service
public class JarvisService {

    private static final Logger log = LoggerFactory.getLogger(JarvisService.class);

    private final List<Skill> skills;   // Spring inyecta todas las @Component que implementen Skill, en orden @Order
    private final AiBrain brain;
    private final InventorySkill inventario;

    public JarvisService(List<Skill> skills, AiBrain brain, InventorySkill inventario) {
        this.skills = skills;
        this.brain = brain;
        this.inventario = inventario;
    }

    public String process(String original) {
        if (original == null || original.isBlank()) return "No escuché nada, señor.";
        String cmd = normalize(original);

        if (cmd.contains("ayuda") || cmd.contains("que puedes hacer")) return help();

        // 1) Modelo local de intenciones (DJL), solo para frases sin palabras clave claras
        //    y nunca si hay un "sí/no" pendiente o una venta con varios productos abierta.
        if (brain.intencionesLocales() && !inventario.esperandoConfirmacion() && !inventario.ventaEnCurso()
                && !inventario.ordenExplicita(cmd)) {
            IntentResult r = brain.interpretar(cmd);
            if (r.intent() != Intent.DESCONOCIDO) {
                Optional<String> res = inventario.ejecutar(r);
                if (res.isPresent()) {
                    log.debug("Intención {} ({}) -> {}", r.intent(), String.format("%.2f", r.confianza()), r);
                    return res.get();
                }
            }
        }

        // 2) Respaldo: procesador de reglas clásico de cada habilidad
        for (Skill s : skills) {
            Optional<String> r = s.handle(cmd, original.trim());
            if (r.isPresent()) return r.get();
        }

        if (brain.enabled()) return brain.ask(original.trim());
        return "Aún no sé hacer eso. Diga \"ayuda\" para ver mis comandos, "
                + "o configure ANTHROPIC_API_KEY para que pueda conversar de cualquier tema.";
    }

    public String help() {
        return "Puedo hacer esto:\n" + skills.stream()
                .map(s -> "• " + s.name() + ": " + s.example())
                .collect(Collectors.joining("\n"))
                + (brain.intencionesLocales() ? "\n• Entiendo órdenes de inventario dichas con sus propias palabras (modelo local)." : "")
                + (brain.enabled() ? "\n• Y conversar de cualquier tema con IA." : "");
    }

    /** minúsculas, sin tildes, sin "jarvis" al inicio, sin signos ¿¡ */
    static String normalize(String s) {
        String n = Normalizer.normalize(s.toLowerCase().trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[¿¡!.]", "")
                .replaceFirst("^(oye |hey )?jarvis,?\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        return n.isEmpty() ? s.toLowerCase().trim() : n;
    }
}
