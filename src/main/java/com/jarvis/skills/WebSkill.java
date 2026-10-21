package com.jarvis.skills;

import com.jarvis.system.LaptopActions;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

/** Abre sitios web y hace búsquedas EN LA LAPTOP (aunque la orden venga del celular). */
@Component
@Order(2)
public class WebSkill implements Skill {

    private static final Map<String, String> SITIOS = Map.of(
            "youtube", "https://www.youtube.com",
            "google", "https://www.google.com",
            "gmail", "https://mail.google.com",
            "whatsapp", "https://web.whatsapp.com",
            "github", "https://github.com",
            "spotify", "https://open.spotify.com"
    );

    private final LaptopActions laptop;

    public WebSkill(LaptopActions laptop) { this.laptop = laptop; }

    @Override public String name() { return "Web"; }
    @Override public String example() { return "Abre YouTube / Busca recetas de quinua / Pon música de Queen en YouTube"; }

    @Override
    public Optional<String> handle(String cmd, String original) {
        if (cmd.startsWith("busca ") || cmd.startsWith("buscar ")) {
            String q = cmd.replaceFirst("^buscar? ", "").replaceFirst("^en google ", "");
            return Optional.of(laptop.openUrl("https://www.google.com/search?q=" + enc(q))
                    ? "Buscando \"" + q + "\" en Google."
                    : "No pude abrir el navegador en la laptop.");
        }
        if ((cmd.startsWith("pon ") || cmd.startsWith("reproduce ")) && cmd.contains("youtube")) {
            String q = cmd.replaceFirst("^(pon|reproduce) ", "").replace("en youtube", "").trim();
            return Optional.of(laptop.openUrl("https://www.youtube.com/results?search_query=" + enc(q))
                    ? "Buscando " + q + " en YouTube."
                    : "No pude abrir el navegador en la laptop.");
        }
        if (cmd.startsWith("abre ")) {
            for (var e : SITIOS.entrySet()) {
                if (cmd.contains(e.getKey())) {
                    return Optional.of(laptop.openUrl(e.getValue())
                            ? "Abriendo " + e.getKey() + "."
                            : "No pude abrir el navegador en la laptop.");
                }
            }
        }
        return Optional.empty();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
