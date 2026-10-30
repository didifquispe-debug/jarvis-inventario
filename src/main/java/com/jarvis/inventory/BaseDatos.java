/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

final class BaseDatos {

    private static final Logger log = LoggerFactory.getLogger(BaseDatos.class);

    static final int RESPALDOS = 7;
    private static final int VERSION = 4;
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter SELLO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @FunctionalInterface
    interface Trabajo<T> { T hacer(Connection cx) throws SQLException; }

    private final Path archivo;
    private LocalDate respaldadoEl;

    BaseDatos(Path archivo) {
        this.archivo = archivo;
        abrirOReparar();
    }

    Path archivo() { return archivo; }

    boolean vacia() {
        return leer(cx -> {
            try (Statement st = cx.createStatement();
                 ResultSet rs = st.executeQuery("SELECT (SELECT COUNT(*) FROM producto) + (SELECT COUNT(*) FROM comprobante)"
                         + " + (SELECT COUNT(*) FROM movimiento) + (SELECT COUNT(*) FROM contador)")) {
                return rs.next() && rs.getLong(1) == 0;
            }
        });
    }

    <T> T transaccion(Trabajo<T> t) throws SQLException {
        try (Connection cx = conectar()) {
            cx.setAutoCommit(false);
            try {
                T r = t.hacer(cx);
                cx.commit();
                return r;
            } catch (SQLException | RuntimeException e) {
                cx.rollback();
                throw e;
            }
        }
    }

    <T> T leer(Trabajo<T> t) {
        try (Connection cx = conectar()) {
            return t.hacer(cx);
        } catch (SQLException e) {
            throw new IllegalStateException("No pude leer la base de datos " + archivo + ": " + e.getMessage(), e);
        }
    }

    void copiarA(Path destino) throws SQLException {
        try (Connection cx = conectar(); Statement st = cx.createStatement()) {
            Files.deleteIfExists(destino);
            st.execute("VACUUM INTO '" + destino.toAbsolutePath().toString().replace("'", "''") + "'");
        } catch (IOException e) {
            throw new SQLException("No pude reemplazar " + destino + ": " + e.getMessage(), e);
        }
    }

    void respaldoDiario() {
        LocalDate hoy = LocalDate.now();
        if (hoy.equals(respaldadoEl)) return;
        Path destino = archivo.resolveSibling(archivo.getFileName() + ".respaldo-" + hoy.format(DIA));
        try {
            if (!Files.exists(destino)) copiarA(destino);
            respaldadoEl = hoy;
            List<Path> viejos = respaldos();
            for (int i = RESPALDOS; i < viejos.size(); i++) Files.deleteIfExists(viejos.get(i));
        } catch (SQLException | IOException e) {
            log.error("No pude hacer el respaldo diario del inventario en {}: {}", destino, e.getMessage());
        }
    }

    List<Path> respaldos() throws IOException {
        String prefijo = archivo.getFileName() + ".respaldo-";
        Path dir = archivo.toAbsolutePath().getParent();
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefijo)
                            && p.getFileName().toString().substring(prefijo.length()).matches("\\d{8}"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        }
    }

    private Connection conectar() throws SQLException {
        Connection cx = DriverManager.getConnection("jdbc:sqlite:" + archivo.toAbsolutePath());
        try (Statement st = cx.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
            st.execute("PRAGMA synchronous = FULL");
        } catch (SQLException e) {
            cx.close();
            throw e;
        }
        return cx;
    }

    private void abrirOReparar() {
        try {
            if (archivo.getParent() != null) Files.createDirectories(archivo.toAbsolutePath().getParent());
            preparar();
            return;
        } catch (SQLException | IOException e) {
            log.error("La base de datos del inventario {} no se puede abrir ({}).", archivo, e.getMessage());
        }
        Path danado = archivo.resolveSibling(archivo.getFileName() + ".danado-" + LocalDateTime.now().format(SELLO));
        try {
            Files.move(archivo, danado, StandardCopyOption.REPLACE_EXISTING);
            for (String extra : new String[]{"-journal", "-wal", "-shm"})
                Files.deleteIfExists(archivo.resolveSibling(archivo.getFileName() + extra));
            List<Path> respaldos = respaldos();
            if (!respaldos.isEmpty()) {
                Files.copy(respaldos.get(0), archivo);
                log.warn("Recuperé el inventario del respaldo {}. El archivo dañado quedó en {}.", respaldos.get(0), danado);
            } else {
                log.error("No hay respaldo: empiezo con el inventario vacío. El archivo dañado quedó en {}.", danado);
            }
            preparar();
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("No pude abrir ni reparar la base de datos " + archivo + ": " + e.getMessage(), e);
        }
    }

    private void preparar() throws SQLException {
        try (Connection cx = conectar(); Statement st = cx.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
                String r = rs.next() ? rs.getString(1) : "sin respuesta";
                if (!"ok".equalsIgnoreCase(r)) throw new SQLException("revisión fallida: " + r);
            }
            int version;
            try (ResultSet rs = st.executeQuery("PRAGMA user_version")) { version = rs.next() ? rs.getInt(1) : 0; }
            if (version > VERSION)
                throw new SQLException("el archivo es de una versión más nueva de JARVIS (" + version + ")");
            if (version < 1) crearVersion1(st);
            if (version < 4) {
                cx.setAutoCommit(false);
                try {
                    if (version < 2) migrarAVersion2(st);
                    if (version < 3) migrarAVersion3(st);
                    migrarAVersion4(st);
                    cx.commit();
                } catch (SQLException e) {
                    cx.rollback();
                    throw e;
                }
            }
        }
    }

    private static void migrarAVersion4(Statement st) throws SQLException {
        st.execute("ALTER TABLE movimiento ADD COLUMN delta_mil INTEGER");
        st.execute("ALTER TABLE movimiento ADD COLUMN saldo_mil INTEGER");
        st.execute("ALTER TABLE movimiento ADD COLUMN costo_dm INTEGER");
        st.execute("PRAGMA user_version = 4");
    }

    private static void migrarAVersion3(Statement st) throws SQLException {
        st.execute("""
                CREATE TABLE IF NOT EXISTS proveedor (
                    id            INTEGER PRIMARY KEY,
                    ruc           TEXT    NOT NULL UNIQUE,
                    razon_social  TEXT    NOT NULL,
                    busqueda      TEXT    NOT NULL DEFAULT '',
                    direccion     TEXT    NOT NULL DEFAULT '',
                    telefono      TEXT    NOT NULL DEFAULT ''
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS compra (
                    id                INTEGER PRIMARY KEY,
                    numero            INTEGER NOT NULL UNIQUE,
                    fecha             TEXT    NOT NULL,
                    hora              TEXT    NOT NULL DEFAULT '',
                    proveedor_id      INTEGER NOT NULL REFERENCES proveedor(id),
                    proveedor_ruc     TEXT    NOT NULL,
                    proveedor_nombre  TEXT    NOT NULL,
                    tipo_documento    TEXT    NOT NULL,
                    numero_documento  TEXT    NOT NULL,
                    fecha_documento   TEXT    NOT NULL,
                    modo_igv          TEXT    NOT NULL,
                    base_cent         INTEGER NOT NULL,
                    igv_cent          INTEGER NOT NULL,
                    total_cent        INTEGER NOT NULL,
                    anulada           INTEGER NOT NULL DEFAULT 0,
                    observacion       TEXT    NOT NULL DEFAULT '',
                    usuario           TEXT    NOT NULL DEFAULT ''
                )""");
        st.execute("CREATE INDEX IF NOT EXISTS ix_compra_fecha ON compra(fecha)");
        st.execute("CREATE INDEX IF NOT EXISTS ix_compra_proveedor ON compra(proveedor_id)");
        st.execute("""
                CREATE TABLE IF NOT EXISTS compra_detalle (
                    id            INTEGER PRIMARY KEY,
                    compra_id     INTEGER NOT NULL REFERENCES compra(id) ON DELETE CASCADE,
                    item          INTEGER NOT NULL,
                    producto_id   INTEGER,
                    producto      TEXT    NOT NULL,
                    unidad        TEXT    NOT NULL,
                    cantidad_mil  INTEGER NOT NULL,
                    costo_dm      INTEGER NOT NULL,
                    importe_cent  INTEGER NOT NULL
                )""");
        st.execute("CREATE INDEX IF NOT EXISTS ix_compra_detalle ON compra_detalle(compra_id)");
        st.execute("PRAGMA user_version = 3");
    }

    private static void migrarAVersion2(Statement st) throws SQLException {
        st.execute("""
                CREATE TABLE IF NOT EXISTS cliente (
                    id         INTEGER PRIMARY KEY,
                    documento  TEXT    NOT NULL UNIQUE,
                    nombre     TEXT    NOT NULL,
                    busqueda   TEXT    NOT NULL DEFAULT '',
                    email      TEXT    NOT NULL DEFAULT '',
                    telefono   TEXT    NOT NULL DEFAULT '',
                    direccion  TEXT    NOT NULL DEFAULT ''
                )""");
        st.execute("ALTER TABLE comprobante ADD COLUMN tipo TEXT NOT NULL DEFAULT 'NOTA_VENTA'");
        st.execute("ALTER TABLE comprobante ADD COLUMN serie TEXT NOT NULL DEFAULT 'NV01'");
        st.execute("ALTER TABLE comprobante ADD COLUMN correlativo INTEGER");
        st.execute("ALTER TABLE comprobante ADD COLUMN cliente_id INTEGER");
        st.execute("ALTER TABLE comprobante ADD COLUMN cliente_doc TEXT NOT NULL DEFAULT ''");
        st.execute("ALTER TABLE comprobante ADD COLUMN cliente_nombre TEXT NOT NULL DEFAULT ''");
        st.execute("ALTER TABLE comprobante ADD COLUMN modo_igv TEXT NOT NULL DEFAULT 'EXONERADO'");
        st.execute("ALTER TABLE comprobante ADD COLUMN base_cent INTEGER NOT NULL DEFAULT 0");
        st.execute("ALTER TABLE comprobante ADD COLUMN igv_cent INTEGER NOT NULL DEFAULT 0");
        st.execute("UPDATE comprobante SET correlativo = numero, base_cent = total_cent");
        st.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_comprobante_serie ON comprobante(serie, correlativo)");
        st.execute("CREATE INDEX IF NOT EXISTS ix_comprobante_cliente ON comprobante(cliente_id)");
        st.execute("PRAGMA user_version = 2");
    }

    private static void crearVersion1(Statement st) throws SQLException {
        st.execute("""
                CREATE TABLE IF NOT EXISTS producto (
                    id              INTEGER PRIMARY KEY,
                    nombre          TEXT    NOT NULL,
                    alias           TEXT    NOT NULL DEFAULT '[]',
                    stock_mil       INTEGER NOT NULL DEFAULT 0,
                    stock_min_mil   INTEGER NOT NULL DEFAULT 0,
                    precio_cent     INTEGER NOT NULL DEFAULT 0,
                    unidad          TEXT    NOT NULL DEFAULT 'unidades',
                    categoria       TEXT    NOT NULL DEFAULT '',
                    codigo          TEXT    NOT NULL DEFAULT '',
                    codigo_barras   TEXT    NOT NULL DEFAULT '',
                    costo_dm        INTEGER
                )""");
        st.execute("""
                CREATE TABLE IF NOT EXISTS comprobante (
                    id          INTEGER PRIMARY KEY,
                    numero      INTEGER UNIQUE,
                    fecha       TEXT    NOT NULL,
                    hora        TEXT    NOT NULL DEFAULT '',
                    anulado     INTEGER NOT NULL DEFAULT 0,
                    total_cent  INTEGER NOT NULL DEFAULT 0
                )""");
        st.execute("CREATE INDEX IF NOT EXISTS ix_comprobante_fecha ON comprobante(fecha)");
        st.execute("""
                CREATE TABLE IF NOT EXISTS venta_detalle (
                    id              INTEGER PRIMARY KEY,
                    comprobante_id  INTEGER NOT NULL REFERENCES comprobante(id) ON DELETE CASCADE,
                    item            INTEGER NOT NULL,
                    producto_id     INTEGER,
                    producto        TEXT    NOT NULL,
                    cantidad_mil    INTEGER NOT NULL,
                    unidad          TEXT    NOT NULL,
                    precio_cent     INTEGER NOT NULL,
                    monto_cent      INTEGER NOT NULL,
                    costo_dm        INTEGER,
                    anulada         INTEGER NOT NULL DEFAULT 0
                )""");
        st.execute("CREATE INDEX IF NOT EXISTS ix_detalle_comprobante ON venta_detalle(comprobante_id)");
        st.execute("""
                CREATE TABLE IF NOT EXISTS movimiento (
                    id            INTEGER PRIMARY KEY,
                    fecha         TEXT    NOT NULL,
                    tipo          TEXT    NOT NULL,
                    producto      TEXT    NOT NULL,
                    producto_id   INTEGER,
                    cantidad_mil  INTEGER,
                    detalle       TEXT    NOT NULL DEFAULT '',
                    usuario       TEXT    NOT NULL DEFAULT ''
                )""");
        st.execute("CREATE INDEX IF NOT EXISTS ix_movimiento_producto ON movimiento(producto_id, id)");
        st.execute("CREATE TABLE IF NOT EXISTS contador (nombre TEXT PRIMARY KEY, valor INTEGER NOT NULL)");
        st.execute("PRAGMA user_version = 1");
    }
}
