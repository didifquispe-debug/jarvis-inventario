package com.jarvis.inventory;

import java.math.BigDecimal;

/**
 * Una línea del kardex. {@code entrada}/{@code salida}: lo que movió (una de las dos es 0); {@code saldo}: el stock
 * que quedó (null en movimientos de versiones anteriores, que no lo guardaban); {@code costo}: costo promedio de ese momento.
 */
public record FilaKardex(String fecha, String tipo, String producto, String detalle, BigDecimal entrada, BigDecimal salida,
                         BigDecimal saldo, BigDecimal costo, String usuario) {}
