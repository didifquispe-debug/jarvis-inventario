package com.jarvis.skills;

import com.jarvis.ai.EntityExtractor;
import com.jarvis.ai.IntentResult;
import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.Palabras;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entiende las órdenes de inventario habladas y las manda a InventoryService.
 * Va primero (@Order(-10)) para atrapar "sí/no" cuando hay una confirmación pendiente
 * y los productos que se dictan durante una venta con varios productos.
 */
@Component
@Order(-10)
public class InventorySkill implements Skill {

    private final InventoryService inv;

    public InventorySkill(InventoryService inv) { this.inv = inv; }

    @Override public String name() { return "Inventario"; }
    @Override public String example() {
        return "Llegaron 20 bolsas de cemento / Vendí 2 bolsas de cemento y 3 focos / Nueva venta … cobrar"
                + " / Lista de precios / ¿Cuánto cuesta el cable? / ¿Cuántos focos quedan? / Último comprobante / Cierre de caja";
    }

    // palabras que indican cada acción.
    // OJO: "compre/compramos" = yo compré al proveedor (entrada); "compro/compraron" = el cliente compró (venta).
    private static final Pattern ENTRADA = Pattern.compile("\\b(entro|entraron|llego|llegaron|ingreso|ingresaron|entrada|compre|compramos|recibi|recibieron|agrega|agregar|sumar|repuse)\\b");
    private static final Pattern SALIDA  = Pattern.compile("\\b(salio|salieron|vende|venden|vendeme|vender|vendo|vendemos|vendimos|vendi|vendieron|vendio|vendidas?|vendidos?"
            + "|venta|salida|despacha|despache|se llevaron|resta|restar|saque|sacaron|entregue|cobra|cobrar|cobre|cobramos|compro|compraron)\\b");
    // Consulta de stock. OJO: evita chocar con la calculadora ("cuanto es 25 por 4").
    private static final Pattern CONSULTA = Pattern.compile(
            "\\b(stock|quedan|queda|disponibles?)\\b" +            // palabras claras de inventario
            "|\\b(cuantos|cuantas)\\b" +                            // plural: "cuantos focos"
            "|\\bcuanto\\b.*\\b(hay|tengo)\\b");                    // "cuanto cable tengo"
    private static final Pattern ES_CALCULO = Pattern.compile("\\bcuanto es\\b|\\bcalcula\\b");
    private static final Pattern CIERRE = Pattern.compile("\\b(cierre|cierra|reporte|resumen)\\b.*\\b(caja|dia|diario|jornada|ventas)\\b|\\bcierre de caja\\b|\\bcierre del dia\\b");
    private static final Pattern RECIBO = Pattern.compile("\\b(comprobante|recibo|boleta|ticket)\\b");
    private static final Pattern LISTA = Pattern.compile("\\b(inventario|lista de productos|que productos|todo el stock|stock general)\\b");

    // precios
    private static final Pattern LISTA_PRECIOS = Pattern.compile("\\b(lista de precios|precios)\\b");
    private static final Pattern CAMBIAR_PRECIO = Pattern.compile(
            "\\b(cambia|cambiar|cambiale|actualiza|actualizar|pon|ponle|nuevo)\\b.*\\bprecio\\b" +   // "cambia el precio del cemento a 30"
            "|\\b(cuesta|cuestan|vale|valen)\\s+\\d");                                              // "el cemento cuesta 30 soles"
    private static final Pattern PRECIO = Pattern.compile("\\b(precio|cuesta|cuestan|vale|valen|a como)\\b");
    private static final Pattern NUMERO = Pattern.compile("(\\d+(?:[.,]\\d+)?)");
    private static final Set<String> PALABRAS_PRECIO = Set.of("precio", "cuesta", "cuestan", "vale", "valen", "cambia", "cambiar",
            "cambiale", "actualiza", "actualizar", "pon", "ponle", "nuevo", "como", "esta", "estan", "en", "cual", "es", "dime", "dame");

    // venta con varios productos
    private static final Pattern NUEVA_VENTA = Pattern.compile("\\b(nueva venta|otra venta|nuevo cliente|(abre|abrir|inicia|iniciar|empieza|empezar) (una )?venta)\\b");
    private static final Pattern COBRAR = Pattern.compile("\\b(cobra|cobrar|cobrale|cobre|cobramos|eso es todo|es todo|seria todo|listo|termina|terminar|finaliza|finalizar|cierra la venta|cerrar (la )?venta)\\b");
    private static final Pattern CANCELAR_VENTA = Pattern.compile("\\b(cancela|cancelar|anula|anular|borra)\\b.*\\bventa\\b");
    private static final Pattern QUITAR = Pattern.compile("\\b(quita|quitar|quitale|saca|sacar|elimina|eliminar|retira|retirar)\\b");
    private static final Pattern VER_VENTA = Pattern.compile("\\b(cuanto va|cuanto llevo|cuanto llevamos|que llevo|que llevamos|ver (la )?venta|total parcial|el total)\\b");
    // "agrega 3 focos" durante una venta significa añadir al carrito, no una entrada de mercadería
    private static final Pattern AGREGAR = Pattern.compile("\\b(agrega|agregar|agregale|sumar|anade|anadir)\\b");
    // separadores entre productos: "2 cementos y 3 focos", "… mas 5 clavos", "… tambien 2 discos"
    private static final Set<String> SEPARADORES = Set.of("y", "mas", "tambien", "ademas");

    private static final Set<String> SI = Set.of("si", "sip", "claro", "confirmo", "correcto", "dale", "afirmativo", "exacto", "ok", "okay", "confirmado");
    private static final Set<String> NO = Set.of("no", "nop", "cancela", "cancelar", "negativo", "mejor no", "anula", "anular");

    @Override
    public Optional<String> handle(String cmdRaw, String original) {
        String cmd = Palabras.norm(cmdRaw);   // sin tildes, sin comas, minúsculas
        // 1) ¿Esperando confirmación? Atender sí/no.
        if (inv.hayPendiente()) {
            String c = cmd.trim();
            if (SI.contains(c) || SI.stream().anyMatch(c::startsWith)) return Optional.ofNullable(inv.resolver(true));
            if (NO.contains(c) || NO.stream().anyMatch(c::startsWith)) return Optional.ofNullable(inv.resolver(false));
            // si dice otra cosa, recordamos la pregunta
            return Optional.of("Antes necesito que confirme. Diga sí o no, señor.");
        }

        List<String> palabras = new ArrayList<>(Arrays.asList(cmd.split(" ")));
        int[] qty = Palabras.buscarCantidad(palabras);

        // 2) Venta con varios productos: abrir, y mientras está abierta, ver / cancelar / cobrar
        if (NUEVA_VENTA.matcher(cmd).find()) return Optional.of(inv.abrirVenta());
        if (inv.ventaAbierta()) {
            if (CANCELAR_VENTA.matcher(cmd).find()) return Optional.of(inv.cancelarVenta());
            if (VER_VENTA.matcher(cmd).find()) return Optional.of(inv.verCarrito());
            if (COBRAR.matcher(cmd).find() && qty[0] < 0) return Optional.of(inv.cobrar());
        }

        // 3) Órdenes que no mueven stock
        if (RECIBO.matcher(cmd).find() && !SALIDA.matcher(cmd).find()) return Optional.of(inv.ultimoComprobante());
        if (CIERRE.matcher(cmd).find()) return Optional.of(reporteDiario());
        if (LISTA_PRECIOS.matcher(cmd).find()) return Optional.of(inv.listaPrecios());
        if (CAMBIAR_PRECIO.matcher(cmd).find()) return Optional.of(cambiarPrecio(cmd, original));
        if (PRECIO.matcher(cmd).find()) {
            String producto = productoSinPalabrasDePrecio(palabras);
            return Optional.of(producto.isBlank() ? "¿De qué producto desea saber el precio, señor?" : inv.precio(producto));
        }
        if (LISTA.matcher(cmd).find()) return Optional.of(inv.inventario());

        // 4) Durante una venta abierta: quitar productos o añadirlos al carrito
        if (inv.ventaAbierta()) {
            if (QUITAR.matcher(cmd).find()) {
                String producto = EntityExtractor.producto(palabras, qty);
                return Optional.of(producto.isBlank() ? "¿Qué producto quito de la venta, señor?" : inv.quitarDelCarrito(producto));
            }
            boolean entradaReal = ENTRADA.matcher(AGREGAR.matcher(cmd).replaceAll("")).find();
            if (qty[0] >= 0 && !entradaReal && !CONSULTA.matcher(cmd).find()) return Optional.of(agregarAlCarrito(palabras));
        }

        if (ES_CALCULO.matcher(cmd).find()) return Optional.empty();   // deja la calculadora en paz

        // Si aparecen palabras de entrada y de salida, manda la que se dijo primero
        int iEntrada = posicion(ENTRADA, cmd), iSalida = posicion(SALIDA, cmd);
        boolean esEntrada = iEntrada >= 0 && (iSalida < 0 || iEntrada < iSalida);
        boolean esSalida = iSalida >= 0 && !esEntrada;
        boolean esConsulta = CONSULTA.matcher(cmd).find();

        if (!esEntrada && !esSalida && !esConsulta) return Optional.empty();

        Double precio = EntityExtractor.precio(cmd);
        String producto = EntityExtractor.producto(palabras, qty);

        if (esEntrada) {
            if (qty[0] < 0) return Optional.of("No logré identificar la cantidad, señor. Por favor repita la orden.");
            if (producto.isBlank()) return Optional.of("No logré identificar el producto, señor. Por favor repita la orden.");
            return Optional.of(registrarEntrada(qty[0], producto, precio));
        }
        if (esSalida) {
            List<List<String>> partes = dividirProductos(palabras);
            if (partes.size() > 1) return Optional.of(venderVarios(partes));
            if (qty[0] < 0 || producto.isBlank())
                return Optional.of("No logré identificar la cantidad ni el producto, señor. Por favor repita la orden.");
            return Optional.of(registrarSalida(qty[0], producto));
        }
        // consulta
        if (producto.isBlank()) return Optional.of("¿De qué producto desea saber el stock, señor?");
        return Optional.of(consultarStock(producto));
    }

    private static int posicion(Pattern p, String cmd) {
        Matcher m = p.matcher(cmd);
        return m.find() ? m.start() : -1;
    }

    /**
     * Corta "vendí 2 cementos y 3 focos" en ["vendí 2 cementos", "3 focos"]. Solo corta en "y/más/también"
     * si después viene una cantidad, y nunca dentro de un número ("treinta y cinco").
     */
    static List<List<String>> dividirProductos(List<String> palabras) {
        List<List<String>> partes = new ArrayList<>();
        List<String> actual = new ArrayList<>();
        for (int i = 0; i < palabras.size(); i++) {
            String w = palabras.get(i);
            boolean corta = SEPARADORES.contains(w) && i + 1 < palabras.size()
                    && Palabras.esInicioCantidad(palabras.get(i + 1))
                    && !(i > 0 && Palabras.esDecena(palabras.get(i - 1)));
            if (corta) {
                if (!actual.isEmpty()) partes.add(actual);
                actual = new ArrayList<>();
            } else {
                actual.add(w);
            }
        }
        if (!actual.isEmpty()) partes.add(actual);
        return partes;
    }

    private String venderVarios(List<List<String>> partes) {
        List<Integer> cantidades = new ArrayList<>();
        List<String> productos = new ArrayList<>();
        for (List<String> p : partes) {
            int[] q = Palabras.buscarCantidad(p);
            String prod = EntityExtractor.producto(p, q);
            if (q[0] < 0 || prod.isBlank())
                return "No logré identificar la cantidad y el producto de \"" + String.join(" ", p) + "\", señor. Por favor repita la orden.";
            cantidades.add(q[0]);
            productos.add(prod);
        }
        return inv.venderVarios(cantidades, productos);
    }

    private String agregarAlCarrito(List<String> palabras) {
        List<String> respuestas = new ArrayList<>();
        for (List<String> p : dividirProductos(palabras)) {
            int[] q = Palabras.buscarCantidad(p);
            String prod = EntityExtractor.producto(p, q);
            if (q[0] < 0 || prod.isBlank()) respuestas.add("No entendí \"" + String.join(" ", p) + "\".");
            else respuestas.add(inv.agregarAlCarrito(q[0], prod));
        }
        return String.join(" ", respuestas);
    }

    /** "cambia el precio del cemento a 30 soles" / "el cemento cuesta 30.50". El número se toma del texto original para no perder decimales. */
    private String cambiarPrecio(String cmd, String original) {
        Matcher m = NUMERO.matcher(original);
        String numero = null;
        while (m.find()) numero = m.group(1);
        if (numero == null) return "¿A qué precio lo dejo, señor?";
        String producto = productoSinPalabrasDePrecio(new ArrayList<>(Arrays.asList(cmd.split(" "))));
        if (producto.isBlank()) return "¿De qué producto cambio el precio, señor?";
        return inv.cambiarPrecio(producto, Double.parseDouble(numero.replace(",", ".")));
    }

    private String productoSinPalabrasDePrecio(List<String> palabras) {
        List<String> limpias = palabras.stream().filter(w -> !PALABRAS_PRECIO.contains(w)).toList();
        return EntityExtractor.producto(limpias, new int[]{-1, -1, -1});
    }

    /**
     * ¿La frase trae palabras clave claras de inventario? En ese caso deciden las reglas y el
     * modelo local no interviene (evita que una similitud engañosa cambie una venta por otra cosa).
     */
    public boolean ordenExplicita(String cmdRaw) {
        String cmd = Palabras.norm(cmdRaw);
        if (ES_CALCULO.matcher(cmd).find()) return false;
        return Set.of(RECIBO, CIERRE, LISTA, LISTA_PRECIOS, PRECIO, NUEVA_VENTA, ENTRADA, SALIDA, CONSULTA)
                .stream().anyMatch(p -> p.matcher(cmd).find());
    }

    // ---------------------------------------------------------------- acciones (las usa también el clasificador DJL)

    /** ¿Hay una operación esperando "sí/no"? Mientras tanto no se debe clasificar con el modelo. */
    public boolean esperandoConfirmacion() { return inv.hayPendiente(); }

    /** ¿Hay una venta con varios productos abierta? Mientras tanto se dictan productos y no se clasifica con el modelo. */
    public boolean ventaEnCurso() { return inv.ventaAbierta(); }

    public String registrarEntrada(int cantidad, String producto, Double precio) { return inv.entrada(cantidad, producto, precio, false); }

    public String registrarSalida(int cantidad, String producto) { return inv.salida(cantidad, producto, false); }

    public String consultarStock(String producto) { return inv.consulta(producto); }

    public String reporteDiario() { return inv.cierre(); }

    /**
     * Ejecuta una intención reconocida por el modelo local. Devuelve vacío si faltan datos
     * o el producto no existe, para que JARVIS recurra al procesador de reglas clásico
     * (que es quien crea productos nuevos y pide repetir la orden).
     */
    public Optional<String> ejecutar(IntentResult r) {
        return switch (r.intent()) {
            case REGISTRAR_ENTRADA -> r.tieneCantidad() && conoce(r)
                    ? Optional.of(registrarEntrada(r.cantidad(), r.producto(), r.precio())) : Optional.empty();
            case REGISTRAR_SALIDA -> r.tieneCantidad() && conoce(r)
                    ? Optional.of(registrarSalida(r.cantidad(), r.producto())) : Optional.empty();
            case CONSULTAR_STOCK -> conoce(r) ? Optional.of(consultarStock(r.producto())) : Optional.empty();
            case REPORTE_DIARIO -> Optional.of(reporteDiario());
            case DESCONOCIDO -> Optional.empty();
        };
    }

    private boolean conoce(IntentResult r) { return r.tieneProducto() && inv.conoce(r.producto()); }
}
