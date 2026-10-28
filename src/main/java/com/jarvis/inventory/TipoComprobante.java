/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

public enum TipoComprobante {
    BOLETA("B001", "Boleta de venta electrónica", "boleta"),
    NOTA_VENTA("NV01", "Nota de venta", "nota de venta");

    public final String serie;
    public final String descripcion;
    public final String hablado;

    TipoComprobante(String serie, String descripcion, String hablado) {
        this.serie = serie;
        this.descripcion = descripcion;
        this.hablado = hablado;
    }

    public String codigo(int correlativo) {
        return serie + "-" + String.format("%08d", correlativo);
    }

    public static TipoComprobante de(String texto) {
        String t = texto == null ? "" : Palabras.norm(texto).replace('_', ' ');
        if (t.startsWith("bol")) return BOLETA;
        if (t.startsWith("nota")) return NOTA_VENTA;
        return null;
    }
}
