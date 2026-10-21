package com.jarvis.inventory;

import java.util.ArrayList;
import java.util.List;

/** Un producto del inventario. */
public class Producto {
    public String nombre;            // nombre exacto, ej. "Cemento Yura 42.5kg"
    public List<String> alias = new ArrayList<>();  // formas habladas, ej. "cemento", "yura"
    public int stock;                // unidades disponibles
    public int stockMin;             // umbral de alerta de stock bajo
    public double precio;            // precio de venta por unidad (soles)
    public String unidad = "unidades"; // bolsas, metros, unidades, cajas…

    public Producto() {}

    public Producto(String nombre, List<String> alias, int stock, int stockMin, double precio, String unidad) {
        this.nombre = nombre;
        this.alias = alias;
        this.stock = stock;
        this.stockMin = stockMin;
        this.precio = precio;
        this.unidad = unidad;
    }
}
