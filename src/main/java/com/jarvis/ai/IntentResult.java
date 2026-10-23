package com.jarvis.ai;

import com.jarvis.inventory.Palabras;

import java.math.BigDecimal;

/**
 * Resultado del pipeline de interpretación: intención + confianza + entidades extraídas.
 *
 * @param intent    intención reconocida (DESCONOCIDO si no hay certeza)
 * @param confianza similitud coseno del mejor ejemplo (0..1)
 * @param cantidad  cantidad detectada (puede tener decimales), o null si no hay
 * @param producto  texto del producto (sin verbos, unidades ni números), puede estar vacío
 * @param precio    precio "a N soles", o null
 * @param enPares   la cantidad salió de "par/pares" (ya multiplicada por 2)
 */
public record IntentResult(Intent intent, double confianza, BigDecimal cantidad, String producto, BigDecimal precio, boolean enPares) {

    public IntentResult(Intent intent, double confianza, BigDecimal cantidad, String producto, BigDecimal precio) {
        this(intent, confianza, cantidad, producto, precio, false);
    }

    public static IntentResult desconocido() { return new IntentResult(Intent.DESCONOCIDO, 0, null, "", null); }

    public boolean tieneCantidad() { return cantidad != null && cantidad.signum() > 0; }

    public boolean tieneProducto() { return producto != null && !producto.isBlank(); }

    /** La cantidad en la unidad del producto ("un par" = 1 si se cuenta en pares). */
    public BigDecimal cantidadEn(String unidad) {
        return new Palabras.Cantidad(cantidad, -1, -1, enPares).valorEn(unidad);
    }
}
