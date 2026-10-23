package com.jarvis.inventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Un producto del inventario. */
public class Producto {
    public String nombre;            // nombre exacto, ej. "Zapatilla Nike Air 40" o "Polera roja M"
    public List<String> alias = new ArrayList<>();  // formas habladas, ej. "nike air 40"
    public BigDecimal stock = BigDecimal.ZERO;      // cantidad disponible (decimal en metros, kilos…)
    public BigDecimal stockMin = BigDecimal.ZERO;   // umbral de alerta de stock bajo
    public BigDecimal precio = Num.dinero(BigDecimal.ZERO);  // precio de venta por unidad (soles, 2 decimales)
    public String unidad = "unidades"; // unidades, pares, metros, kilos, cajas…
    public String categoria = "";      // ej. "Calzado", "Abarrotes" ("" = sin categoría)
    public String codigo = "";         // código interno o SKU, ej. "ZAP-NK-40" (único; "" = sin código)
    public String codigoBarras = "";   // EAN/UPC que lee el escáner, ej. "7750182000123" (único; "" = sin código)
    public BigDecimal costo;           // lo que le cuesta al negocio cada unidad (null = no registrado)

    /** Unidades que se pueden fraccionar: aceptan cantidades como 2.5. El resto se vende entero. */
    private static final Set<String> FRACCIONABLES = Set.of(
            "metro", "metros", "m", "centimetro", "centimetros", "cm",
            "kilo", "kilos", "kg", "gramo", "gramos", "g",
            "litro", "litros", "l", "galon", "galones", "pie", "pies");

    /**
     * Datos opcionales de un producto al crearlo o editarlo. Textos: null = no se toca, "" = se borra.
     * Costo: null = no se toca (para borrarlo, ver {@code Cambios.quitarCosto}).
     */
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

    /** ¿Su unidad admite cantidades con decimales? (no es un getter para que no salga en el JSON) */
    public boolean admiteDecimales() {
        return unidad != null && FRACCIONABLES.contains(Palabras.norm(unidad));
    }

    public boolean stockBajo() { return stock.compareTo(stockMin) <= 0; }

    /** Deja los números en su forma canónica (al cargar archivos viejos que traían int/double). */
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

    /** ¿Este código (interno o de barras) es de este producto? Sin importar mayúsculas. */
    public boolean tieneCodigo(String c) {
        return c != null && !c.isBlank() && (c.equalsIgnoreCase(codigo) || c.equalsIgnoreCase(codigoBarras));
    }
}
