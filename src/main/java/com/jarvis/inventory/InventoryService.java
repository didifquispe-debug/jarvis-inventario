package com.jarvis.inventory;

import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Cerebro del inventario: productos, entradas, salidas, consultas, alertas y cierre.
 * Guarda todo en ~/jarvis-inventario.json (o jarvis.inventory.file) para que no se pierda al cerrar.
 */
@Service
public class InventoryService {

    public enum Tipo { ENTRADA, SALIDA }

    /** Operación que quedó esperando un "sí/no" del usuario. */
    public static class Pendiente {
        public Tipo tipo; public Producto producto; public int cantidad; public Double precio;
        public String pregunta;
    }

    /**
     * Una línea vendida. Las líneas con el mismo {@code numero} forman un comprobante.
     * La lista de ventas guarda líneas, así el cierre del día suma por producto.
     */
    public static class Venta {
        public int numero;                 // correlativo del comprobante (0 en ventas antiguas)
        public String fecha; public String hora;
        public String producto; public int cantidad; public String unidad;
        public double precioUnitario; public double monto;
        public Venta() {}
        public Venta(int n, String f, String h, Producto p, int c) {
            numero = n; fecha = f; hora = h; producto = p.nombre; cantidad = c; unidad = p.unidad;
            precioUnitario = p.precio; monto = c * p.precio;
        }
    }

    /** Comprobante de pago: una o varias líneas con su total. numero = 0 si es la venta en curso (aún sin cobrar). */
    public static class Comprobante {
        public int numero; public String fecha; public String hora;
        public List<Venta> items = new ArrayList<>();
        public double total;

        Comprobante(int numero, String fecha, String hora, List<Venta> items) {
            this.numero = numero; this.fecha = fecha; this.hora = hora; this.items = items;
            this.total = items.stream().mapToDouble(v -> v.monto).sum();
        }
    }

    /** Producto y cantidad a vender (en el carrito o en una frase con varios productos). */
    public record Linea(Producto producto, int cantidad) {}

    private final ObjectMapper json = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    private final Path file;

    private List<Producto> productos = new ArrayList<>();
    private List<Venta> ventas = new ArrayList<>();
    private Pendiente pendiente;
    private Comprobante comprobanteNuevo;   // comprobante que la pantalla aún no mostró
    private List<Linea> carrito;            // venta en curso con varios productos (null = no hay)

    public InventoryService(@Value("${jarvis.inventory.file:${user.home}/jarvis-inventario.json}") String archivo) {
        this.file = Paths.get(archivo);
        cargar();
    }

    public synchronized boolean hayPendiente() { return pendiente != null; }

    public synchronized List<Producto> productos() { return new ArrayList<>(productos); }

    // ---------------------------------------------------------------- persistencia

    private static class Datos { public List<Producto> productos = new ArrayList<>(); public List<Venta> ventas = new ArrayList<>(); }

    private void cargar() {
        try {
            if (Files.exists(file)) {
                Datos d = json.readValue(Files.readAllBytes(file), Datos.class);
                productos = d.productos != null ? d.productos : new ArrayList<>();
                ventas = d.ventas != null ? d.ventas : new ArrayList<>();
                return;
            }
        } catch (Exception e) {
            System.err.println("No pude leer el inventario: " + e.getMessage());
        }
        sembrarDemo();
        guardar();
    }

    private void guardar() {
        try {
            Datos d = new Datos(); d.productos = productos; d.ventas = ventas;
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.write(file, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(d));
        } catch (Exception e) {
            System.err.println("No pude guardar el inventario: " + e.getMessage());
        }
    }

    /** Productos de ejemplo para que funcione desde el primer momento (ferretería). */
    private void sembrarDemo() {
        productos = new ArrayList<>(List.of(
                new Producto("Cemento Yura 42.5kg", List.of("cemento", "yura", "bolsa de cemento"), 50, 10, 28.0, "bolsas"),
                new Producto("Cable Vulcanizado", List.of("cable", "cable vulcanizado", "vulcanizado"), 50, 15, 3.5, "metros"),
                new Producto("Disco de corte 4 1/2", List.of("disco de corte", "discos de corte", "disco", "discos"), 8, 5, 4.0, "unidades"),
                new Producto("Clavo con cabeza 2 pulgadas", List.of("clavo", "clavos", "clavos de dos", "clavo de dos"), 200, 50, 0.1, "unidades"),
                new Producto("Foco LED 9W", List.of("foco", "foco led", "led", "bombilla"), 30, 8, 7.5, "unidades")
        ));
    }

    // ---------------------------------------------------------------- operaciones

    /** Registrar entrada de mercadería. Si el producto no existe, lo crea. */
    public synchronized String entrada(int cantidad, String textoProducto, Double precio, boolean confirmado) {
        if (cantidad <= 0) return "No entendí la cantidad, señor. Por favor repita la orden.";
        Match m = buscar(textoProducto);

        if (m == null) {   // producto nuevo
            String nombre = capitaliza(textoProducto);
            Producto p = new Producto(nombre, new ArrayList<>(List.of(Palabras.norm(textoProducto))),
                    0, 5, precio != null ? precio : 0, "unidades");
            productos.add(p);
            p.stock += cantidad;
            if (precio != null) p.precio = precio;
            guardar();
            return "Producto nuevo registrado: " + p.nombre + ". Entrada de " + cantidad + " " + p.unidad + ". Stock actual: " + p.stock + ".";
        }

        if (m.ambiguo && !confirmado) {
            pendiente = pend(Tipo.ENTRADA, m.producto, cantidad, precio,
                    "¿Confirma la entrada de " + cantidad + " de " + m.producto.nombre + "? Diga sí o no.");
            return pendiente.pregunta;
        }
        if (cantidad > 100 && !confirmado) {
            pendiente = pend(Tipo.ENTRADA, m.producto, cantidad, precio,
                    "¿Confirma la entrada de " + cantidad + " " + m.producto.unidad + " de " + m.producto.nombre + "? Diga sí o no.");
            return pendiente.pregunta;
        }

        Producto p = m.producto;
        p.stock += cantidad;
        if (precio != null) p.precio = precio;
        guardar();
        return "Entrada registrada: " + cantidad + " " + p.unidad + " de " + p.nombre + ". Stock actual: " + p.stock + ".";
    }

    /** Registrar salida/venta. Valida stock. */
    public synchronized String salida(int cantidad, String textoProducto, boolean confirmado) {
        if (cantidad <= 0) return "No entendí la cantidad, señor. Por favor repita la orden.";
        Match m = buscar(textoProducto);
        if (m == null) return "No encontré ese producto en el inventario, señor. ¿Lo registramos como entrada primero?";

        if ((m.ambiguo || cantidad > 100) && !confirmado) {
            pendiente = pend(Tipo.SALIDA, m.producto, cantidad, null,
                    "¿Confirma la salida de " + cantidad + " " + m.producto.unidad + " de " + m.producto.nombre + "? Diga sí o no.");
            return pendiente.pregunta;
        }

        return emitir(List.of(new Linea(m.producto, cantidad)));
    }

    /** Vende varios productos dichos en una sola frase ("2 cementos y 3 focos") con un solo comprobante. */
    public synchronized String venderVarios(List<Integer> cantidades, List<String> textos) {
        List<Linea> lineas = new ArrayList<>();
        for (int i = 0; i < textos.size(); i++) {
            int c = cantidades.get(i);
            if (c <= 0) return "No entendí la cantidad de " + textos.get(i) + ", señor. Por favor repita la orden.";
            Match m = buscar(textos.get(i));
            if (m == null) return "No encontré " + textos.get(i) + " en el inventario, señor. No registré la venta.";
            lineas.add(new Linea(m.producto, c));
        }
        return emitir(lineas);
    }

    /**
     * Registra la venta de todas las líneas con un único comprobante: valida el stock de todo
     * antes de descontar nada, así una venta nunca queda a medias.
     */
    private String emitir(List<Linea> lineas) {
        Map<Producto, Integer> pedido = new LinkedHashMap<>();
        for (Linea l : lineas) pedido.merge(l.producto(), l.cantidad(), Integer::sum);
        for (Map.Entry<Producto, Integer> e : pedido.entrySet()) {
            Producto p = e.getKey();
            if (e.getValue() > p.stock)
                return "Atención: stock insuficiente. Solo quedan " + p.stock + " " + p.unidad + " de " + p.nombre + ". No registré la venta.";
        }

        int numero = ventas.stream().mapToInt(v -> v.numero).max().orElse(0) + 1;
        String fecha = LocalDate.now().toString(), hora = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        List<Venta> items = new ArrayList<>();
        for (Linea l : lineas) {
            l.producto().stock -= l.cantidad();
            items.add(new Venta(numero, fecha, hora, l.producto(), l.cantidad()));
        }
        ventas.addAll(items);
        comprobanteNuevo = new Comprobante(numero, fecha, hora, items);
        guardar();

        String msg;
        if (items.size() == 1) {
            Venta v = items.get(0);
            Producto p = lineas.get(0).producto();
            msg = "Venta registrada: " + v.cantidad + " " + v.unidad + " de " + v.producto
                    + String.format(". Total: %.2f soles. Comprobante número %d. Stock actual: %d.", v.monto, numero, p.stock);
        } else {
            msg = String.format("Venta registrada: %d productos. Total: %.2f soles. Comprobante número %d.",
                    items.size(), comprobanteNuevo.total, numero);
        }
        for (Producto p : pedido.keySet()) {
            String quien = items.size() == 1 ? "el producto" : p.nombre;
            if (p.precio <= 0) msg += " Atención: " + quien + " no tiene precio registrado.";
            if (p.stock <= p.stockMin) msg += " Atención: stock bajo" + (items.size() == 1 ? "" : " de " + p.nombre) + ".";
        }
        return msg;
    }

    // ---------------------------------------------------------------- venta con varios productos (carrito)

    public synchronized boolean ventaAbierta() { return carrito != null; }

    public synchronized String abrirVenta() {
        if (carrito != null && !carrito.isEmpty())
            return "Ya hay una venta en curso con " + carrito.size() + " productos. Diga cobrar o cancelar venta.";
        carrito = new ArrayList<>();
        return "Venta nueva, señor. Dígame los productos y, al terminar, diga cobrar.";
    }

    public synchronized String agregarAlCarrito(int cantidad, String textoProducto) {
        if (carrito == null) carrito = new ArrayList<>();
        if (cantidad <= 0) return "No entendí la cantidad, señor.";
        Match m = buscar(textoProducto);
        if (m == null) return "No encontré " + textoProducto + " en el inventario, señor.";
        Producto p = m.producto;
        int yaEnCarrito = carrito.stream().filter(l -> l.producto() == p).mapToInt(Linea::cantidad).sum();
        if (yaEnCarrito + cantidad > p.stock)
            return "Atención: stock insuficiente. Solo quedan " + p.stock + " " + p.unidad + " de " + p.nombre + ".";
        carrito.removeIf(l -> l.producto() == p);
        carrito.add(new Linea(p, yaEnCarrito + cantidad));
        return String.format("Agregado: %d %s de %s, %.2f soles. Total parcial: %.2f soles.",
                cantidad, p.unidad, p.nombre, cantidad * p.precio, totalCarrito());
    }

    public synchronized String quitarDelCarrito(String textoProducto) {
        if (carrito == null || carrito.isEmpty()) return "No hay productos en la venta, señor.";
        Match m = buscar(textoProducto);
        if (m == null || !carrito.removeIf(l -> l.producto() == m.producto))
            return "Ese producto no está en la venta, señor.";
        return String.format("Quitado: %s. Total parcial: %.2f soles.", m.producto.nombre, totalCarrito());
    }

    public synchronized String verCarrito() {
        if (carrito == null || carrito.isEmpty()) return "La venta está vacía, señor.";
        StringBuilder sb = new StringBuilder("Llevamos: ");
        for (int i = 0; i < carrito.size(); i++) {
            Linea l = carrito.get(i);
            sb.append(i == 0 ? "" : ", ").append(l.cantidad()).append(" ").append(l.producto().unidad).append(" de ").append(l.producto().nombre);
        }
        return sb.append(String.format(". Total: %.2f soles.", totalCarrito())).toString();
    }

    public synchronized String cobrar() {
        if (carrito == null || carrito.isEmpty()) return "La venta está vacía, señor. Dígame qué productos lleva el cliente.";
        String msg = emitir(carrito);
        if (msg.startsWith("Venta registrada")) carrito = null;   // si faltó stock, la venta sigue abierta para corregir
        return msg;
    }

    public synchronized String cancelarVenta() {
        if (carrito == null) return "No hay ninguna venta en curso, señor.";
        carrito = null;
        return "Venta cancelada, señor. No se descontó nada del inventario.";
    }

    /** La venta en curso como comprobante preliminar (numero 0), o null si no hay. */
    public synchronized Comprobante carritoActual() {
        if (carrito == null) return null;
        List<Venta> items = carrito.stream().map(l -> new Venta(0, null, null, l.producto(), l.cantidad())).toList();
        return new Comprobante(0, null, null, new ArrayList<>(items));
    }

    private double totalCarrito() { return carrito.stream().mapToDouble(l -> l.cantidad() * l.producto().precio).sum(); }

    // ---------------------------------------------------------------- precios

    public synchronized String listaPrecios() {
        if (productos.isEmpty()) return "El inventario está vacío, señor.";
        StringBuilder sb = new StringBuilder("Lista de precios:\n");
        for (Producto p : productos)
            sb.append(String.format("• %s: %.2f soles (stock: %d %s)%n", p.nombre, p.precio, p.stock, p.unidad));
        return sb.toString().trim();
    }

    public synchronized String precio(String textoProducto) {
        Match m = buscar(textoProducto);
        if (m == null) return "No encontré ese producto, señor.";
        Producto p = m.producto;
        if (p.precio <= 0) return p.nombre + " aún no tiene precio registrado, señor.";
        return String.format("%s cuesta %.2f soles. Quedan %d %s.", p.nombre, p.precio, p.stock, p.unidad);
    }

    public synchronized String cambiarPrecio(String textoProducto, double nuevo) {
        if (nuevo < 0) return "El precio no puede ser negativo, señor.";
        Match m = buscar(textoProducto);
        if (m == null) return "No encontré ese producto, señor.";
        m.producto.precio = nuevo;
        guardar();
        return String.format("Precio actualizado: %s ahora cuesta %.2f soles.", m.producto.nombre, nuevo);
    }

    /** Consulta de stock. */
    public synchronized String consulta(String textoProducto) {
        Match m = buscar(textoProducto);
        if (m == null) return "No encontré ese producto, señor.";
        Producto p = m.producto;
        String msg = "Quedan " + p.stock + " " + p.unidad + " de " + p.nombre + ".";
        if (p.stock <= p.stockMin) msg += " Atención: stock bajo.";
        return msg;
    }

    /** Cierre de caja del día. */
    public synchronized String cierre() {
        String hoy = LocalDate.now().toString();
        List<Venta> delDia = ventas.stream().filter(v -> hoy.equals(v.fecha)).toList();
        if (delDia.isEmpty()) return "Cierre del día: no se registraron ventas hoy, señor.";
        double total = delDia.stream().mapToDouble(v -> v.monto).sum();
        // un comprobante = una venta (las ventas antiguas sin número cuentan una por línea)
        long n = delDia.stream().filter(v -> v.numero > 0).map(v -> v.numero).distinct().count()
                + delDia.stream().filter(v -> v.numero == 0).count();
        Map<String, Integer> porProducto = new HashMap<>();
        for (Venta v : delDia) porProducto.merge(v.producto, v.cantidad, Integer::sum);
        String top = porProducto.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("-");
        return String.format("Cierre del día: se registraron %d ventas por un total de %.2f soles. Producto más vendido: %s.", n, total, top);
    }

    /** Lista general con alertas de stock bajo. */
    public synchronized String inventario() {
        if (productos.isEmpty()) return "El inventario está vacío, señor.";
        StringBuilder sb = new StringBuilder("Inventario actual:\n");
        for (Producto p : productos) {
            sb.append("• ").append(p.nombre).append(": ").append(p.stock).append(" ").append(p.unidad);
            if (p.stock <= p.stockMin) sb.append(" (stock bajo)");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /** Devuelve (y olvida) el comprobante recién emitido, para mostrarlo en pantalla una sola vez. */
    public synchronized Comprobante tomarComprobanteNuevo() {
        Comprobante c = comprobanteNuevo; comprobanteNuevo = null;
        return c;
    }

    /** Vuelve a mostrar el último comprobante emitido. */
    public synchronized String ultimoComprobante() {
        int ultimo = ventas.stream().mapToInt(v -> v.numero).max().orElse(0);
        if (ultimo == 0) return "Aún no se ha emitido ningún comprobante, señor.";
        List<Venta> items = new ArrayList<>(ventas.stream().filter(v -> v.numero == ultimo).toList());
        comprobanteNuevo = new Comprobante(ultimo, items.get(0).fecha, items.get(0).hora, items);
        return String.format("Mostrando el comprobante número %d. Total: %.2f soles.", ultimo, comprobanteNuevo.total);
    }

    /** ¿El texto corresponde a algún producto existente? (búsqueda difusa) */
    public synchronized boolean conoce(String textoProducto) { return buscar(textoProducto) != null; }

    /** Responder a un "sí / no" pendiente. */
    public synchronized String resolver(boolean si) {
        if (pendiente == null) return null;
        Pendiente p = pendiente; pendiente = null;
        if (!si) return "Operación cancelada, señor.";
        return p.tipo == Tipo.ENTRADA
                ? entrada(p.cantidad, p.producto.nombre, p.precio, true)
                : salida(p.cantidad, p.producto.nombre, true);
    }

    // ---------------------------------------------------------------- búsqueda difusa

    private static class Match { Producto producto; boolean ambiguo; Match(Producto p, boolean a) { producto = p; ambiguo = a; } }

    private Match buscar(String texto) {
        String q = Palabras.norm(texto);
        if (q.isBlank()) return null;
        Producto mejor = null, segundo = null;
        double sMejor = 0, sSegundo = 0;
        for (Producto p : productos) {
            double s = puntaje(q, p);
            if (s > sMejor) { sSegundo = sMejor; segundo = mejor; sMejor = s; mejor = p; }
            else if (s > sSegundo) { sSegundo = s; segundo = p; }
        }
        if (mejor == null || sMejor < 0.34) return null;
        boolean ambiguo = segundo != null && (sMejor - sSegundo) < 0.12 && sSegundo >= 0.34;
        return new Match(mejor, ambiguo);
    }

    /** Similitud entre la consulta y un producto (nombre + alias). */
    private double puntaje(String q, Producto p) {
        double best = sim(q, Palabras.norm(p.nombre));
        for (String a : p.alias) best = Math.max(best, sim(q, Palabras.norm(a)));
        return best;
    }

    private double sim(String q, String cand) {
        if (cand.isBlank()) return 0;
        if (cand.contains(q) || q.contains(cand)) return 0.95;
        Set<String> qt = tokens(q), ct = tokens(cand);
        if (qt.isEmpty() || ct.isEmpty()) return 0;
        int inter = 0;
        for (String a : qt) for (String b : ct) if (tokenEq(a, b)) { inter++; break; }
        return (double) inter / qt.size();
    }

    private static final Set<String> STOP = Set.of("de", "del", "la", "el", "los", "las", "un", "una", "unos", "unas", "a");

    private Set<String> tokens(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : s.split(" ")) if (!t.isBlank() && !STOP.contains(t)) out.add(t);
        return out;
    }

    private boolean tokenEq(String a, String b) {
        if (a.equals(b)) return true;
        if (a.length() <= 2 || b.length() <= 2) return false;   // tokens muy cortos: solo exactos
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

    private Pendiente pend(Tipo t, Producto p, int c, Double precio, String pregunta) {
        Pendiente x = new Pendiente(); x.tipo = t; x.producto = p; x.cantidad = c; x.precio = precio; x.pregunta = pregunta;
        return x;
    }

    private String capitaliza(String s) {
        String n = s.trim();
        return n.isEmpty() ? n : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
