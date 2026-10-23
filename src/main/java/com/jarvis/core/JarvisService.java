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
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * El "núcleo": recibe un comando de cualquier dispositivo y decide quién lo responde.
 * Con clave e internet lo atiende el asistente con IA ({@link AsistenteIA}), que entiende frases libres y usa
 * las mismas órdenes por debajo; si no, o si la IA falla, las reglas de siempre.
 */
@Service
public class JarvisService {

    private static final Logger log = LoggerFactory.getLogger(JarvisService.class);

    public static final String NO_ENTENDI = "No entendí la orden. Diga 'ayuda' para ver lo que puedo hacer.";

    private final List<Skill> skills;   // Spring inyecta todas las @Component que implementen Skill, en orden @Order
    private final AiBrain brain;
    private final InventorySkill inventario;
    private final Diccionario diccionario;
    private final DiccionarioSkill ensenar;
    private final InventoryService inventarioDatos;
    private final AsistenteIA asistente;
    private final AcercaDeJarvis acercaDe;

    public JarvisService(List<Skill> skills, AiBrain brain, InventorySkill inventario,
                         Diccionario diccionario, DiccionarioSkill ensenar, InventoryService inventarioDatos,
                         AsistenteIA asistente, AcercaDeJarvis acercaDe) {
        this.skills = skills;
        this.brain = brain;
        this.inventario = inventario;
        this.diccionario = diccionario;
        this.ensenar = ensenar;
        this.inventarioDatos = inventarioDatos;
        this.asistente = asistente;
        this.acercaDe = acercaDe;
    }

    public String process(String original) {
        if (original == null || original.isBlank()) return "No escuché nada, señor.";
        // Preguntas sobre JARVIS ("qué eres", "qué puedes hacer", "cómo registro una compra"): respuesta fija, al instante
        Optional<String> sobreJarvis = inventario.esperandoConfirmacion() ? Optional.empty()
                : acercaDe.responder(normalize(original), asistente.disponible());
        if (sobreJarvis.isPresent()) return sobreJarvis.get();
        // El "sí/no" de una pregunta de las reglas y los productos de una venta con carrito van directo a las
        // reglas: son respuestas cortas de caja y así no esperan a la IA.
        if (asistente.disponible() && !inventario.esperandoConfirmacion() && !inventario.ventaEnCurso()) {
            Optional<String> r = asistente.responder(original.trim(), this::procesarConReglas);
            if (r.isPresent()) return r.get();
        }
        return procesarConReglas(original);
    }

    /** Las reglas de siempre (sin IA). También las usa la IA, con la herramienta ejecutar_orden. */
    public String procesarConReglas(String original) {
        if (original == null || original.isBlank()) return "No escuché nada, señor.";
        String cmd = normalize(original);

        // 0) "aprende que chompa significa polera" va antes del diccionario (si no, se traduciría a sí misma);
        //    luego el diccionario cambia las palabras del usuario por las que JARVIS entiende
        Optional<String> aprendido = ensenar.handle(cmd, original.trim());
        if (aprendido.isPresent()) return aprendido.get();
        cmd = diccionario.aplicar(cmd);
        // "vendí 2 7750182000123": el código (escrito o escaneado) se cambia por el nombre, si no se leería como cantidad
        cmd = inventarioDatos.codigosANombres(cmd);

        if (cmd.contains("ayuda") || cmd.contains("que puedes hacer"))
            return acercaDe.responder("que puedes hacer", asistente.disponible()).orElseGet(this::help);

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
            if (s == ensenar) continue;   // ya se consultó en el paso 0
            Optional<String> r = s.handle(cmd, original.trim());
            if (r.isPresent()) return r.get();
        }

        // 3) Nadie la entendió: todo es local, no hay respaldo en la nube
        return NO_ENTENDI;
    }

    public String help() {
        return "Puedo hacer esto:\n" + skills.stream()
                .map(s -> "• " + s.name() + ": " + s.example())
                .collect(Collectors.joining("\n"))
                + (brain.intencionesLocales() ? "\n• Entiendo órdenes de inventario dichas con sus propias palabras (modelo local)." : "");
    }

    /** minúsculas, sin tildes, sin "jarvis" al inicio, sin signos ¿¡ (el punto decimal "2.5" se conserva) */
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
