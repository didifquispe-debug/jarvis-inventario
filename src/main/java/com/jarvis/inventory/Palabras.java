package com.jarvis.inventory;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;

/** Utilidades de texto: normalizar, pasar números en palabras a dígitos, etc. */
public final class Palabras {

    private Palabras() {}

    private static final Map<String, Integer> UNIDAD = Map.ofEntries(
            Map.entry("cero", 0), Map.entry("un", 1), Map.entry("uno", 1), Map.entry("una", 1),
            Map.entry("dos", 2), Map.entry("tres", 3), Map.entry("cuatro", 4), Map.entry("cinco", 5),
            Map.entry("seis", 6), Map.entry("siete", 7), Map.entry("ocho", 8), Map.entry("nueve", 9),
            Map.entry("diez", 10), Map.entry("once", 11), Map.entry("doce", 12), Map.entry("trece", 13),
            Map.entry("catorce", 14), Map.entry("quince", 15), Map.entry("dieciseis", 16),
            Map.entry("diecisiete", 17), Map.entry("dieciocho", 18), Map.entry("diecinueve", 19),
            Map.entry("veinte", 20), Map.entry("veintiuno", 21), Map.entry("veintidos", 22),
            Map.entry("veintitres", 23), Map.entry("veinticuatro", 24), Map.entry("veinticinco", 25),
            Map.entry("veintiseis", 26), Map.entry("veintisiete", 27), Map.entry("veintiocho", 28),
            Map.entry("veintinueve", 29));

    private static final Map<String, Integer> DECENA = Map.of(
            "treinta", 30, "cuarenta", 40, "cincuenta", 50, "sesenta", 60,
            "setenta", 70, "ochenta", 80, "noventa", 90);

    private static final Map<String, Integer> CIEN = Map.ofEntries(
            Map.entry("cien", 100), Map.entry("ciento", 100), Map.entry("doscientos", 200),
            Map.entry("trescientos", 300), Map.entry("cuatrocientos", 400), Map.entry("quinientos", 500),
            Map.entry("seiscientos", 600), Map.entry("setecientos", 700), Map.entry("ochocientos", 800),
            Map.entry("novecientos", 900), Map.entry("mil", 1000));

    /** minúsculas, sin tildes, sin signos. */
    public static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.toLowerCase().trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[¿¡!.,;:()]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** ¿Con este token puede empezar una cantidad? (dígitos o número en palabras) */
    public static boolean esInicioCantidad(String t) {
        return t.matches("\\d+") || UNIDAD.containsKey(t) || DECENA.containsKey(t) || CIEN.containsKey(t);
    }

    /** ¿Es una decena ("treinta", "cuarenta"…)? Sirve para no cortar "treinta y cinco" en dos. */
    public static boolean esDecena(String t) { return DECENA.containsKey(t); }

    /** ¿Este token forma parte de un número hablado? */
    public static boolean esNumeroPalabra(String t) {
        return UNIDAD.containsKey(t) || DECENA.containsKey(t) || CIEN.containsKey(t) || t.equals("y");
    }

    /**
     * Busca la primera cantidad en la lista de palabras (dígitos o palabras) y devuelve
     * {valor, índiceInicio, índiceFin(exclusivo)}. Si no hay, valor = -1.
     */
    public static int[] buscarCantidad(List<String> ts) {
        // "un/una/uno" suelen ser artículos ("registra una venta de 5…"): solo valen 1 si no hay otro número
        int[] r = buscarCantidad(ts, false);
        return r[0] >= 0 ? r : buscarCantidad(ts, true);
    }

    private static final java.util.Set<String> ARTICULOS = java.util.Set.of("un", "una", "uno");

    private static int[] buscarCantidad(List<String> ts, boolean conArticulos) {
        for (int i = 0; i < ts.size(); i++) {
            String t = ts.get(i);
            if (!conArticulos && ARTICULOS.contains(t)) continue;
            if (t.matches("\\d+")) return new int[]{Integer.parseInt(t), i, i + 1};
            if (UNIDAD.containsKey(t) || DECENA.containsKey(t) || CIEN.containsKey(t)) {
                int j = i, total = 0, actual = 0;
                while (j < ts.size()) {
                    String w = ts.get(j);
                    if (CIEN.containsKey(w)) { actual = (actual == 0 ? 1 : actual) * CIEN.get(w); total += actual; actual = 0; }
                    else if (DECENA.containsKey(w)) { actual += DECENA.get(w); }
                    else if (UNIDAD.containsKey(w)) { actual += UNIDAD.get(w); }
                    else if (w.equals("y")) { /* enlazar */ }
                    else break;
                    j++;
                }
                return new int[]{total + actual, i, j};
            }
        }
        return new int[]{-1, -1, -1};
    }
}
