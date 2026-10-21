package com.jarvis.ai;

/**
 * Resultado del pipeline de interpretación: intención + confianza + entidades extraídas.
 *
 * @param intent    intención reconocida (DESCONOCIDO si no hay certeza)
 * @param confianza similitud coseno del mejor ejemplo (0..1)
 * @param cantidad  cantidad detectada, o -1 si no hay
 * @param producto  texto del producto (sin verbos, unidades ni números), puede estar vacío
 * @param precio    precio "a N soles", o null
 */
public record IntentResult(Intent intent, double confianza, int cantidad, String producto, Double precio) {

    public static IntentResult desconocido() { return new IntentResult(Intent.DESCONOCIDO, 0, -1, "", null); }

    public boolean tieneCantidad() { return cantidad > 0; }

    public boolean tieneProducto() { return producto != null && !producto.isBlank(); }
}
