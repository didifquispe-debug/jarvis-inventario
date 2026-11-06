/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.security;

import java.util.EnumSet;
import java.util.Set;

public enum Rol {
    ADMIN("Administrador", EnumSet.allOf(Permiso.class)),
    CAJERO("Cajero", EnumSet.of(Permiso.VENDER, Permiso.CLIENTES, Permiso.COMPROBANTES)),
    ALMACEN("Almacén", EnumSet.of(Permiso.INVENTARIO, Permiso.COMPRAS, Permiso.VER_COSTOS));

    public final String descripcion;
    public final Set<Permiso> permisos;

    Rol(String descripcion, Set<Permiso> permisos) {
        this.descripcion = descripcion;
        this.permisos = permisos;
    }

    public static Rol de(String texto) {
        if (texto == null) return null;
        String t = texto.trim().toLowerCase();
        if (t.startsWith("admin")) return ADMIN;
        if (t.startsWith("caj")) return CAJERO;
        if (t.startsWith("almac") || t.startsWith("bodeg")) return ALMACEN;
        return null;
    }

    public enum Permiso {
        VENDER("vender"),
        ANULAR_VENTAS("anular o corregir ventas"),
        CLIENTES("ver y editar clientes"),
        COMPROBANTES("ver comprobantes"),
        INVENTARIO("cambiar productos y stock"),
        COMPRAS("registrar compras y proveedores"),
        VER_COSTOS("ver costos"),
        REPORTES("ver ventas y ganancias"),
        ADMINISTRAR("administrar usuarios y la laptop");

        public final String accion;

        Permiso(String accion) { this.accion = accion; }
    }
}
