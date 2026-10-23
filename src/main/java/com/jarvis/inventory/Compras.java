package com.jarvis.inventory;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Proveedores y compras, guardados directo en la base (como en el sistema de referencia: CompraService y
 * ProveedorService). Registrar o anular una compra cambia el stock, así que eso lo hace {@link InventoryService},
 * que usa los métodos de escritura de esta clase dentro de su misma transacción.
 */
public class Compras {

    /** Documento que entrega el proveedor y respalda la compra. */
    public enum TipoDocumento {
        FACTURA("Factura"), BOLETA("Boleta de venta"), GUIA_REMISION("Guía de remisión"), NOTA_VENTA("Nota de venta");

        public final String descripcion;

        TipoDocumento(String descripcion) { this.descripcion = descripcion; }

        /** "factura", "FACTURA", "guia", "guía de remisión", "boleta", "nota"…; null si no se reconoce. */
        public static TipoDocumento de(String texto) {
            String t = texto == null ? "" : Palabras.norm(texto).replace('_', ' ');
            if (t.startsWith("fact")) return FACTURA;
            if (t.startsWith("bol")) return BOLETA;
            if (t.startsWith("guia")) return GUIA_REMISION;
            if (t.startsWith("nota")) return NOTA_VENTA;
            return null;
        }
    }

    public record Proveedor(long id, String ruc, String razonSocial, String direccion, String telefono) {}

    /** Para crear o editar un proveedor. En una edición, null = no tocar y "" = borrar (salvo RUC y razón social). */
    public record DatosProveedor(String ruc, String razonSocial, String direccion, String telefono) {}

    /** Una línea de una compra nueva: producto (nombre exacto), cantidad y costo unitario tal como figura en el documento. */
    public record LineaCompra(String producto, BigDecimal cantidad, BigDecimal costo) {}

    /**
     * Una compra nueva. {@code razonSocial} solo hace falta si el RUC aún no está registrado. {@code fechaDocumento}:
     * AAAA-MM-DD (vacía = hoy). {@code igv}: incluido (por defecto), agregado o exonerado.
     */
    public record NuevaCompra(String ruc, String razonSocial, String tipoDocumento, String numeroDocumento, String fechaDocumento,
                              String igv, String observacion, List<LineaCompra> items) {}

    public record Detalle(int item, String producto, String unidad, BigDecimal cantidad, BigDecimal costo, BigDecimal importe,
                          @JsonIgnore Long productoId) {}

    public record Compra(@JsonIgnore long id, int numero, String codigo, String fecha, String hora, String proveedorRuc,
                         String proveedor, String tipoDocumento, String tipoDocumentoDescripcion, String numeroDocumento,
                         String fechaDocumento, String modoIgv, BigDecimal base, BigDecimal igv, BigDecimal total, boolean anulada,
                         String observacion, List<Detalle> detalles) {}

    /** Totales de las compras registradas (sin anuladas) de una consulta. */
    public record Totales(int compras, BigDecimal base, BigDecimal igv, BigDecimal total) {}

    private static final int MAX_RAZON = 150, MAX_DIRECCION = 200;
    static final String PATRON_RUC = "(10|15|17|20)\\d{9}";

    private final BaseDatos db;

    Compras(BaseDatos db) { this.db = db; }

    static String codigo(int numero) { return "CP-" + String.format("%08d", numero); }

    // ---------------------------------------------------------------- proveedores

    public synchronized Optional<Proveedor> proveedor(String ruc) {
        String r = ruc == null ? "" : ruc.trim();
        List<Proveedor> l = proveedores("SELECT * FROM proveedor WHERE ruc = ?", r);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    /** Por RUC (que empiece así) si son dígitos; si no, cada palabra debe estar en la razón social. */
    public synchronized List<Proveedor> buscarProveedores(String texto, int limite) {
        String t = texto == null ? "" : texto.trim();
        int lim = Math.min(Math.max(limite, 1), 500);
        if (t.isEmpty()) return proveedores("SELECT * FROM proveedor ORDER BY razon_social LIMIT ?", lim);
        if (t.matches("\\d+")) return proveedores("SELECT * FROM proveedor WHERE ruc LIKE ? ORDER BY ruc LIMIT ?", t + "%", lim);
        StringBuilder sql = new StringBuilder("SELECT * FROM proveedor WHERE 1=1");
        List<Object> v = new ArrayList<>();
        for (String w : Palabras.norm(t).split(" ")) {
            if (w.isBlank()) continue;
            sql.append(" AND busqueda LIKE ?");
            v.add("%" + w + "%");
        }
        sql.append(" ORDER BY razon_social LIMIT ?");
        v.add(lim);
        return proveedores(sql.toString(), v.toArray());
    }

    public synchronized InventoryService.Resultado crearProveedor(DatosProveedor d) {
        if (d == null) return InventoryService.Resultado.error("Faltan los datos del proveedor.");
        String ruc = limpio(d.ruc()), razon = limpio(d.razonSocial());
        String error = errorProveedor(ruc, razon, limpio(d.direccion()), limpio(d.telefono()));
        if (error != null) return InventoryService.Resultado.error(error);
        if (proveedor(ruc).isPresent()) return InventoryService.Resultado.error("Ya hay un proveedor con el RUC " + ruc + ".");
        try {
            db.transaccion(cx -> insertarProveedor(cx, ruc, razon, nz(limpio(d.direccion())), nz(limpio(d.telefono()))));
        } catch (SQLException e) {
            return InventoryService.Resultado.error("No pude guardar el proveedor: " + e.getMessage());
        }
        return InventoryService.Resultado.ok("Proveedor registrado: " + razon + ", RUC " + ruc + ".");
    }

    public synchronized InventoryService.Resultado editarProveedor(String ruc, DatosProveedor d) {
        Optional<Proveedor> actual = proveedor(ruc);
        if (actual.isEmpty()) return InventoryService.Resultado.noEncontrado("No encontré un proveedor con el RUC " + ruc + ".");
        Proveedor p = actual.get();
        String nuevoRuc = d.ruc() == null || d.ruc().isBlank() ? p.ruc() : limpio(d.ruc());
        String razon = d.razonSocial() == null || d.razonSocial().isBlank() ? p.razonSocial() : limpio(d.razonSocial());
        String direccion = d.direccion() == null ? p.direccion() : limpio(d.direccion());
        String telefono = d.telefono() == null ? p.telefono() : limpio(d.telefono());
        String error = errorProveedor(nuevoRuc, razon, direccion, telefono);
        if (error != null) return InventoryService.Resultado.error(error);
        if (!nuevoRuc.equals(p.ruc()) && proveedor(nuevoRuc).isPresent())
            return InventoryService.Resultado.error("Ya hay otro proveedor con el RUC " + nuevoRuc + ".");
        try {
            db.transaccion(cx -> {
                ejecutar(cx, "UPDATE proveedor SET ruc=?, razon_social=?, busqueda=?, direccion=?, telefono=? WHERE id=?",
                        nuevoRuc, razon, Palabras.norm(razon), nz(direccion), nz(telefono), p.id());
                return null;
            });
        } catch (SQLException e) {
            return InventoryService.Resultado.error("No pude guardar el proveedor: " + e.getMessage());
        }
        return InventoryService.Resultado.ok("Guardé los datos de " + razon + ".");
    }

    /** Un proveedor con compras no se elimina (sus compras lo nombran). */
    public synchronized InventoryService.Resultado eliminarProveedor(String ruc) {
        Optional<Proveedor> p = proveedor(ruc);
        if (p.isEmpty()) return InventoryService.Resultado.noEncontrado("No encontré un proveedor con el RUC " + ruc + ".");
        try {
            return db.transaccion(cx -> {
                if (contar(cx, "SELECT COUNT(*) FROM compra WHERE proveedor_id = ?", p.get().id()) > 0)
                    return InventoryService.Resultado.error("No se puede eliminar a " + p.get().razonSocial() + ": tiene compras registradas.");
                ejecutar(cx, "DELETE FROM proveedor WHERE id = ?", p.get().id());
                return InventoryService.Resultado.ok("Eliminé al proveedor " + p.get().razonSocial() + ".");
            });
        } catch (SQLException e) {
            return InventoryService.Resultado.error("No pude eliminar el proveedor: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- consultas de compras

    /**
     * Compras de la más reciente a la más antigua (sin su detalle). {@code texto}: código, RUC, proveedor o número de
     * documento; {@code desde}/{@code hasta}: AAAA-MM-DD, vacíos = sin límite.
     */
    public synchronized List<Compra> listar(String texto, String desde, String hasta, int limite) {
        Filtro f = filtro(texto, desde, hasta);
        List<Object> v = new ArrayList<>(f.valores());
        v.add(Math.min(Math.max(limite, 1), 1000));
        return db.leer(cx -> {
            List<Compra> out = new ArrayList<>();
            try (PreparedStatement ps = cx.prepareStatement("SELECT * FROM compra WHERE " + f.where() + " ORDER BY numero DESC LIMIT ?")) {
                poner(ps, v.toArray());
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(compra(rs, List.of())); }
            }
            return out;
        });
    }

    /** Totales de las compras registradas (no anuladas) que cumplen el mismo filtro que {@link #listar}. */
    public synchronized Totales totalizar(String texto, String desde, String hasta) {
        Filtro f = filtro(texto, desde, hasta);
        return db.leer(cx -> {
            try (PreparedStatement ps = cx.prepareStatement("SELECT COUNT(*), COALESCE(SUM(base_cent),0), COALESCE(SUM(igv_cent),0), "
                    + "COALESCE(SUM(total_cent),0) FROM compra WHERE anulada = 0 AND " + f.where())) {
                poner(ps, f.valores().toArray());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new Totales(rs.getInt(1), Num.deEntero(rs.getLong(2), Num.CENT), Num.deEntero(rs.getLong(3), Num.CENT),
                            Num.deEntero(rs.getLong(4), Num.CENT));
                }
            }
        });
    }

    /** Una compra con su detalle, por código (CP-00000012) o número (12). */
    public synchronized Optional<Compra> obtener(String codigoONumero) {
        String t = codigoONumero == null ? "" : codigoONumero.trim().toUpperCase();
        Integer numero = t.matches("\\d+") ? Integer.valueOf(t) : t.matches("CP-\\d+") ? Integer.valueOf(t.substring(3)) : null;
        if (numero == null) return Optional.empty();
        return db.leer(cx -> {
            try (PreparedStatement ps = cx.prepareStatement("SELECT * FROM compra WHERE numero = ?")) {
                ps.setInt(1, numero);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    long id = rs.getLong("id");
                    return Optional.of(compra(rs, detalles(cx, id)));
                }
            }
        });
    }

    public synchronized int contarProveedores() {
        return db.leer(cx -> contar(cx, "SELECT COUNT(*) FROM proveedor"));
    }

    // ---------------------------------------------------------------- escritura (dentro de la transacción de InventoryService)

    synchronized int siguienteNumero() {
        return db.leer(cx -> contar(cx, "SELECT COALESCE(MAX(numero), 0) FROM compra")) + 1;
    }

    /** ¿Ya se registró (y no se anuló) ese documento de ese proveedor? */
    synchronized boolean documentoRegistrado(String ruc, TipoDocumento tipo, String numeroDocumento) {
        return db.leer(cx -> contar(cx, """
                SELECT COUNT(*) FROM compra WHERE proveedor_ruc = ? AND tipo_documento = ? AND numero_documento = ? AND anulada = 0""",
                ruc, tipo.name(), numeroDocumento) > 0);
    }

    /** Lo que se guarda de una compra ya validada; las líneas con el id del producto (o null). */
    record Registro(int numero, String fecha, String hora, String ruc, String razonSocial, TipoDocumento tipo, String numeroDocumento,
                    String fechaDocumento, Igv.Modo modo, Igv.Totales totales, String observacion, String usuario,
                    List<Detalle> detalles) {}

    /** Inserta la compra (y el proveedor, si su RUC es nuevo) usando la conexión de la transacción en curso. */
    void insertar(Connection cx, Registro r) throws SQLException {
        Long proveedorId = null;
        try (PreparedStatement ps = cx.prepareStatement("SELECT id FROM proveedor WHERE ruc = ?")) {
            ps.setString(1, r.ruc());
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) proveedorId = rs.getLong(1); }
        }
        if (proveedorId == null) proveedorId = insertarProveedor(cx, r.ruc(), r.razonSocial(), "", "");
        long compraId = insertarFila(cx, """
                INSERT INTO compra (numero, fecha, hora, proveedor_id, proveedor_ruc, proveedor_nombre, tipo_documento, numero_documento,
                                    fecha_documento, modo_igv, base_cent, igv_cent, total_cent, observacion, usuario)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                r.numero(), r.fecha(), r.hora(), proveedorId, r.ruc(), r.razonSocial(), r.tipo().name(), r.numeroDocumento(),
                r.fechaDocumento(), r.modo().name(), Num.aEntero(r.totales().base(), Num.CENT), Num.aEntero(r.totales().igv(), Num.CENT),
                Num.aEntero(r.totales().total(), Num.CENT), r.observacion(), r.usuario());
        for (Detalle d : r.detalles())
            insertarFila(cx, """
                    INSERT INTO compra_detalle (compra_id, item, producto_id, producto, unidad, cantidad_mil, costo_dm, importe_cent)
                    VALUES (?,?,?,?,?,?,?,?)""",
                    compraId, d.item(), d.productoId(), d.producto(), d.unidad(), Num.aEntero(d.cantidad(), Num.MIL),
                    Num.aEntero(d.costo(), Num.DM), Num.aEntero(d.importe(), Num.CENT));
    }

    void marcarAnulada(Connection cx, long compraId) throws SQLException {
        ejecutar(cx, "UPDATE compra SET anulada = 1 WHERE id = ?", compraId);
    }

    // ---------------------------------------------------------------- apoyo

    private record Filtro(String where, List<Object> valores) {}

    private static Filtro filtro(String texto, String desde, String hasta) {
        StringBuilder w = new StringBuilder("1=1");
        List<Object> v = new ArrayList<>();
        if (desde != null && !desde.isBlank()) { w.append(" AND fecha >= ?"); v.add(desde.trim()); }
        if (hasta != null && !hasta.isBlank()) { w.append(" AND fecha <= ?"); v.add(hasta.trim()); }
        String t = texto == null ? "" : texto.trim();
        if (!t.isEmpty()) {
            w.append(" AND (UPPER('CP-' || printf('%08d', numero)) LIKE ? OR proveedor_ruc LIKE ? OR LOWER(proveedor_nombre) LIKE ?"
                    + " OR UPPER(numero_documento) LIKE ?)");
            v.add("%" + t.toUpperCase() + "%"); v.add(t + "%"); v.add("%" + t.toLowerCase() + "%"); v.add("%" + t.toUpperCase() + "%");
        }
        return new Filtro(w.toString(), v);
    }

    private static Compra compra(ResultSet rs, List<Detalle> detalles) throws SQLException {
        TipoDocumento tipo = TipoDocumento.valueOf(rs.getString("tipo_documento"));
        int numero = rs.getInt("numero");
        return new Compra(rs.getLong("id"), numero, codigo(numero), rs.getString("fecha"), rs.getString("hora"),
                rs.getString("proveedor_ruc"), rs.getString("proveedor_nombre"), tipo.name(), tipo.descripcion,
                rs.getString("numero_documento"), rs.getString("fecha_documento"), rs.getString("modo_igv"),
                Num.deEntero(rs.getLong("base_cent"), Num.CENT), Num.deEntero(rs.getLong("igv_cent"), Num.CENT),
                Num.deEntero(rs.getLong("total_cent"), Num.CENT), rs.getInt("anulada") != 0, rs.getString("observacion"), detalles);
    }

    private static List<Detalle> detalles(Connection cx, long compraId) throws SQLException {
        List<Detalle> out = new ArrayList<>();
        try (PreparedStatement ps = cx.prepareStatement("SELECT * FROM compra_detalle WHERE compra_id = ? ORDER BY item")) {
            ps.setLong(1, compraId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long pid = rs.getLong("producto_id");
                    Long productoId = rs.wasNull() ? null : pid;
                    out.add(new Detalle(rs.getInt("item"), rs.getString("producto"), rs.getString("unidad"),
                            Num.cantidad(Num.deEntero(rs.getLong("cantidad_mil"), Num.MIL)),
                            costo(rs.getLong("costo_dm")),
                            Num.deEntero(rs.getLong("importe_cent"), Num.CENT), productoId));
                }
            }
        }
        return out;
    }

    /** Costo unitario del documento: con 2 decimales si los tiene ("12.50"), si no con los 4 que se guardaron ("3.3333"). */
    private static BigDecimal costo(long diezmilesimas) {
        BigDecimal c = Num.deEntero(diezmilesimas, Num.DM);
        return c.stripTrailingZeros().scale() <= 2 ? Num.dinero(c) : c;
    }

    private static String errorProveedor(String ruc, String razon, String direccion, String telefono) {
        if (ruc == null || !ruc.matches(PATRON_RUC)) return "El RUC debe tener 11 dígitos y empezar con 10, 15, 17 o 20.";
        if (razon == null || razon.isEmpty()) return "Falta la razón social del proveedor.";
        if (razon.length() > MAX_RAZON) return "La razón social es muy larga (máximo " + MAX_RAZON + " letras).";
        if (direccion != null && direccion.length() > MAX_DIRECCION) return "La dirección es muy larga (máximo " + MAX_DIRECCION + ").";
        if (telefono != null && !telefono.isEmpty() && !telefono.matches("\\d{6,9}")) return "El teléfono debe tener entre 6 y 9 dígitos.";
        return null;
    }

    private static long insertarProveedor(Connection cx, String ruc, String razon, String direccion, String telefono) throws SQLException {
        return insertarFila(cx, "INSERT INTO proveedor (ruc, razon_social, busqueda, direccion, telefono) VALUES (?,?,?,?,?)",
                ruc, razon, Palabras.norm(razon), direccion, telefono);
    }

    private List<Proveedor> proveedores(String sql, Object... valores) {
        return db.leer(cx -> {
            List<Proveedor> out = new ArrayList<>();
            try (PreparedStatement ps = cx.prepareStatement(sql)) {
                poner(ps, valores);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        out.add(new Proveedor(rs.getLong("id"), rs.getString("ruc"), rs.getString("razon_social"),
                                rs.getString("direccion"), rs.getString("telefono")));
                }
            }
            return out;
        });
    }

    private static int contar(Connection cx, String sql, Object... valores) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement(sql)) {
            poner(ps, valores);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    private static void ejecutar(Connection cx, String sql, Object... valores) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement(sql)) {
            poner(ps, valores);
            ps.executeUpdate();
        }
    }

    private static long insertarFila(Connection cx, String sql, Object... valores) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            poner(ps, valores);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) throw new SQLException("la base no devolvió el id nuevo");
                return rs.getLong(1);
            }
        }
    }

    private static void poner(PreparedStatement ps, Object... valores) throws SQLException {
        for (int i = 0; i < valores.length; i++) ps.setObject(i + 1, valores[i]);
    }

    private static String limpio(String s) { return s == null ? null : s.trim().replaceAll("\\s+", " "); }

    private static String nz(String s) { return s == null ? "" : s; }
}
