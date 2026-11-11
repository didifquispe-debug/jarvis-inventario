/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.core;

import com.jarvis.inventory.InventoryService;
import com.jarvis.security.ClientSession;
import com.jarvis.security.Rol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cómo se imprime el ticket: ancho del papel de la ticketera (58 u 80 mm), si se imprime solo al cobrar y si la
 * ventana de la laptop imprime directo en la impresora predeterminada, sin mostrar el diálogo de impresión.
 * Se guarda con la base de datos, así que viaja con las copias de seguridad.
 */
@Component
public class Impresion {

    private static final Logger log = LoggerFactory.getLogger(Impresion.class);

    public record Config(int ancho, boolean alCobrar, boolean directa) {}

    static final Config POR_DEFECTO = new Config(80, false, false);

    private final InventoryService inv;

    public Impresion(InventoryService inv) { this.inv = inv; }

    public Config config() {
        Map<String, String> g;
        try {
            g = inv.ajustes().leer();
        } catch (RuntimeException e) {
            log.warn("No pude leer la configuración del ticket: {}", e.getMessage());
            return POR_DEFECTO;
        }
        int ancho = "58".equals(g.get("ticket.ancho")) ? 58 : 80;
        return new Config(ancho, "1".equals(g.get("ticket.al-cobrar")), "1".equals(g.get("ticket.directa")));
    }

    public InventoryService.Resultado guardar(Config c) {
        String no = ClientSession.sinPermiso(Rol.Permiso.ADMINISTRAR);
        if (no != null) return InventoryService.Resultado.prohibido(no);
        if (c == null) return InventoryService.Resultado.error("Faltan los datos del ticket.");
        if (c.ancho() != 58 && c.ancho() != 80) return InventoryService.Resultado.error("El papel del ticket es de 58 u 80 mm.");
        boolean cambiaDirecta = c.directa() != config().directa();
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ticket.ancho", String.valueOf(c.ancho()));
        m.put("ticket.al-cobrar", c.alCobrar() ? "1" : "0");
        m.put("ticket.directa", c.directa() ? "1" : "0");
        try {
            inv.ajustes().guardar(m);
        } catch (SQLException e) {
            return InventoryService.Resultado.error("No pude guardar la configuración del ticket: " + e.getMessage());
        }
        return InventoryService.Resultado.ok("Ticket de " + c.ancho() + " mm guardado."
                + (cambiaDirecta ? " La impresión directa se aplica al cerrar y volver a abrir JARVIS en la laptop." : ""));
    }
}
