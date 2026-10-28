/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class AccesibilidadTest {

    private static final Pattern ETIQUETA = Pattern.compile("<(input|select|textarea)\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BOTON = Pattern.compile("<button\\b([^>]*)>(.*?)</button>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MODAL = Pattern.compile("<div id=\"([^\"]+)\" class=\"modal\"([^>]*)>");

    private static String leer(String nombre) throws IOException {
        try (InputStream in = AccesibilidadTest.class.getResourceAsStream("/static/" + nombre)) {
            assertNotNull(in, nombre);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String html() throws IOException {
        String s = leer("index.html");
        return s.substring(0, s.indexOf("<script>"));
    }

    private static String atributo(String attrs, String nombre) {
        Matcher m = Pattern.compile("\\b" + nombre + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    @Test void cadaCampoTieneEtiqueta() throws IOException {
        String h = html();
        Set<String> conLabel = new HashSet<>();
        Matcher f = Pattern.compile("<label[^>]*\\bfor=\"([^\"]+)\"").matcher(h);
        while (f.find()) conLabel.add(f.group(1));
        List<String> sin = new ArrayList<>();
        Matcher m = ETIQUETA.matcher(h);
        while (m.find()) {
            String attrs = m.group(2);
            if ("hidden".equals(atributo(attrs, "type"))) continue;
            String id = atributo(attrs, "id");
            boolean dentroDeLabel = h.lastIndexOf("<label", m.start()) > h.lastIndexOf("</label>", m.start());
            if (atributo(attrs, "aria-label") == null && atributo(attrs, "aria-labelledby") == null
                    && !(id != null && conLabel.contains(id)) && !dentroDeLabel) sin.add(id == null ? m.group() : id);
        }
        assertEquals(List.of(), sin, "campos sin etiqueta para el lector de pantalla");
    }

    @Test void cadaBotonTieneNombre() throws IOException {
        String s = leer("index.html");
        List<String> sin = new ArrayList<>();
        Matcher m = BOTON.matcher(s);
        while (m.find()) {
            String texto = m.group(2).replaceAll("\\$\\{icono\\([^}]*}", "").replaceAll("\\$\\{[^}]*}", "texto")
                    .replaceAll("<[^>]*>", "").trim();
            String attrs = m.group(1);
            if (texto.isEmpty() && atributo(attrs, "aria-label") == null && atributo(attrs, "title") == null)
                sin.add(attrs.trim());
        }
        assertEquals(List.of(), sin, "botones sin nombre (solo un ícono)");
    }

    @Test void lasVentanasSonDialogosConNombre() throws IOException {
        String h = html();
        Matcher m = MODAL.matcher(h);
        int n = 0;
        while (m.find()) {
            n++;
            String id = m.group(1), attrs = m.group(2);
            String rol = atributo(attrs, "role");
            assertTrue("dialog".equals(rol) || "alertdialog".equals(rol), id + " sin role=dialog");
            assertEquals("true", atributo(attrs, "aria-modal"), id);
            String titulo = atributo(attrs, "aria-labelledby");
            assertTrue(titulo != null ? h.contains("id=\"" + titulo + "\"") : atributo(attrs, "aria-label") != null,
                    id + " sin nombre");
        }
        assertTrue(n >= 9, "se esperaban las ventanas de la pantalla, hay " + n);
        assertFalse(h.contains("class=\"modal\">"), "una ventana quedó sin atributos de diálogo");
    }

    @ParameterizedTest
    @ValueSource(strings = {"reactor", "reactorGrande"})
    void elReactorSeUsaConElTeclado(String id) throws IOException {
        Matcher m = Pattern.compile("<canvas id=\"" + id + "\"([^>]*)>").matcher(html());
        assertTrue(m.find());
        assertEquals("button", atributo(m.group(1), "role"));
        assertEquals("0", atributo(m.group(1), "tabindex"));
        assertNotNull(atributo(m.group(1), "aria-label"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"micBtn|Alt+H", "hfBtn|Alt+M", "vozBtn|Alt+V", "input|Alt+O", "atajosBtn|Alt+A"})
    void atajosDeTeclado(String caso) throws IOException {
        String[] c = caso.split("\\|");
        Matcher m = Pattern.compile("id=\"" + c[0] + "\"([^>]*)>").matcher(html());
        assertTrue(m.find(), c[0]);
        assertEquals(c[1], atributo(m.group(1), "aria-keyshortcuts"), c[0]);
    }

    @Test void lasRespuestasSeAnuncianEnteras() throws IOException {
        String h = html();
        assertTrue(h.contains("id=\"anuncio\" class=\"sr-only\" role=\"status\" aria-live=\"polite\""), "región para el lector de pantalla");
        assertTrue(h.contains("id=\"log\" role=\"log\" aria-live=\"off\""), "el chat se escribe letra por letra: no se anuncia");
        assertTrue(leer("index.html").contains("if (who !== 'me') anunciar(text);"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"index.html", "manual.html", "manifest.json"})
    void nadaSeCargaDeInternet(String archivo) throws IOException {
        String s = leer(archivo);
        Matcher m = Pattern.compile("(?:src|href)\\s*=\\s*[\"'](https?://[^\"']+)|url\\(\\s*[\"']?(https?://[^)\"']+)|@import\\s+[\"'](https?://[^\"']+)")
                .matcher(s);
        List<String> fuera = new ArrayList<>();
        while (m.find()) {
            String url = Objects.requireNonNullElse(m.group(1), Objects.requireNonNullElse(m.group(2), m.group(3)));
            if (!url.matches("https?://(localhost|127\\.0\\.0\\.1|192\\.168\\.[0-9.]+)(:\\d+)?(/.*)?")) fuera.add(url);
        }
        assertEquals(List.of(), fuera, "recursos de internet en " + archivo);
    }
}
