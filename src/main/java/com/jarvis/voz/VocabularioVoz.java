package com.jarvis.voz;

import com.jarvis.core.Diccionario;
import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.Producto;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Las palabras que JARVIS espera oír: las órdenes y números ({@code voz-vocabulario.txt}) más los nombres de
 * los productos (singular y plural), categorías, unidades, clientes, proveedores y las palabras aprendidas.
 * Con ellas la voz sin Internet acierta mucho más ("vendí tres focos" en vez de "veintitrés focos").
 * Se recalcula como mucho cada 30 segundos.
 */
@Component
public class VocabularioVoz {

    private static final Pattern PALABRA = Pattern.compile("[a-záéíóúüñ]+");
    private static final long VIGENCIA_MS = 30_000;

    private final InventoryService inv;
    private final Diccionario diccionario;
    private final Set<String> base;
    private volatile Set<String> cache = Set.of();
    private volatile long calculado;

    public VocabularioVoz(InventoryService inv, Diccionario diccionario) {
        this.inv = inv;
        this.diccionario = diccionario;
        this.base = leerBase();
    }

    private static Set<String> leerBase() {
        Set<String> s = new TreeSet<>();
        try (InputStream in = new ClassPathResource("voz-vocabulario.txt").getInputStream()) {
            for (String linea : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!linea.startsWith("#")) agregar(s, linea);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Collections.unmodifiableSet(s);
    }

    /** Las palabras de hoy (ordenadas, en minúsculas y con tildes). */
    public Set<String> palabras() {
        long ahora = System.currentTimeMillis();
        if (ahora - calculado < VIGENCIA_MS && !cache.isEmpty()) return cache;
        Set<String> s = new TreeSet<>(base);
        for (Producto p : inv.productos()) {
            agregarConPlural(s, p.nombre);
            agregarConPlural(s, p.unidad);
            agregarConPlural(s, p.categoria);
        }
        inv.clientes().buscar("", 500).forEach(c -> agregar(s, c.nombre()));
        inv.compras().buscarProveedores("", 500).forEach(p -> agregar(s, p.razonSocial()));
        diccionario.propias().forEach((dicho, significa) -> { agregar(s, dicho); agregar(s, significa); });
        cache = Collections.unmodifiableSet(s);
        calculado = ahora;
        return cache;
    }

    static void agregar(Set<String> s, String texto) {
        if (texto == null) return;
        Matcher m = PALABRA.matcher(texto.toLowerCase(Locale.ROOT));
        while (m.find()) s.add(m.group());
    }

    /** "Foco" también se dice "focos"; "pantalón", "pantalones". */
    static void agregarConPlural(Set<String> s, String texto) {
        if (texto == null) return;
        Matcher m = PALABRA.matcher(texto.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group();
            s.add(w);
            if (w.length() < 3) continue;
            char u = w.charAt(w.length() - 1);
            if ("aeiou".indexOf(u) >= 0) s.add(w + "s");
            else if (u != 's') s.add(sinTilde(w) + "es");
        }
    }

    /** pantalón → pantalon (el plural "pantalones" va sin tilde). */
    private static String sinTilde(String w) {
        return w.replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u');
    }
}
