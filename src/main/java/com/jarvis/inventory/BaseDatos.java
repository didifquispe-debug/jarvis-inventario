/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;

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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class BaseDatos {

    private static final Logger log = LoggerFactory.getLogger(BaseDatos.class);

    static final int RESPALDOS = 7;
    static final int RESPALDOS_MANUALES = 10;
    static final int RESPALDOS_EXTRA = 30;
    static final String PREFIJO_EXTRA = "jarvis-respaldo-";
    private static final int VERSION = 7;
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter SELLO = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @FunctionalInterface
    interface Trabajo<T> { T hacer(Connection cx) throws SQLException; }

    /** Una copia guardada del inventario: tipo es DIARIO, MANUAL, ANTES_DE_NUEVO o ANTES_DE_RESTAURAR. */
    record Respaldo(String nombre, String tipo, LocalDateTime fecha, long bytes) {}

    private static final Pattern RESPALDO = Pattern.compile(
            "\\.(respaldo-(\\d{8})|respaldo-manual-(\\d{8}-\\d{6})|antes-de-nuevo-(\\d{8}-\\d{6})|antes-de-restaurar-(\\d{8}-\\d{6}))(-\\d{1,3})?");

    private final Path archivo;
    private LocalDate respaldadoEl;
    private volatile Path carpetaExtra;

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
            return;
        }
        copiarAExtra(destino, PREFIJO_EXTRA + hoy.format(DIA) + ".db", false);
    }

    /** Copia del momento, aparte de la diaria. Devuelve el nombre del archivo. */
    String respaldoManual() throws SQLException {
        Path destino = nombreLibre("respaldo-manual-");
        copiarA(destino);
        try {
            List<Respaldo> manuales = listarRespaldos().stream().filter(r -> r.tipo().equals("MANUAL")).toList();
            for (int i = RESPALDOS_MANUALES; i < manuales.size(); i++)
                Files.deleteIfExists(archivo.resolveSibling(manuales.get(i).nombre()));
        } catch (IOException e) {
            log.warn("No pude borrar respaldos manuales viejos: {}", e.getMessage());
        }
        copiarAExtra(destino, PREFIJO_EXTRA + destino.getFileName().toString().replaceFirst("^.*respaldo-manual-", "") + ".db", true);
        return destino.getFileName().toString();
    }

    /** Archivo junto a la base con la fecha y hora de ahora, sin pisar uno que ya exista en el mismo segundo. */
    Path nombreLibre(String tipo) {
        String base = archivo.getFileName() + "." + tipo + LocalDateTime.now().format(SELLO);
        Path p = archivo.resolveSibling(base);
        for (int i = 2; Files.exists(p) && i < 1000; i++) p = archivo.resolveSibling(base + "-" + i);
        return p;
    }

    /** Carpeta aparte (USB, OneDrive...) donde también se dejan las copias; null para no usar ninguna. */
    void usarCarpetaExtra(Path carpeta) { this.carpetaExtra = carpeta; }

    Path carpetaExtra() { return carpetaExtra; }

    /** Copia el respaldo a la carpeta extra. Si {@code avisar}, lanza el error en vez de solo anotarlo. */
    private void copiarAExtra(Path respaldo, String nombre, boolean avisar) {
        Path carpeta = carpetaExtra;
        if (carpeta == null) return;
        try {
            Files.createDirectories(carpeta);
            Path destino = carpeta.resolve(nombre);
            Path temporal = carpeta.resolve(nombre + ".tmp");
            Files.copy(respaldo, temporal, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temporal, destino, StandardCopyOption.REPLACE_EXISTING);
            try (Stream<Path> s = Files.list(carpeta)) {
                List<Path> viejos = s.filter(p -> p.getFileName().toString().startsWith(PREFIJO_EXTRA)
                                && p.getFileName().toString().endsWith(".db"))
                        .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
                for (int i = RESPALDOS_EXTRA; i < viejos.size(); i++) Files.deleteIfExists(viejos.get(i));
            }
        } catch (IOException | RuntimeException e) {
            log.error("No pude copiar el respaldo a la carpeta extra {}: {}", carpeta, e.getMessage());
            if (avisar) throw new IllegalStateException("la copia quedó en la laptop, pero no pude dejarla en " + carpeta
                    + " (" + e.getMessage() + ")", e);
        }
    }

    /** Todas las copias que hay junto a la base, de la más nueva a la más vieja. */
    List<Respaldo> listarRespaldos() throws IOException {
        String base = archivo.getFileName().toString();
        Path dir = archivo.toAbsolutePath().getParent();
        List<Respaldo> lista = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.toList()) {
                String nombre = p.getFileName().toString();
                if (!nombre.startsWith(base) || !Files.isRegularFile(p)) continue;
                Matcher m = RESPALDO.matcher(nombre.substring(base.length()));
                if (!m.matches()) continue;
                String tipo;
                LocalDateTime fecha;
                if (m.group(2) != null) { tipo = "DIARIO"; fecha = LocalDate.parse(m.group(2), DIA).atStartOfDay(); }
                else if (m.group(3) != null) { tipo = "MANUAL"; fecha = LocalDateTime.parse(m.group(3), SELLO); }
                else if (m.group(4) != null) { tipo = "ANTES_DE_NUEVO"; fecha = LocalDateTime.parse(m.group(4), SELLO); }
                else { tipo = "ANTES_DE_RESTAURAR"; fecha = LocalDateTime.parse(m.group(5), SELLO); }
                if (tipo.equals("DIARIO")) {
                    LocalDateTime mod = LocalDateTime.ofInstant(Files.getLastModifiedTime(p).toInstant(), ZoneId.systemDefault());
                    if (mod.toLocalDate().equals(fecha.toLocalDate())) fecha = mod.withNano(0);
                }
                lista.add(new Respaldo(nombre, tipo, fecha, Files.size(p)));
            }
        }
        lista.sort(Comparator.comparing(Respaldo::fecha).thenComparingInt((Respaldo r) -> r.nombre().length()).thenComparing(Respaldo::nombre).reversed());
        return lista;
    }

    /** Ruta de un respaldo por su nombre; null si no es uno de los de {@link #listarRespaldos()}. */
    Path respaldo(String nombre) throws IOException {
        if (nombre == null) return null;
        for (Respaldo r : listarRespaldos())
            if (r.nombre().equals(nombre)) return archivo.toAbsolutePath().getParent().resolve(r.nombre());
        return null;
    }

    /**
     * Reemplaza el inventario por el de {@code respaldo}. Antes revisa que el respaldo esté sano y guarda una copia
     * del inventario actual (cuyo nombre devuelve). Si algo falla, la base queda como estaba.
     */
    String restaurarDesde(Path respaldo) throws SQLException {
        revisarRespaldo(respaldo);
        Path antes = nombreLibre("antes-de-restaurar-");
        copiarA(antes);
        restaurarArchivo(respaldo);
        try {
            preparar();
        } catch (SQLException e) {
            restaurarArchivo(antes);
            throw e;
        }
        respaldadoEl = null;
        return antes.getFileName().toString();
    }

    private void restaurarArchivo(Path origen) throws SQLException {
        String ruta = origen.toAbsolutePath().toString();
        if (ruta.contains("'")) throw new SQLException("la ruta del respaldo no puede llevar comillas");
        try (Connection cx = conectar(); Statement st = cx.createStatement()) {
            st.executeUpdate("restore from '" + ruta + "'");
        }
    }

    private static void revisarRespaldo(Path respaldo) throws SQLException {
        if (!Files.isRegularFile(respaldo)) throw new SQLException("el respaldo ya no existe");
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        try (Connection cx = DriverManager.getConnection("jdbc:sqlite:" + respaldo.toAbsolutePath(), config.toProperties());
             Statement st = cx.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
                String r = rs.next() ? rs.getString(1) : "sin respuesta";
                if (!"ok".equalsIgnoreCase(r)) throw new SQLException("el respaldo está dañado (" + r + ")");
            }
            try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                int v = rs.next() ? rs.getInt(1) : 0;
                if (v > VERSION) throw new SQLException("el respaldo es de una versión más nueva de JARVIS");
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='producto'")) {
                if (!rs.next() || rs.getInt(1) == 0) throw new SQLException("el archivo no es un inventario de JARVIS");
            }
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
            if (version < 7) {
                cx.setAutoCommit(false);
                try {
                    if (version < 2) migrarAVersion2(st);
                    if (version < 3) migrarAVersion3(st);
                    if (version < 4) migrarAVersion4(st);
                    if (version < 5) migrarAVersion5(st);
                    if (version < 6) migrarAVersion6(st);
                    migrarAVersion7(st);
                    cx.commit();
                } catch (SQLException e) {
                    cx.rollback();
                    throw e;
                }
            }
        }
    }

    /** Varias tiendas: la lista vive en la base principal y cada usuario pertenece a una (la 1 por defecto). */
    private static void migrarAVersion7(Statement st) throws SQLException {
        st.execute("CREATE TABLE IF NOT EXISTS tienda (id INTEGER PRIMARY KEY, nombre TEXT NOT NULL UNIQUE COLLATE NOCASE, creada TEXT NOT NULL)");
        st.execute("ALTER TABLE usuario ADD COLUMN tienda INTEGER NOT NULL DEFAULT 1");
        st.execute("PRAGMA user_version = 7");
    }

    private static void migrarAVersion6(Statement st) throws SQLException {
        st.execute("CREATE TABLE IF NOT EXISTS ajuste (clave TEXT PRIMARY KEY, valor TEXT NOT NULL)");
        st.execute("PRAGMA user_version = 6");
    }

    private static void migrarAVersion5(Statement st) throws SQLException {
        st.execute("""
                CREATE TABLE IF NOT EXISTS usuario (
                    id        INTEGER PRIMARY KEY,
                    nombre    TEXT    NOT NULL UNIQUE COLLATE NOCASE,
                    rol       TEXT    NOT NULL,
                    pin_hash  TEXT    NOT NULL,
                    activo    INTEGER NOT NULL DEFAULT 1,
                    creado    TEXT    NOT NULL
                )""");
        st.execute("PRAGMA user_version = 5");
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
