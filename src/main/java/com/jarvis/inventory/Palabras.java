package com.jarvis.inventory;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** Palabras que multiplican al número que las precede: "dos docenas" = 24, "un par" = 2. */
    private static final Map<String, Integer> MULTIPLICADOR = Map.of(
            "par", 2, "pares", 2, "docena", 12, "docenas", 12, "millar", 1000, "millares", 1000);

    /** Las que valen por sí solas, sin número delante: "docena de huevos" = 12 (el plural suelto no: "cuantos pares quedan"). */
    private static final Set<String> MULTIPLICADOR_SOLO = Set.of("par", "docena", "millar");

    /** "media docena" = 6, "medio ciento" = 50, "medio millar" = 500. */
    private static final Map<String, Integer> MITAD_DE = Map.of("par", 2, "docena", 12, "ciento", 100, "millar", 1000);

    private static final Set<String> PARES = Set.of("par", "pares");

    /**
     * Cantidad encontrada en una frase: valor y posición [inicio, fin) de sus palabras.
     * {@code enPares} indica que el valor salió de "par/pares" (ya multiplicado por 2): ver {@link #valorEn(String)}.
     */
    public record Cantidad(BigDecimal valor, int inicio, int fin, boolean enPares) {
        public static final Cantidad NINGUNA = new Cantidad(null, -1, -1);
        public Cantidad(BigDecimal valor, int inicio, int fin) { this(valor, inicio, fin, false); }
        public boolean hay() { return valor != null; }
        /** ¿La palabra i forma parte de la cantidad? */
        public boolean cubre(int i) { return hay() && i >= inicio && i < fin; }

        /**
         * La cantidad en la unidad del producto: "un par de medias" son 2 unidades, pero si el
         * producto ya se cuenta en pares (zapatillas) es 1 par, no 2.
         */
        public BigDecimal valorEn(String unidad) {
            if (!enPares || valor == null || unidad == null || !PARES.contains(norm(unidad))) return valor;
            return Num.cantidad(valor.divide(BigDecimal.valueOf(2)));
        }
    }

    /** Número con decimales opcionales, ya normalizado (coma → punto). */
    private static final String DECIMAL = "\\d+(?:\\.\\d+)?";

    /**
     * minúsculas, sin tildes, sin signos. El punto o la coma ENTRE dígitos se conservan como
     * punto decimal ("2,5 metros" → "2.5 metros"); antes se borraban y "2.5" se volvía "2 5".
     */
    public static String norm(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.toLowerCase().trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("(\\d),(\\d)", "$1.$2")
                .replaceAll("[¿¡!;:()]|(?<!\\d)[.,]|[.,](?!\\d)", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** ¿Es un número escrito con dígitos ("20", "2.5")? */
    public static boolean esNumero(String t) { return t.matches(DECIMAL); }

    /** ¿Con este token puede empezar una cantidad? (dígitos o número en palabras) */
    public static boolean esInicioCantidad(String t) {
        return esNumero(t) || UNIDAD.containsKey(t) || DECENA.containsKey(t) || CIEN.containsKey(t);
    }

    /** ¿Es una decena ("treinta", "cuarenta"…)? Sirve para no cortar "treinta y cinco" en dos. */
    public static boolean esDecena(String t) { return DECENA.containsKey(t); }

    /** ¿Este token forma parte de un número hablado? */
    public static boolean esNumeroPalabra(String t) {
        return UNIDAD.containsKey(t) || DECENA.containsKey(t) || CIEN.containsKey(t) || t.equals("y");
    }

    /**
     * Busca la primera cantidad en la lista de palabras: dígitos ("20", "2.5"), palabras
     * ("veinticinco"), "dos y medio" / "2 y media", o "medio" solo (= 0.5).
     */
    public static Cantidad buscarCantidad(List<String> ts) {
        // "un/una/uno" suelen ser artículos ("registra una venta de 5…") y "medio/media" puede ser parte
        // del nombre ("tubo de media pulgada"): solo cuentan si no hay otro número en la frase
        Cantidad r = buscarCantidad(ts, false);
        return r.hay() ? r : buscarCantidad(ts, true);
    }

    private static final Set<String> ARTICULOS = Set.of("un", "una", "uno");
    private static final Set<String> MEDIO = Set.of("medio", "media");
    private static final BigDecimal MITAD = new BigDecimal("0.5");

    private static Cantidad buscarCantidad(List<String> ts, boolean conArticulos) {
        for (int i = 0; i < ts.size(); i++) {
            String t = ts.get(i);
            String sig = i + 1 < ts.size() ? ts.get(i + 1) : "";
            if (MEDIO.contains(t) && MITAD_DE.containsKey(sig))
                return new Cantidad(Num.cantidad(MITAD.multiply(BigDecimal.valueOf(MITAD_DE.get(sig)))), i, i + 2, PARES.contains(sig));
            // "una docena", "un par": aquí el "un/una" sí es el número
            if (!conArticulos && ARTICULOS.contains(t) && !MULTIPLICADOR.containsKey(sig)) continue;
            if (conArticulos && MEDIO.contains(t)) return new Cantidad(MITAD, i, i + 1);
            if (esNumero(t)) return multiplicar(ts, new BigDecimal(t), i, i + 1);
            if (MULTIPLICADOR_SOLO.contains(t)) return multiplicar(ts, BigDecimal.ONE, i, i);
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
                // un "y" final no es parte del número ("dos y medio" lo trata masMedio)
                if (j > i + 1 && ts.get(j - 1).equals("y")) j--;
                return multiplicar(ts, BigDecimal.valueOf(total + actual), i, j);
            }
        }
        return Cantidad.NINGUNA;
    }

    /** Tras el número [inicio, fin): "dos docenas" (×12), "un par" (×2), y luego "… y medio". */
    private static Cantidad multiplicar(List<String> ts, BigDecimal valor, int inicio, int fin) {
        Integer f = fin < ts.size() ? MULTIPLICADOR.get(ts.get(fin)) : null;
        if (f == null) return masMedio(ts, valor, inicio, fin, MITAD, false);
        BigDecimal factor = BigDecimal.valueOf(f);
        // "dos docenas y media" = 30: la media es de docena
        return masMedio(ts, valor.multiply(factor), inicio, fin + 1, MITAD.multiply(factor), PARES.contains(ts.get(fin)));
    }

    /** "2 y medio" / "dos y media" → suma la mitad e incluye esas palabras en la cantidad. */
    private static Cantidad masMedio(List<String> ts, BigDecimal valor, int inicio, int fin, BigDecimal mitad, boolean enPares) {
        if (fin + 1 < ts.size() && ts.get(fin).equals("y") && MEDIO.contains(ts.get(fin + 1)))
            return new Cantidad(Num.cantidad(valor.add(mitad)), inicio, fin + 2, enPares);
        return new Cantidad(Num.cantidad(valor), inicio, fin, enPares);
    }
}
