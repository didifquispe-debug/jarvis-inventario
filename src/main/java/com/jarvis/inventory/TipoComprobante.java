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

    /**
     * Serie de este tipo en la tienda {@code tienda}. Con el mismo RUC dos locales no pueden repetir números, así que
     * cada tienda tiene la suya: la 1 usa B001/NV01, la 2 B002/NV02, y así.
     */
    public String serie(int tienda) {
        if (tienda <= 1) return serie;
        return this == BOLETA ? "B" + String.format("%03d", tienda) : "NV" + String.format("%02d", tienda);
    }

    public static String codigo(String serie, int correlativo) {
        return serie + "-" + String.format("%08d", correlativo);
    }

    public static TipoComprobante de(String texto) {
        String t = texto == null ? "" : Palabras.norm(texto).replace('_', ' ');
        if (t.startsWith("bol")) return BOLETA;
        if (t.startsWith("nota")) return NOTA_VENTA;
        return null;
    }
}
