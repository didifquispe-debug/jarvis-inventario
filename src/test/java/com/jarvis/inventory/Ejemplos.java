/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import java.math.BigDecimal;
import java.util.List;

public final class Ejemplos {

    private Ejemplos() {}

    public static InventoryService sembrar(InventoryService inv) {
        crear(inv, "Cemento Yura 42.5kg", "28", "50", "bolsas", "10", "cemento", "yura", "bolsa de cemento");
        crear(inv, "Cable Vulcanizado", "3.5", "50", "metros", "15", "cable", "cable vulcanizado", "vulcanizado");
        crear(inv, "Disco de corte 4 1/2", "4", "8", "unidades", "5", "disco de corte", "discos de corte", "disco", "discos");
        crear(inv, "Clavo con cabeza 2 pulgadas", "0.1", "200", "unidades", "50", "clavo", "clavos", "clavos de dos", "clavo de dos");
        crear(inv, "Foco LED 9W", "7.5", "30", "unidades", "8", "foco", "foco led", "led", "bombilla");
        return inv;
    }

    private static void crear(InventoryService inv, String nombre, String precio, String stock, String unidad, String min, String... alias) {
        if (inv.productos().stream().anyMatch(p -> p.nombre.equals(nombre))) return;
        InventoryService.Resultado r = inv.crearProducto(nombre, new BigDecimal(precio), new BigDecimal(stock), unidad,
                new BigDecimal(min), List.of(alias));
        if (!r.ok()) throw new IllegalStateException(r.mensaje());
    }
}
