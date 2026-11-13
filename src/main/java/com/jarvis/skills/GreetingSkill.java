/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.skills;

import com.jarvis.core.DatosNegocio;
import com.jarvis.inventory.DYSAInventoryService;
import com.jarvis.inventory.Moneda;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Optional;

@Component
@Order(0)
public class GreetingSkill implements Skill {

    private final String owner;
    private final DatosNegocio datos;
    private final DYSAInventoryService inv;

    public GreetingSkill(@Value("${jarvis.owner:señor}") String owner, DatosNegocio datos, DYSAInventoryService inv) {
        this.owner = owner;
        this.datos = datos;
        this.inv = inv;
    }

    @Override public String name() { return "Saludo"; }
    @Override public String example() { return "Hola JARVIS"; }

    @Override
    public Optional<String> handle(String cmd, String original) {
        if (cmd.matches(".*\\b(hola|buenos dias|buenas tardes|buenas noches|hey jarvis)\\b.*")
                && cmd.split("\\s+").length <= 4) {
            String bienvenida = datos.nombre().isEmpty() ? "" : " Bienvenido a " + datos.nombre() + ".";
            return Optional.of(parteDelDia() + ", " + owner + "." + bienvenida + " Todo bien. ¿En qué le ayudo?");
        }
        return Optional.empty();
    }

    public String bienvenida() {
        StringBuilder s = new StringBuilder(parteDelDia()).append(", ").append(owner).append(". ");
        s.append(datos.nombre().isEmpty() ? "JARVIS en línea y a su servicio." : "Bienvenido a " + datos.nombre() + ". JARVIS en línea y a su servicio.");
        DYSAInventoryService.Resumen r = inv.resumen();
        if (r.productos() == 0) {
            return s.append(" Aún no tiene productos registrados: empiece en Inventario o dígame, por ejemplo,")
                    .append(" \"agrega 10 poleras talla M a 25 soles\".").toString();
        }
        if (r.ventasHoy() == 0) s.append(" Hoy todavía no hay ventas.");
        else s.append(String.format(" Hoy lleva %s por %s.", r.ventasHoy() == 1 ? "1 venta" : r.ventasHoy() + " ventas", Moneda.hablado(r.totalHoy())));
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
