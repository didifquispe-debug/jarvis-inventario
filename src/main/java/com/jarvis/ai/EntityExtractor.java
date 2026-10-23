package com.jarvis.ai;

import com.jarvis.inventory.Num;
import com.jarvis.inventory.Palabras;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrae entidades de una orden de inventario con reglas simples:
 * cantidad (dígitos, decimales o palabras), precio ("a N soles") y el texto del producto.
 * Lo usan tanto el clasificador local como el procesador de reglas de InventorySkill.
 */
public final class EntityExtractor {

    private EntityExtractor() {}

    /** Entidades encontradas en una frase. cantidad = null si no hay; enPares: salió de "par/pares" (ver Palabras.Cantidad). */
    public record Entidades(BigDecimal cantidad, String producto, BigDecimal precio, boolean enPares) {}

    private static final Pattern PRECIO = Pattern.compile("\\ba (\\d+(?:\\.\\d+)?)\\s*(soles|sol)?");

    // palabras que NO forman parte del nombre del producto
    private static final Set<String> RUIDO = Set.of(
            "de", "del", "la", "el", "los", "las", "un", "una", "unos", "unas", "a", "por", "me", "que", "con",
            "registra", "registrar", "registro", "anota", "pon", "por favor", "ahora", "soles", "sol", "hoy", "nos",
            "le", "les", "al", "cliente", "todavia", "nuevos", "nuevas", "fueron",
            // acciones
            "entro", "entraron", "llego", "llegaron", "ingreso", "ingresaron", "entrada", "compre", "compraron",
            "recibi", "recibieron", "agrega", "agregar", "sumar", "repuse",
            "salio", "salieron", "vende", "venden", "vendeme", "vender", "vendo", "vendemos", "vendimos", "vendi",
            "vendieron", "vendio", "vendida", "vendidas", "vendido", "vendidos", "venta", "salida",
            "cobra", "cobrar", "cobre", "cobramos", "compro", "compramos", "quiero",
            // venta en tienda (carrito)
            "dame", "deme", "ponme", "lleva", "llevo", "necesito", "agregale", "anade", "anadir", "tambien", "ademas",
            "mas", "otro", "otra", "otros", "otras", "quita", "quitar", "quitale", "saca", "sacar", "elimina", "eliminar",
            "retira", "retirar",
            "despacha", "despache", "resta", "restar", "saque", "sacaron", "entregue", "llevaron", "se",
            "cuanto", "cuantos", "cuantas", "stock", "quedan", "queda", "quedaron", "hay", "tengo", "tenemos",
            "disponible", "disponibles",
            // unidades
            "bolsa", "bolsas", "metro", "metros", "unidad", "unidades", "caja", "cajas", "kilo", "kilos",
            "saco", "sacos", "paquete", "paquetes", "rollo", "rollos", "litro", "litros", "pieza", "piezas",
            "kg", "gramo", "gramos", "galon", "galones", "par", "pares", "docena", "docenas", "millar", "millares",
            // ropa y calzado: "polera talla m" = "polera m", "zapatilla numero 40" = "zapatilla 40"
            "talla", "tallas", "numero", "color");

    /** Palabra hablada → unidad con la que se registra un producto nuevo. */
    private static final Map<String, String> UNIDADES = Map.ofEntries(
            Map.entry("bolsa", "bolsas"), Map.entry("bolsas", "bolsas"), Map.entry("metro", "metros"), Map.entry("metros", "metros"),
            Map.entry("caja", "cajas"), Map.entry("cajas", "cajas"), Map.entry("kilo", "kilos"), Map.entry("kilos", "kilos"),
            Map.entry("kg", "kilos"), Map.entry("saco", "sacos"), Map.entry("sacos", "sacos"), Map.entry("paquete", "paquetes"),
            Map.entry("paquetes", "paquetes"), Map.entry("rollo", "rollos"), Map.entry("rollos", "rollos"),
            Map.entry("litro", "litros"), Map.entry("litros", "litros"), Map.entry("pieza", "piezas"), Map.entry("piezas", "piezas"),
            Map.entry("gramo", "gramos"), Map.entry("gramos", "gramos"), Map.entry("galon", "galones"), Map.entry("galones", "galones"),
            Map.entry("par", "pares"), Map.entry("pares", "pares"));

    /** Pipeline completo sobre un texto ya normalizado (minúsculas, sin tildes). */
    public static Entidades extraer(String cmd) {
        String n = Palabras.norm(cmd);
        List<String> palabras = Arrays.asList(n.split(" "));
        Palabras.Cantidad qty = Palabras.buscarCantidad(palabras);
        return new Entidades(qty.valor(), producto(palabras, qty), precio(n), qty.enPares());
    }

    /** Precio tras "a N soles" o "a N" (texto normalizado: el decimal ya viene con punto). */
    public static BigDecimal precio(String cmd) {
        Matcher m = PRECIO.matcher(cmd);
        if (m.find()) return Num.dinero(Num.parse(m.group(1)));
        return null;
    }

    /** Unidad mencionada en la frase ("llegaron 3 kilos de…" → "kilos"), o null. */
    public static String unidad(List<String> palabras) {
        for (String w : palabras) if (UNIDADES.containsKey(w)) return UNIDADES.get(w);
        return null;
    }

    /**
     * Lo que queda tras quitar acción, cantidad, precio, unidades y ruido = el producto.
     * Un número que va DESPUÉS del nombre es parte de él (la talla: "2 zapatillas nike 40" →
     * "zapatillas nike 40"), salvo que sea el precio ("a 28", "28 soles").
     */
    public static String producto(List<String> palabras, Palabras.Cantidad qty) {
        StringBuilder sb = new StringBuilder();
        boolean hayNombre = false;
        for (int i = 0; i < palabras.size(); i++) {
            if (qty.cubre(i)) continue;       // saltar la cantidad
            String w = palabras.get(i);
            if (w.isBlank() || RUIDO.contains(w) || Palabras.esNumeroPalabra(w)) continue;
            if (Palabras.esNumero(w) && (!hayNombre || esPrecio(palabras, i))) continue;
            sb.append(w).append(" ");
            hayNombre = true;
        }
        return sb.toString().trim();
    }

    private static boolean esPrecio(List<String> palabras, int i) {
        return (i > 0 && palabras.get(i - 1).equals("a"))
                || (i + 1 < palabras.size() && Set.of("soles", "sol").contains(palabras.get(i + 1)));
    }
}
