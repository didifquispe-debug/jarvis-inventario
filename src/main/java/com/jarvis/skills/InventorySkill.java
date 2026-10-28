/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.skills;

import com.jarvis.ai.EntityExtractor;
import com.jarvis.ai.IntentResult;
import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.Num;
import com.jarvis.inventory.Palabras;
import com.jarvis.inventory.TipoComprobante;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(-10)
public class InventorySkill implements Skill {

    private final InventoryService inv;

    public InventorySkill(InventoryService inv) { this.inv = inv; }

    @Override public String name() { return "Inventario"; }
    @Override public String example() {
        return "Agrega 10 poleras talla M a 25 soles / Vendí 2 poleras M y 1 zapatilla 40 / Nueva venta … cobrar"
                + " / Lista de precios / ¿Cuánto cuesta la polera M? / ¿Cuántas tengo de zapatilla 40? / Último comprobante / Cierre de caja"
                + " / Anula la última venta / Corrige la última venta: eran 3 poleras / Stock mínimo de la polera M en 5"
                + " / Cambia la unidad de la tela a metros / Elimina el producto polera M";
    }

    private static final Pattern ENTRADA = Pattern.compile("\\b(entro|entraron|llego|llegaron|ingreso|ingresaron|entrada|compre|compramos|recibi|recibieron|agrega|agregar|sumar|repuse)\\b");
    private static final Pattern SALIDA  = Pattern.compile("\\b(salio|salieron|vende|venden|vendeme|vender|vendo|vendemos|vendimos|vendi|vendieron|vendio|vendidas?|vendidos?"
            + "|venta|salida|despacha|despache|se llevaron|resta|restar|saque|sacaron|entregue|cobra|cobrar|cobre|cobramos|compro|compraron)\\b");
    private static final Pattern CONSULTA = Pattern.compile(
            "\\b(stock|quedan|queda|disponibles?)\\b" +
            "|\\b(cuantos|cuantas)\\b" +
            "|\\bcuanto\\b.*\\b(hay|tengo)\\b");
    private static final Pattern ES_CALCULO = Pattern.compile("\\bcuanto es\\b|\\bcalcula\\b");
    private static final Pattern CIERRE = Pattern.compile("\\b(cierre|cierra|reporte|resumen)\\b.*\\b(caja|dia|diario|jornada|ventas)\\b|\\bcierre de caja\\b|\\bcierre del dia\\b");
    private static final Pattern RECIBO = Pattern.compile("\\b(comprobante|recibo|boleta|ticket)\\b");
    private static final Pattern LISTA = Pattern.compile("\\b(inventario|lista de productos|que productos|todo el stock|stock general)\\b");

    private static final Pattern LISTA_PRECIOS = Pattern.compile("\\b(lista de precios|precios)\\b");
    private static final Pattern CAMBIAR_PRECIO = Pattern.compile(
            "\\b(cambia|cambiar|cambiale|actualiza|actualizar|pon|ponle|nuevo)\\b.*\\bprecio\\b" +
            "|\\b(cuesta|cuestan|vale|valen)\\s+\\d");
    private static final Pattern PRECIO = Pattern.compile("\\b(precio|cuesta|cuestan|vale|valen|a como)\\b");
    private static final Pattern NUMERO = Pattern.compile("(\\d+(?:[.,]\\d+)?)");
    private static final Pattern CANTIDAD_DICHA = Pattern.compile("\\d|\\b(un|uno|una|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez"
            + "|once|doce|quince|veinte|veinti\\w+|treinta|cuarenta|cincuenta|cien|ciento|medio|media|docena)\\b");

    private static final String ULTIMA = "\\b(ultima venta|ultimo comprobante|ultima boleta|ultimo recibo)\\b";
    private static final Pattern ANULAR_ULTIMA = Pattern.compile(
            "\\b(anula|anular|anulale|cancela|cancelar|elimina|eliminar|borra|borrar|deshaz|deshacer|revierte|revertir)\\b.*" + ULTIMA);
    private static final Pattern CORREGIR_ULTIMA = Pattern.compile(
            "\\b(corrige|corregir|corrigeme|edita|editar|modifica|modificar|cambia|cambiar|arregla|arreglar)\\b.*" + ULTIMA);
    private static final Pattern STOCK_MINIMO = Pattern.compile("\\b(stock minimo|minimo de stock|minimo)\\b");
    private static final Pattern CAMBIAR_UNIDAD = Pattern.compile(
            "\\b(cambia|cambiar|cambiale|pon|ponle|actualiza|actualizar)\\b.*\\bunidad\\b|\\bse vende (en|por)\\b");
    private static final Pattern UNIDAD_DE = Pattern.compile("\\bunidad (?:de |del |de la |de los |de las )?(.+?) (?:a|en|por) ([a-z]+)$");
    private static final Pattern SE_VENDE = Pattern.compile("^(?:el |la |los |las )?(.+?) se vende (?:en|por) ([a-z]+)$");
    private static final String VERBOS_ELIMINAR = "elimina|eliminar|eliminame|borra|borrar|borrame|suprime|suprimir|quita|quitar|retira|retirar";
    private static final Pattern ELIMINAR_PRODUCTO = Pattern.compile(
            "\\b(" + VERBOS_ELIMINAR + ")\\b.*\\b(producto|del inventario)\\b|\\bda(r)? de baja\\b");
    private static final Pattern NUEVO_INVENTARIO = Pattern.compile(
            "\\b(nuevo inventario|inventario (nuevo|de cero|desde cero)|empezar (el inventario )?de cero"
            + "|vaciar (el |todo el )?inventario|borrar todo el inventario|borra todo el inventario)\\b");
    private static final Pattern ELIMINAR_SUELTO = Pattern.compile("^(elimina|eliminar|eliminame|suprime|suprimir|borra|borrar|borrame)\\b");
    private static final Set<String> PALABRAS_CORRECCION = Set.of("corrige", "corregir", "corrigeme", "edita", "editar",
            "modifica", "modificar", "cambia", "cambiar", "arregla", "arreglar", "ultima", "ultimo", "venta", "comprobante",
            "boleta", "recibo", "eran", "era", "fueron", "fue", "son", "es", "en", "realidad", "no", "sino", "ahora", "deja", "dejalo", "pon");
    private static final Set<String> PALABRAS_MINIMO = Set.of("pon", "ponle", "cambia", "cambiar", "cambiale", "actualiza", "actualizar",
            "define", "fija", "stock", "minimo", "en", "a", "es", "sea", "cual", "dime", "de", "del", "el", "la", "alerta");
    private static final Set<String> PALABRAS_ELIMINAR = Set.of("elimina", "eliminar", "eliminame", "borra", "borrar", "borrame",
            "suprime", "suprimir", "quita", "quitar", "retira", "retirar", "producto", "del",
            "inventario", "da", "dar", "de", "baja", "el", "la", "los", "las", "todo", "toda", "todos", "todas");
    private static final Set<String> PALABRAS_PRECIO = Set.of("precio", "cuesta", "cuestan", "vale", "valen", "cambia", "cambiar",
            "cambiale", "actualiza", "actualizar", "pon", "ponle", "nuevo", "como", "esta", "estan", "en", "cual", "es", "dime", "dame");

    private static final Pattern TIPO_DICHO = Pattern.compile("\\b(?:con |en |por )?(?:una |la )?(boleta|nota de venta)\\b|\\bcon nota\\b");
    private static final Pattern DOCUMENTO_DICHO = Pattern.compile(
            "\\b(?:para |a |al )?(?:el |la )?(?:cliente )?(?:con )?(?:dni|ruc|documento|cliente)(?: numero)? (\\d{11}|\\d{8})\\b");
    private static final Set<String> RELLENO_OPCIONES = Set.of("con", "para", "el", "la", "a", "al", "de", "del", "cliente",
            "venta", "y", "que", "sea", "una", "un", "por", "en", "ponle", "pon", "hazla", "haz", "esta", "es", "mejor");
    private static final Pattern NUEVA_VENTA = Pattern.compile("\\b(nueva venta|otra venta|nuevo cliente|(abre|abrir|inicia|iniciar|empieza|empezar) (una )?venta)\\b");
    private static final Pattern COBRAR = Pattern.compile("\\b(cobra|cobrar|cobrale|cobre|cobramos|eso es todo|es todo|seria todo|listo|termina|terminar|finaliza|finalizar|cierra la venta|cerrar (la )?venta)\\b");
    private static final Pattern CANCELAR_VENTA = Pattern.compile("\\b(cancela|cancelar|anula|anular|borra)\\b.*\\bventa\\b");
    private static final Pattern QUITAR = Pattern.compile("\\b(quita|quitar|quitale|saca|sacar|elimina|eliminar|retira|retirar)\\b");
    private static final Pattern VER_VENTA = Pattern.compile("\\b(cuanto va|cuanto llevo|cuanto llevamos|que llevo|que llevamos|ver (la )?venta|total parcial|el total)\\b");
    private static final Pattern AGREGAR = Pattern.compile("\\b(agrega|agregar|agregale|sumar|anade|anadir)\\b");
    private static final Set<String> SEPARADORES = Set.of("y", "mas", "tambien", "ademas");

    private static final Set<String> SI = Set.of("si", "sip", "claro", "confirmo", "correcto", "dale", "afirmativo", "exacto", "ok", "okay", "confirmado");
    private static final Set<String> NO = Set.of("no", "nop", "cancela", "cancelar", "negativo", "mejor no", "anula", "anular");

    @Override
    public Optional<String> handle(String cmdRaw, String original) {
        String cmd = Palabras.norm(cmdRaw);
        if (inv.hayPendiente()) {
            String c = cmd.trim();
            if (SI.contains(c) || SI.stream().anyMatch(c::startsWith)) return Optional.ofNullable(inv.resolver(true));
            if (NO.contains(c) || NO.stream().anyMatch(c::startsWith)) return Optional.ofNullable(inv.resolver(false));
            return Optional.of("Antes necesito que confirme. Diga sí o no, señor.");
        }

        List<String> palabras = new ArrayList<>(Arrays.asList(cmd.split(" ")));
        Palabras.Cantidad qty = Palabras.buscarCantidad(palabras);

        Optional<String> correccion = correcciones(cmd, palabras);
        if (correccion.isPresent()) return correccion;

        InventoryService.Opciones op = InventoryService.Opciones.POR_DEFECTO;
        if (SALIDA.matcher(cmd).find() || NUEVA_VENTA.matcher(cmd).find() || inv.ventaAbierta()) {
            Dichas d = opcionesDichas(cmd);
            if (!d.opciones().vacia()) {
                op = d.opciones();
                cmd = d.resto();
                palabras = new ArrayList<>(Arrays.asList(cmd.split(" ")));
                qty = Palabras.buscarCantidad(palabras);
            }
        }

        if (NUEVA_VENTA.matcher(cmd).find()) return Optional.of(inv.abrirVenta(op));
        if (inv.ventaAbierta()) {
            if (!op.vacia()) {
                String r = inv.opcionesVenta(op);
                if (soloOpciones(cmd) || r.startsWith("No tengo")) return Optional.of(r);
            }
            if (CANCELAR_VENTA.matcher(cmd).find()) return Optional.of(inv.cancelarVenta());
            if (VER_VENTA.matcher(cmd).find()) return Optional.of(inv.verCarrito());
            if (COBRAR.matcher(cmd).find() && !qty.hay()) return Optional.of(inv.cobrar());
        }

        if (RECIBO.matcher(cmd).find() && !SALIDA.matcher(cmd).find()) return Optional.of(inv.ultimoComprobante());
        if (CIERRE.matcher(cmd).find()) return Optional.of(reporteDiario());
        if (LISTA_PRECIOS.matcher(cmd).find()) return Optional.of(inv.listaPrecios());
        if (CAMBIAR_PRECIO.matcher(cmd).find()) return Optional.of(cambiarPrecio(cmd, original));
        if (PRECIO.matcher(cmd).find()) {
            String producto = productoSinPalabrasDePrecio(palabras);
            return Optional.of(producto.isBlank() ? "¿De qué producto desea saber el precio, señor?" : inv.precio(producto));
        }
        if (LISTA.matcher(cmd).find()) return Optional.of(inv.inventario());

        if (inv.ventaAbierta()) {
            if (QUITAR.matcher(cmd).find()) {
                String producto = EntityExtractor.producto(palabras, qty);
                return Optional.of(producto.isBlank() ? "¿Qué producto quito de la venta, señor?" : inv.quitarDelCarrito(producto));
            }
            boolean entradaReal = ENTRADA.matcher(AGREGAR.matcher(cmd).replaceAll("")).find();
            if (qty.hay() && !entradaReal && !CONSULTA.matcher(cmd).find()) return Optional.of(agregarAlCarrito(palabras));
        }

        if (ES_CALCULO.matcher(cmd).find()) return Optional.empty();

        int iEntrada = posicion(ENTRADA, cmd), iSalida = posicion(SALIDA, cmd);
        boolean esEntrada = iEntrada >= 0 && (iSalida < 0 || iEntrada < iSalida);
        boolean esSalida = iSalida >= 0 && !esEntrada;
        boolean esConsulta = CONSULTA.matcher(cmd).find();

        if (!esEntrada && !esSalida && !esConsulta) return Optional.empty();

        BigDecimal precio = EntityExtractor.precio(cmd);
        String producto = EntityExtractor.producto(palabras, qty);

        if (esEntrada) {
            if (!qty.hay()) return Optional.of("No logré identificar la cantidad, señor. Por favor repita la orden.");
            if (producto.isBlank()) return Optional.of("No logré identificar el producto, señor. Por favor repita la orden.");
            return Optional.of(inv.entrada(cantidad(qty, producto, palabras), producto, precio, EntityExtractor.unidad(palabras), false));
        }
        if (esSalida) {
            List<List<String>> partes = dividirProductos(palabras);
            if (partes.size() > 1) return Optional.of(venderVarios(partes, op));
            if (!qty.hay() || producto.isBlank())
                return Optional.of("No logré identificar la cantidad ni el producto, señor. Por favor repita la orden.");
            return Optional.of(inv.salida(cantidad(qty, producto, palabras), producto, false, op));
        }
        producto = EntityExtractor.producto(palabras, Palabras.Cantidad.NINGUNA);
        if (producto.isBlank()) return Optional.of("¿De qué producto desea saber el stock, señor?");
        return Optional.of(consultarStock(producto));
    }

    private Optional<String> correcciones(String cmd, List<String> palabras) {
        if (ANULAR_ULTIMA.matcher(cmd).find()) return Optional.of(inv.pedirAnularUltimaVenta());
        if (NUEVO_INVENTARIO.matcher(cmd).find()) return Optional.of(inv.pedirNuevoInventario());

        if (CORREGIR_ULTIMA.matcher(cmd).find()) {
            List<String> resto = palabras.stream().filter(w -> !PALABRAS_CORRECCION.contains(w)).toList();
            Palabras.Cantidad q = Palabras.buscarCantidad(resto);
            if (!q.hay()) return Optional.of("¿Cuál es la cantidad correcta, señor? Por ejemplo: corrige la última venta, eran 3 poleras.");
            String producto = EntityExtractor.producto(resto, q);
            return Optional.of(inv.corregirUltimaVenta(producto, cantidad(q, producto, resto), false).mensaje());
        }

        if (ELIMINAR_PRODUCTO.matcher(cmd).find() && (!inv.ventaAbierta() || cmd.contains("del inventario"))) {
            String producto = EntityExtractor.producto(palabras.stream().filter(w -> !PALABRAS_ELIMINAR.contains(w)).toList(),
                    Palabras.Cantidad.NINGUNA);
            return Optional.of(producto.isBlank() ? "¿Qué producto elimino del inventario, señor?" : inv.pedirEliminarProducto(producto));
        }
        if (ELIMINAR_SUELTO.matcher(cmd).find() && !inv.ventaAbierta()) {
            String producto = EntityExtractor.producto(palabras.stream().filter(w -> !PALABRAS_ELIMINAR.contains(w)).toList(),
                    Palabras.Cantidad.NINGUNA);
            boolean esElimina = cmd.startsWith("elimin") || cmd.startsWith("suprim");
            if (producto.isBlank() && esElimina) return Optional.of("¿Qué producto elimino del inventario, señor?");
            if (!producto.isBlank() && (esElimina || inv.conoce(producto))) return Optional.of(inv.pedirEliminarProducto(producto));
        }

        if (CAMBIAR_UNIDAD.matcher(cmd).find()) {
            Matcher m = UNIDAD_DE.matcher(cmd);
            if (!m.find()) m = SE_VENDE.matcher(cmd);
            else m.reset();
            if (!m.find()) return Optional.of("Dígame así, señor: cambia la unidad de la tela a metros.");
            String producto = EntityExtractor.producto(Arrays.asList(m.group(1).split(" ")), Palabras.Cantidad.NINGUNA);
            if (producto.isBlank()) producto = m.group(1);
            return Optional.of(inv.cambiarUnidad(producto, m.group(2)));
        }

        if (STOCK_MINIMO.matcher(cmd).find() && !ES_CALCULO.matcher(cmd).find()) {
            int iNumero = -1;
            for (int i = 0; i < palabras.size(); i++) if (Palabras.esNumero(palabras.get(i))) iNumero = i;
            List<String> resto = new ArrayList<>();
            for (int i = 0; i < palabras.size(); i++) if (i != iNumero && !PALABRAS_MINIMO.contains(palabras.get(i))) resto.add(palabras.get(i));
            String producto = EntityExtractor.producto(resto, Palabras.Cantidad.NINGUNA);
            if (producto.isBlank()) return Optional.of("¿De qué producto, señor? Por ejemplo: stock mínimo de la polera M en 5.");
            if (iNumero < 0) return Optional.of(inv.stockMinimo(producto));
            return Optional.of(inv.cambiarStockMinimo(producto, new BigDecimal(palabras.get(iNumero))));
        }
        return Optional.empty();
    }

    private BigDecimal cantidad(Palabras.Cantidad q, String producto, List<String> palabras) {
        if (!q.enPares()) return q.valor();
        String unidad = inv.unidadDe(producto);
        return q.valorEn(unidad != null ? unidad : EntityExtractor.unidad(palabras));
    }

    private static int posicion(Pattern p, String cmd) {
        Matcher m = p.matcher(cmd);
        return m.find() ? m.start() : -1;
    }

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

    private String venderVarios(List<List<String>> partes, InventoryService.Opciones op) {
        List<BigDecimal> cantidades = new ArrayList<>();
        List<String> productos = new ArrayList<>();
        for (List<String> p : partes) {
            Palabras.Cantidad q = Palabras.buscarCantidad(p);
            String prod = EntityExtractor.producto(p, q);
            if (!q.hay() || prod.isBlank())
                return "No logré identificar la cantidad y el producto de \"" + String.join(" ", p) + "\", señor. Por favor repita la orden.";
            cantidades.add(cantidad(q, prod, p));
            productos.add(prod);
        }
        return inv.venderVarios(cantidades, productos, op);
    }

    record Dichas(InventoryService.Opciones opciones, String resto) {}

    static Dichas opcionesDichas(String cmd) {
        TipoComprobante tipo = null;
        String doc = null, resto = cmd;
        Matcher m = TIPO_DICHO.matcher(resto);
        if (m.find()) {
            tipo = "boleta".equals(m.group(1)) ? TipoComprobante.BOLETA : TipoComprobante.NOTA_VENTA;
            resto = m.replaceAll(" ");
        }
        m = DOCUMENTO_DICHO.matcher(resto);
        if (m.find()) {
            doc = m.group(1);
            resto = m.replaceAll(" ");
        }
        return new Dichas(new InventoryService.Opciones(tipo, doc, null), resto.replaceAll("\\s+", " ").trim());
    }

    private static boolean soloOpciones(String resto) {
        for (String w : resto.split(" ")) if (!w.isBlank() && !RELLENO_OPCIONES.contains(w)) return false;
        return true;
    }

    private String agregarAlCarrito(List<String> palabras) {
        List<String> respuestas = new ArrayList<>();
        for (List<String> p : dividirProductos(palabras)) {
            Palabras.Cantidad q = Palabras.buscarCantidad(p);
            String prod = EntityExtractor.producto(p, q);
            if (!q.hay() || prod.isBlank()) respuestas.add("No entendí \"" + String.join(" ", p) + "\".");
            else respuestas.add(inv.agregarAlCarrito(cantidad(q, prod, p), prod));
        }
        return String.join(" ", respuestas);
    }

    private String cambiarPrecio(String cmd, String original) {
        Matcher m = NUMERO.matcher(original);
        String numero = null;
        while (m.find()) numero = m.group(1);
        if (numero == null) return "¿A qué precio lo dejo, señor?";
        String producto = productoSinPalabrasDePrecio(new ArrayList<>(Arrays.asList(cmd.split(" "))));
        if (producto.isBlank()) return "¿De qué producto cambio el precio, señor?";
        return inv.cambiarPrecio(producto, Num.parse(numero));
    }

    private String productoSinPalabrasDePrecio(List<String> palabras) {
        List<String> limpias = palabras.stream().filter(w -> !PALABRAS_PRECIO.contains(w)).toList();
        return EntityExtractor.producto(limpias, Palabras.Cantidad.NINGUNA);
    }

    public boolean ordenExplicita(String cmdRaw) {
        String cmd = Palabras.norm(cmdRaw);
        if (ES_CALCULO.matcher(cmd).find()) return false;
        return Set.of(RECIBO, CIERRE, LISTA, LISTA_PRECIOS, PRECIO, NUEVA_VENTA, ENTRADA, SALIDA, CONSULTA,
                        ANULAR_ULTIMA, CORREGIR_ULTIMA, STOCK_MINIMO, CAMBIAR_UNIDAD, ELIMINAR_PRODUCTO, ELIMINAR_SUELTO, NUEVO_INVENTARIO)
                .stream().anyMatch(p -> p.matcher(cmd).find());
    }

    public boolean soloConsulta(String cmdRaw) {
        String cmd = Palabras.norm(cmdRaw);
        boolean cambia = Set.of(NUEVA_VENTA, ENTRADA, SALIDA, ANULAR_ULTIMA, CORREGIR_ULTIMA, STOCK_MINIMO, CAMBIAR_UNIDAD,
                        CAMBIAR_PRECIO, ELIMINAR_PRODUCTO, ELIMINAR_SUELTO, NUEVO_INVENTARIO, COBRAR, CANCELAR_VENTA, QUITAR, AGREGAR)
                .stream().anyMatch(p -> p.matcher(cmd).find());
        return !cambia && Set.of(CONSULTA, LISTA, LISTA_PRECIOS, PRECIO).stream().anyMatch(p -> p.matcher(cmd).find());
    }

    public boolean delicadaPorVoz(String cmdRaw) {
        String cmd = Palabras.norm(cmdRaw);
        if (Set.of(ES_CALCULO, NUEVA_VENTA, CIERRE, RECIBO, ANULAR_ULTIMA, ELIMINAR_PRODUCTO, ELIMINAR_SUELTO, NUEVO_INVENTARIO)
                .stream().anyMatch(p -> p.matcher(cmd).find()) || soloConsulta(cmd)) return false;
        if (COBRAR.matcher(cmd).find() && !CANTIDAD_DICHA.matcher(cmd).find()) return false;
        return Set.of(SALIDA, ENTRADA, CORREGIR_ULTIMA, CAMBIAR_PRECIO, STOCK_MINIMO).stream().anyMatch(p -> p.matcher(cmd).find());
    }

    public boolean esperandoConfirmacion() { return inv.hayPendiente(); }

    public boolean ventaEnCurso() { return inv.ventaAbierta(); }

    public String registrarEntrada(BigDecimal cantidad, String producto, BigDecimal precio) {
        return inv.entrada(cantidad, producto, precio, null, false);
    }

    public String registrarSalida(BigDecimal cantidad, String producto) { return inv.salida(cantidad, producto, false); }

    public String consultarStock(String producto) { return inv.consulta(producto); }

    public String reporteDiario() { return inv.cierre(); }

    public Optional<String> ejecutar(IntentResult r) {
        return switch (r.intent()) {
            case REGISTRAR_ENTRADA -> r.tieneCantidad() && conoce(r)
                    ? Optional.of(registrarEntrada(r.cantidadEn(inv.unidadDe(r.producto())), r.producto(), r.precio())) : Optional.empty();
            case REGISTRAR_SALIDA -> r.tieneCantidad() && conoce(r)
                    ? Optional.of(registrarSalida(r.cantidadEn(inv.unidadDe(r.producto())), r.producto())) : Optional.empty();
            case CONSULTAR_STOCK -> conoce(r) ? Optional.of(consultarStock(r.producto())) : Optional.empty();
            case REPORTE_DIARIO -> Optional.of(reporteDiario());
            case DESCONOCIDO -> Optional.empty();
        };
    }

    private boolean conoce(IntentResult r) { return r.tieneProducto() && inv.conoce(r.producto()); }
}
