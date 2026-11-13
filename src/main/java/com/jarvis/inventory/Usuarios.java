/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import com.jarvis.security.FailureLimiter;
import com.jarvis.security.Rol;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Usuarios con rol y PIN. Mientras no haya ninguno activo, JARVIS funciona como siempre: sin iniciar sesión. */
public class Usuarios {

    public record Usuario(long id, String nombre, Rol rol, boolean activo, String creado, int tienda) {
        public String rolDescripcion() { return rol.descripcion; }
    }

    public record Sesion(String token, long usuarioId, String nombre, Rol rol, int tienda) {}

    public enum Ingreso { OK, PIN_INCORRECTO, BLOQUEADO }

    public record Intento(Ingreso estado, Sesion sesion, long segundos) {}

    static final int ITERACIONES = 120_000;
    private static final long SESION_MS = 12 * 60 * 60 * 1000L;
    private static final SecureRandom RND = new SecureRandom();

    private final DidierBaseDatos db;
    private final FailureLimiter fallos = new FailureLimiter(5, 5 * 60_000);
    private final Map<String, Sesion> sesiones = new ConcurrentHashMap<>();
    private final Map<String, Long> vencen = new ConcurrentHashMap<>();
    private volatile Boolean hay;

    Usuarios(DidierBaseDatos db) { this.db = db; }

    public boolean hayUsuarios() {
        Boolean h = hay;
        if (h == null) hay = h = !listar().stream().filter(Usuario::activo).toList().isEmpty();
        return h;
    }

    public synchronized List<Usuario> listar() {
        return db.leer(cx -> {
            List<Usuario> out = new ArrayList<>();
            try (Statement st = cx.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id, nombre, rol, activo, creado, tienda FROM usuario ORDER BY nombre COLLATE NOCASE")) {
                while (rs.next()) out.add(new Usuario(rs.getLong(1), rs.getString(2), Rol.valueOf(rs.getString(3)), rs.getInt(4) == 1,
                        rs.getString(5), rs.getInt(6)));
            }
            return out;
        });
    }

    public List<String> nombresActivos() {
        return listar().stream().filter(Usuario::activo).map(Usuario::nombre).toList();
    }

    public synchronized DYSAInventoryService.Resultado crear(String nombre, String rolTexto, String pin) {
        return crear(nombre, rolTexto, pin, 1);
    }

    public synchronized DYSAInventoryService.Resultado crear(String nombre, String rolTexto, String pin, int tienda) {
        String n = limpio(nombre);
        if (!tiendaExiste(tienda)) return DYSAInventoryService.Resultado.error("Esa tienda no existe.");
        Rol rol = Rol.de(rolTexto);
        String error = errorNombre(n);
        if (error == null && rol == null) error = "El rol es administrador, cajero o almacén.";
        if (error == null) error = errorPin(pin);
        if (error != null) return DYSAInventoryService.Resultado.error(error);
        if (buscar(n).isPresent()) return DYSAInventoryService.Resultado.error("Ya existe el usuario " + n + ".");
        if (!hayUsuarios() && rol != Rol.ADMIN)
            return DYSAInventoryService.Resultado.error("El primer usuario debe ser administrador, para que alguien pueda manejar a los demás.");
        try {
            db.transaccion(cx -> {
                try (PreparedStatement ps = cx.prepareStatement("INSERT INTO usuario (nombre, rol, pin_hash, activo, creado, tienda) VALUES (?,?,?,1,?,?)")) {
                    ps.setString(1, n);
                    ps.setString(2, rol.name());
                    ps.setString(3, hash(pin.trim()));
                    ps.setString(4, LocalDateTime.now().withNano(0).toString());
                    ps.setInt(5, tienda);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            return DYSAInventoryService.Resultado.error("No pude guardar el usuario: " + e.getMessage());
        }
        hay = true;
        return DYSAInventoryService.Resultado.ok("Usuario creado: " + n + " (" + rol.descripcion.toLowerCase() + ").");
    }

    /** Cambia lo que no sea null. Siempre debe quedar al menos un administrador activo. */
    public synchronized DYSAInventoryService.Resultado editar(long id, String rolTexto, String pin, Boolean activo) {
        return editar(id, rolTexto, pin, activo, null);
    }

    public synchronized DYSAInventoryService.Resultado editar(long id, String rolTexto, String pin, Boolean activo, Integer tienda) {
        if (tienda != null && !tiendaExiste(tienda)) return DYSAInventoryService.Resultado.error("Esa tienda no existe.");
        Optional<Usuario> actual = listar().stream().filter(u -> u.id() == id).findFirst();
        if (actual.isEmpty()) return DYSAInventoryService.Resultado.noEncontrado("No existe ese usuario.");
        Usuario u = actual.get();
        Rol rol = rolTexto == null || rolTexto.isBlank() ? u.rol() : Rol.de(rolTexto);
        if (rol == null) return DYSAInventoryService.Resultado.error("El rol es administrador, cajero o almacén.");
        if (pin != null && !pin.isBlank()) {
            String error = errorPin(pin);
            if (error != null) return DYSAInventoryService.Resultado.error(error);
        }
        boolean queda = activo == null ? u.activo() : activo;
        if (u.rol() == Rol.ADMIN && u.activo() && (rol != Rol.ADMIN || !queda) && adminsActivos() <= 1 && quedanOtrosActivos(id))
            return DYSAInventoryService.Resultado.error("Debe quedar al menos un administrador activo.");
        try {
            db.transaccion(cx -> {
                try (PreparedStatement ps = cx.prepareStatement("UPDATE usuario SET rol=?, activo=?, pin_hash=COALESCE(?, pin_hash), tienda=COALESCE(?, tienda) WHERE id=?")) {
                    ps.setString(1, rol.name());
                    ps.setInt(2, queda ? 1 : 0);
                    ps.setString(3, pin == null || pin.isBlank() ? null : hash(pin.trim()));
                    if (tienda == null) ps.setNull(4, java.sql.Types.INTEGER); else ps.setInt(4, tienda);
                    ps.setLong(5, id);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            return DYSAInventoryService.Resultado.error("No pude guardar el usuario: " + e.getMessage());
        }
        cerrarSesionesDe(id);
        hay = null;
        return DYSAInventoryService.Resultado.ok("Usuario " + u.nombre() + " actualizado" + (queda ? "" : " (desactivado)") + ".");
    }

    public synchronized DYSAInventoryService.Resultado eliminar(long id) {
        Optional<Usuario> actual = listar().stream().filter(u -> u.id() == id).findFirst();
        if (actual.isEmpty()) return DYSAInventoryService.Resultado.noEncontrado("No existe ese usuario.");
        Usuario u = actual.get();
        if (u.rol() == Rol.ADMIN && u.activo() && adminsActivos() <= 1 && quedanOtrosActivos(id))
            return DYSAInventoryService.Resultado.error("No puede eliminar al único administrador mientras haya otros usuarios.");
        try {
            db.transaccion(cx -> {
                try (PreparedStatement ps = cx.prepareStatement("DELETE FROM usuario WHERE id=?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            return DYSAInventoryService.Resultado.error("No pude eliminar el usuario: " + e.getMessage());
        }
        cerrarSesionesDe(id);
        hay = null;
        return DYSAInventoryService.Resultado.ok("Usuario " + u.nombre() + " eliminado.");
    }

    public Intento iniciar(String nombre, String pin, String ip) {
        String n = limpio(nombre);
        String clave = "u:" + Palabras.norm(n);
        if (fallos.bloqueado(clave)) return new Intento(Ingreso.BLOQUEADO, null, fallos.segundosParaReintentar(clave));
        Optional<String[]> fila = buscar(n);
        if (fila.isEmpty() || !"1".equals(fila.get()[3]) || pin == null || !verificar(pin.trim(), fila.get()[4])) {
            fallos.fallo(clave);
            return fallos.bloqueado(clave) ? new Intento(Ingreso.BLOQUEADO, null, fallos.segundosParaReintentar(clave))
                    : new Intento(Ingreso.PIN_INCORRECTO, null, 0);
        }
        String[] f = fila.get();
        String token = token();
        Sesion s = new Sesion(token, Long.parseLong(f[0]), f[1], Rol.valueOf(f[2]), Integer.parseInt(f[5]));
        sesiones.put(token, s);
        vencen.put(token, System.currentTimeMillis() + SESION_MS);
        if (sesiones.size() > 500) purgar();
        return new Intento(Ingreso.OK, s, 0);
    }

    public Optional<Sesion> sesion(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        Sesion s = sesiones.get(token.trim());
        Long vence = vencen.get(token.trim());
        if (s == null || vence == null) return Optional.empty();
        if (vence < System.currentTimeMillis()) { cerrar(token); return Optional.empty(); }
        vencen.put(token.trim(), System.currentTimeMillis() + SESION_MS);
        return Optional.of(s);
    }

    public void cerrar(String token) {
        if (token == null) return;
        sesiones.remove(token.trim());
        vencen.remove(token.trim());
    }

    /** Tras restaurar un respaldo: la lista de usuarios pudo cambiar, así que todos vuelven a entrar. */
    void cerrarTodas() {
        sesiones.clear();
        vencen.clear();
        hay = null;
    }

    /** La tienda 1 (la principal) siempre existe; las demás, si están en la tabla de tiendas. */
    private boolean tiendaExiste(int tienda) {
        if (tienda == 1) return true;
        return db.leer(cx -> {
            try (PreparedStatement ps = cx.prepareStatement("SELECT 1 FROM tienda WHERE id = ?")) {
                ps.setInt(1, tienda);
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        });
    }

    private void cerrarSesionesDe(long id) {
        sesiones.values().removeIf(s -> {
            if (s.usuarioId() != id) return false;
            vencen.remove(s.token());
            return true;
        });
    }

    private void purgar() {
        long ahora = System.currentTimeMillis();
        vencen.entrySet().removeIf(e -> {
            if (e.getValue() >= ahora) return false;
            sesiones.remove(e.getKey());
            return true;
        });
    }

    private long adminsActivos() {
        return listar().stream().filter(u -> u.activo() && u.rol() == Rol.ADMIN).count();
    }

    private boolean quedanOtrosActivos(long id) {
        return listar().stream().anyMatch(u -> u.activo() && u.id() != id);
    }

    /** id, nombre, rol, activo, pin_hash */
    private Optional<String[]> buscar(String nombre) {
        if (nombre == null || nombre.isBlank()) return Optional.empty();
        return db.leer(cx -> {
            try (PreparedStatement ps = cx.prepareStatement("SELECT id, nombre, rol, activo, pin_hash, tienda FROM usuario WHERE nombre = ?")) {
                ps.setString(1, nombre);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new String[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)})
                            : Optional.<String[]>empty();
                }
            }
        });
    }

    private static String limpio(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    static String errorNombre(String n) {
        if (n.length() < 2 || n.length() > 40) return "El nombre de usuario debe tener entre 2 y 40 letras.";
        if (!n.matches("[\\p{L}\\p{N} ._-]+")) return "El nombre de usuario solo puede tener letras, números, espacios, punto o guion.";
        return null;
    }

    static String errorPin(String pin) {
        String p = pin == null ? "" : pin.trim();
        if (!p.matches("\\d{4,6}")) return "El PIN debe tener de 4 a 6 números.";
        if (p.chars().distinct().count() == 1) return "El PIN no puede repetir el mismo número (como 1111).";
        if ("0123456789".contains(p) || "9876543210".contains(p)) return "El PIN no puede ser una escalera (como 1234).";
        return null;
    }

    static String hash(String pin) {
        byte[] sal = new byte[16];
        RND.nextBytes(sal);
        return "pbkdf2$" + ITERACIONES + "$" + Base64.getEncoder().encodeToString(sal) + "$"
                + Base64.getEncoder().encodeToString(pbkdf2(pin, sal, ITERACIONES));
    }

    static boolean verificar(String pin, String guardado) {
        String[] partes = guardado == null ? new String[0] : guardado.split("\\$");
        if (partes.length != 4 || !partes[0].equals("pbkdf2")) return false;
        byte[] esperado = Base64.getDecoder().decode(partes[3]);
        byte[] calculado = pbkdf2(pin, Base64.getDecoder().decode(partes[2]), Integer.parseInt(partes[1]));
        return MessageDigest.isEqual(esperado, calculado);
    }

    private static byte[] pbkdf2(String pin, byte[] sal, int iteraciones) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(pin.toCharArray(), sal, iteraciones, 256)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token() {
        byte[] b = new byte[24];
        RND.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
