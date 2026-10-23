package com.jarvis.inventory;

/** Comprobantes que emite JARVIS, cada uno con su serie y su propio correlativo (como en el sistema de referencia). */
public enum TipoComprobante {
    BOLETA("B001", "Boleta de venta electrónica", "boleta"),
    NOTA_VENTA("NV01", "Nota de venta", "nota de venta");

    public final String serie;
    public final String descripcion;
    /** Cómo se dice en voz alta: "boleta número 12". */
    public final String hablado;

    TipoComprobante(String serie, String descripcion, String hablado) {
        this.serie = serie;
        this.descripcion = descripcion;
        this.hablado = hablado;
    }

    /** "B001-00000012" */
    public String codigo(int correlativo) {
        return serie + "-" + String.format("%08d", correlativo);
    }

    /** "boleta", "nota", "nota de venta", "BOLETA", "NOTA_VENTA"…; null si no se reconoce. */
    public static TipoComprobante de(String texto) {
        String t = texto == null ? "" : Palabras.norm(texto).replace('_', ' ');
        if (t.startsWith("bol")) return BOLETA;
        if (t.startsWith("nota")) return NOTA_VENTA;
        return null;
    }
}
