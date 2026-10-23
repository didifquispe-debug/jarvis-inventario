package com.jarvis.skills;

import com.jarvis.core.Diccionario;
import com.jarvis.inventory.Palabras;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Enseñarle palabras a JARVIS por voz. JarvisService la consulta ANTES de aplicar el diccionario,
 * así "aprende que chompa significa polera" no se convierte en "…polera significa polera".
 */
@Component
@Order(-20)
public class DiccionarioSkill implements Skill {

    private final Diccionario diccionario;

    public DiccionarioSkill(Diccionario diccionario) { this.diccionario = diccionario; }

    @Override public String name() { return "Diccionario"; }
    @Override public String example() {
        return "Aprende que chompa significa polera / Cuando diga zapas entiende zapatillas / Olvida la palabra chompa / Qué palabras aprendiste";
    }

    private static final Pattern APRENDE = Pattern.compile(
            "^(?:aprende|aprender) (?:que |la palabra )?(.+?) (?:significa|quiere decir|es lo mismo que|es igual a|es) (.+)$");
    private static final Pattern CUANDO_DIGA = Pattern.compile(
            "^cuando (?:te )?(?:diga|digo|escuches|escuche) (.+?) (?:entiende|entiendes|es|significa|quiere decir) (.+)$");
    private static final Pattern OLVIDA = Pattern.compile("^(?:olvida|olvidate de|borra del diccionario) (?:la palabra |el significado de )?(.+)$");
    private static final Pattern LISTA = Pattern.compile(
            "\\b(que palabras (?:has aprendido|aprendiste|sabes|conoces)|(?:muestra|lee|dime|ver) (?:el |mi )?diccionario|mi diccionario)\\b");

    @Override
    public Optional<String> handle(String cmdRaw, String original) {
        String cmd = Palabras.norm(cmdRaw.replaceAll("[\"'“”«»]", " "));
        Matcher m = APRENDE.matcher(cmd);
        if (!m.find()) { m = CUANDO_DIGA.matcher(cmd); if (!m.find()) m = null; }
        if (m != null) return Optional.of(diccionario.aprender(m.group(1), m.group(2)));

        m = OLVIDA.matcher(cmd);
        if (m.find()) return Optional.of(diccionario.olvidar(m.group(1)));

        if (LISTA.matcher(cmd).find()) {
            Map<String, String> p = diccionario.propias();
            if (p.isEmpty())
                return Optional.of("Aún no me enseñó palabras propias, señor. Diga por ejemplo: aprende que chompa significa polera.");
            return Optional.of("Palabras que aprendí:\n" + p.entrySet().stream()
                    .map(e -> "• " + e.getKey() + " = " + e.getValue()).collect(Collectors.joining("\n")));
        }
        return Optional.empty();
    }
}
