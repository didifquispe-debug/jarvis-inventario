/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class Clientes {

    public record Cliente(long id, String documento, String nombre, String email, String telefono, String direccion) {
        @JsonProperty("tipoDocumento")
        public String tipoDocumento() { return documento.length() == 11 ? "RUC" : "DNI"; }
    }

    public record Datos(String documento, String nombre, String email, String telefono, String direccion) {}

    private static final int MAX_NOMBRE = 120, MAX_EMAIL = 120, MAX_DIRECCION = 200;

    private final BaseDatos db;

    Clientes(BaseDatos db) { this.db = db; }

    public synchronized Optional<Cliente> porDocumento(String documento) {
        String d = documento == null ? "" : documento.trim();
        if (d.isEmpty()) return Optional.empty();
        List<Cliente> r = consultar("SELECT * FROM cliente WHERE documento = ?", d);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public synchronized Optional<Cliente> porId(long id) {
        List<Cliente> r = consultar("SELECT * FROM cliente WHERE id = ?", id);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    public synchronized List<Cliente> buscar(String texto, int limite) {
        String t = texto == null ? "" : texto.trim();
        int lim = Math.min(Math.max(limite, 1), 500);
        if (t.isEmpty()) return consultar("SELECT * FROM cliente ORDER BY nombre LIMIT ?", lim);
        if (t.matches("\\d+")) return consultar("SELECT * FROM cliente WHERE documento LIKE ? ORDER BY documento LIMIT ?", t + "%", lim);
        StringBuilder sql = new StringBuilder("SELECT * FROM cliente WHERE 1=1");
        List<Object> valores = new ArrayList<>();
        for (String palabra : Palabras.norm(t).split(" ")) {
            if (palabra.isBlank()) continue;
            sql.append(" AND busqueda LIKE ?");
            valores.add("%" + palabra + "%");
        }
        sql.append(" ORDER BY nombre LIMIT ?");
        valores.add(lim);
        return consultar(sql.toString(), valores.toArray());
    }

    public synchronized int contar() {
        return db.leer(cx -> {
            try (Statement st = cx.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM cliente")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    public synchronized InventoryService.Resultado crear(Datos d) {
        if (d == null) return InventoryService.Resultado.error("Faltan los datos del cliente.");
        String doc = limpio(d.documento()), nombre = limpio(d.nombre());
        String error = errorDocumento(doc);
        if (error == null) error = errorNombre(nombre);
        if (error == null) error = errorContacto(limpio(d.email()), limpio(d.telefono()), limpio(d.direccion()));
        if (error != null) return InventoryService.Resultado.error(error);
        if (porDocumento(doc).isPresent())
            return InventoryService.Resultado.error("Ya hay un cliente registrado con el " + tipo(doc) + " " + doc + ".");
        try {
            db.transaccion(cx -> {
                try (PreparedStatement ps = cx.prepareStatement("""
                        INSERT INTO cliente (documento, nombre, busqueda, email, telefono, direccion) VALUES (?,?,?,?,?,?)""")) {
                    ps.setString(1, doc);
                    ps.setString(2, nombre);
                    ps.setString(3, Palabras.norm(nombre));
                    ps.setString(4, nz(limpio(d.email())));
                    ps.setString(5, nz(limpio(d.telefono())));
                    ps.setString(6, nz(limpio(d.direccion())));
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            return InventoryService.Resultado.error("No pude guardar el cliente: " + e.getMessage());
        }
        return InventoryService.Resultado.ok("Cliente registrado: " + nombre + ", " + tipo(doc) + " " + doc + ".");
    }

    public synchronized InventoryService.Resultado editar(String documento, Datos d) {
        Optional<Cliente> actual = porDocumento(documento);
        if (actual.isEmpty()) return InventoryService.Resultado.noEncontrado("No encontré un cliente con el documento " + documento + ".");
        Cliente c = actual.get();
        String doc = d.documento() == null || d.documento().isBlank() ? c.documento() : limpio(d.documento());
        String nombre = d.nombre() == null || d.nombre().isBlank() ? c.nombre() : limpio(d.nombre());
        String email = d.email() == null ? c.email() : limpio(d.email());
        String telefono = d.telefono() == null ? c.telefono() : limpio(d.telefono());
        String direccion = d.direccion() == null ? c.direccion() : limpio(d.direccion());
        String error = errorDocumento(doc);
        if (error == null) error = errorNombre(nombre);
        if (error == null) error = errorContacto(email, telefono, direccion);
        if (error != null) return InventoryService.Resultado.error(error);
        if (!doc.equals(c.documento()) && porDocumento(doc).isPresent())
            return InventoryService.Resultado.error("Ya hay otro cliente con el " + tipo(doc) + " " + doc + ".");
        try {
            db.transaccion(cx -> {
                try (PreparedStatement ps = cx.prepareStatement("""
                        UPDATE cliente SET documento=?, nombre=?, busqueda=?, email=?, telefono=?, direccion=? WHERE id=?""")) {
                    ps.setString(1, doc);
                    ps.setString(2, nombre);
                    ps.setString(3, Palabras.norm(nombre));
                    ps.setString(4, nz(email));
                    ps.setString(5, nz(telefono));
                    ps.setString(6, nz(direccion));
                    ps.setLong(7, c.id());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            return InventoryService.Resultado.error("No pude guardar el cliente: " + e.getMessage());
        }
        return InventoryService.Resultado.ok("Guardé los datos de " + nombre + ".");
    }

    public synchronized InventoryService.Resultado eliminar(String documento) {
        Optional<Cliente> c = porDocumento(documento);
        if (c.isEmpty()) return InventoryService.Resultado.noEncontrado("No encontré un cliente con el documento " + documento + ".");
        try {
            return db.transaccion(cx -> {
                try (PreparedStatement ps = cx.prepareStatement("SELECT COUNT(*) FROM comprobante WHERE cliente_id = ?")) {
                    ps.setLong(1, c.get().id());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0)
                            return InventoryService.Resultado.error("No se puede eliminar a " + c.get().nombre()
                                    + ": tiene comprobantes registrados.");
                    }
                }
                try (PreparedStatement ps = cx.prepareStatement("DELETE FROM cliente WHERE id = ?")) {
                    ps.setLong(1, c.get().id());
                    ps.executeUpdate();
                }
                return InventoryService.Resultado.ok("Eliminé al cliente " + c.get().nombre() + ".");
            });
        } catch (SQLException e) {
            return InventoryService.Resultado.error("No pude eliminar el cliente: " + e.getMessage());
        }
    }

    static String tipo(String documento) { return documento != null && documento.length() == 11 ? "RUC" : "DNI"; }

    private static String errorDocumento(String doc) {
        if (doc == null || doc.isEmpty()) return "Falta el DNI o RUC del cliente.";
        if (!doc.matches("\\d{8}|(10|15|17|20)\\d{9}"))
            return "El documento debe ser un DNI de 8 dígitos o un RUC de 11 dígitos (que empiece con 10, 15, 17 o 20).";
        return null;
    }

    private static String errorNombre(String nombre) {
        if (nombre == null || nombre.isEmpty()) return "Falta el nombre del cliente.";
        if (nombre.length() > MAX_NOMBRE) return "El nombre es muy largo (máximo " + MAX_NOMBRE + " letras).";
        return null;
    }

    private static String errorContacto(String email, String telefono, String direccion) {
        if (email != null && !email.isEmpty() && (email.length() > MAX_EMAIL || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")))
            return "El correo no tiene un formato válido.";
        if (telefono != null && !telefono.isEmpty() && !telefono.matches("\\d{6,9}"))
            return "El teléfono debe tener entre 6 y 9 dígitos.";
        if (direccion != null && direccion.length() > MAX_DIRECCION) return "La dirección es muy larga (máximo " + MAX_DIRECCION + ").";
        return null;
    }

    private static String limpio(String s) { return s == null ? null : s.trim().replaceAll("\\s+", " "); }

    private static String nz(String s) { return s == null ? "" : s; }

    private List<Cliente> consultar(String sql, Object... valores) {
        return db.leer(cx -> {
            List<Cliente> out = new ArrayList<>();
            try (PreparedStatement ps = cx.prepareStatement(sql)) {
                for (int i = 0; i < valores.length; i++) ps.setObject(i + 1, valores[i]);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        out.add(new Cliente(rs.getLong("id"), rs.getString("documento"), rs.getString("nombre"),
                                rs.getString("email"), rs.getString("telefono"), rs.getString("direccion")));
                }
            }
            return out;
        });
    }
}
