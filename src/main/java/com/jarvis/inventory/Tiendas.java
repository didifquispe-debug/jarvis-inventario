/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import com.jarvis.security.ClientSession;
import com.jarvis.security.Rol.Permiso;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Las tiendas del negocio. Cada una es como un negocio aparte, con su propia base de datos: productos, precios,
 * stock, ventas, compras, caja y copias de seguridad. La tienda 1 es la base de siempre y además guarda la lista
 * de tiendas y los usuarios (cada usuario pertenece a una tienda).
 */
@Component
public class Tiendas {

    private static final Logger log = LoggerFactory.getLogger(Tiendas.class);

    public record Tienda(int id, String nombre, String creada) {}

    static final int MAXIMO = 50;

    private final String archivo, tipo, igv;
    private final InventoryService principal;
    private final Map<Integer, InventoryService> abiertas = new ConcurrentHashMap<>();
    private volatile List<Tienda> lista = List.of();

    public Tiendas(@Value("${jarvis.inventory.file:${user.home}/jarvis-inventario.json}") String archivo,
                   @Value("${jarvis.comprobante.tipo:boleta}") String tipo,
                   @Value("${jarvis.igv:incluido}") String igv) {
        this.archivo = archivo;
        this.tipo = tipo;
        this.igv = igv;
        this.principal = new InventoryService(archivo, tipo, igv);
        abiertas.put(ClientSession.TIENDA_PRINCIPAL, principal);
        asegurarPrincipal();
    }

    /** La tienda 1: su base guarda también los usuarios y la lista de tiendas. */
    public InventoryService principal() { return principal; }

    /** El inventario de la tienda de este pedido (la principal si no hay otra elegida). */
    public InventoryService actual() { return de(ClientSession.tienda()); }

    public InventoryService de(int id) {
        if (id == ClientSession.TIENDA_PRINCIPAL || !existe(id)) return principal;
        return abiertas.computeIfAbsent(id, k -> new InventoryService(archivoDe(archivo, k), tipo, igv));
    }

    public List<Tienda> listar() { return lista; }

    public boolean existe(int id) { return lista.stream().anyMatch(t -> t.id() == id); }

    public String nombre(int id) {
        return lista.stream().filter(t -> t.id() == id).map(Tienda::nombre).findFirst().orElse("Tienda " + id);
    }

    public synchronized InventoryService.Resultado crear(String nombreDicho) {
        String no = ClientSession.sinPermiso(Permiso.ADMINISTRAR);
        if (no != null) return InventoryService.Resultado.prohibido(no);
        String nombre = limpio(nombreDicho);
        String error = errorNombre(nombre, 0);
        if (error != null) return InventoryService.Resultado.error(error);
        if (lista.size() >= MAXIMO) return InventoryService.Resultado.error("Ya hay " + MAXIMO + " tiendas, el máximo.");
        int id;
        try {
            id = principal.baseDatos().transaccion(cx -> {
                try (PreparedStatement ps = cx.prepareStatement("INSERT INTO tienda (nombre, creada) VALUES (?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setString(1, nombre);
                    ps.setString(2, LocalDateTime.now().withNano(0).toString());
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) { return rs.next() ? rs.getInt(1) : -1; }
                }
            });
        } catch (SQLException e) {
            return InventoryService.Resultado.error("No pude crear la tienda: " + e.getMessage());
        }
        leer();
        de(id);
        log.info("Tienda nueva {}: {} (base {}).", id, nombre, archivoDe(archivo, id));
        return InventoryService.Resultado.ok("Tienda creada: " + nombre + ". Empieza vacía: agregue sus productos estando en esa tienda.");
    }

    public synchronized InventoryService.Resultado renombrar(int id, String nombreDicho) {
        String no = ClientSession.sinPermiso(Permiso.ADMINISTRAR);
        if (no != null) return InventoryService.Resultado.prohibido(no);
        if (!existe(id)) return InventoryService.Resultado.noEncontrado("No existe esa tienda.");
        String nombre = limpio(nombreDicho);
        String error = errorNombre(nombre, id);
        if (error != null) return InventoryService.Resultado.error(error);
        try {
            principal.baseDatos().transaccion(cx -> {
                try (PreparedStatement ps = cx.prepareStatement("UPDATE tienda SET nombre = ? WHERE id = ?")) {
                    ps.setString(1, nombre);
                    ps.setInt(2, id);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            return InventoryService.Resultado.error("No pude cambiar el nombre: " + e.getMessage());
        }
        leer();
        return InventoryService.Resultado.ok("Tienda renombrada: " + nombre + ".");
    }

    /** Vuelve a leer la lista (por ejemplo, después de restaurar una copia de la tienda principal). */
    public synchronized void leer() {
        lista = principal.baseDatos().leer(cx -> {
            List<Tienda> l = new ArrayList<>();
            try (Statement st = cx.createStatement(); ResultSet rs = st.executeQuery("SELECT id, nombre, creada FROM tienda ORDER BY id")) {
                while (rs.next()) l.add(new Tienda(rs.getInt(1), rs.getString(2), rs.getString(3)));
            }
            return List.copyOf(l);
        });
    }

    private void asegurarPrincipal() {
        try {
            principal.baseDatos().transaccion(cx -> {
                try (PreparedStatement ps = cx.prepareStatement("INSERT OR IGNORE INTO tienda (id, nombre, creada) VALUES (1, ?, ?)")) {
                    ps.setString(1, "Tienda principal");
                    ps.setString(2, LocalDateTime.now().withNano(0).toString());
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (SQLException e) {
            log.error("No pude registrar la tienda principal: {}", e.getMessage());
        }
        leer();
    }

    private String errorNombre(String nombre, int propio) {
        if (nombre.isEmpty()) return "Escriba el nombre de la tienda.";
        if (nombre.length() > 60) return "El nombre de la tienda es muy largo (máximo 60 letras).";
        boolean repetido = lista.stream().anyMatch(t -> t.id() != propio && Palabras.norm(t.nombre()).equals(Palabras.norm(nombre)));
        return repetido ? "Ya hay una tienda llamada " + nombre + "." : null;
    }

    private static String limpio(String s) { return s == null ? "" : s.trim().replaceAll("\\s+", " "); }

    /** jarvis-inventario.json → jarvis-inventario-tienda-2.db, en la misma carpeta. */
    static String archivoDe(String base, int id) {
        Path p = Paths.get(base);
        String nombre = p.getFileName().toString().replaceFirst("\\.(json|db)$", "");
        return p.resolveSibling(nombre + "-tienda-" + id + ".db").toString();
    }
}
