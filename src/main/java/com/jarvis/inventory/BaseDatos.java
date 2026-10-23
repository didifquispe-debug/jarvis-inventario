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

/**
 * El archivo SQLite del inventario ({@code jarvis-inventario.db}).
 * <ul>
 *   <li>Se abre una conexión por operación y se cierra al terminar: el archivo nunca queda bloqueado
 *       (OneDrive, el antivirus o una copia manual pueden tocarlo sin problemas).</li>
 *   <li>Cada guardado es una transacción: un corte de luz deja el estado anterior completo o el nuevo completo.</li>
 *   <li>Respaldo diario ({@code .respaldo-AAAAMMDD}, se conservan los últimos {@value #RESPALDOS}).</li>
 *   <li>Si al abrir el archivo está dañado, se guarda aparte y se vuelve al último respaldo.</li>
 * </ul>
 * Dinero y cantidades se guardan como enteros exactos (céntimos, milésimas, diezmilésimas): las sumas cuadran al céntimo.
 */
final class BaseDatos {

    private static final Logger log = LoggerFactory.getLogger(BaseDatos.class);

    static final int RESPALDOS = 7;
    private static final int VERSION = 2;
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

    /** ¿El archivo se acaba de crear (aún sin datos)? Sirve para importar el inventario JSON antiguo una sola vez. */
    boolean vacia() {
        return leer(cx -> {
            try (Statement st = cx.createStatement();
                 ResultSet rs = st.executeQuery("SELECT (SELECT COUNT(*) FROM producto) + (SELECT COUNT(*) FROM comprobante)"
                         + " + (SELECT COUNT(*) FROM movimiento) + (SELECT COUNT(*) FROM contador)")) {
                return rs.next() && rs.getLong(1) == 0;
            }
        });
    }

    /** Ejecuta {@code t} en una transacción: todo o nada. */
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

    /** Solo lectura. Un error de lectura es inesperado (el archivo se revisó al abrir): se lanza sin envolver en IO. */
    <T> T leer(Trabajo<T> t) {
        try (Connection cx = conectar()) {
            return t.hacer(cx);
        } catch (SQLException e) {
            throw new IllegalStateException("No pude leer la base de datos " + archivo + ": " + e.getMessage(), e);
        }
    }

    /** Copia consistente de la base completa en {@code destino} (aunque se esté usando). */
    void copiarA(Path destino) throws SQLException {
        try (Connection cx = conectar(); Statement st = cx.createStatement()) {
            Files.deleteIfExists(destino);
            st.execute("VACUUM INTO '" + destino.toAbsolutePath().toString().replace("'", "''") + "'");
        } catch (IOException e) {
            throw new SQLException("No pude reemplazar " + destino + ": " + e.getMessage(), e);
        }
    }

    /** Hace el respaldo del día si aún no existe y borra los más antiguos. Si falla, solo lo dice en el log. */
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

    /** Respaldos existentes, del más reciente al más antiguo. */
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

    // ---------------------------------------------------------------- apertura

    private Connection conectar() throws SQLException {
        Connection cx = DriverManager.getConnection("jdbc:sqlite:" + archivo.toAbsolutePath());
        try (Statement st = cx.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
            st.execute("PRAGMA synchronous = FULL");
        } catch (SQLException e) {
            cx.close();   // si no, Windows deja el archivo bloqueado y no se puede apartar el dañado
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

    /** Revisa que el archivo sea una base sana y crea (o actualiza) las tablas. */
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
            if (version < 2) {
                cx.setAutoCommit(false);   // en SQLite los cambios de tablas también son transaccionales
                try {
                    migrarAVersion2(st);
                    cx.commit();
                } catch (SQLException e) {
                    cx.rollback();
                    throw e;
                }
            }
        }
    }

    /**
     * Versión 2: clientes y comprobantes con tipo, serie, correlativo, cliente e IGV.
     * Las ventas que ya existían quedan como notas de venta (NV01) con su mismo número y sin IGV
     * (no se sabe si el negocio lo cobraba; no se inventan impuestos).
     */
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
