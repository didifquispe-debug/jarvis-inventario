/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import java.math.BigDecimal;

/** Moneda del negocio para textos, comprobantes y reportes. Por defecto, soles. */
public final class Moneda {

    private static volatile String simbolo = "S/", nombre = "soles", singular = "sol";

    private Moneda() {}

    public static String simbolo() { return simbolo; }

    /** En plural y minúsculas: "soles", "dólares". */
    public static String nombre() { return nombre; }

    public static String singular() { return singular; }

    /** "12.50 soles", como lo dice JARVIS. */
    public static String hablado(BigDecimal v) {
        return String.format("%.2f %s", v == null ? BigDecimal.ZERO : v, nombre);
    }

    public static void usar(String simbolo, String nombre, String singular) {
        Moneda.simbolo = simbolo;
        Moneda.nombre = nombre;
        Moneda.singular = singular;
    }
}
