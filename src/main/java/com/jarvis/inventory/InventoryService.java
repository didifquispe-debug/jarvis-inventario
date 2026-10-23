package com.jarvis.inventory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.security.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Cerebro del inventario: productos, entradas, salidas, consultas, alertas, correcciones y cierre.
 * Guarda todo en la base de datos ~/jarvis-inventario.db ({@link BaseDatos}): cada operación se guarda
 * entera o no se guarda, con respaldo diario. La primera vez importa el inventario de las versiones
 * anteriores (~/jarvis-inventario.json y su carpeta de historial), que se conservan tal cual.
 * Cada cambio queda en el historial de movimientos.
 * El "sí/no" pendiente, la venta en curso y el comprobante por mostrar son de cada sesión
 * (dispositivo/pestaña), no globales: ver {@link ClientSession}.
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    public enum Tipo { ENTRADA, SALIDA, ANULAR, ELIMINAR, NUEVO_INVENTARIO }

    /** Operación que quedó esperando un "sí/no" del usuario. */
    public static class Pendiente {
        public Tipo tipo; public Producto producto; public BigDecimal cantidad; public BigDecimal precio; public String unidad;
        public int numero;                 // comprobante a anular
        public Opciones opciones;          // tipo de comprobante y cliente de la venta que espera el "sí"
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
        public BigDecimal costoUnitario;   // costo del producto al momento de vender (null = no registrado), para la ganancia
        public boolean anulada;            // anulada: no cuenta en el cierre, el stock se devolvió
        Long id, comprobanteId;            // en la base de datos (no salen en la API)
        public Venta() {}
        public Venta(int n, String f, String h, Producto p, BigDecimal c) {
            numero = n; fecha = f; hora = h; producto = p.nombre; cantidad = Num.cantidad(c); unidad = p.unidad;
            precioUnitario = p.precio; monto = Num.monto(p.precio, c); costoUnitario = p.costo;
        }
        void normalizar() {
            cantidad = Num.cantidad(cantidad == null ? BigDecimal.ZERO : cantidad);
            precioUnitario = Num.dinero(precioUnitario == null ? BigDecimal.ZERO : precioUnitario);
            costoUnitario = Num.dinero(costoUnitario);
            monto = Num.dinero(monto == null ? Num.monto(precioUnitario, cantidad) : monto);
        }
    }

    /**
     * Comprobante de pago (boleta o nota de venta): una o varias líneas con su base, IGV y total.
     * numero = 0 si es la venta en curso (aún sin cobrar). {@code numero} es el número interno de la venta;
     * lo que ve el cliente es {@code codigo} (serie y correlativo, p. ej. B001-00000012).
     */
    public static class Comprobante {
        public int numero; public String fecha; public String hora;
        public String tipo;                // BOLETA o NOTA_VENTA
        public String tipoDescripcion;     // "Boleta de venta electrónica"
        public String serie; public int correlativo;
        public String codigo;              // "B001-00000012" ("" en la venta en curso)
        public ClienteResumen cliente;     // null = clientes varios
        public String modoIgv; public String leyendaIgv;
        public BigDecimal base; public BigDecimal igv; public BigDecimal tasaIgv;
        public List<Venta> items = new ArrayList<>();
        public BigDecimal total;
        public String totalEnLetras;
        public boolean anulado;

        Comprobante(int numero, String fecha, String hora, List<Venta> items, Cabecera cab) {
            this.numero = numero; this.fecha = fecha; this.hora = hora; this.items = items;
            TipoComprobante t = cab.tipo();
            this.tipo = t.name(); this.tipoDescripcion = t.descripcion; this.serie = t.serie;
            this.correlativo = cab.correlativo();
            this.codigo = cab.correlativo() > 0 ? t.codigo(cab.correlativo()) : "";
            this.cliente = cab.clienteDoc() == null || cab.clienteDoc().isBlank() ? null
                    : new ClienteResumen(cab.clienteDoc(), Clientes.tipo(cab.clienteDoc()), cab.clienteNombre());
            this.modoIgv = cab.modo().name(); this.leyendaIgv = cab.modo().leyenda;
            Igv.Totales tot = Igv.calcular(items.stream().map(v -> v.monto).toList(), cab.modo());
            this.base = tot.base(); this.igv = tot.igv(); this.tasaIgv = tot.tasa(); this.total = tot.total();
            this.totalEnLetras = NumeroALetras.importe(total);
            this.anulado = !items.isEmpty() && items.stream().allMatch(v -> v.anulada);
        }

        /** Cómo se dice: "boleta número 12". (No es un getter: no sale en la API.) */
        public String hablado() { return TipoComprobante.valueOf(tipo).hablado + " número " + correlativo; }
    }

    /** Cliente tal como quedó en el comprobante (si después cambia su nombre, el comprobante no cambia). */
    public record ClienteResumen(String documento, String tipoDocumento, String nombre) {}

    /** Datos del comprobante que no están en sus líneas: tipo y correlativo, cliente (copiado al emitir) y modalidad de IGV. */
    record Cabecera(TipoComprobante tipo, int correlativo, Long clienteId, String clienteDoc, String clienteNombre, Igv.Modo modo) {}

    /**
     * Cómo emitir una venta. Un campo null = lo de siempre: el tipo de comprobante y el IGV configurados, sin cliente.
     * {@code clienteDoc}: DNI o RUC de un cliente registrado.
     */
    public record Opciones(TipoComprobante tipo, String clienteDoc, Igv.Modo modo) {
        public static final Opciones POR_DEFECTO = new Opciones(null, null, null);

        /** Estas opciones con lo que trae {@code otras} encima (lo que no trae se conserva). */
        public Opciones con(Opciones otras) {
            if (otras == null) return this;
            return new Opciones(otras.tipo != null ? otras.tipo : tipo,
                    otras.clienteDoc != null ? otras.clienteDoc : clienteDoc, otras.modo != null ? otras.modo : modo);
        }

        public boolean vacia() { return tipo == null && (clienteDoc == null || clienteDoc.isBlank()) && modo == null; }
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
        Opciones opciones = Opciones.POR_DEFECTO;   // tipo de comprobante y cliente de la venta en curso
        Comprobante comprobanteNuevo;     // comprobante que la pantalla aún no mostró
        long usado;
    }

    private static final long SESION_INACTIVA_MS = 4 * 60 * 60 * 1000L;
    private static final BigDecimal CONFIRMAR_DESDE = BigDecimal.valueOf(100);
    /** Desde este total la SUNAT pide identificar al cliente en una boleta. */
    private static final BigDecimal BOLETA_CON_DNI_DESDE = BigDecimal.valueOf(700);
    private static final DateTimeFormatter SELLO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObjectMapper json = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path archivoJson;                 // inventario de versiones anteriores (solo para importarlo)
    private final Almacen almacen;

    private List<Producto> productos = new ArrayList<>();
    private List<Venta> ventas = new ArrayList<>();
    private int ultimoNumero;                       // número interno de la última venta
    private Map<Integer, Cabecera> cabeceras = new HashMap<>();   // por número de venta
    private final Map<TipoComprobante, Integer> correlativos = new EnumMap<>(TipoComprobante.class);
    private final TipoComprobante tipoPorDefecto;
    private final Igv.Modo igvPorDefecto;
    private final Clientes clientes;
    private final Compras compras;
    private final Map<String, Sesion> sesiones = new HashMap<>();
    private final List<Almacen.Pendiente> porRegistrar = new ArrayList<>();

    /**
     * @param archivo la base de datos ({@code .db}) o, como en versiones anteriores, el inventario {@code .json}:
     *                entonces la base es el mismo nombre con {@code .db}, y el JSON se importa si la base está vacía.
     */
    public InventoryService(String archivo) {
        this(archivo, TipoComprobante.BOLETA.name(), Igv.Modo.INCLUIDO.name());
    }

    /**
     * @param tipo comprobante que se emite si no se pide otro: boleta (como el sistema de referencia) o nota de venta
     * @param igv  incluido (los precios ya tienen IGV), agregado (se suma al precio) o exonerado
     */
    @Autowired
    public InventoryService(@Value("${jarvis.inventory.file:${user.home}/jarvis-inventario.json}") String archivo,
                            @Value("${jarvis.comprobante.tipo:boleta}") String tipo,
                            @Value("${jarvis.igv:incluido}") String igv) {
        this.tipoPorDefecto = Objects.requireNonNullElse(TipoComprobante.de(tipo), TipoComprobante.BOLETA);
        this.igvPorDefecto = Igv.Modo.de(igv);
        Path ruta = Paths.get(archivo);
        String nombre = ruta.getFileName().toString();
        boolean esBase = nombre.endsWith(".db");
        this.archivoJson = esBase ? ruta.resolveSibling(nombre.replaceFirst("\\.db$", ".json")) : ruta;
        this.almacen = new Almacen(esBase ? ruta : ruta.resolveSibling(nombre.replaceFirst("\\.json$", "") + ".db"));
        this.clientes = new Clientes(almacen.baseDatos());
        this.compras = new Compras(almacen.baseDatos());
        cargar();
    }

    public synchronized boolean hayPendiente() { return sesion().pendiente != null; }

    public synchronized List<Producto> productos() { return new ArrayList<>(productos); }

    /** El archivo de la base de datos del inventario. */
    public Path archivoBaseDatos() { return almacen.archivo(); }

    public Clientes clientes() { return clientes; }

    public Compras compras() { return compras; }

    public TipoComprobante tipoPorDefecto() { return tipoPorDefecto; }

    public Igv.Modo igvPorDefecto() { return igvPorDefecto; }

    // ---------------------------------------------------------------- sesiones

    private Sesion sesion() {
        long ahora = System.currentTimeMillis();
        sesiones.values().removeIf(s -> ahora - s.usado > SESION_INACTIVA_MS);   // pestañas que ya no vuelven
        Sesion s = sesiones.computeIfAbsent(ClientSession.id(), k -> new Sesion());
        s.usado = ahora;
        return s;
    }

    // ---------------------------------------------------------------- persistencia

    /** El inventario JSON de las versiones anteriores (solo para importarlo). */
    private static class Datos {
        public List<Producto> productos = new ArrayList<>();
        public List<Venta> ventas = new ArrayList<>();
        public int ultimoNumero;
    }

    private void cargar() {
        if (almacen.vacio()) importarJsonAntiguo();
        usarEstado(almacen.cargar());
        almacen.respaldoDiario();
    }

    private void usarEstado(Almacen.Estado e) {
        productos = new ArrayList<>(e.productos());
        ventas = new ArrayList<>(e.ventas());
        cabeceras = new HashMap<>(e.cabeceras());
        recalcularContadores(e.contadores());
    }

    /** Los últimos números usados: lo guardado como contador o lo que hay en las ventas, lo mayor. */
    private void recalcularContadores(Map<String, Integer> guardados) {
        ultimoNumero = Math.max(guardados.getOrDefault(Almacen.ULTIMO_COMPROBANTE, 0),
                ventas.stream().mapToInt(v -> v.numero).max().orElse(0));
        correlativos.clear();
        for (TipoComprobante t : TipoComprobante.values()) correlativos.put(t, guardados.getOrDefault("serie:" + t.serie, 0));
        for (Venta v : ventas) {
            if (v.numero <= 0) continue;
            Cabecera c = Almacen.cabecera(cabeceras, v.numero);
            correlativos.merge(c.tipo(), c.correlativo(), Math::max);
        }
    }

    private Map<String, Integer> contadores() {
        Map<String, Integer> m = new HashMap<>();
        m.put(Almacen.ULTIMO_COMPROBANTE, ultimoNumero);
        correlativos.forEach((t, n) -> m.put("serie:" + t.serie, n));
        return m;
    }

    /**
     * Pasa a la base de datos el inventario de las versiones anteriores: el .json (o su .bak si está dañado),
     * las ventas archivadas por mes y el historial de movimientos. Los archivos no se borran: el .json se
     * renombra a .json.migrado para que no se vuelva a importar.
     */
    private void importarJsonAntiguo() {
        Path bak = archivoJson.resolveSibling(archivoJson.getFileName() + ".bak");
        if (!Files.exists(archivoJson) && !Files.exists(bak)) return;
        Datos d = Files.exists(archivoJson) ? leer(archivoJson) : null;
        if (d == null && Files.exists(bak)) {
            d = leer(bak);
            if (d != null) log.warn("El inventario {} estaba dañado o vacío: importo su respaldo {}.", archivoJson, bak);
        }
        if (d == null) {
            log.error("No pude leer el inventario antiguo {} ni su respaldo: empiezo vacío. Los archivos se conservan.", archivoJson);
            return;
        }
        Historial h = new Historial(archivoJson.resolveSibling(archivoJson.getFileName().toString()
                .replaceFirst("\\.json$", "") + "-historial"));
        List<Venta> archivadas = h.ventasArchivadas();
        List<Venta> todas = new ArrayList<>(archivadas);
        if (d.ventas != null) todas.addAll(d.ventas);
        d.ventas = todas;
        usar(d);
        cabeceras = new HashMap<>();
        recalcularContadores(Map.of());
        List<Almacen.Pendiente> movimientos = h.movimientos().stream().map(m -> new Almacen.Pendiente(m, null)).toList();
        try {
            almacen.guardar(productos, ventas, cabeceras, contadores(), movimientos);
        } catch (SQLException e) {
            throw new IllegalStateException("No pude importar el inventario antiguo a la base de datos: " + e.getMessage(), e);
        }
        try {
            if (Files.exists(archivoJson))
                Files.move(archivoJson, archivoJson.resolveSibling(archivoJson.getFileName() + ".migrado"),
                        StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Importé {} pero no pude renombrarlo: {}", archivoJson, e.getMessage());
        }
        log.info("Pasé el inventario antiguo a la base de datos {}: {} productos, {} líneas de venta ({} archivadas) y {} movimientos.",
                almacen.archivo(), productos.size(), ventas.size(), archivadas.size(), movimientos.size());
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

    /**
     * Guarda en la base lo que cambió y los movimientos pendientes, en una transacción. Si no se pudo guardar,
     * vuelve a leer la base (que quedó como antes), así lo que se ve es siempre lo que está guardado.
     */
    private String persistir(String msg) {
        return guardarTodo(null) ? msg : NO_GUARDADO;
    }

    private static final String NO_GUARDADO =
            "Atención: no pude guardar en la base de datos, así que no se registró nada, señor. Revise jarvis.log.";

    /** Guarda los cambios y {@code extra} (filas de otras tablas) en una sola transacción. false = no se guardó nada. */
    private boolean guardarTodo(BaseDatos.Trabajo<?> extra) {
        almacen.respaldoDiario();   // el primero del día copia el estado de antes de este cambio
        try {
            almacen.guardar(productos, ventas, cabeceras, contadores(), porRegistrar, extra);
            return true;
        } catch (SQLException | RuntimeException e) {
            log.error("No pude guardar en la base de datos {}: {}", almacen.archivo(), e.toString());
            recargar();
            return false;
        } finally {
            porRegistrar.clear();
        }
    }

    // ---------------------------------------------------------------- compras a proveedores

    private static final BigDecimal UNO_MAS_IGV = BigDecimal.ONE.add(Igv.TASA);

    /**
     * Registra una compra (como CompraService del sistema de referencia): valida todo, suma el stock de cada producto,
     * recalcula su costo promedio ponderado y guarda la compra, todo en una transacción. El costo que se promedia es sin
     * IGV (el IGV de la compra es crédito fiscal). Si el RUC es nuevo, el proveedor se registra con la razón social dada.
     */
    public synchronized Resultado registrarCompra(Compras.NuevaCompra c) {
        if (c == null) return Resultado.error("Faltan los datos de la compra.");
        String ruc = c.ruc() == null ? "" : c.ruc().trim();
        if (!ruc.matches(Compras.PATRON_RUC)) return Resultado.error("Ingrese un RUC de proveedor válido (11 dígitos, empieza con 10, 15, 17 o 20).");
        Optional<Compras.Proveedor> registrado = compras.proveedor(ruc);
        String razon = registrado.map(Compras.Proveedor::razonSocial)
                .orElse(c.razonSocial() == null ? "" : c.razonSocial().trim().replaceAll("\\s+", " "));
        if (razon.isEmpty()) return Resultado.error("El RUC " + ruc + " no está registrado: escriba la razón social del proveedor.");
        if (razon.length() > 150) return Resultado.error("La razón social es muy larga (máximo 150 letras).");
        Compras.TipoDocumento tipo = c.tipoDocumento() == null || c.tipoDocumento().isBlank()
                ? Compras.TipoDocumento.FACTURA : Compras.TipoDocumento.de(c.tipoDocumento());
        if (tipo == null) return Resultado.error("El documento del proveedor es factura, boleta, guía de remisión o nota de venta.");
        String numeroDoc = c.numeroDocumento() == null ? "" : c.numeroDocumento().trim().toUpperCase().replaceAll("\\s+", "");
        if (numeroDoc.isEmpty()) return Resultado.error("Ingrese la serie y número del documento del proveedor (por ejemplo F001-452).");
        if (numeroDoc.length() > 20) return Resultado.error("El número de documento es muy largo (máximo 20 caracteres).");
        LocalDate fechaDoc;
        try {
            fechaDoc = c.fechaDocumento() == null || c.fechaDocumento().isBlank() ? LocalDate.now() : LocalDate.parse(c.fechaDocumento().trim());
        } catch (RuntimeException e) {
            return Resultado.error("La fecha del documento debe ser AAAA-MM-DD.");
        }
        if (fechaDoc.isAfter(LocalDate.now())) return Resultado.error("La fecha del documento no puede ser posterior a hoy.");
        Igv.Modo modo = c.igv() == null || c.igv().isBlank() ? Igv.Modo.INCLUIDO : Igv.Modo.de(c.igv());
        if (c.items() == null || c.items().isEmpty()) return Resultado.error("Agregue al menos un producto a la compra.");
        if (c.items().size() > 500) return Resultado.error("La compra tiene demasiadas líneas (máximo 500).");
        if (compras.documentoRegistrado(ruc, tipo, numeroDoc))
            return Resultado.error("Ya se registró la " + tipo.descripcion.toLowerCase() + " " + numeroDoc + " de " + razon + ".");

        // 1) validar todas las líneas antes de tocar nada
        List<Producto> prods = new ArrayList<>();
        List<BigDecimal> cantidades = new ArrayList<>(), costos = new ArrayList<>(), importes = new ArrayList<>();
        for (Compras.LineaCompra l : c.items()) {
            if (l == null) return Resultado.error("Hay una línea vacía en la compra.");
            Producto p = porNombre(l.producto());
            if (p == null) return Resultado.noEncontrado("No existe el producto " + l.producto() + ". Regístrelo primero; no guardé la compra.");
            if (cantidadInvalida(l.cantidad())) return Resultado.error("La cantidad de " + p.nombre + " debe ser mayor que cero.");
            BigDecimal cant = Num.cantidad(l.cantidad());
            String error = errorDecimales(p, cant);
            if (error != null) return Resultado.error(error);
            if (l.costo() == null || l.costo().signum() <= 0) return Resultado.error("El costo de " + p.nombre + " debe ser mayor que cero.");
            BigDecimal costo = l.costo().setScale(4, RoundingMode.HALF_UP);
            prods.add(p); cantidades.add(cant); costos.add(costo); importes.add(Num.monto(costo, cant));
        }
        Igv.Totales totales = Igv.calcular(importes, modo);

        // 2) stock y costo promedio
        int numero = compras.siguienteNumero();
        String codigo = Compras.codigo(numero);
        List<Compras.Detalle> detalles = new ArrayList<>();
        for (int i = 0; i < prods.size(); i++) {
            Producto p = prods.get(i);
            BigDecimal cant = cantidades.get(i);
            BigDecimal sinIgv = modo == Igv.Modo.INCLUIDO ? costos.get(i).divide(UNO_MAS_IGV, 4, RoundingMode.HALF_UP) : costos.get(i);
            BigDecimal anterior = p.stock.max(BigDecimal.ZERO);
            p.costo = Num.dinero(p.costo == null || anterior.signum() == 0 ? sinIgv
                    : p.costo.multiply(anterior).add(sinIgv.multiply(cant)).divide(anterior.add(cant), 4, RoundingMode.HALF_UP));
            p.stock = Num.cantidad(p.stock.add(cant));
            registrar("COMPRA", p, cant, codigo + " · " + tipo.descripcion.toLowerCase() + " " + numeroDoc + " de " + razon
                    + ", costo " + Num.dinero(sinIgv) + ", stock " + Num.txt(p.stock));
            detalles.add(new Compras.Detalle(i + 1, p.nombre, p.unidad, cant, costos.get(i), importes.get(i), p.id));
        }
        Compras.Registro reg = new Compras.Registro(numero, LocalDate.now().toString(),
                LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), ruc, razon, tipo, numeroDoc, fechaDoc.toString(), modo,
                totales, c.observacion() == null ? "" : c.observacion().trim(), ClientSession.usuario(), detalles);
        if (!guardarTodo(cx -> { compras.insertar(cx, reg); return null; })) return Resultado.error(NO_GUARDADO);
        return Resultado.ok(String.format("Compra %s registrada: %s %s de %s por %.2f soles. Ingresaron %d %s al stock.",
                codigo, tipo.descripcion.toLowerCase(), numeroDoc, razon, totales.total(), detalles.size(),
                detalles.size() == 1 ? "producto" : "productos"));
    }

    /**
     * Anula una compra y retira del stock lo que ingresó con ella. Como en el sistema de referencia, no se puede
     * si parte de esa mercadería ya se vendió (no hay stock suficiente para retirarla).
     */
    public synchronized Resultado anularCompra(String codigoONumero) {
        Optional<Compras.Compra> encontrada = compras.obtener(codigoONumero);
        if (encontrada.isEmpty()) return Resultado.noEncontrado("No encontré la compra " + codigoONumero + ".");
        Compras.Compra c = encontrada.get();
        if (c.anulada()) return Resultado.error("La compra " + c.codigo() + " ya estaba anulada.");
        Map<Producto, BigDecimal> retiro = new LinkedHashMap<>();
        for (Compras.Detalle d : c.detalles()) {
            Producto p = productos.stream().filter(x -> d.productoId() != null && d.productoId().equals(x.id)).findFirst()
                    .orElseGet(() -> porNombre(d.producto()));
            if (p == null) return Resultado.error(d.producto() + " ya no existe en el inventario: no puedo anular la compra " + c.codigo() + ".");
            retiro.merge(p, d.cantidad(), BigDecimal::add);
        }
        List<String> faltan = new ArrayList<>();
        retiro.forEach((p, cant) -> {
            if (cant.compareTo(p.stock) > 0)
                faltan.add(p.nombre + " (disponible " + Num.txt(p.stock.max(BigDecimal.ZERO)) + ", se requieren " + Num.txt(cant) + ")");
        });
        if (!faltan.isEmpty())
            return Resultado.error("No se puede anular: parte de la mercadería ya se vendió. Stock insuficiente: " + String.join("; ", faltan) + ".");
        retiro.forEach((p, cant) -> {
            p.stock = Num.cantidad(p.stock.subtract(cant));
            registrar("ANULACION_COMPRA", p, cant, c.codigo() + " anulada, stock " + Num.txt(p.stock));
        });
        if (!guardarTodo(cx -> { compras.marcarAnulada(cx, c.id()); return null; })) return Resultado.error(NO_GUARDADO);
        return Resultado.ok("Compra " + c.codigo() + " anulada: retiré del stock lo que había ingresado.");
    }

    /** Vuelve a lo guardado. Las ventas en curso y las preguntas pendientes se cancelan: apuntan a datos que ya no valen. */
    private void recargar() {
        try {
            usarEstado(almacen.cargar());
        } catch (RuntimeException e) {
            log.error("Tampoco pude volver a leer la base de datos: {}", e.toString());
        }
        for (Sesion s : sesiones.values()) { s.carrito = null; s.pendiente = null; s.comprobanteNuevo = null; }
    }

    private void registrar(String tipo, Producto p, BigDecimal cantidad, String detalle) {
        int signo = Almacen.SIGNO.getOrDefault(tipo, 0);
        registrar(tipo, p, cantidad, detalle, cantidad == null || signo == 0 ? null : cantidad.multiply(BigDecimal.valueOf(signo)));
    }

    /**
     * Deja el movimiento en el historial. {@code delta}: cuánto cambió el stock (+ entra, − sale; null si no lo mueve).
     * Se llama DESPUÉS de cambiar el stock: así el saldo del kardex es el stock que quedó.
     */
    private void registrar(String tipo, Producto p, BigDecimal cantidad, String detalle, BigDecimal delta) {
        BigDecimal saldo = delta == null ? null : "ELIMINAR".equals(tipo) ? BigDecimal.ZERO : p.stock;
        porRegistrar.add(new Almacen.Pendiente(new Movimiento(LocalDateTime.now().withNano(0).toString(), tipo, p.nombre,
                cantidad == null ? null : Num.cantidad(cantidad), detalle, ClientSession.usuario()), p,
                delta == null ? null : Num.cantidad(delta), saldo == null ? null : Num.cantidad(saldo), delta == null ? null : p.costo));
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

    public synchronized String salida(BigDecimal cantidad, String textoProducto, boolean confirmado) {
        return salida(cantidad, textoProducto, confirmado, Opciones.POR_DEFECTO);
    }

    /** Registrar salida/venta con su comprobante. Valida stock. */
    public synchronized String salida(BigDecimal cantidad, String textoProducto, boolean confirmado, Opciones opciones) {
        if (cantidadInvalida(cantidad)) return "No entendí la cantidad, señor. Por favor repita la orden.";
        cantidad = Num.cantidad(cantidad);
        Match m = buscar(textoProducto);
        if (m == null) return "No encontré ese producto en el inventario, señor. ¿Lo registramos como entrada primero?";
        if (m.empate()) return cualDe(m);
        String error = errorDecimales(m.producto, cantidad);
        if (error != null) return error;

        if ((m.ambiguo || cantidad.compareTo(CONFIRMAR_DESDE) > 0) && !confirmado) {
            Pendiente p = pend(Tipo.SALIDA, m.producto, cantidad, null,
                    "¿Confirma la salida de " + Num.txt(cantidad) + " " + m.producto.unidad + " de " + m.producto.nombre + "? Diga sí o no.");
            p.opciones = opciones;
            return preguntar(p);
        }

        return emitir(List.of(new Linea(m.producto, cantidad)), opciones);
    }

    /** Vende varios productos dichos en una sola frase ("2 poleras y 3 gorras") con un solo comprobante. */
    /** Una línea de una venta por API: nombre exacto del producto y cantidad. */
    public record Pedido(String nombre, BigDecimal cantidad) {}

    /**
     * Venta desde la API o la pantalla: nombres exactos (sin búsqueda difusa: un programa no debe vender "el más
     * parecido"). Mismas reglas que por voz: valida todo el stock antes de descontar nada.
     */
    public synchronized Resultado vender(List<Pedido> pedido, Opciones opciones) {
        if (pedido == null || pedido.isEmpty()) return Resultado.error("La venta no tiene productos.");
        List<Linea> lineas = new ArrayList<>();
        for (Pedido x : pedido) {
            if (x == null) return Resultado.error("Hay una línea vacía en la venta.");
            Producto p = porNombre(x.nombre());
            if (p == null) return Resultado.noEncontrado("No existe el producto " + x.nombre() + ". No registré la venta.");
            if (cantidadInvalida(x.cantidad())) return Resultado.error("La cantidad de " + p.nombre + " debe ser mayor que cero.");
            lineas.add(new Linea(p, Num.cantidad(x.cantidad())));
        }
        String msg = emitir(lineas, opciones);
        return msg.startsWith("Venta registrada") ? Resultado.ok(msg) : Resultado.error(msg);
    }

    /** Una fila de la lista de comprobantes. {@code cliente}: nombre, o "" si fue a clientes varios. */
    public record FilaComprobante(int numero, String codigo, String tipo, String fecha, String hora, String cliente,
                                  int productos, BigDecimal total, boolean anulado) {}

    /** Los últimos comprobantes emitidos, del más reciente al más antiguo. */
    public synchronized List<FilaComprobante> comprobantes(int limite) {
        return comprobantes(limite, null, null);
    }

    /** Los últimos comprobantes de un periodo (AAAA-MM-DD; vacías = sin límite), del más reciente al más antiguo. */
    public synchronized List<FilaComprobante> comprobantes(int limite, String desde, String hasta) {
        Map<Integer, List<Venta>> porNumero = new TreeMap<>(Comparator.reverseOrder());
        for (Venta v : ventas)
            if (v.numero > 0 && enPeriodo(v.fecha, desde, hasta)) porNumero.computeIfAbsent(v.numero, k -> new ArrayList<>()).add(v);
        List<FilaComprobante> out = new ArrayList<>();
        for (Map.Entry<Integer, List<Venta>> e : porNumero.entrySet()) {
            if (out.size() >= Math.min(Math.max(limite, 1), 1000)) break;
            List<Venta> items = e.getValue();
            Comprobante c = new Comprobante(e.getKey(), items.get(0).fecha, items.get(0).hora, items, Almacen.cabecera(cabeceras, e.getKey()));
            out.add(new FilaComprobante(c.numero, c.codigo, c.tipo, c.fecha, c.hora, c.cliente == null ? "" : c.cliente.nombre(),
                    c.items.size(), c.total, c.anulado));
        }
        return out;
    }

    /** Cifras para la pantalla de inicio. Valor al costo: solo de los productos que tienen costo registrado. */
    public record Resumen(int ventasHoy, BigDecimal totalHoy, BigDecimal igvHoy, int anuladasHoy, int productos, int stockBajo,
                          int sinStock, BigDecimal valorVenta, BigDecimal valorCosto, int clientes, String igv, String tipoComprobante) {}

    public synchronized Resumen resumen() {
        String hoy = LocalDate.now().toString();
        Map<Integer, List<Venta>> deHoy = new HashMap<>();
        for (Venta v : ventas) if (v.numero > 0 && hoy.equals(v.fecha)) deHoy.computeIfAbsent(v.numero, k -> new ArrayList<>()).add(v);
        int ventasHoy = 0, anuladas = 0;
        BigDecimal total = Num.dinero(BigDecimal.ZERO), igv = Num.dinero(BigDecimal.ZERO);
        for (Map.Entry<Integer, List<Venta>> e : deHoy.entrySet()) {
            Comprobante c = new Comprobante(e.getKey(), hoy, "", e.getValue(), Almacen.cabecera(cabeceras, e.getKey()));
            if (c.anulado) { anuladas++; continue; }
            ventasHoy++;
            total = total.add(c.total);
            igv = igv.add(c.igv);
        }
        int bajo = 0, sin = 0;
        BigDecimal valorVenta = BigDecimal.ZERO, valorCosto = BigDecimal.ZERO;
        for (Producto p : productos) {
            if (p.stock.signum() <= 0) sin++;
            else if (p.stockBajo()) bajo++;
            if (p.stock.signum() > 0) {
                valorVenta = valorVenta.add(p.precio.multiply(p.stock));
                if (p.costo != null) valorCosto = valorCosto.add(p.costo.multiply(p.stock));
            }
        }
        return new Resumen(ventasHoy, total, igv, anuladas, productos.size(), bajo, sin, Num.dinero(valorVenta), Num.dinero(valorCosto),
                clientes.contar(), igvPorDefecto.name(), tipoPorDefecto.name());
    }

    // ---------------------------------------------------------------- kardex e inventario valorizado

    /** Kardex de un producto: sus datos de hoy y los movimientos de stock (los últimos {@code limite}). */
    public record Kardex(String producto, String unidad, BigDecimal stock, BigDecimal costo, List<FilaKardex> filas) {}

    public synchronized Kardex kardex(String nombre, int limite) {
        Producto p = porNombre(nombre);
        if (p == null) return null;
        return new Kardex(p.nombre, p.unidad, p.stock, p.costo, almacen.kardex(p.id, p.nombre, limite));
    }

    /** Kardex de todos los productos (para el Excel), ordenado por producto. */
    public synchronized List<FilaKardex> kardexCompleto() {
        return almacen.kardex(null, null, 100_000);
    }

    /** Una fila del inventario valorizado. {@code estado}: OK, BAJO o SIN_STOCK. Sin costo: valorCosto null. */
    public record FilaValorizada(String producto, String categoria, String codigo, String unidad, BigDecimal stock, BigDecimal stockMin,
                                 String estado, BigDecimal costo, BigDecimal valorCosto, BigDecimal precio, BigDecimal valorVenta) {}

    /** {@code margen}: valor a precio de venta − valor al costo, solo de los productos con costo. */
    public record Valorizado(List<FilaValorizada> filas, int productos, int sinCosto, int stockBajo, int sinStock,
                             BigDecimal valorCosto, BigDecimal valorVenta, BigDecimal margen) {}

    /** Inventario valorizado al costo promedio y a precio de venta (como el del sistema de referencia). */
    public synchronized Valorizado valorizado() {
        List<FilaValorizada> filas = new ArrayList<>();
        int sinCosto = 0, bajo = 0, sin = 0;
        BigDecimal costoTotal = BigDecimal.ZERO, ventaTotal = BigDecimal.ZERO, ventaConCosto = BigDecimal.ZERO;
        List<Producto> orden = new ArrayList<>(productos);
        orden.sort(Comparator.comparing((Producto p) -> p.categoria.isEmpty() ? "\uffff" : Palabras.norm(p.categoria))
                .thenComparing(p -> Palabras.norm(p.nombre)));
        for (Producto p : orden) {
            BigDecimal stock = p.stock.max(BigDecimal.ZERO);
            String estado = p.stock.signum() <= 0 ? "SIN_STOCK" : p.stockBajo() ? "BAJO" : "OK";
            if (estado.equals("SIN_STOCK")) sin++; else if (estado.equals("BAJO")) bajo++;
            BigDecimal valorVenta = Num.dinero(p.precio.multiply(stock));
            BigDecimal valorCosto = p.costo == null ? null : Num.dinero(p.costo.multiply(stock));
            if (valorCosto == null) sinCosto++;
            else { costoTotal = costoTotal.add(valorCosto); ventaConCosto = ventaConCosto.add(valorVenta); }
            ventaTotal = ventaTotal.add(valorVenta);
            filas.add(new FilaValorizada(p.nombre, p.categoria, p.codigo, p.unidad, p.stock, p.stockMin, estado, p.costo,
                    valorCosto, p.precio, valorVenta));
        }
        return new Valorizado(filas, filas.size(), sinCosto, bajo, sin, Num.dinero(costoTotal), Num.dinero(ventaTotal),
                Num.dinero(ventaConCosto.subtract(costoTotal)));
    }

    /** Comprobantes completos (con sus líneas) de un periodo, del más antiguo al más reciente. Fechas AAAA-MM-DD; vacías = sin límite. */
    public synchronized List<Comprobante> ventasPeriodo(String desde, String hasta) {
        Map<Integer, List<Venta>> porNumero = new TreeMap<>();
        for (Venta v : ventas)
            if (v.numero > 0 && enPeriodo(v.fecha, desde, hasta)) porNumero.computeIfAbsent(v.numero, k -> new ArrayList<>()).add(v);
        List<Comprobante> out = new ArrayList<>();
        porNumero.forEach((n, items) -> out.add(new Comprobante(n, items.get(0).fecha, items.get(0).hora, items, Almacen.cabecera(cabeceras, n))));
        return out;
    }

    private static boolean enPeriodo(String fecha, String desde, String hasta) {
        String f = fecha == null ? "" : fecha;
        return (desde == null || desde.isBlank() || f.compareTo(desde.trim()) >= 0)
                && (hasta == null || hasta.isBlank() || f.compareTo(hasta.trim()) <= 0);
    }

    /** El comprobante con ese número interno de venta (el de {@code Comprobante.numero}), o null. */
    public synchronized Comprobante comprobantePorNumero(int numero) {
        return numero <= 0 ? null : comprobante(numero);
    }

    public synchronized String venderVarios(List<BigDecimal> cantidades, List<String> textos) {
        return venderVarios(cantidades, textos, Opciones.POR_DEFECTO);
    }

    public synchronized String venderVarios(List<BigDecimal> cantidades, List<String> textos, Opciones opciones) {
        List<Linea> lineas = new ArrayList<>();
        for (int i = 0; i < textos.size(); i++) {
            BigDecimal c = cantidades.get(i);
            if (cantidadInvalida(c)) return "No entendí la cantidad de " + textos.get(i) + ", señor. Por favor repita la orden.";
            Match m = buscar(textos.get(i));
            if (m == null) return "No encontré " + textos.get(i) + " en el inventario, señor. No registré la venta.";
            if (m.empate()) return cualDe(m) + " No registré la venta.";
            lineas.add(new Linea(m.producto, Num.cantidad(c)));
        }
        return emitir(lineas, opciones);
    }

    /**
     * Registra la venta de todas las líneas con un único comprobante: valida el stock de todo
     * antes de descontar nada, así una venta nunca queda a medias.
     */
    private String emitir(List<Linea> lineas, Opciones opciones) {
        Opciones op = opciones == null ? Opciones.POR_DEFECTO : opciones;
        TipoComprobante tipo = op.tipo() != null ? op.tipo() : tipoPorDefecto;
        Igv.Modo modo = op.modo() != null ? op.modo() : igvPorDefecto;
        Clientes.Cliente cliente = null;
        if (op.clienteDoc() != null && !op.clienteDoc().isBlank()) {
            String doc = op.clienteDoc().trim();
            cliente = clientes.porDocumento(doc).orElse(null);
            if (cliente == null)
                return "No tengo registrado al cliente con " + Clientes.tipo(doc) + " " + doc
                        + ", señor. Regístrelo primero o haga la venta sin documento. No registré la venta.";
        }
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

        int numero = ++ultimoNumero;
        Cabecera cab = new Cabecera(tipo, correlativos.merge(tipo, 1, Integer::sum), cliente == null ? null : cliente.id(),
                cliente == null ? "" : cliente.documento(), cliente == null ? "" : cliente.nombre(), modo);
        cabeceras.put(numero, cab);
        String fecha = LocalDate.now().toString(), hora = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        List<Venta> items = new ArrayList<>();
        for (Linea l : lineas) {
            Producto p = l.producto();
            p.stock = Num.cantidad(p.stock.subtract(l.cantidad()));
            items.add(new Venta(numero, fecha, hora, p, l.cantidad()));
            registrar("VENTA", p, l.cantidad(), tipo.codigo(cab.correlativo()) + ", stock " + Num.txt(p.stock));
        }
        ventas.addAll(items);
        Comprobante c = new Comprobante(numero, fecha, hora, items, cab);
        sesion().comprobanteNuevo = c;

        String msg;
        if (items.size() == 1) {
            Venta v = items.get(0);
            Producto p = lineas.get(0).producto();
            msg = "Venta registrada: " + Num.txt(v.cantidad) + " " + v.unidad + " de " + v.producto
                    + String.format(". Total: %.2f soles. %s. Stock actual: %s.", c.total, capitaliza(c.hablado()), Num.txt(p.stock));
        } else {
            msg = String.format("Venta registrada: %d productos. Total: %.2f soles. %s.", items.size(), c.total, capitaliza(c.hablado()));
        }
        if (cliente != null) msg += " Cliente: " + cliente.nombre() + ".";
        else if (tipo == TipoComprobante.BOLETA && c.total.compareTo(BOLETA_CON_DNI_DESDE) >= 0)
            msg += " Atención: en boletas de 700 soles o más se debe identificar al cliente con su DNI.";
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

    /** Venta nueva con su tipo de comprobante y/o cliente ("nueva venta con boleta para el DNI 12345678"). */
    public synchronized String abrirVenta(Opciones opciones) {
        String msg = abrirVenta();
        if (opciones == null || opciones.vacia() || !msg.startsWith("Venta nueva")) return msg;
        String r = opcionesVenta(opciones);
        return r.startsWith("No tengo") ? r : msg + " " + r;
    }

    /** Cambia el tipo de comprobante y/o el cliente de la venta en curso. */
    public synchronized String opcionesVenta(Opciones opciones) {
        Sesion s = sesion();
        Opciones nuevas = s.opciones.con(opciones);
        String doc = nuevas.clienteDoc();
        Clientes.Cliente cliente = null;
        if (doc != null && !doc.isBlank()) {
            cliente = clientes.porDocumento(doc.trim()).orElse(null);
            if (cliente == null)
                return "No tengo registrado al cliente con " + Clientes.tipo(doc.trim()) + " " + doc.trim() + ", señor. Regístrelo primero.";
        }
        if (s.carrito == null) s.carrito = new ArrayList<>();
        s.opciones = nuevas;
        TipoComprobante tipo = nuevas.tipo() != null ? nuevas.tipo() : tipoPorDefecto;
        return "La venta será " + tipo.hablado + (cliente != null ? " para " + cliente.nombre() : "") + ".";
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
        String msg = emitir(s.carrito, s.opciones);
        if (msg.startsWith("Venta registrada")) { s.carrito = null; s.opciones = Opciones.POR_DEFECTO; }   // si faltó stock, sigue abierta
        return msg;
    }

    public synchronized String cancelarVenta() {
        Sesion s = sesion();
        if (s.carrito == null) return "No hay ninguna venta en curso, señor.";
        s.carrito = null;
        s.opciones = Opciones.POR_DEFECTO;
        return "Venta cancelada, señor. No se descontó nada del inventario.";
    }

    /** La venta en curso de esta sesión como comprobante preliminar (numero 0), o null si no hay. */
    public synchronized Comprobante carritoActual() {
        Sesion s = sesion();
        if (s.carrito == null) return null;
        List<Venta> items = s.carrito.stream().map(l -> new Venta(0, null, null, l.producto(), l.cantidad())).toList();
        String doc = s.opciones.clienteDoc() == null ? "" : s.opciones.clienteDoc().trim();
        String nombre = doc.isEmpty() ? "" : clientes.porDocumento(doc).map(Clientes.Cliente::nombre).orElse("");
        return new Comprobante(0, null, null, new ArrayList<>(items), new Cabecera(
                s.opciones.tipo() != null ? s.opciones.tipo() : tipoPorDefecto, 0, null, doc, nombre,
                s.opciones.modo() != null ? s.opciones.modo() : igvPorDefecto));
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
        // el total de cada comprobante (con el IGV si se agregó al precio); las ventas antiguas sin número, por línea
        BigDecimal total = validas.stream().filter(v -> v.numero > 0).map(v -> v.numero).distinct()
                .map(n -> comprobante(n).total).reduce(Num.dinero(BigDecimal.ZERO), BigDecimal::add)
                .add(validas.stream().filter(v -> v.numero == 0).map(v -> v.monto).reduce(BigDecimal.ZERO, BigDecimal::add));
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
        return String.format("Mostrando la %s%s. Total: %.2f soles.", c.hablado(), c.anulado ? " (ANULADA)" : "", c.total);
    }

    /** El comprobante con el número más alto que sigue en el archivo, o null. */
    private Comprobante ultimo() {
        int n = ventas.stream().mapToInt(v -> v.numero).max().orElse(0);
        return n == 0 ? null : comprobante(n);
    }

    private Comprobante comprobante(int numero) {
        List<Venta> items = new ArrayList<>(ventas.stream().filter(v -> v.numero == numero).toList());
        if (items.isEmpty()) return null;
        return new Comprobante(numero, items.get(0).fecha, items.get(0).hora, items, Almacen.cabecera(cabeceras, numero));
    }

    // ---------------------------------------------------------------- correcciones

    /** Voz: pregunta antes de anular el último comprobante (se responde con sí/no). */
    public synchronized String pedirAnularUltimaVenta() {
        Comprobante c = ultimo();
        if (c == null) return "No hay ventas para anular, señor.";
        if (c.anulado) return "La " + c.hablado() + " ya está anulada, señor.";
        Pendiente p = new Pendiente();
        p.tipo = Tipo.ANULAR; p.numero = c.numero;
        p.pregunta = String.format("¿Confirma anular la %s por %.2f soles? Diga sí o no.", c.hablado(), c.total);
        return preguntar(p);
    }

    /** Anula el último comprobante: devuelve el stock y lo deja fuera del cierre (queda en el historial). */
    public synchronized Resultado anularUltimaVenta() {
        Comprobante c = ultimo();
        return c == null ? Resultado.noEncontrado("No hay ventas para anular, señor.") : anular(c.numero);
    }

    private Resultado anular(int numero) {
        Comprobante c = comprobante(numero);
        if (c == null) return Resultado.noEncontrado("No encontré esa venta, señor.");
        if (c.anulado) return Resultado.error("La " + c.hablado() + " ya está anulada, señor.");
        List<String> sinProducto = new ArrayList<>();
        for (Venta v : c.items) {
            v.anulada = true;
            Producto p = porNombre(v.producto);
            if (p == null) { sinProducto.add(v.producto); continue; }
            p.stock = Num.cantidad(p.stock.add(v.cantidad));
            registrar("ANULACION", p, v.cantidad, (c.codigo.isEmpty() ? "venta " + numero : c.codigo) + ", stock " + Num.txt(p.stock));
        }
        String msg = String.format("Venta anulada: %s por %.2f soles. Devolví los productos al stock.", c.hablado(), c.total);
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
        if (c.anulado) return Resultado.error("La " + c.hablado() + " está anulada, señor. No se puede corregir.");

        Venta linea;
        if (textoProducto == null || textoProducto.isBlank()) {
            if (c.items.size() != 1)
                return Resultado.error("La " + c.hablado() + " tiene varios productos. Dígame cuál corrijo, señor.");
            linea = c.items.get(0);
        } else {
            Producto buscado = exacto ? porNombre(textoProducto) : Optional.ofNullable(buscar(textoProducto)).map(x -> x.producto).orElse(null);
            linea = buscado == null ? null : c.items.stream().filter(v -> v.producto.equals(buscado.nombre)).findFirst().orElse(null);
            if (linea == null)
                return Resultado.noEncontrado("Ese producto no está en la " + c.hablado() + ", señor.");
        }
        Producto p = porNombre(linea.producto);
        if (p == null) return Resultado.error(linea.producto + " ya no existe en el inventario, señor. No puedo corregir su stock.");
        String error = errorDecimales(p, nueva);
        if (error != null) return Resultado.error(error);
        if (nueva.signum() == 0 && c.items.size() == 1)
            return Resultado.error("Es el único producto de la " + c.hablado() + ". Para quitarlo, anule la última venta, señor.");

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
        registrar("CORRECCION_VENTA", p, nueva, c.codigo + ": antes " + Num.txt(antes) + ", stock " + Num.txt(p.stock), diferencia.negate());
        Comprobante corregido = comprobante(c.numero);
        sesion().comprobanteNuevo = corregido;
        String que = nueva.signum() == 0 ? "quité " + p.nombre : p.nombre + " ahora es " + Num.txt(nueva) + " " + p.unidad;
        return Resultado.ok(persistir(String.format("Corregí la %s: %s. Nuevo total: %.2f soles. Stock actual: %s.",
                corregido.hablado(), que, corregido.total, Num.txt(p.stock))));
    }

    /**
     * Cambios de un producto desde la pantalla. Los null no se tocan. En la ficha, un texto "" borra ese dato;
     * {@code quitarCosto} borra el costo (null en la ficha significa "no tocar").
     */
    public record Cambios(String nombre, BigDecimal precio, BigDecimal stock, BigDecimal stockMin, String unidad, List<String> alias,
                          Producto.Ficha ficha, boolean quitarCosto) {
        public Cambios(String nombre, BigDecimal precio, BigDecimal stock, BigDecimal stockMin, String unidad, List<String> alias) {
            this(nombre, precio, stock, stockMin, unidad, alias, Producto.Ficha.VACIA, false);
        }
        boolean vacio() {
            return (nombre == null || nombre.isBlank()) && precio == null && stock == null && stockMin == null
                    && (unidad == null || unidad.isBlank()) && alias == null
                    && (ficha == null || ficha.equals(Producto.Ficha.VACIA)) && !quitarCosto;
        }
    }

    private static final int MAX_NOMBRE = 80, MAX_UNIDAD = 20, MAX_CATEGORIA = 40, MAX_CODIGO = 40;

    public synchronized Resultado crearProducto(String nombre, BigDecimal precio, BigDecimal stock, String unidad,
                                                BigDecimal stockMin, List<String> alias) {
        return crearProducto(nombre, precio, stock, unidad, stockMin, alias, Producto.Ficha.VACIA);
    }

    /**
     * Crea un producto desde la pantalla. Nombre, precio y unidad son obligatorios; el stock inicial,
     * el stock mínimo, los alias y la ficha (categoría, códigos, costo) son opcionales. No admite dos productos
     * con el mismo nombre (sin importar mayúsculas ni tildes) ni con el mismo código.
     */
    public synchronized Resultado crearProducto(String nombre, BigDecimal precio, BigDecimal stock, String unidad,
                                                BigDecimal stockMin, List<String> alias, Producto.Ficha ficha) {
        String n = limpiarNombre(nombre);
        String u = unidad == null ? "" : unidad.trim().toLowerCase();
        String error = errorAlCrear(n, precio, stock, u, stockMin);
        if (error != null) return Resultado.error(error);
        Producto repetido = mismoNombre(n, null);
        if (repetido != null) return Resultado.error("Ya existe un producto llamado " + repetido.nombre + ". Edítelo en lugar de crear otro.");
        error = errorEnFicha(ficha, null);
        if (error != null) return Resultado.error(error);

        Producto p = nuevo(n, aliasDe(n, alias), precio, stock, u, stockMin, ficha, "");
        return Resultado.ok(persistir("Producto registrado: " + p.nombre + ". Stock: " + Num.txt(p.stock) + " " + p.unidad
                + String.format(". Precio: %.2f soles.", p.precio) + avisoCosto(p)));
    }

    /** Sin espacios de más; null se queda null (en una edición, null es "no tocar"). */
    private static String limpio(String s) { return s == null ? null : s.trim().replaceAll("\\s+", " "); }

    /** Por qué no sirven la categoría, los códigos o el costo, o null si sirven. Un código no puede ser de otro producto. */
    private String errorEnFicha(Producto.Ficha f, Producto excepto) {
        if (f == null) return null;
        String cat = limpio(f.categoria()), cod = limpio(f.codigo()), bar = limpio(f.codigoBarras());
        if (cat != null && cat.length() > MAX_CATEGORIA) return "La categoría es muy larga (máximo " + MAX_CATEGORIA + " letras).";
        if (cod != null && cod.length() > MAX_CODIGO) return "El código es muy largo (máximo " + MAX_CODIGO + " caracteres).";
        if (bar != null && bar.length() > MAX_CODIGO) return "El código de barras es muy largo (máximo " + MAX_CODIGO + " caracteres).";
        if (f.costo() != null && f.costo().signum() < 0) return "El costo no puede ser negativo.";
        for (String c : new String[]{cod, bar}) {
            Producto otro = conCodigo(c, excepto);
            if (otro != null) return "El código " + c + " ya es de " + otro.nombre + ".";
        }
        return null;
    }

    /** Otro producto (distinto de {@code excepto}) que ya usa este código interno o de barras, o null. */
    private Producto conCodigo(String c, Producto excepto) {
        if (c == null || c.isBlank()) return null;
        return productos.stream().filter(p -> p != excepto && p.tieneCodigo(c)).findFirst().orElse(null);
    }

    /** " Atención: …" si se vende por debajo del costo; "" si no. */
    private static String avisoCosto(Producto p) {
        return p.costo != null && p.costo.compareTo(p.precio) > 0
                ? String.format(" Atención: el costo (%.2f soles) es mayor que el precio de venta.", p.costo) : "";
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
        Map<String, Integer> codigosEnLista = new HashMap<>();   // código en mayúsculas → línea donde se aceptó
        List<ListaProductos.Fila> validas = new ArrayList<>();

        // 1) validar todo, sin tocar el inventario
        for (ListaProductos.Fila f : filas) {
            String n = limpiarNombre(f.nombre());
            String u = f.unidad() == null || f.unidad().isBlank() ? "unidades" : f.unidad().trim().toLowerCase();
            String error = errorAlCrear(n, f.precio(), f.stock(), u, f.stockMin());
            if (error != null) { errores.add(new ListaProductos.ErrorLinea(f.linea(), error)); continue; }
            Producto existe = mismoNombre(n, null);
            if (existe != null) { omitidos.add(new ListaProductos.ErrorLinea(f.linea(), "ya existe: " + existe.nombre)); continue; }
            if (enLista.containsKey(Palabras.norm(n))) {
                omitidos.add(new ListaProductos.ErrorLinea(f.linea(), "repetido: ya viene en la línea " + enLista.get(Palabras.norm(n))));
                continue;
            }
            Producto.Ficha ficha = f.ficha() == null ? Producto.Ficha.VACIA : f.ficha();
            error = errorEnFicha(ficha, null);
            if (error == null) error = codigoRepetidoEnLista(ficha, codigosEnLista);
            if (error != null) { errores.add(new ListaProductos.ErrorLinea(f.linea(), error)); continue; }
            enLista.put(Palabras.norm(n), f.linea());
            for (String c : new String[]{limpio(ficha.codigo()), limpio(ficha.codigoBarras())})
                if (c != null && !c.isEmpty()) codigosEnLista.putIfAbsent(c.toUpperCase(Locale.ROOT), f.linea());
            validas.add(new ListaProductos.Fila(f.linea(), n, f.precio(), f.stock(), u, f.stockMin(), ficha));
        }
        errores.sort(Comparator.comparingInt(ListaProductos.ErrorLinea::linea));

        // 2) registrar los válidos y guardar una sola vez
        for (ListaProductos.Fila f : validas)
            nuevo(f.nombre(), aliasDe(f.nombre(), null), f.precio(), f.stock(), f.unidad(), f.stockMin(), f.ficha(), " (carga masiva)");
        String resumen = "Carga de productos: " + validas.size() + (validas.size() == 1 ? " agregado" : " agregados")
                + ", " + omitidos.size() + (omitidos.size() == 1 ? " omitido" : " omitidos")
                + ", " + errores.size() + (errores.size() == 1 ? " con error." : " con errores.");
        return new ResultadoLote(validas.size(), omitidos, errores, validas.isEmpty() ? resumen : persistir(resumen));
    }

    /** Un código que ya trae una línea anterior de la misma lista, o null. */
    private static String codigoRepetidoEnLista(Producto.Ficha f, Map<String, Integer> codigosEnLista) {
        for (String c : new String[]{limpio(f.codigo()), limpio(f.codigoBarras())}) {
            Integer linea = c == null || c.isEmpty() ? null : codigosEnLista.get(c.toUpperCase(Locale.ROOT));
            if (linea != null) return "el código " + c + " ya viene en la línea " + linea;
        }
        return null;
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
    private Producto nuevo(String n, List<String> alias, BigDecimal precio, BigDecimal stock, String u, BigDecimal stockMin,
                           Producto.Ficha f, String nota) {
        Producto p = new Producto(n, alias, stock == null ? BigDecimal.ZERO : stock,
                stockMin == null ? BigDecimal.ZERO : stockMin, precio, u);
        if (f != null) {
            p.categoria = Objects.requireNonNullElse(limpio(f.categoria()), "");
            p.codigo = Objects.requireNonNullElse(limpio(f.codigo()), "");
            p.codigoBarras = Objects.requireNonNullElse(limpio(f.codigoBarras()), "");
            p.costo = Num.dinero(f.costo());
        }
        productos.add(p);
        registrar("NUEVO_PRODUCTO", p, null, "unidad " + p.unidad + ", precio " + p.precio
                + (p.costo != null ? ", costo " + p.costo : "") + (p.categoria.isEmpty() ? "" : ", categoría " + p.categoria)
                + (p.codigo.isEmpty() ? "" : ", código " + p.codigo)
                + (p.codigoBarras.isEmpty() ? "" : ", código de barras " + p.codigoBarras) + nota);
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
        BigDecimal antes = p.stock;
        p.stock = nuevo;
        registrar("AJUSTE", p, cantidad, Num.txt(antes) + " -> " + Num.txt(nuevo));
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
        Producto.Ficha f = c.ficha() == null ? Producto.Ficha.VACIA : c.ficha();
        String errorFicha = errorEnFicha(f, p);
        if (errorFicha != null) return Resultado.error(errorFicha);

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
            BigDecimal nuevo = Num.cantidad(c.stock()), antes = p.stock;
            p.stock = nuevo;
            registrar("AJUSTE", p, Num.cantidad(nuevo.subtract(antes)), Num.txt(antes) + " -> " + Num.txt(nuevo));
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
        String cat = limpio(f.categoria());
        if (cat != null && !cat.equals(p.categoria)) {
            registrar("CATEGORIA", p, null, sinDato(p.categoria) + " -> " + sinDato(cat));
            p.categoria = cat;
            hecho.add(cat.isEmpty() ? p.nombre + " quedó sin categoría." : "Categoría de " + p.nombre + ": " + cat + ".");
        }
        String cod = limpio(f.codigo());
        if (cod != null && !cod.equals(p.codigo)) {
            registrar("CODIGO", p, null, sinDato(p.codigo) + " -> " + sinDato(cod));
            p.codigo = cod;
            hecho.add(cod.isEmpty() ? p.nombre + " quedó sin código." : "Código de " + p.nombre + ": " + cod + ".");
        }
        String bar = limpio(f.codigoBarras());
        if (bar != null && !bar.equals(p.codigoBarras)) {
            registrar("CODIGO_BARRAS", p, null, sinDato(p.codigoBarras) + " -> " + sinDato(bar));
            p.codigoBarras = bar;
            hecho.add(bar.isEmpty() ? p.nombre + " quedó sin código de barras." : "Código de barras de " + p.nombre + ": " + bar + ".");
        }
        BigDecimal costo = c.quitarCosto() ? null : f.costo() == null ? p.costo : Num.dinero(f.costo());
        if (!Objects.equals(costo, p.costo)) {
            registrar("COSTO", p, null, (p.costo == null ? "sin costo" : p.costo) + " -> " + (costo == null ? "sin costo" : costo));
            p.costo = costo;
            hecho.add(costo == null ? p.nombre + " quedó sin costo registrado."
                    : String.format("Costo de %s: %.2f soles (margen de %.2f soles sobre el precio de venta).", p.nombre, costo,
                            p.precio.subtract(costo)));
        }
        if (c.precio() != null || c.ficha() != null && c.ficha().costo() != null) {
            String aviso = avisoCosto(p);
            if (!aviso.isEmpty()) hecho.add(aviso.trim());
        }
        if (hecho.isEmpty()) hecho.add("Guardé los cambios de " + p.nombre + ".");
        return Resultado.ok(persistir(String.join(" ", hecho)));
    }

    /** Para el historial: "(ninguno)" en lugar de un texto vacío. */
    private static String sinDato(String s) { return s.isEmpty() ? "(ninguno)" : s; }

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

    /** Voz: pregunta antes de vaciar el inventario para empezar uno nuevo (se responde con sí/no). */
    public synchronized String pedirNuevoInventario() {
        if (productos.isEmpty() && ventas.isEmpty()) return "El inventario ya está vacío, señor. Puede agregar productos cuando quiera.";
        return preguntar(pend(Tipo.NUEVO_INVENTARIO, null, null, null, resumenAntesDeVaciar() + " Diga sí o no."));
    }

    /** "¿Confirma empezar un inventario nuevo? Se quitarán N productos y M ventas…" (la usa también el asistente con IA). */
    public synchronized String resumenAntesDeVaciar() {
        return "¿Confirma empezar un inventario nuevo desde cero? Se quitarán " + productos.size()
                + (productos.size() == 1 ? " producto" : " productos") + " y " + ventas.size()
                + (ventas.size() == 1 ? " línea de venta" : " líneas de venta") + ". Antes guardaré una copia del inventario actual.";
    }

    /**
     * Empieza un inventario nuevo: quita todos los productos y las ventas, y cancela las ventas en curso.
     * Antes copia la base actual a {@code jarvis-inventario.db.antes-de-nuevo-<fecha>}; si esa copia falla, no borra nada.
     * Se conserva el correlativo de comprobantes (no se repiten números) y el historial de movimientos.
     */
    public synchronized Resultado empezarDeCero() {
        Path base = almacen.archivo();
        Path copia = base.resolveSibling(base.getFileName() + ".antes-de-nuevo-" + LocalDateTime.now().format(SELLO));
        try {
            almacen.copiarA(copia);
        } catch (SQLException e) {
            log.error("No empecé el inventario nuevo: falló la copia de respaldo {}: {}", copia, e.getMessage());
            return Resultado.error("No pude guardar la copia del inventario actual, así que no borré nada, señor.");
        }
        int n = productos.size();
        porRegistrar.add(new Almacen.Pendiente(new Movimiento(LocalDateTime.now().withNano(0).toString(), "NUEVO_INVENTARIO",
                "(todos)", null, n + " productos y " + ventas.size() + " líneas de venta; copia en " + copia.getFileName(),
                ClientSession.usuario()), null));
        productos = new ArrayList<>();
        ventas = new ArrayList<>();
        cabeceras = new HashMap<>();   // los correlativos siguen: nunca se repite un número de boleta
        for (Sesion s : sesiones.values()) { s.carrito = null; s.comprobanteNuevo = null; s.opciones = Opciones.POR_DEFECTO; }
        return Resultado.ok(persistir("Listo, señor: empecé un inventario nuevo, vacío. La copia del anterior (" + n
                + (n == 1 ? " producto" : " productos") + ") quedó en " + copia.getFileName() + "."));
    }

    /** Últimos movimientos del historial, del más reciente al más antiguo. */
    public List<Movimiento> movimientos(int n) {
        synchronized (this) { return almacen.ultimosMovimientos(Math.min(Math.max(n, 1), 1000)); }
    }

    /**
     * Cambia cada palabra que sea el código (interno o de barras) de un producto por el nombre de ese producto.
     * Los números de hasta 5 cifras no se tocan: son cantidades o tallas ("vendí 2 polos", "zapatilla 40"),
     * aunque algún producto tenga ese código; un código de barras tiene 8 a 13 cifras.
     */
    public synchronized String codigosANombres(String cmd) {
        if (cmd == null || productos.stream().allMatch(p -> p.codigo.isEmpty() && p.codigoBarras.isEmpty())) return cmd;
        StringBuilder out = new StringBuilder();
        for (String palabra : cmd.split(" ", -1)) {
            Producto p = palabra.matches("\\d{1,5}") ? null : conCodigo(palabra, null);
            if (out.length() > 0) out.append(' ');
            out.append(p == null ? palabra : Palabras.norm(p.nombre));
        }
        return out.toString();
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
                    ? salida(p.cantidad, p.producto.nombre, true, p.opciones) : "Ese producto ya no existe, señor.";
            case ANULAR -> anular(p.numero).mensaje();
            case ELIMINAR -> productos.contains(p.producto)
                    ? eliminarProducto(p.producto.nombre, true).mensaje() : "Ese producto ya no existe, señor.";
            case NUEVO_INVENTARIO -> empezarDeCero().mensaje();
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
        // un código exacto (escrito o leído con el escáner) gana a cualquier parecido por nombre
        Producto porCodigo = conCodigo(texto == null ? null : texto.trim(), null);
        if (porCodigo != null) return new Match(porCodigo, false, List.of(porCodigo));
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
        double best = sim(q, normalizado(p.nombre));
        for (String a : p.alias) best = Math.max(best, sim(q, normalizado(a)));
        return best;
    }

    /** Nombres ya normalizados: cada búsqueda recorre todos los productos y normalizar cuesta (con miles se notaba). */
    private final Map<String, String> normalizados = new HashMap<>();

    private String normalizado(String s) {
        if (normalizados.size() > 100_000) normalizados.clear();
        return normalizados.computeIfAbsent(s, Palabras::norm);
    }

    /** Lo mismo con las palabras de cada nombre ya normalizado. */
    private final Map<String, Set<String>> palabras = new HashMap<>();

    private Set<String> palabrasDe(String normalizado) {
        if (palabras.size() > 100_000) palabras.clear();
        return palabras.computeIfAbsent(normalizado, this::tokens);
    }

    private double sim(String q, String cand) {
        if (cand.isBlank()) return 0;
        if (q.equals(cand)) return 1.0;                                   // nombre exacto: gana siempre
        Set<String> qt = tokens(q), ct = palabrasDe(cand);
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
