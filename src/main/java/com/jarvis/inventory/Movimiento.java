package com.jarvis.inventory;

import java.math.BigDecimal;

/**
 * Una línea del historial de auditoría.
 *
 * @param fecha    fecha y hora local, ISO (2026-10-21T09:30:05)
 * @param tipo     ENTRADA, VENTA, ANULACION, CORRECCION_VENTA, PRECIO, STOCK_MINIMO, UNIDAD, NUEVO_PRODUCTO, ELIMINAR
 * @param producto nombre del producto
 * @param cantidad cantidad movida (null si el movimiento no mueve stock, p. ej. un cambio de precio)
 * @param detalle  texto libre: comprobante, valor anterior y nuevo…
 * @param usuario  quién lo hizo: "sesión@ip" del dispositivo, o "local"
 */
public record Movimiento(String fecha, String tipo, String producto, BigDecimal cantidad, String detalle, String usuario) {}
