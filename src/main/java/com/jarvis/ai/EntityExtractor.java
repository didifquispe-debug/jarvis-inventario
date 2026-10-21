package com.jarvis.ai;

import com.jarvis.inventory.Palabras;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrae entidades de una orden de inventario con reglas simples:
 * cantidad (dígitos o palabras), precio ("a N soles") y el texto del producto.
 * Lo usan tanto el clasificador local como el procesador de reglas de InventorySkill.
 */
public final class EntityExtractor {

    private EntityExtractor() {}

    /** Entidades encontradas en una frase. cantidad = -1 si no hay. */
    public record Entidades(int cantidad, String producto, Double precio) {}

    private static final Pattern PRECIO = Pattern.compile("\\ba (\\d+(?:[.,]\\d+)?)\\s*(soles|sol)?");

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
            "saco", "sacos", "paquete", "paquetes", "rollo", "rollos", "litro", "litros", "pieza", "piezas");

    /** Pipeline completo sobre un texto ya normalizado (minúsculas, sin tildes). */
    public static Entidades extraer(String cmd) {
        String n = Palabras.norm(cmd);
        List<String> palabras = Arrays.asList(n.split(" "));
        int[] qty = Palabras.buscarCantidad(palabras);
        return new Entidades(qty[0], producto(palabras, qty), precio(n));
    }

    /** Precio tras "a N soles" o "a N". */
    public static Double precio(String cmd) {
        Matcher m = PRECIO.matcher(cmd);
        if (m.find()) return Double.parseDouble(m.group(1).replace(",", "."));
        return null;
    }

    /** Lo que queda tras quitar acción, cantidad, precio, unidades y ruido = el producto. */
    public static String producto(List<String> palabras, int[] qty) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < palabras.size(); i++) {
            if (qty[0] >= 0 && i >= qty[1] && i < qty[2]) continue;       // saltar la cantidad
            String w = palabras.get(i);
            if (w.isBlank() || w.matches("\\d+") || RUIDO.contains(w) || Palabras.esNumeroPalabra(w)) continue;
            sb.append(w).append(" ");
        }
        return sb.toString().trim();
    }
}
