/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

final class Almacen {

    record Pendiente(Movimiento movimiento, Producto producto, BigDecimal delta, BigDecimal saldo, BigDecimal costo) {
        Pendiente(Movimiento movimiento, Producto producto) { this(movimiento, producto, null, null, null); }
    }

    static final Map<String, Integer> SIGNO = Map.of("ENTRADA", 1, "VENTA", -1, "ANULACION", 1, "COMPRA", 1,
            "ANULACION_COMPRA", -1, "AJUSTE", 1, "ELIMINAR", -1, "CORRECCION_VENTA", 0);

    record Estado(List<Producto> productos, List<InventoryService.Venta> ventas,
                  Map<Integer, InventoryService.Cabecera> cabeceras, Map<String, Integer> contadores) {}

    private record FilaProducto(String nombre, String alias, long stock, long stockMin, long precio, String unidad,
                                String categoria, String codigo, String codigoBarras, Long costo) {}

    private record FilaVenta(long comprobanteId, int item, Long productoId, String producto, long cantidad, String unidad,
                             long precio, long monto, Long costo, boolean anulada) {}

    static final String ULTIMO_COMPROBANTE = "ultimo_comprobante";

    private final BaseDatos db;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Producto, FilaProducto> fotosProductos = new IdentityHashMap<>();
    private final Map<InventoryService.Venta, FilaVenta> fotosVentas = new IdentityHashMap<>();
    private final Map<Integer, Long> comprobantePorNumero = new HashMap<>();
    private final Map<String, Integer> contadoresGuardados = new HashMap<>();

    Almacen(Path archivo) {
        this.db = new BaseDatos(archivo);
    }

    Path archivo() { return db.archivo(); }

    BaseDatos baseDatos() { return db; }

    boolean vacio() { return db.vacia(); }

    void respaldoDiario() { db.respaldoDiario(); }

    void copiarA(Path destino) throws SQLException { db.copiarA(destino); }

    Estado cargar() {
        fotosProductos.clear();
        fotosVentas.clear();
        comprobantePorNumero.clear();
        return db.leer(cx -> {
            List<Producto> productos = new ArrayList<>();
            try (Statement st = cx.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM producto ORDER BY id")) {
                while (rs.next()) {
                    Producto p = new Producto();
                    p.id = rs.getLong("id");
                    p.nombre = rs.getString("nombre");
                    p.alias = alias(rs.getString("alias"));
                    p.stock = Num.cantidad(Num.deEntero(rs.getLong("stock_mil"), Num.MIL));
                    p.stockMin = Num.cantidad(Num.deEntero(rs.getLong("stock_min_mil"), Num.MIL));
                    p.precio = Num.deEntero(rs.getLong("precio_cent"), Num.CENT);
                    p.unidad = rs.getString("unidad");
                    p.categoria = rs.getString("categoria");
                    p.codigo = rs.getString("codigo");
                    p.codigoBarras = rs.getString("codigo_barras");
                    long costo = rs.getLong("costo_dm");
                    p.costo = rs.wasNull() ? null : Num.dinero(Num.deEntero(costo, Num.DM));
                    productos.add(p);
                    fotosProductos.put(p, fila(p, p.id));
                }
            }
            Map<Integer, InventoryService.Cabecera> cabeceras = new HashMap<>();
            try (Statement st = cx.createStatement(); ResultSet rs = st.executeQuery("""
                    SELECT id, numero, tipo, correlativo, cliente_id, cliente_doc, cliente_nombre, modo_igv FROM comprobante""")) {
                while (rs.next()) {
                    int numero = rs.getInt("numero");
                    if (rs.wasNull()) continue;
                    comprobantePorNumero.put(numero, rs.getLong("id"));
                    long cliente = rs.getLong("cliente_id");
                    Long clienteId = rs.wasNull() ? null : cliente;
                    TipoComprobante tipo = TipoComprobante.valueOf(rs.getString("tipo"));
                    cabeceras.put(numero, new InventoryService.Cabecera(tipo, rs.getInt("correlativo"), clienteId,
                            rs.getString("cliente_doc"), rs.getString("cliente_nombre"), Igv.Modo.valueOf(rs.getString("modo_igv"))));
                }
            }
            List<InventoryService.Venta> ventas = new ArrayList<>();
            try (Statement st = cx.createStatement(); ResultSet rs = st.executeQuery("""
                    SELECT d.*, c.numero, c.fecha, c.hora FROM venta_detalle d
                    JOIN comprobante c ON c.id = d.comprobante_id ORDER BY c.id, d.item""")) {
                while (rs.next()) {
                    InventoryService.Venta v = new InventoryService.Venta();
                    v.id = rs.getLong("id");
                    v.comprobanteId = rs.getLong("comprobante_id");
                    v.numero = rs.getInt("numero");
                    v.fecha = rs.getString("fecha");
                    v.hora = rs.getString("hora");
                    v.producto = rs.getString("producto");
                    v.cantidad = Num.cantidad(Num.deEntero(rs.getLong("cantidad_mil"), Num.MIL));
                    v.unidad = rs.getString("unidad");
                    v.precioUnitario = Num.deEntero(rs.getLong("precio_cent"), Num.CENT);
                    v.monto = Num.deEntero(rs.getLong("monto_cent"), Num.CENT);
                    long costo = rs.getLong("costo_dm");
                    v.costoUnitario = rs.wasNull() ? null : Num.dinero(Num.deEntero(costo, Num.DM));
                    v.anulada = rs.getInt("anulada") != 0;
                    long pid = rs.getLong("producto_id");
                    fotosVentas.put(v, filaVenta(v, v.comprobanteId, rs.getInt("item"), rs.wasNull() ? null : pid));
                    ventas.add(v);
                }
            }
            contadoresGuardados.clear();
            try (Statement st = cx.createStatement(); ResultSet rs = st.executeQuery("SELECT nombre, valor FROM contador")) {
                while (rs.next()) contadoresGuardados.put(rs.getString(1), rs.getInt(2));
            }
            return new Estado(productos, ventas, cabeceras, new HashMap<>(contadoresGuardados));
        });
    }

    List<Movimiento> ultimosMovimientos(int n) {
        return db.leer(cx -> {
            List<Movimiento> out = new ArrayList<>();
            try (PreparedStatement ps = cx.prepareStatement(
                    "SELECT fecha, tipo, producto, cantidad_mil, detalle, usuario FROM movimiento ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, n);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long c = rs.getLong("cantidad_mil");
                        BigDecimal cantidad = rs.wasNull() ? null : Num.cantidad(Num.deEntero(c, Num.MIL));
                        out.add(new Movimiento(rs.getString("fecha"), rs.getString("tipo"), rs.getString("producto"),
                                cantidad, rs.getString("detalle"), rs.getString("usuario")));
                    }
                }
            }
            return out;
        });
    }

    List<FilaKardex> kardex(Long productoId, String nombre, int limite) {
        String tipos = SIGNO.keySet().stream().map(t -> "'" + t + "'").collect(java.util.stream.Collectors.joining(","));
        String donde = "tipo IN (" + tipos + ")" + (productoId == null && nombre == null ? ""
                : " AND (producto_id = ? OR (producto_id IS NULL AND producto = ?))");
        String sql = productoId == null && nombre == null
                ? "SELECT * FROM movimiento WHERE " + donde + " ORDER BY producto, id LIMIT ?"
                : "SELECT * FROM (SELECT * FROM movimiento WHERE " + donde + " ORDER BY id DESC LIMIT ?) ORDER BY id";
        return db.leer(cx -> {
            List<FilaKardex> out = new ArrayList<>();
            try (PreparedStatement ps = cx.prepareStatement(sql)) {
                int i = 1;
                if (productoId != null || nombre != null) { ps.setObject(i++, productoId); ps.setString(i++, nombre); }
                ps.setInt(i, Math.min(Math.max(limite, 1), 100_000));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tipo = rs.getString("tipo");
                        BigDecimal delta = decimal(rs, "delta_mil", Num.MIL);
                        if (delta == null) {
                            BigDecimal cant = decimal(rs, "cantidad_mil", Num.MIL);
                            int signo = SIGNO.getOrDefault(tipo, 0);
                            delta = cant == null || signo == 0 ? null : "AJUSTE".equals(tipo) ? cant : cant.multiply(BigDecimal.valueOf(signo));
                        }
                        BigDecimal costo = decimal(rs, "costo_dm", Num.DM);
                        out.add(new FilaKardex(rs.getString("fecha"), tipo, rs.getString("producto"), rs.getString("detalle"),
                                delta == null || delta.signum() < 0 ? BigDecimal.ZERO : Num.cantidad(delta),
                                delta == null || delta.signum() > 0 ? BigDecimal.ZERO : Num.cantidad(delta.negate()),
                                decimal(rs, "saldo_mil", Num.MIL), costo == null ? null : Num.dinero(costo), rs.getString("usuario")));
                    }
                }
            }
            return out;
        });
    }

    private static BigDecimal decimal(ResultSet rs, String columna, int escala) throws SQLException {
        long v = rs.getLong(columna);
        if (rs.wasNull()) return null;
        BigDecimal d = Num.deEntero(v, escala);
        return escala == Num.MIL ? Num.cantidad(d) : d;
    }

    void guardar(List<Producto> productos, List<InventoryService.Venta> ventas, Map<Integer, InventoryService.Cabecera> cabeceras,
                 Map<String, Integer> contadores, List<Pendiente> movimientos) throws SQLException {
        guardar(productos, ventas, cabeceras, contadores, movimientos, null);
    }

    void guardar(List<Producto> productos, List<InventoryService.Venta> ventas, Map<Integer, InventoryService.Cabecera> cabeceras,
                 Map<String, Integer> contadores, List<Pendiente> movimientos, BaseDatos.Trabajo<?> extra) throws SQLException {
        Map<Producto, Long> idsNuevos = new IdentityHashMap<>();
        Map<Producto, FilaProducto> fotosP = new IdentityHashMap<>();
        Map<InventoryService.Venta, Long> idsVentas = new IdentityHashMap<>();
        Map<InventoryService.Venta, Long> comprobanteDe = new IdentityHashMap<>();
        Map<InventoryService.Venta, FilaVenta> fotosV = new IdentityHashMap<>();
        Map<Integer, Long> numerosNuevos = new HashMap<>();
        Set<Integer> numerosBorrados = new HashSet<>();

        db.transaccion(cx -> {
            Set<Producto> vivos = Collections.newSetFromMap(new IdentityHashMap<>());
            vivos.addAll(productos);
            for (Producto p : fotosProductos.keySet())
                if (!vivos.contains(p)) ejecutar(cx, "DELETE FROM producto WHERE id = ?", p.id);
            for (Producto p : productos) {
                FilaProducto anterior = fotosProductos.get(p);
                if (anterior == null) {
                    long id = insertar(cx, """
                            INSERT INTO producto (nombre, alias, stock_mil, stock_min_mil, precio_cent, unidad, categoria,
                                                  codigo, codigo_barras, costo_dm) VALUES (?,?,?,?,?,?,?,?,?,?)""",
                            valores(fila(p, null)).toArray());
                    idsNuevos.put(p, id);
                    fotosP.put(p, fila(p, id));
                } else {
                    FilaProducto actual = fila(p, p.id);
                    if (!actual.equals(anterior)) {
                        List<Object> v = new ArrayList<>(valores(actual));
                        v.add(p.id);
                        ejecutar(cx, """
                                UPDATE producto SET nombre=?, alias=?, stock_mil=?, stock_min_mil=?, precio_cent=?, unidad=?,
                                       categoria=?, codigo=?, codigo_barras=?, costo_dm=? WHERE id=?""", v.toArray());
                    }
                    fotosP.put(p, actual);
                }
            }

            Set<InventoryService.Venta> vivas = Collections.newSetFromMap(new IdentityHashMap<>());
            vivas.addAll(ventas);
            Set<Long> sucios = new LinkedHashSet<>();
            for (Map.Entry<InventoryService.Venta, FilaVenta> e : fotosVentas.entrySet()) {
                if (vivas.contains(e.getKey())) continue;
                ejecutar(cx, "DELETE FROM venta_detalle WHERE id = ?", e.getKey().id);
                sucios.add(e.getValue().comprobanteId());
            }
            for (InventoryService.Venta v : ventas) {
                if (v.comprobanteId != null) { comprobanteDe.put(v, v.comprobanteId); continue; }
                Long cid = v.numero > 0 ? numerosNuevos.getOrDefault(v.numero, comprobantePorNumero.get(v.numero)) : null;
                if (cid == null) {
                    InventoryService.Cabecera cab = cabecera(cabeceras, v.numero);
                    cid = insertar(cx, """
                            INSERT INTO comprobante (numero, fecha, hora, tipo, serie, correlativo, cliente_id, cliente_doc,
                                                     cliente_nombre, modo_igv) VALUES (?,?,?,?,?,?,?,?,?,?)""",
                            v.numero > 0 ? v.numero : null, v.fecha == null ? "" : v.fecha, v.hora == null ? "" : v.hora,
                            cab.tipo().name(), cab.tipo().serie, cab.correlativo() > 0 ? cab.correlativo() : null,
                            cab.clienteId(), nz(cab.clienteDoc()), nz(cab.clienteNombre()), cab.modo().name());
                    if (v.numero > 0) numerosNuevos.put(v.numero, cid);
                }
                comprobanteDe.put(v, cid);
            }
            Map<String, Long> idPorNombre = new HashMap<>();
            for (Producto p : productos) idPorNombre.putIfAbsent(p.nombre, p.id != null ? p.id : idsNuevos.get(p));
            Map<Long, Integer> items = new HashMap<>();
            for (InventoryService.Venta v : ventas) {
                long cid = comprobanteDe.get(v);
                int item = items.merge(cid, 1, Integer::sum);
                FilaVenta actual = filaVenta(v, cid, item, idPorNombre.get(v.producto));
                FilaVenta anterior = fotosVentas.get(v);
                if (anterior == null) {
                    idsVentas.put(v, insertar(cx, """
                            INSERT INTO venta_detalle (comprobante_id, item, producto_id, producto, cantidad_mil, unidad,
                                                       precio_cent, monto_cent, costo_dm, anulada) VALUES (?,?,?,?,?,?,?,?,?,?)""",
                            valores(actual).toArray()));
                    sucios.add(cid);
                } else if (!actual.equals(anterior)) {
                    List<Object> val = new ArrayList<>(valores(actual));
                    val.add(v.id);
                    ejecutar(cx, """
                            UPDATE venta_detalle SET comprobante_id=?, item=?, producto_id=?, producto=?, cantidad_mil=?, unidad=?,
                                   precio_cent=?, monto_cent=?, costo_dm=?, anulada=? WHERE id=?""", val.toArray());
                    sucios.add(cid);
                }
                fotosV.put(v, actual);
            }
            for (long cid : sucios) {
                List<InventoryService.Venta> lineas = ventas.stream().filter(v -> comprobanteDe.get(v) == cid).toList();
                if (lineas.isEmpty()) {
                    ejecutar(cx, "DELETE FROM comprobante WHERE id = ?", cid);
                    comprobantePorNumero.forEach((n, id) -> { if (id == cid) numerosBorrados.add(n); });
                    numerosNuevos.values().remove(cid);
                    continue;
                }
                Igv.Totales t = Igv.calcular(lineas.stream().map(v -> v.monto).toList(), cabecera(cabeceras, lineas.get(0).numero).modo());
                boolean anulado = lineas.stream().allMatch(v -> v.anulada);
                ejecutar(cx, "UPDATE comprobante SET anulado = ?, base_cent = ?, igv_cent = ?, total_cent = ? WHERE id = ?",
                        anulado ? 1 : 0, Num.aEntero(t.base(), Num.CENT), Num.aEntero(t.igv(), Num.CENT),
                        Num.aEntero(t.total(), Num.CENT), cid);
            }

            if (extra != null) extra.hacer(cx);

            for (Pendiente m : movimientos) {
                Movimiento mv = m.movimiento();
                Long pid = m.producto() == null ? null : m.producto().id != null ? m.producto().id : idsNuevos.get(m.producto());
                insertar(cx, """
                        INSERT INTO movimiento (fecha, tipo, producto, producto_id, cantidad_mil, detalle, usuario, delta_mil, saldo_mil, costo_dm)
                        VALUES (?,?,?,?,?,?,?,?,?,?)""",
                        mv.fecha(), mv.tipo(), mv.producto(), pid, Num.aEntero(mv.cantidad(), Num.MIL),
                        mv.detalle() == null ? "" : mv.detalle(), mv.usuario() == null ? "" : mv.usuario(),
                        Num.aEntero(m.delta(), Num.MIL), Num.aEntero(m.saldo(), Num.MIL), Num.aEntero(m.costo(), Num.DM));
            }
            for (Map.Entry<String, Integer> c : contadores.entrySet())
                if (!c.getValue().equals(contadoresGuardados.get(c.getKey())))
                    ejecutar(cx, "INSERT OR REPLACE INTO contador (nombre, valor) VALUES (?, ?)", c.getKey(), c.getValue());
            return null;
        });

        idsNuevos.forEach((p, id) -> p.id = id);
        idsVentas.forEach((v, id) -> v.id = id);
        comprobanteDe.forEach((v, cid) -> v.comprobanteId = cid);
        fotosProductos.clear();
        fotosProductos.putAll(fotosP);
        fotosVentas.clear();
        fotosVentas.putAll(fotosV);
        numerosBorrados.forEach(comprobantePorNumero::remove);
        comprobantePorNumero.putAll(numerosNuevos);
        contadoresGuardados.putAll(contadores);
    }

    static InventoryService.Cabecera cabecera(Map<Integer, InventoryService.Cabecera> cabeceras, int numero) {
        InventoryService.Cabecera c = numero > 0 ? cabeceras.get(numero) : null;
        return c != null ? c : new InventoryService.Cabecera(TipoComprobante.NOTA_VENTA, numero, null, "", "", Igv.Modo.EXONERADO);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private FilaProducto fila(Producto p, Long id) {
        return new FilaProducto(p.nombre, aliasJson(p.alias), Num.aEntero(p.stock, Num.MIL), Num.aEntero(p.stockMin, Num.MIL),
                Num.aEntero(p.precio, Num.CENT), p.unidad, p.categoria == null ? "" : p.categoria,
                p.codigo == null ? "" : p.codigo, p.codigoBarras == null ? "" : p.codigoBarras, Num.aEntero(p.costo, Num.DM));
    }

    private static List<Object> valores(FilaProducto f) {
        return Arrays.asList(f.nombre(), f.alias(), f.stock(), f.stockMin(), f.precio(), f.unidad(), f.categoria(),
                f.codigo(), f.codigoBarras(), f.costo());
    }

    private static FilaVenta filaVenta(InventoryService.Venta v, long comprobanteId, int item, Long productoId) {
        return new FilaVenta(comprobanteId, item, productoId, v.producto, Num.aEntero(v.cantidad, Num.MIL),
                v.unidad == null ? "" : v.unidad, Num.aEntero(v.precioUnitario, Num.CENT), Num.aEntero(v.monto, Num.CENT),
                Num.aEntero(v.costoUnitario, Num.DM), v.anulada);
    }

    private static List<Object> valores(FilaVenta f) {
        return Arrays.asList(f.comprobanteId(), f.item(), f.productoId(), f.producto(), f.cantidad(), f.unidad(),
                f.precio(), f.monto(), f.costo(), f.anulada() ? 1 : 0);
    }

    private String aliasJson(List<String> alias) {
        try {
            return json.writeValueAsString(alias == null ? List.of() : alias);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> alias(String s) {
        try {
            return new ArrayList<>(json.readValue(s, new TypeReference<List<String>>() {}));
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static void ejecutar(Connection cx, String sql, Object... valores) throws SQLException {
        try (PreparedStatement ps = cx.prepareStatement(sql)) {
            poner(ps, valores);
            ps.executeUpdate();
        }
    }

    private static long insertar(Connection cx, String sql, Object... valores) throws SQLException {
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
}
