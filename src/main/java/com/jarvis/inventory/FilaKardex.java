/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import java.math.BigDecimal;

public record FilaKardex(String fecha, String tipo, String producto, String detalle, BigDecimal entrada, BigDecimal salida,
                         BigDecimal saldo, BigDecimal costo, String usuario) {}
