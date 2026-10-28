/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class Producto {
    public String nombre;
    public List<String> alias = new ArrayList<>();
    public BigDecimal stock = BigDecimal.ZERO;
    public BigDecimal stockMin = BigDecimal.ZERO;
    public BigDecimal precio = Num.dinero(BigDecimal.ZERO);
    public String unidad = "unidades";
    public String categoria = "";
    public String codigo = "";
    public String codigoBarras = "";
    public BigDecimal costo;
    Long id;

    private static final Set<String> FRACCIONABLES = Set.of(
            "metro", "metros", "m", "centimetro", "centimetros", "cm",
            "kilo", "kilos", "kg", "gramo", "gramos", "g",
            "litro", "litros", "l", "galon", "galones", "pie", "pies");

    public record Ficha(String categoria, String codigo, String codigoBarras, BigDecimal costo) {
        public static final Ficha VACIA = new Ficha(null, null, null, null);
    }

    public Producto() {}

    public Producto(String nombre, List<String> alias, BigDecimal stock, BigDecimal stockMin, BigDecimal precio, String unidad) {
        this.nombre = nombre;
        this.alias = alias;
        this.stock = Num.cantidad(stock);
        this.stockMin = Num.cantidad(stockMin);
        this.precio = Num.dinero(precio);
        this.unidad = unidad;
    }

    public boolean admiteDecimales() {
        return unidad != null && FRACCIONABLES.contains(Palabras.norm(unidad));
    }

    public boolean stockBajo() { return stock.compareTo(stockMin) <= 0; }

    void normalizar() {
        if (alias == null) alias = new ArrayList<>();
        stock = Num.cantidad(stock == null ? BigDecimal.ZERO : stock);
        stockMin = Num.cantidad(stockMin == null ? BigDecimal.ZERO : stockMin);
        precio = Num.dinero(precio == null ? BigDecimal.ZERO : precio);
        if (unidad == null || unidad.isBlank()) unidad = "unidades";
        if (categoria == null) categoria = "";
        if (codigo == null) codigo = "";
        if (codigoBarras == null) codigoBarras = "";
        costo = Num.dinero(costo);
    }

    public boolean tieneCodigo(String c) {
        return c != null && !c.isBlank() && (c.equalsIgnoreCase(codigo) || c.equalsIgnoreCase(codigoBarras));
    }
}
