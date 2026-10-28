/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.core;

import com.jarvis.ai.AiBrain;
import com.jarvis.ai.Intent;
import com.jarvis.ai.IntentResult;
import com.jarvis.ia.AsistenteIA;
import com.jarvis.inventory.InventoryService;
import com.jarvis.skills.DiccionarioSkill;
import com.jarvis.skills.InventorySkill;
import com.jarvis.skills.Skill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class JarvisService {

    private static final Logger log = LoggerFactory.getLogger(JarvisService.class);

    public static final String NO_ENTENDI = "No entendí la orden. Diga 'ayuda' para ver lo que puedo hacer.";

    private final List<Skill> skills;
    private final AiBrain brain;
    private final InventorySkill inventario;
    private final Diccionario diccionario;
    private final DiccionarioSkill ensenar;
    private final InventoryService inventarioDatos;
    private final AsistenteIA asistente;
    private final AcercaDeJarvis acercaDe;
    private final OrdenesPorConfirmar porConfirmar;
    private final boolean confirmarVoz;

    public JarvisService(List<Skill> skills, AiBrain brain, InventorySkill inventario,
                         Diccionario diccionario, DiccionarioSkill ensenar, InventoryService inventarioDatos,
                         AsistenteIA asistente, AcercaDeJarvis acercaDe, OrdenesPorConfirmar porConfirmar,
                         @Value("${jarvis.voz.confirmar:true}") boolean confirmarVoz) {
        this.skills = skills;
        this.brain = brain;
        this.inventario = inventario;
        this.diccionario = diccionario;
        this.ensenar = ensenar;
        this.inventarioDatos = inventarioDatos;
        this.asistente = asistente;
        this.acercaDe = acercaDe;
        this.porConfirmar = porConfirmar;
        this.confirmarVoz = confirmarVoz;
    }

    public String process(String original) {
        return process(original, false);
    }

    public String process(String original, boolean porVoz) {
        if (original == null || original.isBlank()) return "No escuché nada, Didì.";
        Optional<String> confirmada = porConfirmar.resolver(original, this::procesarConReglas);
        if (confirmada.isPresent()) return confirmada.get();
        Optional<String> sobreJarvis = inventario.esperandoConfirmacion() ? Optional.empty()
                : acercaDe.responder(normalize(original), asistente.disponible());
        if (sobreJarvis.isPresent()) return sobreJarvis.get();
        if (porVoz && confirmarVoz && !inventario.esperandoConfirmacion() && !inventario.ventaEnCurso()
                && inventario.delicadaPorVoz(diccionario.aplicar(normalize(original))))
            return porConfirmar.proponer(original.trim().replaceFirst("(?i)^(oye |hey )?jarvis[,.]?\\s*", ""));
        String r = procesarConReglas(original);
        if (NO_ENTENDI.equals(r) && asistente.disponible() && !inventario.esperandoConfirmacion() && !inventario.ventaEnCurso())
            return asistente.responder(original.trim(), this::procesarConReglas).orElse(r);
        return r;
    }

    public String procesarConReglas(String original) {
        if (original == null || original.isBlank()) return "No escuché nada, Didì.";
        String cmd = normalize(original);

        Optional<String> aprendido = ensenar.handle(cmd, original.trim());
        if (aprendido.isPresent()) return aprendido.get();
        cmd = diccionario.aplicar(cmd);
        cmd = inventarioDatos.codigosANombres(cmd);

        if (cmd.contains("ayuda") || cmd.contains("que puedes hacer"))
            return acercaDe.responder("que puedes hacer", asistente.disponible()).orElseGet(this::help);

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

        for (Skill s : skills) {
            if (s == ensenar) continue;
            Optional<String> r = s.handle(cmd, original.trim());
            if (r.isPresent()) return r.get();
        }

        return NO_ENTENDI;
    }

    public String help() {
        return "Puedo hacer esto:\n" + skills.stream()
                .map(s -> "• " + s.name() + ": " + s.example())
                .collect(Collectors.joining("\n"))
                + (brain.intencionesLocales() ? "\n• Entiendo órdenes de inventario dichas con sus propias palabras (modelo local)." : "");
    }

    static String normalize(String s) {
        String n = Normalizer.normalize(s.toLowerCase().trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[¿¡!]|(?<!\\d)\\.|\\.(?!\\d)", "")
                .replaceFirst("^(oye |hey )?jarvis,?\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        return n.isEmpty() ? s.toLowerCase().trim() : n;
    }
}
