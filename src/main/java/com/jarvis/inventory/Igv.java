/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

public final class Igv {

    public static final BigDecimal TASA = new BigDecimal("0.18");

    private Igv() {}

    public enum Modo {
        INCLUIDO("Los precios incluyen IGV."),
        AGREGADO("Los precios no incluyen IGV."),
        EXONERADO("Operación inafecta / exonerada de IGV.");

        public final String leyenda;

        Modo(String leyenda) { this.leyenda = leyenda; }

        public static Modo de(String texto) {
            String t = texto == null ? "" : Palabras.norm(texto);
            if (t.startsWith("agreg") || t.startsWith("mas") || t.startsWith("sum")) return AGREGADO;
            if (t.startsWith("exoner") || t.startsWith("inafect") || t.startsWith("sin") || t.equals("no")) return EXONERADO;
            return INCLUIDO;
        }
    }

    public record Totales(BigDecimal base, BigDecimal igv, BigDecimal total, BigDecimal tasa) {}

    public static Totales calcular(Collection<BigDecimal> importes, Modo modo) {
        BigDecimal suma = Num.dinero(importes.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        return switch (modo) {
            case EXONERADO -> new Totales(suma, Num.dinero(BigDecimal.ZERO), suma, BigDecimal.ZERO);
            case INCLUIDO -> {
                BigDecimal base = suma.divide(BigDecimal.ONE.add(TASA), 2, RoundingMode.HALF_UP);
                yield new Totales(base, suma.subtract(base), suma, TASA);
            }
            case AGREGADO -> {
                BigDecimal igv = Num.dinero(suma.multiply(TASA));
                yield new Totales(suma, igv, suma.add(igv), TASA);
            }
        };
    }
}
