package com.jarvis.inventory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.security.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Cerebro del inventario: productos, entradas, salidas, consultas, alertas, correcciones y cierre.
 * Guarda todo en ~/jarvis-inventario.json (o jarvis.inventory.file) para que no se pierda al cerrar:
 * <ul>
 *   <li>Guardado atómico: se escribe un .tmp y se reemplaza el archivo de una vez, así un corte de
 *       luz a mitad nunca deja el inventario a medias. Antes se copia la versión anterior a .bak.</li>
 *   <li>Si al arrancar el archivo está dañado, se recupera del .bak (el dañado se conserva aparte).</li>
 *   <li>Cada cambio queda en el historial de movimientos; las ventas antiguas se archivan por mes.</li>
 * </ul>
 * El "sí/no" pendiente, la venta en curso y el comprobante por mostrar son de cada sesión
 * (dispositivo/pestaña), no globales: ver {@link ClientSession}.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    public enum Tipo { ENTRADA, SALIDA, ANULAR, ELIMINAR }

    /** Operación que quedó esperando un "sí/no" del usuario. */
    public static class Pendiente {
        public Tipo tipo; public Producto producto; public BigDecimal cantidad; public BigDecimal precio; public String unidad;
        public int numero;                 // comprobante a anular
        public String pregunta;
    }

    /**
     * Una línea vendida. Las líneas con el mismo {@code numero} forman un comprobante.
     * La lista de ventas guarda líneas, así el cierre del día suma por producto.
     */
    public static class Venta {
        public int numero;                 // correlativo del comprobante (0 en ventas antiguas)
        public String fecha; public String hora;
        public String producto; public BigDecimal cantidad; public String unidad;
        public BigDecimal precioUnitario; public BigDecimal monto;
        public boolean anulada;            // anulada: no cuenta en el cierre, el stock se devolvió
        public Venta() {}
        public Venta(int n, String f, String h, Producto p, BigDecimal c) {
            numero = n; fecha = f; hora = h; producto = p.nombre; cantidad = Num.cantidad(c); unidad = p.unidad;
            precioUnitario = p.precio; monto = Num.monto(p.precio, c);
        }
        void normalizar() {
            cantidad = Num.cantidad(cantidad == null ? BigDecimal.ZERO : cantidad);
            precioUnitario = Num.dinero(precioUnitario == null ? BigDecimal.ZERO : precioUnitario);
            monto = Num.dinero(monto == null ? Num.monto(precioUnitario, cantidad) : monto);
        }
    }

    /** Comprobante de pago: una o varias líneas con su total. numero = 0 si es la venta en curso (aún sin cobrar). */
    public static class Comprobante {
        public int numero; public String fecha; public String hora;
        public List<Venta> items = new ArrayList<>();
        public BigDecimal total;
        public boolean anulado;

        Comprobante(int numero, String fecha, String hora, List<Venta> items) {
            this.numero = numero; this.fecha = fecha; this.hora = hora; this.items = items;
            this.total = items.stream().map(v -> v.monto).reduce(Num.dinero(BigDecimal.ZERO), BigDecimal::add);
            this.anulado = !items.isEmpty() && items.stream().allMatch(v -> v.anulada);
        }
    }

    /** Producto y cantidad a vender (en el carrito o en una frase con varios productos). */
    public record Linea(Producto producto, BigDecimal cantidad) {}

    /** Resultado de una corrección (la usan la voz y la API REST, que necesita saber si falló). */
    public enum Estado { OK, ERROR, NO_ENCONTRADO }
    public record Resultado(Estado estado, String mensaje) {
        static Resultado ok(String m) { return new Resultado(Estado.OK, m); }
        static Resultado error(String m) { return new Resultado(Estado.ERROR, m); }
        static Resultado noEncontrado(String m) { return new Resultado(Estado.NO_ENCONTRADO, m); }
        public boolean ok() { return estado == Estado.OK; }
    }

    /** Lo que es de cada dispositivo/pestaña. */
    private static class Sesion {
        Pendiente pendiente;
        List<Linea> carrito;              // venta en curso con varios productos (null = no hay)
        Comprobante comprobanteNuevo;     // comprobante que la pantalla aún no mostró
        long usado;
    }

    private static final long SESION_INACTIVA_MS = 4 * 60 * 60 * 1000L;
    private static final BigDecimal CONFIRMAR_DESDE = BigDecimal.valueOf(100);
    private static final DateTimeFormatter SELLO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObjectMapper json = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path file, bak, tmp;
    private final Historial historial;
    private final int diasVentas;

    private List<Producto> productos = new ArrayList<>();
    private List<Venta> ventas = new ArrayList<>();
    private int ultimoNumero;                       // último comprobante emitido (aunque su venta ya esté archivada)
    private boolean archivoValido = true;           // false si el archivo en disco está dañado: no se copia a .bak
    private LocalDate compactadoEl;
    private final Map<String, Sesion> sesiones = new HashMap<>();
    private final List<Movimiento> porRegistrar = new ArrayList<>();

    public InventoryService(@Value("${jarvis.inventory.file:${user.home}/jarvis-inventario.json}") String archivo,
                            @Value("${jarvis.inventory.dias-ventas:90}") int diasVentas) {
        this.file = Paths.get(archivo);
        String nombre = file.getFileName().toString();
        this.bak = file.resolveSibling(nombre + ".bak");
        this.tmp = file.resolveSibling(nombre + ".tmp");
        this.historial = new Historial(file.resolveSibling(nombre.replaceFirst("\\.json$", "") + "-historial"));
        this.diasVentas = Math.max(1, diasVentas);
        cargar();
    }

    public synchronized boolean hayPendiente() { return sesion().pendiente != null; }

    public synchronized List<Producto> productos() { return new ArrayList<>(productos); }

    /** Carpeta de los archivos de historial (movimientos y ventas archivadas). */
    public Path carpetaHistorial() { return historial.dir(); }

    // ---------------------------------------------------------------- sesiones

    private Sesion sesion() {
        long ahora = System.currentTimeMillis();
        sesiones.values().removeIf(s -> ahora - s.usado > SESION_INACTIVA_MS);   // pestañas que ya no vuelven
        Sesion s = sesiones.computeIfAbsent(ClientSession.id(), k -> new Sesion());
        s.usado = ahora;
        return s;
    }

    // ---------------------------------------------------------------- persistencia

    private static class Datos {
        public List<Producto> productos = new ArrayList<>();
        public List<Venta> ventas = new ArrayList<>();
        public int ultimoNumero;
    }

    private void cargar() {
        if (Files.exists(file)) {
            Datos d = leer(file);
            if (d == null) {
                archivoValido = false;
                Path copia = conservarDanado();
                d = Files.exists(bak) ? leer(bak) : null;
                if (d != null)
                    log.warn("El inventario {} estaba dañado: lo recuperé del respaldo {}. El dañado quedó en {}.", file, bak, copia);
                else
                    log.error("El inventario {} está dañado y no hay respaldo válido. El dañado quedó en {}. "
                            + "Empiezo con el inventario vacío.", file, copia);
            }
            if (d != null) {
                usar(d);
                if (compactar() || !archivoValido) guardar();
                return;
            }
        }
        guardar();   // primera vez (o dañado sin respaldo): inventario vacío, sin productos de ejemplo
    }

    private Datos leer(Path p) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            if (bytes.length == 0) throw new IOException("archivo vacío");
            Datos d = json.readValue(bytes, Datos.class);
            if (d == null) throw new IOException("sin contenido");
            return d;
        } catch (Exception e) {
            log.warn("No pude leer {}: {}", p, e.getMessage());
            return null;
        }
    }

    private void usar(Datos d) {
        productos = d.productos != null ? new ArrayList<>(d.productos) : new ArrayList<>();
        ventas = d.ventas != null ? new ArrayList<>(d.ventas) : new ArrayList<>();
        productos.removeIf(Objects::isNull);
        ventas.removeIf(Objects::isNull);
        productos.forEach(Producto::normalizar);
        ventas.forEach(Venta::normalizar);
        ultimoNumero = Math.max(d.ultimoNumero, ventas.stream().mapToInt(v -> v.numero).max().orElse(0));
    }

    /** Copia el archivo dañado aparte (no se pierde nada: se puede revisar o reparar a mano). */
    private Path conservarDanado() {
        Path copia = file.resolveSibling(file.getFileName() + ".danado-" + LocalDateTime.now().format(SELLO));
        try {
            Files.copy(file, copia, StandardCopyOption.REPLACE_EXISTING);
            return copia;
        } catch (IOException e) {
            log.error("No pude copiar el inventario dañado: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Guardado atómico: 1) escribe todo en .tmp y lo fuerza a disco, 2) copia el archivo actual
     * (que está sano) a .bak, 3) reemplaza el archivo por el .tmp con un solo movimiento atómico.
     * En ningún momento existe un inventario escrito a medias.
     */
    private boolean guardar() {
        try {
            Datos d = new Datos(); d.productos = productos; d.ventas = ventas; d.ultimoNumero = ultimoNumero;
            byte[] bytes = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(d);
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) ch.write(buf);
                ch.force(true);
            }
            if (archivoValido && Files.exists(file)) Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
            mover(tmp, file);
            archivoValido = true;
            return true;
        } catch (Exception e) {
            log.error("No pude guardar el inventario en {}: {}", file, e.toString());
            return false;
        }
    }

    private static void mover(Path origen, Path destino) throws IOException {
        for (int intento = 1; ; intento++) {
            try {
                try {
                    Files.move(origen, destino, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(origen, destino, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (FileSystemException e) {
                // OneDrive o el antivirus pueden tener el archivo abierto un instante: se reintenta
                if (intento >= 5) throw e;
                try { Thread.sleep(100L * intento); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
            }
        }
    }

    /** Guarda y escribe los movimientos pendientes. Si no se pudo guardar, lo dice en la respuesta. */
    private String persistir(String msg) {
        boolean ok = guardar();
        for (Movimiento m : porRegistrar)
            historial.registrar(ok ? m : new Movimiento(m.fecha(), m.tipo(), m.producto(), m.cantidad(),
                    m.detalle() + " (NO SE PUDO GUARDAR)", m.usuario()));
        porRegistrar.clear();
        return ok ? msg : msg + " Atención: no pude guardarlo en disco, señor. Revise jarvis.log.";
    }

    private void registrar(String tipo, Producto p, BigDecimal cantidad, String detalle) {
        porRegistrar.add(new Movimiento(LocalDateTime.now().withNano(0).toString(), tipo, p.nombre,
                cantidad == null ? null : Num.cantidad(cantidad), detalle, ClientSession.usuario()));
    }

    /**
     * Pasa al archivo histórico (ventas-AAAA-MM.jsonl) las ventas de hace más de
     * jarvis.inventory.dias-ventas días, para que el archivo principal no crezca sin límite.
     * Se hace al arrancar y una vez al día. Si no se pudo archivar, no se borra nada.
     */
    private boolean compactar() {
        LocalDate hoy = LocalDate.now();
        compactadoEl = hoy;
        String limite = hoy.minusDays(diasVentas).toString();   // fechas ISO: se comparan como texto
        List<Venta> viejas = ventas.stream().filter(v -> v.fecha != null && v.fecha.compareTo(limite) < 0).toList();
        if (viejas.isEmpty()) return false;
        try {
            historial.archivarVentas(viejas);
        } catch (IOException e) {
            log.error("No pude archivar las ventas antiguas en {}: {}", historial.dir(), e.toString());
            return false;
        }
        Set<Venta> fuera = Collections.newSetFromMap(new IdentityHashMap<>());
        fuera.addAll(viejas);
        ventas.removeIf(fuera::contains);
        log.info("Archivé {} líneas de venta anteriores a {} en {}.", viejas.size(), limite, historial.dir());
        return true;
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    // ---------------------------------------------------------------- operaciones

    /** Si la unidad del producto no admite decimales y la cantidad los trae, devuelve el aviso; si no, null. */
    private static String errorDecimales(Producto p, BigDecimal cantidad) {
        if (Num.tieneDecimales(cantidad) && !p.admiteDecimales())
            return p.nombre + " no admite cantidades con decimales (se maneja en " + p.unidad + "), señor. No registré nada.";
        return null;
    }

    private static boolean cantidadInvalida(BigDecimal c) { return c == null || c.signum() <= 0; }

    /** Registrar entrada de mercadería. Si el producto no existe, lo crea (con la unidad dicha, si la hay). */
    public synchronized String entrada(BigDecimal cantidad, String textoProducto, BigDecimal precio, String unidad, boolean confirmado) {
        if (cantidadInvalida(cantidad)) return "No entendí la cantidad, señor. Por favor repita la orden.";
        cantidad = Num.cantidad(cantidad);
        Match m = buscar(textoProducto);

        if (m == null) {   // producto nuevo
            Producto p = new Producto(capitaliza(textoProducto), new ArrayList<>(List.of(Palabras.norm(textoProducto))),
                    BigDecimal.ZERO, bd("5"), precio != null ? precio : BigDecimal.ZERO, unidad != null ? unidad : "unidades");
            String error = errorDecimales(p, cantidad);
            if (error != null) return error;
            productos.add(p);
            p.stock = Num.cantidad(p.stock.add(cantidad));
            registrar("NUEVO_PRODUCTO", p, null, "unidad " + p.unidad + ", precio " + p.precio);
            registrar("ENTRADA", p, cantidad, "stock " + Num.txt(p.stock));
            return persistir("Producto nuevo registrado: " + p.nombre + ". Entrada de " + Num.txt(cantidad) + " " + p.unidad
                    + ". Stock actual: " + Num.txt(p.stock) + ".");
        }

        if (m.empate()) return cualDe(m);
        String error = errorDecimales(m.producto, cantidad);
        if (error != null) return error;
        if ((m.ambiguo || cantidad.compareTo(CONFIRMAR_DESDE) > 0) && !confirmado) {
            String pregunta = m.ambiguo
                    ? "¿Confirma la entrada de " + Num.txt(cantidad) + " de " + m.producto.nombre + "? Diga sí o no."
                    : "¿Confirma la entrada de " + Num.txt(cantidad) + " " + m.producto.unidad + " de " + m.producto.nombre + "? Diga sí o no.";
            return preguntar(pend(Tipo.ENTRADA, m.producto, cantidad, precio, pregunta));
        }

        Producto p = m.producto;
        p.stock = Num.cantidad(p.stock.add(cantidad));
        registrar("ENTRADA", p, cantidad, "stock " + Num.txt(p.stock));
        if (precio != null && precio.compareTo(p.precio) != 0) {
            registrar("PRECIO", p, null, p.precio + " -> " + Num.dinero(precio));
            p.precio = Num.dinero(precio);
        }
        return persistir("Entrada registrada: " + Num.txt(cantidad) + " " + p.unidad + " de " + p.nombre
                + ". Stock actual: " + Num.txt(p.stock) + ".");
    }

    /** Registrar salida/venta. Valida stock. */
    public synchronized String salida(BigDecimal cantidad, String textoProducto, boolean confirmado) {
        if (cantidadInvalida(cantidad)) return "No entendí la cantidad, señor. Por favor repita la orden.";
        cantidad = Num.cantidad(cantidad);
        Match m = buscar(textoProducto);
        if (m == null) return "No encontré ese producto en el inventario, señor. ¿Lo registramos como entrada primero?";
        if (m.empate()) return cualDe(m);
        String error = errorDecimales(m.producto, cantidad);
        if (error != null) return error;

        if ((m.ambiguo || cantidad.compareTo(CONFIRMAR_DESDE) > 0) && !confirmado)
            return preguntar(pend(Tipo.SALIDA, m.producto, cantidad, null,
                    "¿Confirma la salida de " + Num.txt(cantidad) + " " + m.producto.unidad + " de " + m.producto.nombre + "? Diga sí o no."));

        return emitir(List.of(new Linea(m.producto, cantidad)));
    }

    /** Vende varios productos dichos en una sola frase ("2 poleras y 3 gorras") con un solo comprobante. */
    public synchronized String venderVarios(List<BigDecimal> cantidades, List<String> textos) {
        List<Linea> lineas = new ArrayList<>();
        for (int i = 0; i < textos.size(); i++) {
            BigDecimal c = cantidades.get(i);
            if (cantidadInvalida(c)) return "No entendí la cantidad de " + textos.get(i) + ", señor. Por favor repita la orden.";
            Match m = buscar(textos.get(i));
            if (m == null) return "No encontré " + textos.get(i) + " en el inventario, señor. No registré la venta.";
            if (m.empate()) return cualDe(m) + " No registré la venta.";
            lineas.add(new Linea(m.producto, Num.cantidad(c)));
        }
        return emitir(lineas);
    }

    /**
     * Registra la venta de todas las líneas con un único comprobante: valida el stock de todo
     * antes de descontar nada, así una venta nunca queda a medias.
     */
    private String emitir(List<Linea> lineas) {
        Map<Producto, BigDecimal> pedido = new LinkedHashMap<>();
        for (Linea l : lineas) pedido.merge(l.producto(), l.cantidad(), BigDecimal::add);
        for (Map.Entry<Producto, BigDecimal> e : pedido.entrySet()) {
            Producto p = e.getKey();
            if (!productos.contains(p)) return p.nombre + " ya no existe en el inventario, señor. No registré la venta.";
            String error = errorDecimales(p, e.getValue());
            if (error != null) return error;
            if (e.getValue().compareTo(p.stock) > 0)
                return "Atención: stock insuficiente. Solo quedan " + Num.txt(p.stock) + " " + p.unidad + " de " + p.nombre + ". No registré la venta.";
        }
        if (!LocalDate.now().equals(compactadoEl)) compactar();   // primera venta del día

        int numero = ++ultimoNumero;
        String fecha = LocalDate.now().toString(), hora = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        List<Venta> items = new ArrayList<>();
        for (Linea l : lineas) {
            Producto p = l.producto();
            p.stock = Num.cantidad(p.stock.subtract(l.cantidad()));
            items.add(new Venta(numero, fecha, hora, p, l.cantidad()));
            registrar("VENTA", p, l.cantidad(), "comprobante " + numero + ", stock " + Num.txt(p.stock));
        }
        ventas.addAll(items);
        Comprobante c = new Comprobante(numero, fecha, hora, items);
        sesion().comprobanteNuevo = c;

        String msg;
        if (items.size() == 1) {
            Venta v = items.get(0);
            Producto p = lineas.get(0).producto();
            msg = "Venta registrada: " + Num.txt(v.cantidad) + " " + v.unidad + " de " + v.producto
                    + String.format(". Total: %.2f soles. Comprobante número %d. Stock actual: %s.", v.monto, numero, Num.txt(p.stock));
        } else {
            msg = String.format("Venta registrada: %d productos. Total: %.2f soles. Comprobante número %d.", items.size(), c.total, numero);
        }
        for (Producto p : pedido.keySet()) {
            String quien = items.size() == 1 ? "el producto" : p.nombre;
            if (p.precio.signum() <= 0) msg += " Atención: " + quien + " no tiene precio registrado.";
            if (p.stockBajo()) msg += " Atención: stock bajo" + (items.size() == 1 ? "" : " de " + p.nombre) + ".";
        }
        return persistir(msg);
    }

    // ---------------------------------------------------------------- venta con varios productos (carrito)

    public synchronized boolean ventaAbierta() { return sesion().carrito != null; }

    public synchronized String abrirVenta() {
        Sesion s = sesion();
        if (s.carrito != null && !s.carrito.isEmpty())
            return "Ya hay una venta en curso con " + s.carrito.size() + " productos. Diga cobrar o cancelar venta.";
        s.carrito = new ArrayList<>();
        return "Venta nueva, señor. Dígame los productos y, al terminar, diga cobrar.";
    }

    public synchronized String agregarAlCarrito(BigDecimal cantidad, String textoProducto) {
        Sesion s = sesion();
        if (s.carrito == null) s.carrito = new ArrayList<>();
        if (cantidadInvalida(cantidad)) return "No entendí la cantidad, señor.";
        cantidad = Num.cantidad(cantidad);
        Match m = buscar(textoProducto);
        if (m == null) return "No encontré " + textoProducto + " en el inventario, señor.";
        if (m.empate()) return cualDe(m);
        Producto p = m.producto;
        String error = errorDecimales(p, cantidad);
        if (error != null) return error;
        BigDecimal yaEnCarrito = s.carrito.stream().filter(l -> l.producto() == p).map(Linea::cantidad).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = Num.cantidad(yaEnCarrito.add(cantidad));
        if (total.compareTo(p.stock) > 0)
            return "Atención: stock insuficiente. Solo quedan " + Num.txt(p.stock) + " " + p.unidad + " de " + p.nombre + ".";
        s.carrito.removeIf(l -> l.producto() == p);
        s.carrito.add(new Linea(p, total));
        return String.format("Agregado: %s %s de %s, %.2f soles. Total parcial: %.2f soles.",
                Num.txt(cantidad), p.unidad, p.nombre, Num.monto(p.precio, cantidad), totalCarrito(s));
    }

    public synchronized String quitarDelCarrito(String textoProducto) {
        Sesion s = sesion();
        if (s.carrito == null || s.carrito.isEmpty()) return "No hay productos en la venta, señor.";
        Match m = buscar(textoProducto);
        if (m == null || !s.carrito.removeIf(l -> l.producto() == m.producto))
            return "Ese producto no está en la venta, señor.";
        return String.format("Quitado: %s. Total parcial: %.2f soles.", m.producto.nombre, totalCarrito(s));
    }

    public synchronized String verCarrito() {
        Sesion s = sesion();
        if (s.carrito == null || s.carrito.isEmpty()) return "La venta está vacía, señor.";
        StringBuilder sb = new StringBuilder("Llevamos: ");
        for (int i = 0; i < s.carrito.size(); i++) {
            Linea l = s.carrito.get(i);
            sb.append(i == 0 ? "" : ", ").append(Num.txt(l.cantidad())).append(" ").append(l.producto().unidad).append(" de ").append(l.producto().nombre);
        }
        return sb.append(String.format(". Total: %.2f soles.", totalCarrito(s))).toString();
    }

    public synchronized String cobrar() {
        Sesion s = sesion();
        if (s.carrito == null || s.carrito.isEmpty()) return "La venta está vacía, señor. Dígame qué productos lleva el cliente.";
        String msg = emitir(s.carrito);
        if (msg.startsWith("Venta registrada")) s.carrito = null;   // si faltó stock, la venta sigue abierta para corregir
        return msg;
    }

    public synchronized String cancelarVenta() {
        Sesion s = sesion();
        if (s.carrito == null) return "No hay ninguna venta en curso, señor.";
        s.carrito = null;
        return "Venta cancelada, señor. No se descontó nada del inventario.";
    }

    /** La venta en curso de esta sesión como comprobante preliminar (numero 0), o null si no hay. */
    public synchronized Comprobante carritoActual() {
        Sesion s = sesion();
        if (s.carrito == null) return null;
        List<Venta> items = s.carrito.stream().map(l -> new Venta(0, null, null, l.producto(), l.cantidad())).toList();
        return new Comprobante(0, null, null, new ArrayList<>(items));
    }

    private BigDecimal totalCarrito(Sesion s) {
        return s.carrito.stream().map(l -> Num.monto(l.producto().precio, l.cantidad())).reduce(Num.dinero(BigDecimal.ZERO), BigDecimal::add);
    }

    // ---------------------------------------------------------------- precios y consultas

    public synchronized String listaPrecios() {
        if (productos.isEmpty()) return "El inventario está vacío, señor.";
        StringBuilder sb = new StringBuilder("Lista de precios:\n");
        for (Producto p : productos)
            sb.append(String.format("• %s: %.2f soles (stock: %s %s)%n", p.nombre, p.precio, Num.txt(p.stock), p.unidad));
        return sb.toString().trim();
    }

    public synchronized String precio(String textoProducto) {
        Match m = buscar(textoProducto);
        if (m == null) return "No encontré ese producto, señor.";
        if (m.empate()) {
            StringBuilder sb = new StringBuilder("Tengo varios parecidos:");
            for (Producto x : m.empatados) sb.append(String.format(" %s cuesta %.2f soles.", x.nombre, x.precio));
            return sb.toString();
        }
        Producto p = m.producto;
        if (p.precio.signum() <= 0) return p.nombre + " aún no tiene precio registrado, señor.";
        return String.format("%s cuesta %.2f soles. Quedan %s %s.", p.nombre, p.precio, Num.txt(p.stock), p.unidad);
    }

    public synchronized String cambiarPrecio(String textoProducto, BigDecimal nuevo) {
        return editarProducto(textoProducto, false, nuevo, null, null).mensaje();
    }

    public synchronized String cambiarStockMinimo(String textoProducto, BigDecimal minimo) {
        return editarProducto(textoProducto, false, null, minimo, null).mensaje();
    }

    public synchronized String cambiarUnidad(String textoProducto, String unidad) {
        return editarProducto(textoProducto, false, null, null, unidad).mensaje();
    }

    public synchronized String stockMinimo(String textoProducto) {
        Match m = buscar(textoProducto);
        if (m == null) return "No encontré ese producto, señor.";
        if (m.empate()) return cualDe(m);
        return "El stock mínimo de " + m.producto.nombre + " es " + Num.txt(m.producto.stockMin) + " " + m.producto.unidad + ".";
    }

    /** Consulta de stock. */
    public synchronized String consulta(String textoProducto) {
        Match m = buscar(textoProducto);
        if (m == null) return "No encontré ese producto, señor.";
        if (m.empate()) {   // "¿cuántas zapatillas nike quedan?" -> el stock de cada talla
            StringBuilder sb = new StringBuilder("Tengo varios:");
            for (Producto x : m.empatados) sb.append(" ").append(x.nombre).append(": ").append(Num.txt(x.stock)).append(" ").append(x.unidad).append(".");
            return sb.toString();
        }
        Producto p = m.producto;
        String msg = "Quedan " + Num.txt(p.stock) + " " + p.unidad + " de " + p.nombre + ".";
        if (p.stockBajo()) msg += " Atención: stock bajo.";
        return msg;
    }

    /** Cierre de caja del día (las ventas anuladas no cuentan). */
    public synchronized String cierre() {
        String hoy = LocalDate.now().toString();
        List<Venta> delDia = ventas.stream().filter(v -> hoy.equals(v.fecha)).toList();
        List<Venta> validas = delDia.stream().filter(v -> !v.anulada).toList();
        long anuladas = delDia.stream().filter(v -> v.anulada && v.numero > 0).map(v -> v.numero).distinct().count();
        if (validas.isEmpty())
            return "Cierre del día: no se registraron ventas hoy, señor." + (anuladas > 0 ? " Ventas anuladas: " + anuladas + "." : "");
        BigDecimal total = validas.stream().map(v -> v.monto).reduce(Num.dinero(BigDecimal.ZERO), BigDecimal::add);
        // un comprobante = una venta (las ventas antiguas sin número cuentan una por línea)
        long n = validas.stream().filter(v -> v.numero > 0).map(v -> v.numero).distinct().count()
                + validas.stream().filter(v -> v.numero == 0).count();
        Map<String, BigDecimal> porProducto = new HashMap<>();
        for (Venta v : validas) porProducto.merge(v.producto, v.cantidad, BigDecimal::add);
        String top = porProducto.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("-");
        return String.format("Cierre del día: se registraron %d ventas por un total de %.2f soles. Producto más vendido: %s.", n, total, top)
                + (anuladas > 0 ? " Ventas anuladas: " + anuladas + "." : "");
    }

    /** Lista general con alertas de stock bajo. */
    public synchronized String inventario() {
        if (productos.isEmpty()) return "El inventario está vacío, señor.";
        StringBuilder sb = new StringBuilder("Inventario actual:\n");
        for (Producto p : productos) {
            sb.append("• ").append(p.nombre).append(": ").append(Num.txt(p.stock)).append(" ").append(p.unidad);
            if (p.stockBajo()) sb.append(" (stock bajo)");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /** Devuelve (y olvida) el comprobante recién emitido en esta sesión, para mostrarlo en pantalla una sola vez. */
    public synchronized Comprobante tomarComprobanteNuevo() {
        Sesion s = sesion();
        Comprobante c = s.comprobanteNuevo; s.comprobanteNuevo = null;
        return c;
    }

    /** Vuelve a mostrar el último comprobante emitido. */
    public synchronized String ultimoComprobante() {
        Comprobante c = ultimo();
        if (c == null) return "Aún no se ha emitido ningún comprobante, señor.";
        sesion().comprobanteNuevo = c;
        return String.format("Mostrando el comprobante número %d%s. Total: %.2f soles.", c.numero, c.anulado ? " (ANULADO)" : "", c.total);
    }

    /** El comprobante con el número más alto que sigue en el archivo, o null. */
    private Comprobante ultimo() {
        int n = ventas.stream().mapToInt(v -> v.numero).max().orElse(0);
        return n == 0 ? null : comprobante(n);
    }

    private Comprobante comprobante(int numero) {
        List<Venta> items = new ArrayList<>(ventas.stream().filter(v -> v.numero == numero).toList());
        if (items.isEmpty()) return null;
        return new Comprobante(numero, items.get(0).fecha, items.get(0).hora, items);
    }

    // ---------------------------------------------------------------- correcciones

    /** Voz: pregunta antes de anular el último comprobante (se responde con sí/no). */
    public synchronized String pedirAnularUltimaVenta() {
        Comprobante c = ultimo();
        if (c == null) return "No hay ventas para anular, señor.";
        if (c.anulado) return "El comprobante número " + c.numero + " ya está anulado, señor.";
        Pendiente p = new Pendiente();
        p.tipo = Tipo.ANULAR; p.numero = c.numero;
        p.pregunta = String.format("¿Confirma anular el comprobante número %d por %.2f soles? Diga sí o no.", c.numero, c.total);
        return preguntar(p);
    }

    /** Anula el último comprobante: devuelve el stock y lo deja fuera del cierre (queda en el historial). */
    public synchronized Resultado anularUltimaVenta() {
        Comprobante c = ultimo();
        return c == null ? Resultado.noEncontrado("No hay ventas para anular, señor.") : anular(c.numero);
    }

    private Resultado anular(int numero) {
        Comprobante c = comprobante(numero);
        if (c == null) return Resultado.noEncontrado("No encontré el comprobante número " + numero + ", señor.");
        if (c.anulado) return Resultado.error("El comprobante número " + numero + " ya está anulado, señor.");
        List<String> sinProducto = new ArrayList<>();
        for (Venta v : c.items) {
            v.anulada = true;
            Producto p = porNombre(v.producto);
            if (p == null) { sinProducto.add(v.producto); continue; }
            p.stock = Num.cantidad(p.stock.add(v.cantidad));
            registrar("ANULACION", p, v.cantidad, "comprobante " + numero + ", stock " + Num.txt(p.stock));
        }
        String msg = String.format("Venta anulada: comprobante número %d por %.2f soles. Devolví los productos al stock.", numero, c.total);
        if (!sinProducto.isEmpty()) msg += " Ojo: " + String.join(", ", sinProducto) + " ya no existe, no se devolvió.";
        return Resultado.ok(persistir(msg));
    }

    /**
     * Corrige la cantidad de un producto en el último comprobante (p. ej. se dictó 2 y eran 3).
     * Ajusta el stock por la diferencia. Cantidad 0 quita esa línea del comprobante.
     *
     * @param textoProducto vacío si el comprobante tiene una sola línea
     * @param exacto        true = nombre exacto (API REST); false = búsqueda difusa (voz)
     */
    public synchronized Resultado corregirUltimaVenta(String textoProducto, BigDecimal nueva, boolean exacto) {
        if (nueva == null || nueva.signum() < 0) return Resultado.error("No entendí la nueva cantidad, señor.");
        nueva = Num.cantidad(nueva);
        Comprobante c = ultimo();
        if (c == null) return Resultado.noEncontrado("No hay ventas para corregir, señor.");
        if (c.anulado) return Resultado.error("El comprobante número " + c.numero + " está anulado, señor. No se puede corregir.");

        Venta linea;
        if (textoProducto == null || textoProducto.isBlank()) {
            if (c.items.size() != 1)
                return Resultado.error("El comprobante número " + c.numero + " tiene varios productos. Dígame cuál corrijo, señor.");
            linea = c.items.get(0);
        } else {
            Producto buscado = exacto ? porNombre(textoProducto) : Optional.ofNullable(buscar(textoProducto)).map(x -> x.producto).orElse(null);
            linea = buscado == null ? null : c.items.stream().filter(v -> v.producto.equals(buscado.nombre)).findFirst().orElse(null);
            if (linea == null)
                return Resultado.noEncontrado("Ese producto no está en el comprobante número " + c.numero + ", señor.");
        }
        Producto p = porNombre(linea.producto);
        if (p == null) return Resultado.error(linea.producto + " ya no existe en el inventario, señor. No puedo corregir su stock.");
        String error = errorDecimales(p, nueva);
        if (error != null) return Resultado.error(error);
        if (nueva.signum() == 0 && c.items.size() == 1)
            return Resultado.error("Es el único producto del comprobante. Para quitarlo, anule la última venta, señor.");

        BigDecimal antes = linea.cantidad;
        BigDecimal diferencia = nueva.subtract(antes);   // + = se vendió más, - = se devuelve al stock
        if (diferencia.compareTo(p.stock) > 0)
            return Resultado.error("Atención: stock insuficiente. Solo quedan " + Num.txt(p.stock) + " " + p.unidad + " de " + p.nombre + ". No corregí nada.");

        p.stock = Num.cantidad(p.stock.subtract(diferencia));
        if (nueva.signum() == 0) {
            ventas.removeIf(v -> v == linea);
        } else {
            linea.cantidad = nueva;
            linea.monto = Num.monto(linea.precioUnitario, nueva);
        }
        registrar("CORRECCION_VENTA", p, nueva, "comprobante " + c.numero + ": antes " + Num.txt(antes) + ", stock " + Num.txt(p.stock));
        Comprobante corregido = comprobante(c.numero);
        sesion().comprobanteNuevo = corregido;
        String que = nueva.signum() == 0 ? "quité " + p.nombre : p.nombre + " ahora es " + Num.txt(nueva) + " " + p.unidad;
        return Resultado.ok(persistir(String.format("Corregí el comprobante número %d: %s. Nuevo total: %.2f soles. Stock actual: %s.",
                c.numero, que, corregido.total, Num.txt(p.stock))));
    }

    /** Cambios de un producto desde la pantalla. Los null no se tocan. */
    public record Cambios(String nombre, BigDecimal precio, BigDecimal stock, BigDecimal stockMin, String unidad, List<String> alias) {
        boolean vacio() {
            return (nombre == null || nombre.isBlank()) && precio == null && stock == null && stockMin == null
                    && (unidad == null || unidad.isBlank()) && alias == null;
        }
    }

    private static final int MAX_NOMBRE = 80, MAX_UNIDAD = 20;

    /**
     * Crea un producto desde la pantalla. Nombre, precio y unidad son obligatorios; el stock inicial,
     * el stock mínimo y los alias son opcionales. No admite dos productos con el mismo nombre
     * (sin importar mayúsculas ni tildes).
     */
    public synchronized Resultado crearProducto(String nombre, BigDecimal precio, BigDecimal stock, String unidad,
                                                BigDecimal stockMin, List<String> alias) {
        String n = limpiarNombre(nombre);
        String u = unidad == null ? "" : unidad.trim().toLowerCase();
        String error = errorAlCrear(n, precio, stock, u, stockMin);
        if (error != null) return Resultado.error(error);
        Producto repetido = mismoNombre(n, null);
        if (repetido != null) return Resultado.error("Ya existe un producto llamado " + repetido.nombre + ". Edítelo en lugar de crear otro.");

        Producto p = nuevo(n, aliasDe(n, alias), precio, stock, u, stockMin, "");
        return Resultado.ok(persistir("Producto registrado: " + p.nombre + ". Stock: " + Num.txt(p.stock) + " " + p.unidad
                + String.format(". Precio: %.2f soles.", p.precio)));
    }

    /** Resumen de una carga masiva. {@code omitidosDetalle}: los que no se registraron por ya existir o venir repetidos. */
    public record ResultadoLote(int agregados, List<ListaProductos.ErrorLinea> omitidosDetalle,
                                List<ListaProductos.ErrorLinea> errores, String mensaje) {
        public int omitidos() { return omitidosDetalle.size(); }
    }

    /**
     * Alta masiva ("Cargar lista"). Valida toda la lista primero con las mismas reglas que el alta de a uno,
     * registra solo las filas válidas y guarda el archivo UNA vez al final. Nunca pisa un producto que ya existe
     * (sin importar mayúsculas ni tildes): lo cuenta como omitido. Si un nombre viene varias veces en la lista,
     * se registra la primera fila válida y se omiten las demás.
     *
     * @param erroresDeLectura errores que ya encontró quien leyó el texto (se devuelven junto a los de aquí)
     */
    public synchronized ResultadoLote crearProductos(List<ListaProductos.Fila> filas, List<ListaProductos.ErrorLinea> erroresDeLectura) {
        List<ListaProductos.ErrorLinea> errores = new ArrayList<>(erroresDeLectura);
        List<ListaProductos.ErrorLinea> omitidos = new ArrayList<>();
        Map<String, Integer> enLista = new HashMap<>();   // nombre normalizado → línea donde se aceptó
        List<ListaProductos.Fila> validas = new ArrayList<>();

        // 1) validar todo, sin tocar el inventario
        for (ListaProductos.Fila f : filas) {
            String n = limpiarNombre(f.nombre());
            String u = f.unidad() == null || f.unidad().isBlank() ? "unidades" : f.unidad().trim().toLowerCase();
            String error = errorAlCrear(n, f.precio(), f.stock(), u, f.stockMin());
            if (error != null) { errores.add(new ListaProductos.ErrorLinea(f.linea(), error)); continue; }
            Producto existe = mismoNombre(n, null);
            if (existe != null) { omitidos.add(new ListaProductos.ErrorLinea(f.linea(), "ya existe: " + existe.nombre)); continue; }
            Integer antes = enLista.putIfAbsent(Palabras.norm(n), f.linea());
            if (antes != null) { omitidos.add(new ListaProductos.ErrorLinea(f.linea(), "repetido: ya viene en la línea " + antes)); continue; }
            validas.add(new ListaProductos.Fila(f.linea(), n, f.precio(), f.stock(), u, f.stockMin()));
        }
        errores.sort(Comparator.comparingInt(ListaProductos.ErrorLinea::linea));

        // 2) registrar los válidos y guardar una sola vez
        for (ListaProductos.Fila f : validas)
            nuevo(f.nombre(), aliasDe(f.nombre(), null), f.precio(), f.stock(), f.unidad(), f.stockMin(), " (carga masiva)");
        String resumen = "Carga de productos: " + validas.size() + (validas.size() == 1 ? " agregado" : " agregados")
                + ", " + omitidos.size() + (omitidos.size() == 1 ? " omitido" : " omitidos")
                + ", " + errores.size() + (errores.size() == 1 ? " con error." : " con errores.");
        return new ResultadoLote(validas.size(), omitidos, errores, validas.isEmpty() ? resumen : persistir(resumen));
    }

    private static String limpiarNombre(String nombre) { return nombre == null ? "" : nombre.trim().replaceAll("\\s+", " "); }

    /** Por qué no se puede crear este producto, o null si se puede. No revisa duplicados. Unidad ya en minúsculas. */
    private static String errorAlCrear(String n, BigDecimal precio, BigDecimal stock, String u, BigDecimal stockMin) {
        if (n.isEmpty()) return "Falta el nombre del producto.";
        if (n.length() > MAX_NOMBRE) return "El nombre es muy largo (máximo " + MAX_NOMBRE + " letras).";
        if (precio == null) return "Falta el precio de " + n + ".";
        if (u.isEmpty()) return "Falta la unidad de " + n + " (unidades, pares, metros, kilos…).";
        if (u.length() > MAX_UNIDAD) return "La unidad es muy larga (máximo " + MAX_UNIDAD + " letras).";
        if (precio.signum() < 0) return "El precio no puede ser negativo.";
        if (stock != null && stock.signum() < 0) return "El stock no puede ser negativo.";
        if (stockMin != null && stockMin.signum() < 0) return "El stock mínimo no puede ser negativo.";
        Producto prueba = new Producto(); prueba.unidad = u;
        if (!prueba.admiteDecimales() && (Num.tieneDecimales(stock) || Num.tieneDecimales(stockMin)))
            return n + " se maneja en " + u + ": el stock no puede tener decimales.";
        return null;
    }

    /** Agrega un producto ya validado y deja su alta (y el stock inicial) en el historial. No guarda el archivo. */
    private Producto nuevo(String n, List<String> alias, BigDecimal precio, BigDecimal stock, String u, BigDecimal stockMin, String nota) {
        Producto p = new Producto(n, alias, stock == null ? BigDecimal.ZERO : stock,
                stockMin == null ? BigDecimal.ZERO : stockMin, precio, u);
        productos.add(p);
        registrar("NUEVO_PRODUCTO", p, null, "unidad " + p.unidad + ", precio " + p.precio + nota);
        if (p.stock.signum() > 0) registrar("ENTRADA", p, p.stock, "stock inicial " + Num.txt(p.stock) + nota);
        return p;
    }

    /**
     * Ajuste rápido de stock desde la pantalla (botones ＋/－): suma o resta sin emitir comprobante.
     * Queda en el historial como AJUSTE. Nombre exacto.
     */
    public synchronized Resultado ajustarStock(String nombre, BigDecimal cantidad) {
        Producto p = porNombre(nombre);
        if (p == null) return Resultado.noEncontrado("No encontré ese producto, señor.");
        if (cantidad == null || cantidad.signum() == 0) return Resultado.error("Dígame cuánto sumo o resto, señor.");
        cantidad = Num.cantidad(cantidad);
        String error = errorDecimales(p, cantidad);
        if (error != null) return Resultado.error(error);
        BigDecimal nuevo = Num.cantidad(p.stock.add(cantidad));
        if (nuevo.signum() < 0)
            return Resultado.error("No se puede: solo quedan " + Num.txt(p.stock) + " " + p.unidad + " de " + p.nombre + ".");
        registrar("AJUSTE", p, cantidad, Num.txt(p.stock) + " -> " + Num.txt(nuevo));
        p.stock = nuevo;
        return Resultado.ok(persistir("Stock de " + p.nombre + ": " + Num.txt(p.stock) + " " + p.unidad + "."
                + (p.stockBajo() ? " Atención: stock bajo." : "")));
    }

    /**
     * Cambia precio, stock mínimo y/o unidad (los null no se tocan).
     *
     * @param exacto true = nombre exacto (API REST); false = búsqueda difusa (voz)
     */
    public synchronized Resultado editarProducto(String nombre, boolean exacto, BigDecimal precio, BigDecimal stockMin, String unidad) {
        return editarProducto(nombre, exacto, new Cambios(null, precio, null, stockMin, unidad, null));
    }

    /** Edita un producto: nombre, precio, stock, stock mínimo, unidad y/o alias. Valida todo antes de cambiar nada. */
    public synchronized Resultado editarProducto(String nombre, boolean exacto, Cambios c) {
        Producto p;
        if (exacto) {
            p = porNombre(nombre);
        } else {
            Match m = buscar(nombre);
            if (m != null && m.empate()) return Resultado.error(cualDe(m));
            p = m == null ? null : m.producto;
        }
        if (p == null) return Resultado.noEncontrado("No encontré ese producto, señor.");
        if (c.vacio()) return Resultado.error("No me dijo qué cambiar de " + p.nombre + ", señor.");
        if (c.precio() != null && c.precio().signum() < 0) return Resultado.error("El precio no puede ser negativo, señor.");
        if (c.stock() != null && c.stock().signum() < 0) return Resultado.error("El stock no puede ser negativo, señor.");
        if (c.stockMin() != null && c.stockMin().signum() < 0) return Resultado.error("El stock mínimo no puede ser negativo, señor.");
        String nuevoNombre = c.nombre() == null ? "" : c.nombre().trim().replaceAll("\\s+", " ");
        if (nuevoNombre.isEmpty() || nuevoNombre.equals(p.nombre)) nuevoNombre = null;
        if (nuevoNombre != null) {
            if (nuevoNombre.length() > MAX_NOMBRE) return Resultado.error("El nombre es muy largo (máximo " + MAX_NOMBRE + " letras).");
            Producto repetido = mismoNombre(nuevoNombre, p);
            if (repetido != null) return Resultado.error("Ya existe un producto llamado " + repetido.nombre + ".");
        }
        String nuevaUnidad = c.unidad() == null ? "" : c.unidad().trim().toLowerCase();
        if (nuevaUnidad.length() > MAX_UNIDAD) return Resultado.error("No entendí la unidad, señor.");
        if (nuevaUnidad.isEmpty() || nuevaUnidad.equals(p.unidad)) nuevaUnidad = null;
        Producto prueba = new Producto(); prueba.unidad = nuevaUnidad != null ? nuevaUnidad : p.unidad;
        BigDecimal stockFinal = c.stock() != null ? c.stock() : p.stock, minFinal = c.stockMin() != null ? c.stockMin() : p.stockMin;
        if (!prueba.admiteDecimales() && (Num.tieneDecimales(stockFinal) || Num.tieneDecimales(minFinal)))
            return Resultado.error(nuevaUnidad != null
                    ? p.nombre + " tiene cantidades con decimales; no puede pasar a " + nuevaUnidad + ", señor."
                    : p.nombre + " se maneja en " + p.unidad + ": no admite cantidades con decimales, señor.");

        List<String> hecho = new ArrayList<>();
        if (nuevoNombre != null) {
            String antes = p.nombre;
            registrar("NOMBRE", p, null, antes + " -> " + nuevoNombre);
            p.alias.remove(Palabras.norm(antes));
            p.nombre = nuevoNombre;
            if (!p.alias.contains(Palabras.norm(nuevoNombre))) p.alias.add(Palabras.norm(nuevoNombre));
            for (Venta v : ventas) if (antes.equals(v.producto)) v.producto = nuevoNombre;   // anular/corregir siguen funcionando
            hecho.add(antes + " ahora se llama " + nuevoNombre + ".");
        }
        if (c.alias() != null) {
            p.alias = aliasDe(p.nombre, c.alias());
            registrar("ALIAS", p, null, String.join(", ", p.alias));
        }
        if (c.precio() != null) {
            registrar("PRECIO", p, null, p.precio + " -> " + Num.dinero(c.precio()));
            p.precio = Num.dinero(c.precio());
            hecho.add(String.format("Precio actualizado: %s ahora cuesta %.2f soles.", p.nombre, p.precio));
        }
        String unidadFinal = nuevaUnidad != null ? nuevaUnidad : p.unidad;
        if (c.stock() != null && c.stock().compareTo(p.stock) != 0) {
            BigDecimal nuevo = Num.cantidad(c.stock());
            registrar("AJUSTE", p, Num.cantidad(nuevo.subtract(p.stock)), Num.txt(p.stock) + " -> " + Num.txt(nuevo));
            p.stock = nuevo;
            hecho.add("Stock de " + p.nombre + ": " + Num.txt(p.stock) + " " + unidadFinal + ".");
        }
        if (c.stockMin() != null) {
            registrar("STOCK_MINIMO", p, null, Num.txt(p.stockMin) + " -> " + Num.txt(c.stockMin()));
            p.stockMin = Num.cantidad(c.stockMin());
            hecho.add("Stock mínimo de " + p.nombre + ": " + Num.txt(p.stockMin) + " " + unidadFinal + ".");
        }
        if (nuevaUnidad != null) {
            registrar("UNIDAD", p, null, p.unidad + " -> " + nuevaUnidad);
            p.unidad = nuevaUnidad;
            hecho.add(p.nombre + " ahora se maneja en " + nuevaUnidad + ".");
        }
        if (hecho.isEmpty()) hecho.add("Guardé los cambios de " + p.nombre + ".");
        return Resultado.ok(persistir(String.join(" ", hecho)));
    }

    /** Otro producto (distinto de {@code excepto}) con el mismo nombre sin contar mayúsculas ni tildes, o null. */
    private Producto mismoNombre(String nombre, Producto excepto) {
        String n = Palabras.norm(nombre);
        return productos.stream().filter(p -> p != excepto && Palabras.norm(p.nombre).equals(n)).findFirst().orElse(null);
    }

    /** Alias normalizados y sin repetir; siempre incluye el propio nombre. */
    private static List<String> aliasDe(String nombre, List<String> alias) {
        Set<String> out = new LinkedHashSet<>();
        out.add(Palabras.norm(nombre));
        if (alias != null) for (String a : alias) if (a != null && !Palabras.norm(a).isBlank()) out.add(Palabras.norm(a));
        return new ArrayList<>(out);
    }

    /** Voz: pregunta antes de eliminar un producto (se responde con sí/no). */
    public synchronized String pedirEliminarProducto(String textoProducto) {
        Match m = buscar(textoProducto);
        if (m == null) return "No encontré ese producto, señor.";
        if (m.empate()) return cualDe(m);
        return preguntar(pend(Tipo.ELIMINAR, m.producto, null, null,
                "¿Confirma eliminar " + m.producto.nombre + " del inventario? Quedan " + Num.txt(m.producto.stock) + " "
                        + m.producto.unidad + ". Diga sí o no."));
    }

    /** Quita el producto del inventario (sus ventas pasadas se conservan con su nombre). */
    public synchronized Resultado eliminarProducto(String nombre, boolean exacto) {
        Producto p = exacto ? porNombre(nombre) : Optional.ofNullable(buscar(nombre)).map(x -> x.producto).orElse(null);
        if (p == null) return Resultado.noEncontrado("No encontré ese producto, señor.");
        productos.remove(p);
        for (Sesion s : sesiones.values()) if (s.carrito != null) s.carrito.removeIf(l -> l.producto() == p);
        registrar("ELIMINAR", p, p.stock, "stock al eliminar " + Num.txt(p.stock) + " " + p.unidad);
        return Resultado.ok(persistir("Eliminé " + p.nombre + " del inventario, señor."));
    }

    /** Últimos movimientos del historial, del más reciente al más antiguo. */
    public List<Movimiento> movimientos(int n) {
        synchronized (this) { return historial.ultimos(Math.min(Math.max(n, 1), 1000)); }
    }

    /** ¿El texto corresponde a algún producto existente? (búsqueda difusa) */
    public synchronized boolean conoce(String textoProducto) { return buscar(textoProducto) != null; }

    /** Unidad del producto más parecido (para leer "un par" como 1 par en las zapatillas), o null si no hay. */
    public synchronized String unidadDe(String textoProducto) {
        Match m = buscar(textoProducto);
        return m == null ? null : m.producto.unidad;
    }

    /** Responder a un "sí / no" pendiente de ESTA sesión. */
    public synchronized String resolver(boolean si) {
        Sesion s = sesion();
        if (s.pendiente == null) return null;
        Pendiente p = s.pendiente; s.pendiente = null;
        if (!si) return "Operación cancelada, señor.";
        return switch (p.tipo) {
            case ENTRADA -> productos.contains(p.producto)
                    ? entrada(p.cantidad, p.producto.nombre, p.precio, p.unidad, true) : "Ese producto ya no existe, señor.";
            case SALIDA -> productos.contains(p.producto)
                    ? salida(p.cantidad, p.producto.nombre, true) : "Ese producto ya no existe, señor.";
            case ANULAR -> anular(p.numero).mensaje();
            case ELIMINAR -> productos.contains(p.producto)
                    ? eliminarProducto(p.producto.nombre, true).mensaje() : "Ese producto ya no existe, señor.";
        };
    }

    private String preguntar(Pendiente p) {
        sesion().pendiente = p;
        return p.pregunta;
    }

    // ---------------------------------------------------------------- búsqueda

    private Producto porNombre(String nombre) {
        if (nombre == null) return null;
        String n = nombre.trim();
        return productos.stream().filter(p -> p.nombre.equalsIgnoreCase(n)).findFirst().orElse(null);
    }

    /**
     * Resultado de la búsqueda difusa. {@code empatados} tiene más de un producto cuando varios
     * encajan igual de bien (p. ej. "zapatilla nike" con las tallas 39 y 40): no se adivina, se pregunta.
     */
    private static class Match {
        Producto producto; boolean ambiguo; List<Producto> empatados;
        Match(Producto p, boolean a, List<Producto> e) { producto = p; ambiguo = a; empatados = e; }
        boolean empate() { return empatados.size() > 1; }
    }

    private Match buscar(String texto) {
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
        // el nombre exacto (1.0) nunca es ambiguo: "polera roja m" no duda con "polera roja m manga larga"
        boolean ambiguo = segundo != null && sMejor < 1.0 && (sMejor - sSegundo) < 0.12 && sSegundo >= 0.34;
        double tope = sMejor;
        List<Producto> empatados = puntajes.entrySet().stream()
                .filter(e -> tope - e.getValue() < 1e-9).map(Map.Entry::getKey).toList();
        return new Match(mejor, ambiguo, empatados);
    }

    /** "Tengo varios parecidos: A, B y C. ¿Cuál de ellos?" (cuando la búsqueda empata). */
    private static String cualDe(Match m) {
        List<String> nombres = m.empatados.stream().map(p -> p.nombre).toList();
        String lista = nombres.size() == 1 ? nombres.get(0)
                : String.join(", ", nombres.subList(0, nombres.size() - 1)) + " y " + nombres.get(nombres.size() - 1);
        return "Tengo varios parecidos: " + lista + ". ¿Cuál de ellos, señor? Dígame el nombre completo (con talla o color).";
    }

    /** Similitud entre la consulta y un producto (nombre + alias). */
    private double puntaje(String q, Producto p) {
        double best = sim(q, Palabras.norm(p.nombre));
        for (String a : p.alias) best = Math.max(best, sim(q, Palabras.norm(a)));
        return best;
    }

    private double sim(String q, String cand) {
        if (cand.isBlank()) return 0;
        if (q.equals(cand)) return 1.0;                                   // nombre exacto: gana siempre
        Set<String> qt = tokens(q), ct = tokens(cand);
        if (distintos(qt, ct, InventoryService::esTalla)) return 0;       // la 39 nunca es la 40, la S nunca es la M
        if (distintos(qt, ct, InventoryService::esColor)) return 0;       // ni la roja es la azul
        String qb = " " + q + " ", cb = " " + cand + " ";
        if (cb.contains(qb) || qb.contains(cb)) return 0.95;              // contiene palabras completas ("air 4" no es "air 40")
        if (qt.isEmpty() || ct.isEmpty()) return 0;
        int inter = 0;
        for (String a : qt) for (String b : ct) if (tokenEq(a, b)) { inter++; break; }
        return (double) inter / qt.size();
    }

    /** Tallas de ropa y calzado: números sueltos ("38", "40.5") y letras XS…XXXL. */
    private static final Set<String> TALLAS_LETRA = Set.of("xxs", "xs", "s", "m", "l", "xl", "xxl", "xxxl");

    /** Colores (en masculino y femenino, que es como se dicen según la prenda). */
    private static final Set<String> COLORES = Set.of("rojo", "roja", "rojos", "rojas", "azul", "azules", "verde", "verdes",
            "negro", "negra", "negros", "negras", "blanco", "blanca", "blancos", "blancas", "amarillo", "amarilla",
            "gris", "plomo", "plomos", "rosado", "rosada", "rosa", "morado", "morada", "lila", "celeste", "celestes",
            "beige", "marron", "cafe", "naranja", "crema", "dorado", "dorada", "plateado", "plateada", "turquesa", "vino", "guinda");

    private static boolean esTalla(String t) { return Palabras.esNumero(t) || TALLAS_LETRA.contains(t); }

    private static boolean esColor(String t) { return COLORES.contains(t); }

    /** "roja" y "rojo", "negras" y "negro", "azules" y "azul" son el mismo color. */
    private static String raizColor(String t) {
        if (t.endsWith("es") && t.length() > 4 && "lnr".indexOf(t.charAt(t.length() - 3)) >= 0) t = t.substring(0, t.length() - 2);
        else if (t.endsWith("s")) t = t.substring(0, t.length() - 1);
        return t.replaceFirst("[oa]$", "");
    }

    /**
     * ¿La consulta y el producto dicen una talla (o un color) y no coincide ninguna?
     * Entonces son productos distintos aunque el resto del nombre sea igual.
     */
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

    private Pendiente pend(Tipo t, Producto p, BigDecimal c, BigDecimal precio, String pregunta) {
        Pendiente x = new Pendiente(); x.tipo = t; x.producto = p; x.cantidad = c; x.precio = precio; x.pregunta = pregunta;
        return x;
    }

    private String capitaliza(String s) {
        String n = s.trim();
        return n.isEmpty() ? n : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
