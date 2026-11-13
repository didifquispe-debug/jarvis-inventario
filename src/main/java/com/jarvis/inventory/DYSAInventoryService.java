/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.inventory.BuscadorProductos.Match;
import com.jarvis.security.ClientSession;
import com.jarvis.security.Rol.Permiso;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
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

import static com.jarvis.inventory.BuscadorProductos.cualDe;
import static com.jarvis.inventory.Estadisticas.enPeriodo;

/** El inventario de una tienda. En la aplicación se usa el de la tienda del pedido en curso (ver TiendaScope y Tiendas). */
@Service
@Scope(value = TiendaScope.NOMBRE, proxyMode = ScopedProxyMode.TARGET_CLASS)
public class DYSAInventoryService {

    private static final Logger log = LoggerFactory.getLogger(DYSAInventoryService.class);

    public enum Tipo { ENTRADA, SALIDA, ANULAR, ELIMINAR, NUEVO_INVENTARIO }

    public static class Pendiente {
        public Tipo tipo; public Producto producto; public BigDecimal cantidad; public BigDecimal precio; public String unidad;
        public int numero;
        public Opciones opciones;
        public String pregunta;
    }

    public static class Venta {
        public int numero;
        public String fecha; public String hora;
        public String producto; public BigDecimal cantidad; public String unidad;
        public BigDecimal precioUnitario; public BigDecimal monto;
        public BigDecimal costoUnitario;
        public boolean anulada;
        Long id, comprobanteId;
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

    public static class Comprobante {
        public int numero; public String fecha; public String hora;
        public String tipo;
        public String tipoDescripcion;
        public String serie; public int correlativo;
        public String codigo;
        public ClienteResumen cliente;
        public String modoIgv; public String leyendaIgv;
        public BigDecimal base; public BigDecimal igv; public BigDecimal tasaIgv;
        public List<Venta> items = new ArrayList<>();
        public BigDecimal total;
        public String totalEnLetras;
        public boolean anulado;

        Comprobante(int numero, String fecha, String hora, List<Venta> items, Cabecera cab) {
            this.numero = numero; this.fecha = fecha; this.hora = hora; this.items = items;
            TipoComprobante t = cab.tipo();
            this.tipo = t.name(); this.tipoDescripcion = t.descripcion; this.serie = cab.serie();
            this.correlativo = cab.correlativo();
            this.codigo = cab.correlativo() > 0 ? TipoComprobante.codigo(cab.serie(), cab.correlativo()) : "";
            this.cliente = cab.clienteDoc() == null || cab.clienteDoc().isBlank() ? null
                    : new ClienteResumen(cab.clienteDoc(), Clientes.tipo(cab.clienteDoc()), cab.clienteNombre());
            this.modoIgv = cab.modo().name(); this.leyendaIgv = cab.modo().leyenda;
            Igv.Totales tot = Igv.calcular(items.stream().map(v -> v.monto).toList(), cab.modo());
            this.base = tot.base(); this.igv = tot.igv(); this.tasaIgv = tot.tasa(); this.total = tot.total();
            this.totalEnLetras = NumeroALetras.importe(total);
            this.anulado = !items.isEmpty() && items.stream().allMatch(v -> v.anulada);
        }

        public String hablado() { return TipoComprobante.valueOf(tipo).hablado + " número " + correlativo; }
    }

    public record ClienteResumen(String documento, String tipoDocumento, String nombre) {}

    /** {@code serie} es la que se usó al emitir (la de la tienda en ese momento); no se recalcula. */
    record Cabecera(TipoComprobante tipo, String serie, int correlativo, Long clienteId, String clienteDoc, String clienteNombre,
                    Igv.Modo modo) {}

    public record Opciones(TipoComprobante tipo, String clienteDoc, Igv.Modo modo) {
        public static final Opciones POR_DEFECTO = new Opciones(null, null, null);

        public Opciones con(Opciones otras) {
            if (otras == null) return this;
            return new Opciones(otras.tipo != null ? otras.tipo : tipo,
                    otras.clienteDoc != null ? otras.clienteDoc : clienteDoc, otras.modo != null ? otras.modo : modo);
        }

        public boolean vacia() { return tipo == null && (clienteDoc == null || clienteDoc.isBlank()) && modo == null; }
    }

    public record Linea(Producto producto, BigDecimal cantidad) {}

    public enum Estado { OK, ERROR, NO_ENCONTRADO, PROHIBIDO }
    public record Resultado(Estado estado, String mensaje) {
        public static Resultado ok(String m) { return new Resultado(Estado.OK, m); }
        public static Resultado error(String m) { return new Resultado(Estado.ERROR, m); }
        public static Resultado noEncontrado(String m) { return new Resultado(Estado.NO_ENCONTRADO, m); }
        public static Resultado prohibido(String m) { return new Resultado(Estado.PROHIBIDO, m); }
        public boolean ok() { return estado == Estado.OK; }
    }

    private static class Sesion {
        Pendiente pendiente;
        List<Linea> carrito;
        Opciones opciones = Opciones.POR_DEFECTO;
        Comprobante comprobanteNuevo;
        long usado;
    }

    private static final long SESION_INACTIVA_MS = 4 * 60 * 60 * 1000L;
    private static final BigDecimal CONFIRMAR_DESDE = BigDecimal.valueOf(100);
    private static final BigDecimal BOLETA_CON_DNI_DESDE = BigDecimal.valueOf(700);
    private static final DateTimeFormatter SELLO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObjectMapper json = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final Path archivoJson;
    private final Almacen almacen;

    private List<Producto> productos = new ArrayList<>();
    private List<Venta> ventas = new ArrayList<>();
    private int ultimoNumero;
    private Map<Integer, Cabecera> cabeceras = new HashMap<>();
    private final Map<TipoComprobante, Integer> correlativos = new EnumMap<>(TipoComprobante.class);
    private final int tienda;
    private final TipoComprobante tipoPorDefecto;
    private final Igv.Modo igvPorDefecto;
    private final Clientes clientes;
    private final Compras compras;
    private final Usuarios usuarios;
    private final Ajustes ajustes;
    private final Map<String, Sesion> sesiones = new HashMap<>();
    private final List<Almacen.Pendiente> porRegistrar = new ArrayList<>();

    public DYSAInventoryService(String archivo) {
        this(archivo, TipoComprobante.BOLETA.name(), Igv.Modo.INCLUIDO.name());
    }

    @Autowired
    public DYSAInventoryService(@Value("${jarvis.inventory.file:${user.home}/jarvis-inventario.json}") String archivo,
                            @Value("${jarvis.comprobante.tipo:boleta}") String tipo,
                            @Value("${jarvis.igv:incluido}") String igv) {
        this(archivo, tipo, igv, ClientSession.TIENDA_PRINCIPAL);
    }

    /** {@code tienda} decide la serie de sus comprobantes (ver TipoComprobante.serie). */
    public DYSAInventoryService(String archivo, String tipo, String igv, int tienda) {
        this.tienda = tienda;
        this.tipoPorDefecto = Objects.requireNonNullElse(TipoComprobante.de(tipo), TipoComprobante.BOLETA);
        this.igvPorDefecto = Igv.Modo.de(igv);
        Path ruta = Paths.get(archivo);
        String nombre = ruta.getFileName().toString();
        boolean esBase = nombre.endsWith(".db");
        this.archivoJson = esBase ? ruta.resolveSibling(nombre.replaceFirst("\\.db$", ".json")) : ruta;
        this.almacen = new Almacen(esBase ? ruta : ruta.resolveSibling(nombre.replaceFirst("\\.json$", "") + ".db"));
        this.clientes = new Clientes(almacen.baseDatos());
        this.compras = new Compras(almacen.baseDatos());
        this.usuarios = new Usuarios(almacen.baseDatos());
        this.ajustes = new Ajustes(almacen.baseDatos());
        leerCarpetaExtra();
        cargar();
    }

    // ---------------------------------------------------------------- copias de seguridad

    public static final String AJUSTE_CARPETA_RESPALDO = "respaldo.carpeta";

    public record Respaldo(String nombre, String tipo, String fecha, long bytes) {}

    public synchronized List<Respaldo> respaldos() throws IOException {
        return almacen.baseDatos().listarRespaldos().stream()
                .map(r -> new Respaldo(r.nombre(), r.tipo(), r.fecha().toString(), r.bytes())).toList();
    }

    /** Ruta del respaldo para descargarlo; null si no existe. */
    public synchronized Path archivoRespaldo(String nombre) throws IOException {
        return almacen.baseDatos().respaldo(nombre);
    }

    public String carpetaExtra() {
        Path c = almacen.baseDatos().carpetaExtra();
        return c == null ? "" : c.toString();
    }

    public synchronized Resultado respaldarAhora() {
        String no = ClientSession.sinPermiso(Permiso.ADMINISTRAR);
        if (no != null) return Resultado.prohibido(no);
        try {
            String nombre = almacen.baseDatos().respaldoManual();
            Path extra = almacen.baseDatos().carpetaExtra();
            return Resultado.ok("Copia de seguridad lista: " + nombre + (extra == null ? "." : ", y otra en " + extra + "."));
        } catch (SQLException e) {
            log.error("No pude hacer la copia de seguridad: {}", e.getMessage());
            return Resultado.error("No pude hacer la copia de seguridad: " + e.getMessage());
        } catch (IllegalStateException e) {
            return Resultado.error("Atención: " + e.getMessage() + ".");
        }
    }

    public synchronized Resultado restaurar(String nombre) {
        String no = ClientSession.sinPermiso(Permiso.ADMINISTRAR);
        if (no != null) return Resultado.prohibido(no);
        Path origen;
        try {
            origen = almacen.baseDatos().respaldo(nombre);
        } catch (IOException e) {
            return Resultado.error("No pude leer la carpeta de respaldos: " + e.getMessage());
        }
        if (origen == null) return Resultado.noEncontrado("No encuentro ese respaldo; actualice la lista.");
        String antes;
        try {
            antes = almacen.baseDatos().restaurarDesde(origen);
        } catch (SQLException e) {
            log.error("No restauré {}: {}", origen, e.getMessage());
            return Resultado.error("No restauré nada: " + e.getMessage() + ". El inventario sigue como estaba.");
        }
        porRegistrar.clear();
        recargar();
        for (Sesion s : sesiones.values()) s.opciones = Opciones.POR_DEFECTO;
        usuarios.cerrarTodas();
        leerCarpetaExtra();
        log.warn("Inventario restaurado desde {}. El anterior quedó en {}.", origen.getFileName(), antes);
        return Resultado.ok("Listo: restauré el inventario de la copia " + nombre + ". Por si acaso, el que había quedó guardado en "
                + antes + ". Los demás usuarios deben volver a entrar.");
    }

    /** Carpeta aparte (USB, OneDrive) para otra copia de cada respaldo. Vacía para no usar ninguna. */
    public synchronized Resultado usarCarpetaExtra(String texto) {
        String no = ClientSession.sinPermiso(Permiso.ADMINISTRAR);
        if (no != null) return Resultado.prohibido(no);
        String t = texto == null ? "" : texto.trim().replaceAll("^\"|\"$", "");
        Path carpeta = null;
        if (!t.isEmpty()) {
            try {
                carpeta = Paths.get(t);
            } catch (InvalidPathException e) {
                return Resultado.error("Esa ruta no es válida: " + t);
            }
            if (!carpeta.isAbsolute()) return Resultado.error("Escriba la ruta completa de la carpeta, por ejemplo D:\\Respaldos JARVIS.");
            carpeta = carpeta.normalize();
            if (carpeta.equals(almacen.archivo().toAbsolutePath().getParent().normalize()))
                return Resultado.error("Elija otra carpeta: esa es donde ya está el inventario.");
            try {
                Files.createDirectories(carpeta);
                Path prueba = Files.createTempFile(carpeta, "jarvis-prueba", ".tmp");
                Files.delete(prueba);
            } catch (IOException | RuntimeException e) {
                return Resultado.error("No puedo escribir en " + carpeta + ". ¿Está conectada la USB o el disco?");
            }
        }
        try {
            ajustes.guardar(Map.of(AJUSTE_CARPETA_RESPALDO, carpeta == null ? "" : carpeta.toString()));
        } catch (SQLException e) {
            return Resultado.error("No pude guardar la carpeta: " + e.getMessage());
        }
        almacen.baseDatos().usarCarpetaExtra(carpeta);
        return Resultado.ok(carpeta == null ? "Listo: ya no guardo copias en otra carpeta."
                : "Listo: cada respaldo también se guardará en " + carpeta + ".");
    }

    private void leerCarpetaExtra() {
        String t = "";
        try {
            t = ajustes.leer().getOrDefault(AJUSTE_CARPETA_RESPALDO, "").trim();
        } catch (RuntimeException e) {
            log.warn("No pude leer la carpeta de respaldos: {}", e.getMessage());
        }
        Path carpeta = null;
        try {
            if (!t.isEmpty()) carpeta = Paths.get(t);
        } catch (InvalidPathException e) {
            log.warn("La carpeta de respaldos guardada no es válida: {}", t);
        }
        almacen.baseDatos().usarCarpetaExtra(carpeta);
    }

    public synchronized boolean hayPendiente() { return sesion().pendiente != null; }

    public synchronized List<Producto> productos() { return new ArrayList<>(productos); }

    public Path archivoBaseDatos() { return almacen.archivo(); }

    public Clientes clientes() { return clientes; }

    public Compras compras() { return compras; }

    public Usuarios usuarios() { return usuarios; }

    public Ajustes ajustes() { return ajustes; }

    DidierBaseDatos baseDatos() { return almacen.baseDatos(); }

    public TipoComprobante tipoPorDefecto() { return tipoPorDefecto; }

    public Igv.Modo igvPorDefecto() { return igvPorDefecto; }

    private Sesion sesion() {
        long ahora = System.currentTimeMillis();
        sesiones.values().removeIf(s -> ahora - s.usado > SESION_INACTIVA_MS);
        Sesion s = sesiones.computeIfAbsent(ClientSession.id(), k -> new Sesion());
        s.usado = ahora;
        return s;
    }

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

    private void recalcularContadores(Map<String, Integer> guardados) {
        ultimoNumero = Math.max(guardados.getOrDefault(Almacen.ULTIMO_COMPROBANTE, 0),
                ventas.stream().mapToInt(v -> v.numero).max().orElse(0));
        correlativos.clear();
        for (TipoComprobante t : TipoComprobante.values()) correlativos.put(t, guardados.getOrDefault("serie:" + t.serie(tienda), 0));
        for (Venta v : ventas) {
            if (v.numero <= 0) continue;
            Cabecera c = Almacen.cabecera(cabeceras, v.numero);
            // Los emitidos con otra serie (antes de que cada tienda tuviera la suya) no cuentan para la serie actual.
            if (c.serie().equals(c.tipo().serie(tienda))) correlativos.merge(c.tipo(), c.correlativo(), Math::max);
        }
    }

    private Map<String, Integer> contadores() {
        Map<String, Integer> m = new HashMap<>();
        m.put(Almacen.ULTIMO_COMPROBANTE, ultimoNumero);
        correlativos.forEach((t, n) -> m.put("serie:" + t.serie(tienda), n));
        return m;
    }

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

    private String persistir(String msg) {
        return guardarTodo(null) ? msg : NO_GUARDADO;
    }

    private static final String NO_GUARDADO =
            "Atención: no pude guardar en la base de datos, así que no se registró nada, señor. Revise jarvis.log.";

    private boolean guardarTodo(DidierBaseDatos.Trabajo<?> extra) {
        almacen.respaldoDiario();
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

    private static final BigDecimal UNO_MAS_IGV = BigDecimal.ONE.add(Igv.TASA);

    public synchronized Resultado registrarCompra(Compras.NuevaCompra c) {
        String no = ClientSession.sinPermiso(Permiso.COMPRAS);
        if (no != null) return Resultado.prohibido(no);
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
        return Resultado.ok(String.format("Compra %s registrada: %s %s de %s por %.2f " + Moneda.nombre() + ". Ingresaron %d %s al stock.",
                codigo, tipo.descripcion.toLowerCase(), numeroDoc, razon, totales.total(), detalles.size(),
                detalles.size() == 1 ? "producto" : "productos"));
    }

    public synchronized Resultado anularCompra(String codigoONumero) {
        String no = ClientSession.sinPermiso(Permiso.COMPRAS);
        if (no != null) return Resultado.prohibido(no);
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

    private void registrar(String tipo, Producto p, BigDecimal cantidad, String detalle, BigDecimal delta) {
        BigDecimal saldo = delta == null ? null : "ELIMINAR".equals(tipo) ? BigDecimal.ZERO : p.stock;
        porRegistrar.add(new Almacen.Pendiente(new Movimiento(LocalDateTime.now().withNano(0).toString(), tipo, p.nombre,
                cantidad == null ? null : Num.cantidad(cantidad), detalle, ClientSession.usuario()), p,
                delta == null ? null : Num.cantidad(delta), saldo == null ? null : Num.cantidad(saldo), delta == null ? null : p.costo));
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    private static String errorDecimales(Producto p, BigDecimal cantidad) {
        if (Num.tieneDecimales(cantidad) && !p.admiteDecimales())
            return p.nombre + " no admite cantidades con decimales (se maneja en " + p.unidad + "), señor. No registré nada.";
        return null;
    }

    private static boolean cantidadInvalida(BigDecimal c) { return c == null || c.signum() <= 0; }

    public synchronized String entrada(BigDecimal cantidad, String textoProducto, BigDecimal precio, String unidad, boolean confirmado) {
        String no = ClientSession.sinPermiso(Permiso.INVENTARIO);
        if (no != null) return no;
        if (cantidadInvalida(cantidad)) return "No entendí la cantidad, señor. Por favor repita la orden.";
        cantidad = Num.cantidad(cantidad);
        Match m = buscar(textoProducto);

        if (m == null) {
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

    public synchronized String salida(BigDecimal cantidad, String textoProducto, boolean confirmado, Opciones opciones) {
        String no = ClientSession.sinPermiso(Permiso.VENDER);
        if (no != null) return no;
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

    public record Pedido(String nombre, BigDecimal cantidad) {}

    public synchronized Resultado vender(List<Pedido> pedido, Opciones opciones) {
        String no = ClientSession.sinPermiso(Permiso.VENDER);
        if (no != null) return Resultado.prohibido(no);
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

    public record FilaComprobante(int numero, String codigo, String tipo, String fecha, String hora, String cliente,
                                  int productos, BigDecimal total, boolean anulado) {}

    public synchronized List<FilaComprobante> comprobantes(int limite) {
        return comprobantes(limite, null, null);
    }

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

    public record Kardex(String producto, String unidad, BigDecimal stock, BigDecimal costo, List<FilaKardex> filas) {}

    public synchronized Kardex kardex(String nombre, int limite) {
        Producto p = porNombre(nombre);
        if (p == null) return null;
        return new Kardex(p.nombre, p.unidad, p.stock, p.costo, almacen.kardex(p.id, p.nombre, limite));
    }

    public synchronized List<FilaKardex> kardexCompleto() {
        return almacen.kardex(null, null, 100_000);
    }

    public record FilaValorizada(String producto, String categoria, String codigo, String unidad, BigDecimal stock, BigDecimal stockMin,
                                 String estado, BigDecimal costo, BigDecimal valorCosto, BigDecimal precio, BigDecimal valorVenta) {}

    public record Valorizado(List<FilaValorizada> filas, int productos, int sinCosto, int stockBajo, int sinStock,
                             BigDecimal valorCosto, BigDecimal valorVenta, BigDecimal margen) {}

    public synchronized Valorizado valorizado() {
        return Estadisticas.valorizado(productos);
    }

    public record Periodo(String desde, String hasta, int ventas, int anuladas, BigDecimal total, BigDecimal base, BigDecimal costo,
                          BigDecimal ganancia, int lineasSinCosto) {}

    public record DiaPanel(String fecha, int ventas, BigDecimal total, BigDecimal ganancia) {}

    /** {@code ganancia} es null si ninguna de sus ventas tenía costo registrado. */
    public record MasVendido(String producto, String unidad, BigDecimal cantidad, BigDecimal total, BigDecimal ganancia) {}

    public record Alerta(String producto, String unidad, BigDecimal stock, BigDecimal stockMin, String estado) {}

    public record SinMovimiento(String producto, String unidad, BigDecimal stock, BigDecimal valorVenta, String ultimaVenta) {}

    public record Panel(String hoy, Periodo deHoy, Periodo semana, Periodo mes, Periodo rango, List<DiaPanel> porDia,
                        List<MasVendido> masVendidos, List<Alerta> alertas, List<SinMovimiento> sinMovimiento, int diasSinMovimiento) {}

    public static final int DIAS_SIN_MOVIMIENTO = 30;

    /** Ventas y ganancia de hoy, la semana (desde el lunes), el mes y los últimos {@code dias} días, más alertas de stock. */
    public synchronized Panel panel(int dias) {
        return panel(LocalDate.now(), dias);
    }

    synchronized Panel panel(LocalDate hoy, int dias) {
        return Estadisticas.panel(ventasPeriodo(null, null), productos, hoy, dias);
    }

    /** Ventas y ganancia entre dos fechas (inclusive). */
    public synchronized Periodo ventasDe(LocalDate desde, LocalDate hasta) {
        return Estadisticas.periodo(ventasPeriodo(desde.toString(), hasta.toString()), desde, hasta);
    }

    /** Lo más vendido entre dos fechas, de mayor a menor importe. */
    public synchronized List<MasVendido> masVendidos(LocalDate desde, LocalDate hasta, int limite) {
        return Estadisticas.masVendidos(ventasPeriodo(desde.toString(), hasta.toString()).stream().filter(c -> !c.anulado).toList(), limite);
    }

    /** Nombre exacto del producto que se entiende por {@code texto}, o null si no se reconoce. */
    public synchronized String nombreDe(String texto) {
        Match m = buscar(texto);
        return m == null || m.empate() ? null : m.producto.nombre;
    }

    public synchronized List<Comprobante> ventasPeriodo(String desde, String hasta) {
        Map<Integer, List<Venta>> porNumero = new TreeMap<>();
        for (Venta v : ventas)
            if (v.numero > 0 && enPeriodo(v.fecha, desde, hasta)) porNumero.computeIfAbsent(v.numero, k -> new ArrayList<>()).add(v);
        List<Comprobante> out = new ArrayList<>();
        porNumero.forEach((n, items) -> out.add(new Comprobante(n, items.get(0).fecha, items.get(0).hora, items, Almacen.cabecera(cabeceras, n))));
        return out;
    }

    public synchronized Comprobante comprobantePorNumero(int numero) {
        return numero <= 0 ? null : comprobante(numero);
    }

    public synchronized String venderVarios(List<BigDecimal> cantidades, List<String> textos) {
        return venderVarios(cantidades, textos, Opciones.POR_DEFECTO);
    }

    public synchronized String venderVarios(List<BigDecimal> cantidades, List<String> textos, Opciones opciones) {
        String no = ClientSession.sinPermiso(Permiso.VENDER);
        if (no != null) return no;
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
        Cabecera cab = new Cabecera(tipo, tipo.serie(tienda), correlativos.merge(tipo, 1, Integer::sum), cliente == null ? null : cliente.id(),
                cliente == null ? "" : cliente.documento(), cliente == null ? "" : cliente.nombre(), modo);
        cabeceras.put(numero, cab);
        String fecha = LocalDate.now().toString(), hora = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        List<Venta> items = new ArrayList<>();
        for (Linea l : lineas) {
            Producto p = l.producto();
            p.stock = Num.cantidad(p.stock.subtract(l.cantidad()));
            items.add(new Venta(numero, fecha, hora, p, l.cantidad()));
            registrar("VENTA", p, l.cantidad(), TipoComprobante.codigo(cab.serie(), cab.correlativo()) + ", stock " + Num.txt(p.stock));
        }
        ventas.addAll(items);
        Comprobante c = new Comprobante(numero, fecha, hora, items, cab);
        sesion().comprobanteNuevo = c;

        String msg;
        if (items.size() == 1) {
            Venta v = items.get(0);
            Producto p = lineas.get(0).producto();
            msg = "Venta registrada: " + Num.txt(v.cantidad) + " " + v.unidad + " de " + v.producto
                    + String.format(". Total: %.2f " + Moneda.nombre() + ". %s. Stock actual: %s.", c.total, capitaliza(c.hablado()), Num.txt(p.stock));
        } else {
            msg = String.format("Venta registrada: %d productos. Total: %.2f " + Moneda.nombre() + ". %s.", items.size(), c.total, capitaliza(c.hablado()));
        }
        if (cliente != null) msg += " Cliente: " + cliente.nombre() + ".";
        else if (tipo == TipoComprobante.BOLETA && c.total.compareTo(BOLETA_CON_DNI_DESDE) >= 0)
            msg += " Atención: en boletas de 700 " + Moneda.nombre() + " o más se debe identificar al cliente con su DNI.";
        for (Producto p : pedido.keySet()) {
            String quien = items.size() == 1 ? "el producto" : p.nombre;
            if (p.precio.signum() <= 0) msg += " Atención: " + quien + " no tiene precio registrado.";
            if (p.stockBajo()) msg += " Atención: stock bajo" + (items.size() == 1 ? "" : " de " + p.nombre) + ".";
        }
        return persistir(msg);
    }

    public synchronized boolean ventaAbierta() { return sesion().carrito != null; }

    public synchronized String abrirVenta() {
        Sesion s = sesion();
        if (s.carrito != null && !s.carrito.isEmpty())
            return "Ya hay una venta en curso con " + s.carrito.size() + " productos. Diga cobrar o cancelar venta.";
        s.carrito = new ArrayList<>();
        return "Venta nueva, señor. Dígame los productos y, al terminar, diga cobrar.";
    }

    public synchronized String abrirVenta(Opciones opciones) {
        String no = ClientSession.sinPermiso(Permiso.VENDER);
        if (no != null) return no;
        String msg = abrirVenta();
        if (opciones == null || opciones.vacia() || !msg.startsWith("Venta nueva")) return msg;
        String r = opcionesVenta(opciones);
        return r.startsWith("No tengo") ? r : msg + " " + r;
    }

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
        String no = ClientSession.sinPermiso(Permiso.VENDER);
        if (no != null) return no;
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
        return String.format("Agregado: %s %s de %s, %.2f " + Moneda.nombre() + ". Total parcial: %.2f " + Moneda.nombre() + ".",
                Num.txt(cantidad), p.unidad, p.nombre, Num.monto(p.precio, cantidad), totalCarrito(s));
    }

    public synchronized String quitarDelCarrito(String textoProducto) {
        Sesion s = sesion();
        if (s.carrito == null || s.carrito.isEmpty()) return "No hay productos en la venta, señor.";
        Match m = buscar(textoProducto);
        if (m == null || !s.carrito.removeIf(l -> l.producto() == m.producto))
            return "Ese producto no está en la venta, señor.";
        return String.format("Quitado: %s. Total parcial: %.2f " + Moneda.nombre() + ".", m.producto.nombre, totalCarrito(s));
    }

    public synchronized String verCarrito() {
        Sesion s = sesion();
        if (s.carrito == null || s.carrito.isEmpty()) return "La venta está vacía, señor.";
        StringBuilder sb = new StringBuilder("Llevamos: ");
        for (int i = 0; i < s.carrito.size(); i++) {
            Linea l = s.carrito.get(i);
            sb.append(i == 0 ? "" : ", ").append(Num.txt(l.cantidad())).append(" ").append(l.producto().unidad).append(" de ").append(l.producto().nombre);
        }
        return sb.append(String.format(". Total: %.2f " + Moneda.nombre() + ".", totalCarrito(s))).toString();
    }

    public synchronized String cobrar() {
        String no = ClientSession.sinPermiso(Permiso.VENDER);
        if (no != null) return no;
        Sesion s = sesion();
        if (s.carrito == null || s.carrito.isEmpty()) return "La venta está vacía, señor. Dígame qué productos lleva el cliente.";
        String msg = emitir(s.carrito, s.opciones);
        if (msg.startsWith("Venta registrada")) { s.carrito = null; s.opciones = Opciones.POR_DEFECTO; }
        return msg;
    }

    public synchronized String cancelarVenta() {
        Sesion s = sesion();
        if (s.carrito == null) return "No hay ninguna venta en curso, señor.";
        s.carrito = null;
        s.opciones = Opciones.POR_DEFECTO;
        return "Venta cancelada, señor. No se descontó nada del inventario.";
    }

    public synchronized Comprobante carritoActual() {
        Sesion s = sesion();
        if (s.carrito == null) return null;
        List<Venta> items = s.carrito.stream().map(l -> new Venta(0, null, null, l.producto(), l.cantidad())).toList();
        String doc = s.opciones.clienteDoc() == null ? "" : s.opciones.clienteDoc().trim();
        String nombre = doc.isEmpty() ? "" : clientes.porDocumento(doc).map(Clientes.Cliente::nombre).orElse("");
        TipoComprobante tipo = s.opciones.tipo() != null ? s.opciones.tipo() : tipoPorDefecto;
        return new Comprobante(0, null, null, new ArrayList<>(items), new Cabecera(tipo, tipo.serie(tienda), 0, null, doc, nombre,
                s.opciones.modo() != null ? s.opciones.modo() : igvPorDefecto));
    }

    private BigDecimal totalCarrito(Sesion s) {
        return s.carrito.stream().map(l -> Num.monto(l.producto().precio, l.cantidad())).reduce(Num.dinero(BigDecimal.ZERO), BigDecimal::add);
    }

    public synchronized String listaPrecios() {
        if (productos.isEmpty()) return "El inventario está vacío, señor.";
        StringBuilder sb = new StringBuilder("Lista de precios:\n");
        for (Producto p : productos)
            sb.append(String.format("• %s: %.2f " + Moneda.nombre() + " (stock: %s %s)%n", p.nombre, p.precio, Num.txt(p.stock), p.unidad));
        return sb.toString().trim();
    }

    public synchronized String precio(String textoProducto) {
        Match m = buscar(textoProducto);
        if (m == null) return "No encontré ese producto, señor.";
        if (m.empate()) {
            StringBuilder sb = new StringBuilder("Tengo varios parecidos:");
            for (Producto x : m.empatados) sb.append(String.format(" %s cuesta %.2f " + Moneda.nombre() + ".", x.nombre, x.precio));
            return sb.toString();
        }
        Producto p = m.producto;
        if (p.precio.signum() <= 0) return p.nombre + " aún no tiene precio registrado, señor.";
        return String.format("%s cuesta %.2f " + Moneda.nombre() + ". Quedan %s %s.", p.nombre, p.precio, Num.txt(p.stock), p.unidad);
    }

    public synchronized String cambiarPrecio(String textoProducto, BigDecimal nuevo) {
        String no = ClientSession.sinPermiso(Permiso.INVENTARIO);
        if (no != null) return no;
        return editarProducto(textoProducto, false, nuevo, null, null).mensaje();
    }

    public synchronized String cambiarStockMinimo(String textoProducto, BigDecimal minimo) {
        String no = ClientSession.sinPermiso(Permiso.INVENTARIO);
        if (no != null) return no;
        return editarProducto(textoProducto, false, null, minimo, null).mensaje();
    }

    public synchronized String cambiarUnidad(String textoProducto, String unidad) {
        String no = ClientSession.sinPermiso(Permiso.INVENTARIO);
        if (no != null) return no;
        return editarProducto(textoProducto, false, null, null, unidad).mensaje();
    }

    public synchronized String stockMinimo(String textoProducto) {
        Match m = buscar(textoProducto);
        if (m == null) return "No encontré ese producto, señor.";
        if (m.empate()) return cualDe(m);
        return "El stock mínimo de " + m.producto.nombre + " es " + Num.txt(m.producto.stockMin) + " " + m.producto.unidad + ".";
    }

    public synchronized String consulta(String textoProducto) {
        Match m = buscar(textoProducto);
        if (m == null) return "No encontré ese producto, señor.";
        if (m.empate()) {
            StringBuilder sb = new StringBuilder("Tengo varios:");
            for (Producto x : m.empatados) sb.append(" ").append(x.nombre).append(": ").append(Num.txt(x.stock)).append(" ").append(x.unidad).append(".");
            return sb.toString();
        }
        Producto p = m.producto;
        String msg = "Quedan " + Num.txt(p.stock) + " " + p.unidad + " de " + p.nombre + ".";
        if (p.stockBajo()) msg += " Atención: stock bajo.";
        return msg;
    }

    public synchronized String cierre() {
        String hoy = LocalDate.now().toString();
        List<Venta> delDia = ventas.stream().filter(v -> hoy.equals(v.fecha)).toList();
        List<Venta> validas = delDia.stream().filter(v -> !v.anulada).toList();
        long anuladas = delDia.stream().filter(v -> v.anulada && v.numero > 0).map(v -> v.numero).distinct().count();
        if (validas.isEmpty())
            return "Cierre del día: no se registraron ventas hoy, señor." + (anuladas > 0 ? " Ventas anuladas: " + anuladas + "." : "");
        BigDecimal total = validas.stream().filter(v -> v.numero > 0).map(v -> v.numero).distinct()
                .map(n -> comprobante(n).total).reduce(Num.dinero(BigDecimal.ZERO), BigDecimal::add)
                .add(validas.stream().filter(v -> v.numero == 0).map(v -> v.monto).reduce(BigDecimal.ZERO, BigDecimal::add));
        long n = validas.stream().filter(v -> v.numero > 0).map(v -> v.numero).distinct().count()
                + validas.stream().filter(v -> v.numero == 0).count();
        Map<String, BigDecimal> porProducto = new HashMap<>();
        for (Venta v : validas) porProducto.merge(v.producto, v.cantidad, BigDecimal::add);
        String top = porProducto.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("-");
        return String.format("Cierre del día: se registraron %d ventas por un total de %.2f " + Moneda.nombre() + ". Producto más vendido: %s.", n, total, top)
                + (anuladas > 0 ? " Ventas anuladas: " + anuladas + "." : "");
    }

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

    public synchronized Comprobante tomarComprobanteNuevo() {
        Sesion s = sesion();
        Comprobante c = s.comprobanteNuevo; s.comprobanteNuevo = null;
        return c;
    }

    public synchronized String ultimoComprobante() {
        String no = ClientSession.sinPermiso(Permiso.COMPROBANTES);
        if (no != null) return no;
        Comprobante c = ultimo();
        if (c == null) return "Aún no se ha emitido ningún comprobante, señor.";
        sesion().comprobanteNuevo = c;
        return String.format("Mostrando la %s%s. Total: %.2f " + Moneda.nombre() + ".", c.hablado(), c.anulado ? " (ANULADA)" : "", c.total);
    }

    private Comprobante ultimo() {
        int n = ventas.stream().mapToInt(v -> v.numero).max().orElse(0);
        return n == 0 ? null : comprobante(n);
    }

    private Comprobante comprobante(int numero) {
        List<Venta> items = new ArrayList<>(ventas.stream().filter(v -> v.numero == numero).toList());
        if (items.isEmpty()) return null;
        return new Comprobante(numero, items.get(0).fecha, items.get(0).hora, items, Almacen.cabecera(cabeceras, numero));
    }

    public synchronized String pedirAnularUltimaVenta() {
        String no = ClientSession.sinPermiso(Permiso.ANULAR_VENTAS);
        if (no != null) return no;
        Comprobante c = ultimo();
        if (c == null) return "No hay ventas para anular, señor.";
        if (c.anulado) return "La " + c.hablado() + " ya está anulada, señor.";
        Pendiente p = new Pendiente();
        p.tipo = Tipo.ANULAR; p.numero = c.numero;
        p.pregunta = String.format("¿Confirma anular la %s por %.2f " + Moneda.nombre() + "? Diga sí o no.", c.hablado(), c.total);
        return preguntar(p);
    }

    public synchronized Resultado anularUltimaVenta() {
        String no = ClientSession.sinPermiso(Permiso.ANULAR_VENTAS);
        if (no != null) return Resultado.prohibido(no);
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
        String msg = String.format("Venta anulada: %s por %.2f " + Moneda.nombre() + ". Devolví los productos al stock.", c.hablado(), c.total);
        if (!sinProducto.isEmpty()) msg += " Ojo: " + String.join(", ", sinProducto) + " ya no existe, no se devolvió.";
        return Resultado.ok(persistir(msg));
    }

    public synchronized Resultado corregirUltimaVenta(String textoProducto, BigDecimal nueva, boolean exacto) {
        String no = ClientSession.sinPermiso(Permiso.ANULAR_VENTAS);
        if (no != null) return Resultado.prohibido(no);
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
        BigDecimal diferencia = nueva.subtract(antes);
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
        return Resultado.ok(persistir(String.format("Corregí la %s: %s. Nuevo total: %.2f " + Moneda.nombre() + ". Stock actual: %s.",
                corregido.hablado(), que, corregido.total, Num.txt(p.stock))));
    }

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

    public synchronized Resultado crearProducto(String nombre, BigDecimal precio, BigDecimal stock, String unidad,
                                                BigDecimal stockMin, List<String> alias, Producto.Ficha ficha) {
        String no = ClientSession.sinPermiso(Permiso.INVENTARIO);
        if (no != null) return Resultado.prohibido(no);
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
                + String.format(". Precio: %.2f " + Moneda.nombre() + ".", p.precio) + avisoCosto(p)));
    }

    private static String limpio(String s) { return s == null ? null : s.trim().replaceAll("\\s+", " "); }

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

    private Producto conCodigo(String c, Producto excepto) {
        if (c == null || c.isBlank()) return null;
        return productos.stream().filter(p -> p != excepto && p.tieneCodigo(c)).findFirst().orElse(null);
    }

    private static String avisoCosto(Producto p) {
        return p.costo != null && p.costo.compareTo(p.precio) > 0
                ? String.format(" Atención: el costo (%.2f " + Moneda.nombre() + ") es mayor que el precio de venta.", p.costo) : "";
    }

    public record ResultadoLote(int agregados, List<ListaProductos.ErrorLinea> omitidosDetalle,
                                List<ListaProductos.ErrorLinea> errores, String mensaje) {
        public int omitidos() { return omitidosDetalle.size(); }
    }

    public synchronized ResultadoLote crearProductos(List<ListaProductos.Fila> filas, List<ListaProductos.ErrorLinea> erroresDeLectura) {
        List<ListaProductos.ErrorLinea> errores = new ArrayList<>(erroresDeLectura);
        List<ListaProductos.ErrorLinea> omitidos = new ArrayList<>();
        Map<String, Integer> enLista = new HashMap<>();
        Map<String, Integer> codigosEnLista = new HashMap<>();
        List<ListaProductos.Fila> validas = new ArrayList<>();

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

        for (ListaProductos.Fila f : validas)
            nuevo(f.nombre(), aliasDe(f.nombre(), null), f.precio(), f.stock(), f.unidad(), f.stockMin(), f.ficha(), " (carga masiva)");
        String resumen = "Carga de productos: " + validas.size() + (validas.size() == 1 ? " agregado" : " agregados")
                + ", " + omitidos.size() + (omitidos.size() == 1 ? " omitido" : " omitidos")
                + ", " + errores.size() + (errores.size() == 1 ? " con error." : " con errores.");
        return new ResultadoLote(validas.size(), omitidos, errores, validas.isEmpty() ? resumen : persistir(resumen));
    }

    private static String codigoRepetidoEnLista(Producto.Ficha f, Map<String, Integer> codigosEnLista) {
        for (String c : new String[]{limpio(f.codigo()), limpio(f.codigoBarras())}) {
            Integer linea = c == null || c.isEmpty() ? null : codigosEnLista.get(c.toUpperCase(Locale.ROOT));
            if (linea != null) return "el código " + c + " ya viene en la línea " + linea;
        }
        return null;
    }

    private static String limpiarNombre(String nombre) { return nombre == null ? "" : nombre.trim().replaceAll("\\s+", " "); }

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

    public synchronized Resultado ajustarStock(String nombre, BigDecimal cantidad) {
        String no = ClientSession.sinPermiso(Permiso.INVENTARIO);
        if (no != null) return Resultado.prohibido(no);
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

    public synchronized Resultado editarProducto(String nombre, boolean exacto, BigDecimal precio, BigDecimal stockMin, String unidad) {
        return editarProducto(nombre, exacto, new Cambios(null, precio, null, stockMin, unidad, null));
    }

    public synchronized Resultado editarProducto(String nombre, boolean exacto, Cambios c) {
        String no = ClientSession.sinPermiso(Permiso.INVENTARIO);
        if (no != null) return Resultado.prohibido(no);
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
            for (Venta v : ventas) if (antes.equals(v.producto)) v.producto = nuevoNombre;
            hecho.add(antes + " ahora se llama " + nuevoNombre + ".");
        }
        if (c.alias() != null) {
            p.alias = aliasDe(p.nombre, c.alias());
            registrar("ALIAS", p, null, String.join(", ", p.alias));
        }
        if (c.precio() != null) {
            registrar("PRECIO", p, null, p.precio + " -> " + Num.dinero(c.precio()));
            p.precio = Num.dinero(c.precio());
            hecho.add(String.format("Precio actualizado: %s ahora cuesta %.2f " + Moneda.nombre() + ".", p.nombre, p.precio));
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
                    : String.format("Costo de %s: %.2f " + Moneda.nombre() + " (margen de %.2f " + Moneda.nombre() + " sobre el precio de venta).", p.nombre, costo,
                            p.precio.subtract(costo)));
        }
        if (c.precio() != null || c.ficha() != null && c.ficha().costo() != null) {
            String aviso = avisoCosto(p);
            if (!aviso.isEmpty()) hecho.add(aviso.trim());
        }
        if (hecho.isEmpty()) hecho.add("Guardé los cambios de " + p.nombre + ".");
        return Resultado.ok(persistir(String.join(" ", hecho)));
    }

    private static String sinDato(String s) { return s.isEmpty() ? "(ninguno)" : s; }

    private Producto mismoNombre(String nombre, Producto excepto) {
        String n = Palabras.norm(nombre);
        return productos.stream().filter(p -> p != excepto && Palabras.norm(p.nombre).equals(n)).findFirst().orElse(null);
    }

    private static List<String> aliasDe(String nombre, List<String> alias) {
        Set<String> out = new LinkedHashSet<>();
        out.add(Palabras.norm(nombre));
        if (alias != null) for (String a : alias) if (a != null && !Palabras.norm(a).isBlank()) out.add(Palabras.norm(a));
        return new ArrayList<>(out);
    }

    public synchronized String pedirEliminarProducto(String textoProducto) {
        String no = ClientSession.sinPermiso(Permiso.INVENTARIO);
        if (no != null) return no;
        Match m = buscar(textoProducto);
        if (m == null) return "No encontré ese producto, señor.";
        if (m.empate()) return cualDe(m);
        return preguntar(pend(Tipo.ELIMINAR, m.producto, null, null,
                "¿Confirma eliminar " + m.producto.nombre + " del inventario? Quedan " + Num.txt(m.producto.stock) + " "
                        + m.producto.unidad + ". Diga sí o no."));
    }

    public synchronized Resultado eliminarProducto(String nombre, boolean exacto) {
        String no = ClientSession.sinPermiso(Permiso.INVENTARIO);
        if (no != null) return Resultado.prohibido(no);
        Producto p = exacto ? porNombre(nombre) : Optional.ofNullable(buscar(nombre)).map(x -> x.producto).orElse(null);
        if (p == null) return Resultado.noEncontrado("No encontré ese producto, señor.");
        productos.remove(p);
        for (Sesion s : sesiones.values()) if (s.carrito != null) s.carrito.removeIf(l -> l.producto() == p);
        registrar("ELIMINAR", p, p.stock, "stock al eliminar " + Num.txt(p.stock) + " " + p.unidad);
        return Resultado.ok(persistir("Eliminé " + p.nombre + " del inventario, señor."));
    }

    public synchronized String pedirNuevoInventario() {
        String no = ClientSession.sinPermiso(Permiso.ADMINISTRAR);
        if (no != null) return no;
        if (productos.isEmpty() && ventas.isEmpty()) return "El inventario ya está vacío, señor. Puede agregar productos cuando quiera.";
        return preguntar(pend(Tipo.NUEVO_INVENTARIO, null, null, null, resumenAntesDeVaciar() + " Diga sí o no."));
    }

    public synchronized String resumenAntesDeVaciar() {
        return "¿Confirma empezar un inventario nuevo desde cero? Se quitarán " + productos.size()
                + (productos.size() == 1 ? " producto" : " productos") + " y " + ventas.size()
                + (ventas.size() == 1 ? " línea de venta" : " líneas de venta") + ". Antes guardaré una copia del inventario actual.";
    }

    public synchronized Resultado empezarDeCero() {
        String no = ClientSession.sinPermiso(Permiso.ADMINISTRAR);
        if (no != null) return Resultado.prohibido(no);
        Path copia = almacen.baseDatos().nombreLibre("antes-de-nuevo-");
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
        cabeceras = new HashMap<>();
        for (Sesion s : sesiones.values()) { s.carrito = null; s.comprobanteNuevo = null; s.opciones = Opciones.POR_DEFECTO; }
        return Resultado.ok(persistir("Listo, señor: empecé un inventario nuevo, vacío. La copia del anterior (" + n
                + (n == 1 ? " producto" : " productos") + ") quedó en " + copia.getFileName() + "."));
    }

    public List<Movimiento> movimientos(int n) {
        synchronized (this) { return almacen.ultimosMovimientos(Math.min(Math.max(n, 1), 1000)); }
    }

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

    public synchronized boolean conoce(String textoProducto) { return buscar(textoProducto) != null; }

    public synchronized String unidadDe(String textoProducto) {
        Match m = buscar(textoProducto);
        return m == null ? null : m.producto.unidad;
    }

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

    private Producto porNombre(String nombre) {
        if (nombre == null) return null;
        String n = nombre.trim();
        return productos.stream().filter(p -> p.nombre.equalsIgnoreCase(n)).findFirst().orElse(null);
    }

    private final BuscadorProductos buscador = new BuscadorProductos();

    private Match buscar(String texto) {
        Producto porCodigo = conCodigo(texto == null ? null : texto.trim(), null);
        if (porCodigo != null) return new Match(porCodigo, false, List.of(porCodigo));
        return buscador.buscar(texto, productos);
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
