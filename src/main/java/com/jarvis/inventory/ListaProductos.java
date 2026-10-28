/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class ListaProductos {

    private ListaProductos() {}

    public static final int MAX_FILAS = 5000;

    public record Fila(int linea, String nombre, BigDecimal precio, BigDecimal stock, String unidad, BigDecimal stockMin,
                       Producto.Ficha ficha) {}

    public record ErrorLinea(int linea, String motivo) {}

    public record Lectura(List<Fila> filas, List<ErrorLinea> errores) {
        public int total() { return filas.size() + errores.size(); }
    }

    public static Lectura leer(String texto) {
        List<Fila> filas = new ArrayList<>();
        List<ErrorLinea> errores = new ArrayList<>();
        if (texto == null) return new Lectura(filas, errores);
        String[] lineas = texto.replace("﻿", "").split("\r\n|\r|\n", -1);
        boolean primera = true;
        for (int i = 0; i < lineas.length; i++) {
            String l = lineas[i].strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            int n = i + 1;
            List<String> c = campos(l);
            boolean encabezado = primera && c.size() > 1 && Palabras.norm(c.get(1)).equals("precio");
            primera = false;
            if (encabezado) continue;
            String error = errorDe(c);
            if (error != null) { errores.add(new ErrorLinea(n, error)); continue; }
            filas.add(new Fila(n, c.get(0), numero(c.get(1)),
                    campo(c, 2).isEmpty() ? BigDecimal.ZERO : numero(c.get(2)),
                    campo(c, 3).isEmpty() ? "unidades" : c.get(3),
                    campo(c, 4).isEmpty() ? null : numero(c.get(4)),
                    new Producto.Ficha(campo(c, 6), campo(c, 7), campo(c, 8), campo(c, 5).isEmpty() ? null : numero(c.get(5)))));
        }
        return new Lectura(filas, errores);
    }

    private static String errorDe(List<String> c) {
        if (c.size() > 9) return "sobran campos (son 9 como máximo: nombre, precio, stock, unidad, stock mínimo, "
                + "costo, categoría, código, código de barras)";
        if (c.get(0).isEmpty()) return "nombre vacío";
        if (campo(c, 1).isEmpty()) return "precio vacío";
        if (numero(c.get(1)) == null) return "el precio no es un número: \"" + c.get(1) + "\"";
        if (!campo(c, 2).isEmpty() && numero(c.get(2)) == null) return "el stock no es un número: \"" + c.get(2) + "\"";
        if (!campo(c, 4).isEmpty() && numero(c.get(4)) == null) return "el stock mínimo no es un número: \"" + c.get(4) + "\"";
        if (!campo(c, 5).isEmpty() && numero(c.get(5)) == null) return "el costo no es un número: \"" + c.get(5) + "\"";
        return null;
    }

    private static String campo(List<String> c, int i) { return i < c.size() ? c.get(i) : ""; }

    private static BigDecimal numero(String s) {
        return Num.parse(s.replaceFirst("(?i)^s/\\s*", "").replace(" ", ""));
    }

    static List<String> campos(String linea) {
        char sep = linea.indexOf('\t') >= 0 ? '\t' : linea.indexOf(';') >= 0 ? ';' : ',';
        List<String> out = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        boolean comillas = false;
        for (int i = 0; i < linea.length(); i++) {
            char ch = linea.charAt(i);
            if (ch == '"') {
                if (comillas && i + 1 < linea.length() && linea.charAt(i + 1) == '"') { actual.append('"'); i++; }
                else comillas = !comillas;
            } else if (ch == sep && !comillas) {
                out.add(actual.toString().strip());
                actual.setLength(0);
            } else {
                actual.append(ch);
            }
        }
        out.add(actual.toString().strip());
        while (out.size() > 1 && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
        return out;
    }
}
