package com.jarvis.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * IGV de un comprobante (Perú, 18%). Adaptado de la CalculadoraIgv del sistema de referencia.
 * <ul>
 *   <li>{@link Modo#INCLUIDO}: los precios ya incluyen el IGV. El total es la suma; la base se obtiene dividiendo
 *       entre 1.18 y el IGV es la diferencia (así base + IGV cuadra al céntimo).</li>
 *   <li>{@link Modo#AGREGADO}: los precios no incluyen el IGV: la base es la suma y el IGV se agrega encima.</li>
 *   <li>{@link Modo#EXONERADO}: inafecto o exonerado: base = total = suma, IGV cero.</li>
 * </ul>
 */
public final class Igv {

    public static final BigDecimal TASA = new BigDecimal("0.18");

    private Igv() {}

    public enum Modo {
        INCLUIDO("Los precios incluyen IGV."),
        AGREGADO("Los precios no incluyen IGV."),
        EXONERADO("Operación inafecta / exonerada de IGV.");

        public final String leyenda;

        Modo(String leyenda) { this.leyenda = leyenda; }

        /** "incluido", "agregado"/"mas", "exonerado"/"sin"/"inafecto"; INCLUIDO si no se reconoce. */
        public static Modo de(String texto) {
            String t = texto == null ? "" : Palabras.norm(texto);
            if (t.startsWith("agreg") || t.startsWith("mas") || t.startsWith("sum")) return AGREGADO;
            if (t.startsWith("exoner") || t.startsWith("inafect") || t.startsWith("sin") || t.equals("no")) return EXONERADO;
            return INCLUIDO;
        }
    }

    /** Base imponible, IGV y total, todos con 2 decimales; {@code tasa} es 0 si no se aplica IGV. */
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
