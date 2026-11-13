/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import java.util.*;

/**
 * Encuentra el producto que el usuario quiso decir aunque lo diga incompleto o con errores
 * ("polo rojo m", "arros"). Distingue tallas y colores para no confundir "polo M" con "polo L".
 * No es seguro para hilos: InventoryService lo usa siempre dentro de sus métodos synchronized.
 */
final class BuscadorProductos {

    static final class Match {
        Producto producto; boolean ambiguo; List<Producto> empatados;
        Match(Producto p, boolean a, List<Producto> e) { producto = p; ambiguo = a; empatados = e; }
        boolean empate() { return empatados.size() > 1; }
    }

    Match buscar(String texto, List<Producto> productos) {
        String q = Palabras.norm(texto);
        if (q.isBlank()) return null;
        Producto mejor = null, segundo = null;
        double sMejor = 0, sSegundo = 0;
        Map<Producto, Double> puntajes = new LinkedHashMap<>();
        for (Producto p : productos) {
            double s = puntaje(q, p);
            puntajes.put(p, s);
            if (s > sMejor) { sSegundo = sMejor; segundo = mejor; sMejor = s; mejor = p; }
            else if (s > sSegundo) { sSegundo = s; segundo = p; }
        }
        if (mejor == null || sMejor < 0.34) return null;
        boolean ambiguo = segundo != null && sMejor < 1.0 && (sMejor - sSegundo) < 0.12 && sSegundo >= 0.34;
        double tope = sMejor;
        List<Producto> empatados = puntajes.entrySet().stream()
                .filter(e -> tope - e.getValue() < 1e-9).map(Map.Entry::getKey).toList();
        return new Match(mejor, ambiguo, empatados);
    }

    static String cualDe(Match m) {
        List<String> nombres = m.empatados.stream().map(p -> p.nombre).toList();
        String lista = nombres.size() == 1 ? nombres.get(0)
                : String.join(", ", nombres.subList(0, nombres.size() - 1)) + " y " + nombres.get(nombres.size() - 1);
        return "Tengo varios parecidos: " + lista + ". ¿Cuál de ellos, señor? Dígame el nombre completo (con talla o color).";
    }

    private double puntaje(String q, Producto p) {
        double best = sim(q, normalizado(p.nombre));
        for (String a : p.alias) best = Math.max(best, sim(q, normalizado(a)));
        return best;
    }

    private final Map<String, String> normalizados = new HashMap<>();

    private String normalizado(String s) {
        if (normalizados.size() > 100_000) normalizados.clear();
        return normalizados.computeIfAbsent(s, Palabras::norm);
    }

    private final Map<String, Set<String>> palabras = new HashMap<>();

    private Set<String> palabrasDe(String normalizado) {
        if (palabras.size() > 100_000) palabras.clear();
        return palabras.computeIfAbsent(normalizado, this::tokens);
    }

    private double sim(String q, String cand) {
        if (cand.isBlank()) return 0;
        if (q.equals(cand)) return 1.0;
        Set<String> qt = tokens(q), ct = palabrasDe(cand);
        if (distintos(qt, ct, BuscadorProductos::esTalla)) return 0;
        if (distintos(qt, ct, BuscadorProductos::esColor)) return 0;
        String qb = " " + q + " ", cb = " " + cand + " ";
        if (cb.contains(qb) || qb.contains(cb)) return 0.95;
        if (qt.isEmpty() || ct.isEmpty()) return 0;
        int inter = 0;
        for (String a : qt) for (String b : ct) if (tokenEq(a, b)) { inter++; break; }
        return (double) inter / qt.size();
    }

    private static final Set<String> TALLAS_LETRA = Set.of("xxs", "xs", "s", "m", "l", "xl", "xxl", "xxxl");

    private static final Set<String> COLORES = Set.of("rojo", "roja", "rojos", "rojas", "azul", "azules", "verde", "verdes",
            "negro", "negra", "negros", "negras", "blanco", "blanca", "blancos", "blancas", "amarillo", "amarilla",
            "gris", "plomo", "plomos", "rosado", "rosada", "rosa", "morado", "morada", "lila", "celeste", "celestes",
            "beige", "marron", "cafe", "naranja", "crema", "dorado", "dorada", "plateado", "plateada", "turquesa", "vino", "guinda");

    private static boolean esTalla(String t) { return Palabras.esNumero(t) || TALLAS_LETRA.contains(t); }

    private static boolean esColor(String t) { return COLORES.contains(t); }

    private static String raizColor(String t) {
        if (t.endsWith("es") && t.length() > 4 && "lnr".indexOf(t.charAt(t.length() - 3)) >= 0) t = t.substring(0, t.length() - 2);
        else if (t.endsWith("s")) t = t.substring(0, t.length() - 1);
        return t.replaceFirst("[oa]$", "");
    }

    private static boolean distintos(Set<String> qt, Set<String> ct, java.util.function.Predicate<String> tipo) {
        Set<String> tq = new HashSet<>(), tc = new HashSet<>();
        for (String t : qt) if (tipo.test(t)) tq.add(clave(t));
        for (String t : ct) if (tipo.test(t)) tc.add(clave(t));
        if (tq.isEmpty() || tc.isEmpty()) return false;
        tq.retainAll(tc);
        return tq.isEmpty();
    }

    private static String clave(String t) { return esColor(t) ? raizColor(t) : t; }

    private static final Set<String> STOP = Set.of("de", "del", "la", "el", "los", "las", "un", "una", "unos", "unas", "a");

    private Set<String> tokens(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : s.split(" ")) if (!t.isBlank() && !STOP.contains(t)) out.add(t);
        return out;
    }

    private boolean tokenEq(String a, String b) {
        if (a.equals(b)) return true;
        if (a.length() <= 2 || b.length() <= 2) return false;
        if (a.length() >= 4 && b.length() >= 4 && (a.startsWith(b.substring(0, 4)) || b.startsWith(a.substring(0, 4)))) return true;
        return levenshtein(a, b) <= (Math.max(a.length(), b.length()) > 5 ? 2 : 1);
    }

    private int levenshtein(String a, String b) {
        int[] d = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) d[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            int prev = d[0]; d[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int tmp = d[j];
                d[j] = Math.min(Math.min(d[j] + 1, d[j - 1] + 1), prev + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
                prev = tmp;
            }
        }
        return d[b.length()];
    }
}
