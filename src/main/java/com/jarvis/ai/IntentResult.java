/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.ai;

import com.jarvis.inventory.Palabras;

import java.math.BigDecimal;

public record IntentResult(Intent intent, double confianza, BigDecimal cantidad, String producto, BigDecimal precio, boolean enPares) {

    public IntentResult(Intent intent, double confianza, BigDecimal cantidad, String producto, BigDecimal precio) {
        this(intent, confianza, cantidad, producto, precio, false);
    }

    public static IntentResult desconocido() { return new IntentResult(Intent.DESCONOCIDO, 0, null, "", null); }

    public boolean tieneCantidad() { return cantidad != null && cantidad.signum() > 0; }

    public boolean tieneProducto() { return producto != null && !producto.isBlank(); }

    public BigDecimal cantidadEn(String unidad) {
        return new Palabras.Cantidad(cantidad, -1, -1, enPares).valorEn(unidad);
    }
}
