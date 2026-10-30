/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Num {

    private Num() {}

    public static BigDecimal dinero(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal cantidad(BigDecimal v) {
        if (v == null) return null;
        BigDecimal n = v.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        return n.scale() < 0 ? n.setScale(0) : n;
    }

    public static BigDecimal cantidad(long v) { return BigDecimal.valueOf(v); }

    public static BigDecimal monto(BigDecimal precio, BigDecimal cantidad) {
        return dinero(precio.multiply(cantidad));
    }

    public static String txt(BigDecimal v) {
        return v == null ? "" : cantidad(v).toPlainString();
    }

    public static boolean tieneDecimales(BigDecimal v) {
        return v != null && cantidad(v).scale() > 0;
    }

    static final int CENT = 2, MIL = 3, DM = 4;

    static Long aEntero(BigDecimal v, int escala) {
        return v == null ? null : v.setScale(escala, RoundingMode.HALF_UP).unscaledValue().longValueExact();
    }

    static BigDecimal deEntero(long n, int escala) {
        return BigDecimal.valueOf(n, escala);
    }

    public static BigDecimal parse(String s) {
        if (s == null) return null;
        try {
            return new BigDecimal(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
