/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.core;

import com.jarvis.inventory.ComprobantePdf;
import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.Moneda;
import com.jarvis.inventory.Tiendas;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Nombre, RUC, dirección y moneda del negocio. Se editan en "Mi negocio" y se guardan en la base de datos;
 * lo que no se haya editado sale de application.properties (jarvis.negocio.*, jarvis.moneda.*).
 */
@Component
public class DatosNegocio {

    private static final Logger log = LoggerFactory.getLogger(DatosNegocio.class);

    public record Datos(String nombre, String ruc, String direccion, String telefono, String email,
                        String monedaSimbolo, String monedaNombre, String monedaSingular) {}

    private final InventoryService inv;
    private Tiendas tiendas;
    private final Datos porDefecto;
    private volatile Datos actuales;

    public DatosNegocio(InventoryService inv,
                        @Value("${jarvis.negocio:}") String negocio,
                        @Value("${jarvis.negocio.nombre:}") String nombre,
                        @Value("${jarvis.negocio.ruc:}") String ruc,
                        @Value("${jarvis.negocio.direccion:}") String direccion,
                        @Value("${jarvis.negocio.telefono:}") String telefono,
                        @Value("${jarvis.negocio.email:}") String email,
                        @Value("${jarvis.moneda.simbolo:S/}") String simbolo,
                        @Value("${jarvis.moneda.nombre:soles}") String moneda,
                        @Value("${jarvis.moneda.singular:sol}") String singular) {
        this.inv = inv;
        this.porDefecto = new Datos((negocio.isBlank() ? nombre : negocio).trim(), ruc.trim(), direccion.trim(), telefono.trim(),
                email.trim(), simbolo.trim(), moneda.trim().toLowerCase(), singular.trim().toLowerCase());
        recargar();
    }

    /** Vuelve a leer los datos guardados (por ejemplo, tras restaurar una copia de seguridad). */
    public synchronized void recargar() {
        Map<String, String> g;
        try {
            g = principal().ajustes().leer();
        } catch (RuntimeException e) {
            log.warn("No pude leer los datos del negocio guardados: {}. Uso los de application.properties.", e.getMessage());
            g = Map.of();
        }
        Datos d = new Datos(g.getOrDefault("negocio.nombre", porDefecto.nombre()), g.getOrDefault("negocio.ruc", porDefecto.ruc()),
                g.getOrDefault("negocio.direccion", porDefecto.direccion()), g.getOrDefault("negocio.telefono", porDefecto.telefono()),
                g.getOrDefault("negocio.email", porDefecto.email()), g.getOrDefault("moneda.simbolo", porDefecto.monedaSimbolo()),
                g.getOrDefault("moneda.nombre", porDefecto.monedaNombre()), g.getOrDefault("moneda.singular", porDefecto.monedaSingular()));
        String error = error(d);
        if (error != null) {
            log.warn("Los datos del negocio no son válidos ({}): uso soles y los datos de application.properties.", error);
            d = error(porDefecto) == null ? porDefecto
                    : new Datos(porDefecto.nombre(), "", porDefecto.direccion(), "", "", "S/", "soles", "sol");
        }
        usar(d);
    }

    /** Por ahora los datos del negocio son los mismos para todas las tiendas: viven en la base principal. */
    private InventoryService principal() { return tiendas == null ? inv : tiendas.principal(); }

    @Autowired(required = false)
    void usarTiendas(Tiendas tiendas) { this.tiendas = tiendas; }

    public Datos datos() { return actuales; }

    /** Nombre para mostrar; vacío si no se configuró. */
    public String nombre() { return actuales.nombre(); }

    public ComprobantePdf.Emisor emisor() {
        Datos d = actuales;
        return new ComprobantePdf.Emisor(d.nombre(), d.ruc(), d.direccion(), d.telefono(), d.email());
    }

    /** Lo que viaja con cada comprobante para imprimir el ticket. */
    public Map<String, String> paraRecibo() {
        Datos d = actuales;
        return Map.of("nombre", d.nombre().isEmpty() ? "Mi Negocio" : d.nombre(), "ruc", d.ruc(), "direccion", d.direccion(),
                "monedaSimbolo", d.monedaSimbolo());
    }

    public synchronized InventoryService.Resultado guardar(Datos nuevos) {
        if (nuevos == null) return InventoryService.Resultado.error("Faltan los datos del negocio.");
        Datos d = new Datos(limpio(nuevos.nombre()), limpio(nuevos.ruc()), limpio(nuevos.direccion()), limpio(nuevos.telefono()),
                limpio(nuevos.email()), limpio(nuevos.monedaSimbolo()), limpio(nuevos.monedaNombre()).toLowerCase(),
                limpio(nuevos.monedaSingular()).toLowerCase());
        String error = error(d);
        if (error != null) return InventoryService.Resultado.error(error);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("negocio.nombre", d.nombre());
        m.put("negocio.ruc", d.ruc());
        m.put("negocio.direccion", d.direccion());
        m.put("negocio.telefono", d.telefono());
        m.put("negocio.email", d.email());
        m.put("moneda.simbolo", d.monedaSimbolo());
        m.put("moneda.nombre", d.monedaNombre());
        m.put("moneda.singular", d.monedaSingular());
        try {
            principal().ajustes().guardar(m);
        } catch (SQLException e) {
            return InventoryService.Resultado.error("No pude guardar los datos del negocio: " + e.getMessage());
        }
        usar(d);
        return InventoryService.Resultado.ok("Datos del negocio guardados" + (d.nombre().isEmpty() ? "." : ": " + d.nombre() + "."));
    }

    private void usar(Datos d) {
        actuales = d;
        Moneda.usar(d.monedaSimbolo(), d.monedaNombre(), d.monedaSingular());
    }

    static String error(Datos d) {
        if (d.nombre().length() > 80) return "El nombre del negocio es muy largo (máximo 80 letras).";
        if (!d.ruc().isEmpty() && !d.ruc().matches("(10|15|17|20)\\d{9}"))
            return "El RUC tiene 11 dígitos y empieza con 10, 15, 17 o 20 (o déjelo vacío).";
        if (d.direccion().length() > 150) return "La dirección es muy larga (máximo 150 letras).";
        if (!d.telefono().isEmpty() && !d.telefono().matches("[+\\d][\\d -]{5,19}")) return "El teléfono solo lleva números (6 a 20).";
        if (!d.email().isEmpty() && !d.email().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) return "El correo no parece válido.";
        if (!d.monedaSimbolo().matches("[^\\s%<>\"']{1,4}")) return "El símbolo de la moneda va de 1 a 4 caracteres (por ejemplo S/, $, US$).";
        if (!d.monedaNombre().matches("\\p{L}+( \\p{L}+)?") || d.monedaNombre().length() > 20)
            return "Escriba el nombre de la moneda en plural, por ejemplo soles o dólares.";
        if (!d.monedaSingular().matches("\\p{L}+( \\p{L}+)?") || d.monedaSingular().length() > 20)
            return "Escriba el nombre de la moneda en singular, por ejemplo sol o dólar.";
        return null;
    }

    private static String limpio(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }
}
