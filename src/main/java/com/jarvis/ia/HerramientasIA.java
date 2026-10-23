package com.jarvis.ia;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.Palabras;
import com.jarvis.inventory.Producto;
import com.jarvis.security.ClientSession;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/**
 * Lo que el asistente con IA puede hacer en JARVIS. Cada herramienta llama a los mismos métodos que la pantalla
 * y la voz, así que valen las mismas reglas (no vender más de lo que hay, códigos únicos…).
 *
 * <p>Las acciones que no se pueden deshacer (eliminar un producto, empezar un inventario nuevo) tienen un freno
 * en el código, no solo en las instrucciones de la IA: la primera vez que la IA las pide, no se hacen y se le
 * devuelve la pregunta que debe hacer. Solo se ejecutan si la IA las vuelve a pedir en el turno siguiente y lo
 * último que dijo el usuario es un "sí".</p>
 */
public class HerramientasIA {

    static final String CONFIRMAR = "CONFIRMACION_REQUERIDA";
    private static final int MAX_PRODUCTOS = 300;
    private static final Set<String> SI = Set.of("si", "sip", "claro", "confirmo", "correcto", "dale", "afirmativo",
            "exacto", "ok", "okay", "confirmado", "hazlo", "adelante", "de acuerdo", "por supuesto", "si hazlo", "si claro");

    /** Acción irreversible esperando el "sí" del usuario, por sesión. {@code turno}: en qué turno se pidió. */
    private record Pendiente(String accion, String clave, long turno) {}

    private final InventoryService inv;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Pendiente> pendientes = new HashMap<>();

    public HerramientasIA(InventoryService inv) {
        this.inv = inv;
    }

    /** ¿El usuario dijo que sí? ("sí", "sí, hazlo", "claro que sí"…) */
    static boolean esSi(String texto) {
        String t = Palabras.norm(texto).replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || t.startsWith("no ") || t.equals("no")) return false;
        return SI.contains(t) || SI.stream().anyMatch(s -> t.startsWith(s + " ")) || t.endsWith(" que si");
    }

    // ---------------------------------------------------------------- definiciones para la API

    List<Tool> definiciones() {
        return List.of(
                tool("ejecutar_orden", """
                        Ejecuta una orden en el lenguaje propio de JARVIS y devuelve su respuesta. Úsala para vender, \
                        registrar entradas de mercadería, consultar stock o precios, ventas con carrito, comprobantes, \
                        anular o corregir la última venta, cierre de caja, notas, hora, abrir programas o páginas, y \
                        enseñarle palabras. Escribe la orden corta y explícita, por ejemplo: "vendí 2 polera roja M y 1 gorra negra", \
                        "agrega 10 polera roja M a 25 soles", "cuántas tengo de zapatilla nike 40", "nueva venta", "cobrar", \
                        "último comprobante", "anula la última venta", "cierre de caja", "lista de precios", "abre youtube", \
                        "anota llamar al proveedor", "aprende que chompa significa polera". Usa el nombre exacto del producto \
                        cuando lo conozcas. Las ventas se emiten con boleta salvo que se pida otra cosa; para elegir el \
                        comprobante o el cliente (registrado) añade "con nota de venta", "con boleta" o "para el DNI 12345678" \
                        (o "para el RUC …"), por ejemplo "vendí 2 polera roja M con boleta para el DNI 12345678". \
                        Si la respuesta es una pregunta de sí o no, pásasela al usuario tal cual.""",
                        props(Map.of("orden", prop("string", "La orden, en español, como la diría el usuario."))), List.of("orden")),
                tool("ver_productos", """
                        Lista los productos con todos sus datos (stock, unidad, precio, costo, stock mínimo, categoría, \
                        códigos y si tienen stock bajo). Úsala antes de responder preguntas sobre el inventario y antes de \
                        cambios en varios productos. Sin filtro devuelve todos.""",
                        props(Map.of("filtro", prop("string", "Opcional: parte del nombre, una categoría o un código."))), List.of()),
                tool("crear_producto", "Registra un producto nuevo. Nombre, precio y unidad son obligatorios; pregunta si faltan.",
                        props(camposProducto(true)), List.of("nombre", "precio", "unidad")),
                tool("editar_producto", """
                        Cambia datos de un producto existente (nombre exacto). Envía solo lo que cambia. Un texto vacío \
                        borra la categoría o un código. "stock" fija el stock exacto (para sumar mercadería usa ejecutar_orden).""",
                        props(camposProducto(false)), List.of("nombre")),
                tool("eliminar_producto", "Quita un producto del inventario (nombre exacto). Pide confirmación al usuario.",
                        props(Map.of("nombre", prop("string", "Nombre exacto del producto."))), List.of("nombre")),
                tool("nuevo_inventario", """
                        Empieza un inventario nuevo desde cero: quita todos los productos y ventas (antes guarda una copia). \
                        Úsala cuando el usuario quiera un inventario nuevo, vaciar el inventario o empezar de cero. \
                        Pide confirmación al usuario.""",
                        props(Map.of()), List.of()));
    }

    private static Map<String, Map<String, Object>> camposProducto(boolean alta) {
        Map<String, Map<String, Object>> m = new LinkedHashMap<>();
        m.put("nombre", prop("string", alta ? "Nombre con talla o color si aplica, ej. \"Polera roja M\"." : "Nombre exacto actual."));
        if (!alta) m.put("nuevo_nombre", prop("string", "Nuevo nombre."));
        m.put("precio", prop("number", "Precio de venta por unidad, en soles."));
        m.put("unidad", prop("string", "unidades, pares, metros, kilos, litros, cajas…"));
        m.put("stock", prop("number", alta ? "Stock inicial (0 si no se dice)." : "Stock exacto nuevo."));
        m.put("stock_minimo", prop("number", "Avisa cuando el stock llegue a este número."));
        m.put("costo", prop("number", "Lo que le cuesta al negocio cada unidad, en soles."));
        m.put("categoria", prop("string", "Categoría, ej. Calzado."));
        m.put("codigo", prop("string", "Código interno o SKU."));
        m.put("codigo_barras", prop("string", "Código de barras (EAN/UPC)."));
        return m;
    }

    private static Map<String, Object> prop(String tipo, String descripcion) {
        return Map.of("type", tipo, "description", descripcion);
    }

    private static Map<String, Map<String, Object>> props(Map<String, Map<String, Object>> m) { return m; }

    private static Tool tool(String nombre, String descripcion, Map<String, Map<String, Object>> props, List<String> requeridos) {
        Tool.InputSchema.Properties.Builder p = Tool.InputSchema.Properties.builder();
        props.forEach((k, v) -> p.putAdditionalProperty(k, JsonValue.from(v)));
        return Tool.builder().name(nombre).description(descripcion)
                .inputSchema(Tool.InputSchema.builder().properties(p.build()).required(requeridos).build())
                .build();
    }

    // ---------------------------------------------------------------- ejecución

    /**
     * Ejecuta una herramienta pedida por la IA.
     *
     * @param ultimoMensaje lo último que dijo el usuario (para el "sí" de las acciones irreversibles)
     * @param turno         número del turno del usuario en esta conversación
     * @param reglas        el procesador de órdenes clásico de JARVIS (para ejecutar_orden)
     */
    synchronized String ejecutar(String nombre, Map<String, Object> e, String ultimoMensaje, long turno, Function<String, String> reglas) {
        Map<String, Object> in = e == null ? Map.of() : e;
        return switch (nombre) {
            case "ejecutar_orden" -> {
                String orden = texto(in, "orden");
                yield orden == null || orden.isBlank() ? "Falta la orden." : reglas.apply(orden);
            }
            case "ver_productos" -> verProductos(texto(in, "filtro"));
            case "crear_producto" -> inv.crearProducto(texto(in, "nombre"), numero(in, "precio"), numero(in, "stock"),
                    texto(in, "unidad"), numero(in, "stock_minimo"), null, ficha(in)).mensaje();
            case "editar_producto" -> inv.editarProducto(texto(in, "nombre"), true, new InventoryService.Cambios(
                    texto(in, "nuevo_nombre"), numero(in, "precio"), numero(in, "stock"), numero(in, "stock_minimo"),
                    texto(in, "unidad"), null, ficha(in), false)).mensaje();
            case "eliminar_producto" -> {
                String n = texto(in, "nombre");
                Producto p = inv.productos().stream().filter(x -> x.nombre.equalsIgnoreCase(n == null ? "" : n.trim()))
                        .findFirst().orElse(null);
                if (p == null) yield "No existe un producto con ese nombre exacto. Usa ver_productos para encontrarlo.";
                yield conConfirmacion("eliminar_producto", p.nombre, ultimoMensaje, turno,
                        "¿Confirma eliminar " + p.nombre + " del inventario? Quedan " + p.stock.toPlainString() + " " + p.unidad + ".",
                        () -> inv.eliminarProducto(p.nombre, true).mensaje());
            }
            case "nuevo_inventario" -> conConfirmacion("nuevo_inventario", "", ultimoMensaje, turno,
                    inv.resumenAntesDeVaciar(), () -> inv.empezarDeCero().mensaje());
            default -> "Herramienta desconocida: " + nombre;
        };
    }

    /** El freno de las acciones irreversibles (ver la descripción de la clase). */
    private String conConfirmacion(String accion, String clave, String ultimoMensaje, long turno, String pregunta,
                                   java.util.function.Supplier<String> hacer) {
        String sesion = ClientSession.id();
        Pendiente p = pendientes.get(sesion);
        if (p != null && p.accion().equals(accion) && p.clave().equals(clave) && p.turno() < turno && esSi(ultimoMensaje)) {
            pendientes.remove(sesion);
            return hacer.get();
        }
        pendientes.put(sesion, new Pendiente(accion, clave, turno));
        return CONFIRMAR + ": todavía no se hizo. Pregunta al usuario exactamente esto y espera su respuesta: \"" + pregunta
                + "\". Si responde que sí, vuelve a llamar a esta herramienta con los mismos datos; si no, no hagas nada.";
    }

    private String verProductos(String filtro) {
        String f = filtro == null ? "" : Palabras.norm(filtro);
        List<Map<String, Object>> filas = new ArrayList<>();
        int total = 0;
        for (Producto p : inv.productos()) {
            if (!f.isBlank() && !Palabras.norm(p.nombre).contains(f) && !Palabras.norm(p.categoria).contains(f)
                    && !p.tieneCodigo(filtro.trim())) continue;
            total++;
            if (filas.size() >= MAX_PRODUCTOS) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nombre", p.nombre);
            m.put("stock", p.stock);
            m.put("unidad", p.unidad);
            m.put("precio", p.precio);
            if (p.costo != null) m.put("costo", p.costo);
            m.put("stock_minimo", p.stockMin);
            if (p.stockBajo()) m.put("stock_bajo", true);
            if (!p.categoria.isEmpty()) m.put("categoria", p.categoria);
            if (!p.codigo.isEmpty()) m.put("codigo", p.codigo);
            if (!p.codigoBarras.isEmpty()) m.put("codigo_barras", p.codigoBarras);
            filas.add(m);
        }
        if (total == 0) return inv.productos().isEmpty() ? "El inventario está vacío." : "Ningún producto coincide con \"" + filtro + "\".";
        try {
            String lista = json.writeValueAsString(filas);
            return total > filas.size() ? lista + "\n(Se muestran " + filas.size() + " de " + total + "; usa un filtro.)" : lista;
        } catch (Exception ex) {
            return "No pude leer los productos.";
        }
    }

    private static Producto.Ficha ficha(Map<String, Object> in) {
        return new Producto.Ficha(texto(in, "categoria"), texto(in, "codigo"), texto(in, "codigo_barras"), numero(in, "costo"));
    }

    private static String texto(Map<String, Object> in, String k) {
        Object v = in.get(k);
        return v == null ? null : String.valueOf(v);
    }

    /** Número enviado por la IA (puede llegar como número o como texto "12.50"); null si falta o no es número. */
    private static BigDecimal numero(Map<String, Object> in, String k) {
        Object v = in.get(k);
        if (v == null) return null;
        try {
            return new BigDecimal(String.valueOf(v).trim().replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
