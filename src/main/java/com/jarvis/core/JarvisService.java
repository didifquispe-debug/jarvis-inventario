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

/**
 * El "núcleo": recibe un comando de cualquier dispositivo y decide quién lo responde.
 * Primero las reglas de siempre (con el diccionario y el modelo local de intenciones). Solo si no entendieron la
 * frase, y si el modelo de lenguaje local (Ollama) está a mano, la atiende el asistente con IA ({@link AsistenteIA}),
 * que responde con palabras o traduce la frase a una orden que de nuevo ejecutan las reglas.
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

    /**
     * @param porVoz la frase llegó por el micrófono: las ventas y cantidades se repiten y esperan un "sí"
     *               (jarvis.voz.confirmar), porque el reconocimiento puede confundir números
     */
    public String process(String original, boolean porVoz) {
        if (original == null || original.isBlank()) return "No escuché nada, Didì.";
        // El "sí/no" a una orden por confirmar ("Entendí: vendí 2 poleras. ¿Lo hago?"), de la IA o de la voz
        Optional<String> confirmada = porConfirmar.resolver(original, this::procesarConReglas);
        if (confirmada.isPresent()) return confirmada.get();
        // Preguntas sobre JARVIS ("qué eres", "qué puedes hacer", "cómo registro una compra"): respuesta fija, al instante
        Optional<String> sobreJarvis = inventario.esperandoConfirmacion() ? Optional.empty()
                : acercaDe.responder(normalize(original), asistente.disponible());
        if (sobreJarvis.isPresent()) return sobreJarvis.get();
        // Con el carrito abierto los productos dictados se ven en pantalla y se revisan al cobrar: no se preguntan uno a uno
        if (porVoz && confirmarVoz && !inventario.esperandoConfirmacion() && !inventario.ventaEnCurso()
                && inventario.delicadaPorVoz(diccionario.aplicar(normalize(original))))
            return porConfirmar.proponer(original.trim().replaceFirst("(?i)^(oye |hey )?jarvis[,.]?\\s*", ""));
        String r = procesarConReglas(original);
        // Las reglas no la entendieron: la IA local lo intenta (nunca con un "sí/no" pendiente ni con el carrito abierto)
        if (NO_ENTENDI.equals(r) && asistente.disponible() && !inventario.esperandoConfirmacion() && !inventario.ventaEnCurso())
            return asistente.responder(original.trim(), this::procesarConReglas).orElse(r);
        return r;
    }

    /** Las reglas de siempre (sin IA). También ejecutan las órdenes que traduce la IA. */
    public String procesarConReglas(String original) {
        if (original == null || original.isBlank()) return "No escuché nada, Didì.";
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

        // 3) Nadie la entendió (process() puede pasársela a la IA local)
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
