package com.jarvis.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Números del inventario. Todo es BigDecimal: con double, 0.1 + 0.2 = 0.30000000000000004 y el
 * cierre de caja de un día con cientos de ventas podía descuadrar por céntimos.
 * <ul>
 *   <li>Dinero: siempre 2 decimales, redondeo HALF_UP (como en caja).</li>
 *   <li>Cantidades y stock: hasta 3 decimales (gramos, milímetros) y sin ceros sobrantes,
 *       así "50" se guarda y se muestra como 50 y no como 50.000.</li>
 * </ul>
 */
public final class Num {

    private Num() {}

    public static BigDecimal dinero(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal cantidad(BigDecimal v) {
        if (v == null) return null;
        BigDecimal n = v.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        // stripTrailingZeros convierte 50 en 5E+1: se vuelve a escala 0 para escribir "50"
        return n.scale() < 0 ? n.setScale(0) : n;
    }

    public static BigDecimal cantidad(long v) { return BigDecimal.valueOf(v); }

    /** precio × cantidad, redondeado a céntimos. */
    public static BigDecimal monto(BigDecimal precio, BigDecimal cantidad) {
        return dinero(precio.multiply(cantidad));
    }

    /** Cantidad para leer o decir en voz alta: "45", "2.5". */
    public static String txt(BigDecimal v) {
        return v == null ? "" : cantidad(v).toPlainString();
    }

    public static boolean tieneDecimales(BigDecimal v) {
        return v != null && cantidad(v).scale() > 0;
    }

    /** Escalas con que se guardan en la base de datos: céntimos, milésimas y diezmilésimas. */
    static final int CENT = 2, MIL = 3, DM = 4;

    /** 25.50 con escala 2 → 2550 (exacto: así se guarda en la base de datos). null → null. */
    static Long aEntero(BigDecimal v, int escala) {
        return v == null ? null : v.setScale(escala, RoundingMode.HALF_UP).unscaledValue().longValueExact();
    }

    /** 2550 con escala 2 → 25.50. */
    static BigDecimal deEntero(long n, int escala) {
        return BigDecimal.valueOf(n, escala);
    }

    /** "2,5" o "2.5" → 2.5 ; null si no es un número. */
    public static BigDecimal parse(String s) {
        if (s == null) return null;
        try {
            return new BigDecimal(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
