/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import java.math.BigDecimal;

public record Movimiento(String fecha, String tipo, String producto, BigDecimal cantidad, String detalle, String usuario) {}
