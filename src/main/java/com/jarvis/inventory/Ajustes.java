/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/** Ajustes que se cambian desde la pantalla (datos del negocio, moneda) y viajan con la base de datos. */
public class Ajustes {

    private final DidierBaseDatos db;

    Ajustes(DidierBaseDatos db) { this.db = db; }

    public synchronized Map<String, String> leer() {
        return db.leer(cx -> {
            Map<String, String> m = new LinkedHashMap<>();
            try (Statement st = cx.createStatement(); ResultSet rs = st.executeQuery("SELECT clave, valor FROM ajuste")) {
                while (rs.next()) m.put(rs.getString(1), rs.getString(2));
            }
            return m;
        });
    }

    public synchronized void guardar(Map<String, String> valores) throws SQLException {
        db.transaccion(cx -> {
            try (PreparedStatement ps = cx.prepareStatement("INSERT INTO ajuste (clave, valor) VALUES (?, ?) "
                    + "ON CONFLICT(clave) DO UPDATE SET valor = excluded.valor")) {
                for (Map.Entry<String, String> e : valores.entrySet()) {
                    ps.setString(1, e.getKey());
                    ps.setString(2, e.getValue() == null ? "" : e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }
}
