/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.skills;

import com.jarvis.inventory.InventoryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Optional;

@Component
@Order(0)
public class GreetingSkill implements Skill {

    private final String owner;
    private final String negocio;
    private final InventoryService inv;

    public GreetingSkill(@Value("${jarvis.owner:señor}") String owner,
                         @Value("${jarvis.negocio:}") String negocio,
                         InventoryService inv) {
        this.owner = owner;
        this.negocio = negocio == null ? "" : negocio.trim();
        this.inv = inv;
    }

    @Override public String name() { return "Saludo"; }
    @Override public String example() { return "Hola JARVIS"; }

    @Override
    public Optional<String> handle(String cmd, String original) {
        if (cmd.matches(".*\\b(hola|buenos dias|buenas tardes|buenas noches|hey jarvis)\\b.*")
                && cmd.split("\\s+").length <= 4) {
            String bienvenida = negocio.isEmpty() ? "" : " Bienvenido a " + negocio + ".";
            return Optional.of(parteDelDia() + ", " + owner + "." + bienvenida + " Todo bien. ¿En qué le ayudo?");
        }
        return Optional.empty();
    }

    public String bienvenida() {
        StringBuilder s = new StringBuilder(parteDelDia()).append(", ").append(owner).append(". ");
        s.append(negocio.isEmpty() ? "JARVIS en línea y a su servicio." : "Bienvenido a " + negocio + ". JARVIS en línea y a su servicio.");
        InventoryService.Resumen r = inv.resumen();
        if (r.productos() == 0) {
            return s.append(" Aún no tiene productos registrados: empiece en Inventario o dígame, por ejemplo,")
                    .append(" \"agrega 10 poleras talla M a 25 soles\".").toString();
        }
        if (r.ventasHoy() == 0) s.append(" Hoy todavía no hay ventas.");
        else s.append(String.format(" Hoy lleva %s por %.2f soles.", r.ventasHoy() == 1 ? "1 venta" : r.ventasHoy() + " ventas", r.totalHoy()));
        if (r.sinStock() > 0 || r.stockBajo() > 0) {
            s.append(" Atención:");
            if (r.sinStock() > 0) s.append(" ").append(r.sinStock()).append(r.sinStock() == 1 ? " producto sin stock" : " productos sin stock");
            if (r.sinStock() > 0 && r.stockBajo() > 0) s.append(" y");
            if (r.stockBajo() > 0) s.append(" ").append(r.stockBajo()).append(r.sinStock() > 0 ? " con stock bajo"
                    : r.stockBajo() == 1 ? " producto con stock bajo" : " productos con stock bajo");
            s.append(".");
        } else {
            s.append(" El stock está en orden.");
        }
        return s.append(" ¿En qué le ayudo?").toString();
    }

    private static String parteDelDia() {
        int h = LocalTime.now().getHour();
        return h < 5 ? "Buenas noches" : h < 12 ? "Buenos días" : h < 19 ? "Buenas tardes" : "Buenas noches";
    }
}
