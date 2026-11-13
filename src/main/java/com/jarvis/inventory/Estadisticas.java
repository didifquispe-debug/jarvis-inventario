/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import com.jarvis.inventory.DYSAInventoryService.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

import static com.jarvis.inventory.DYSAInventoryService.DIAS_SIN_MOVIMIENTO;

/** Cálculos del panel y de los reportes (ventas, ganancia, más vendidos, valorizado). Solo lee: no guarda nada. */
final class Estadisticas {

    private Estadisticas() {}

    static Valorizado valorizado(List<Producto> productos) {
        List<FilaValorizada> filas = new ArrayList<>();
        int sinCosto = 0, bajo = 0, sin = 0;
        BigDecimal costoTotal = BigDecimal.ZERO, ventaTotal = BigDecimal.ZERO, ventaConCosto = BigDecimal.ZERO;
        List<Producto> orden = new ArrayList<>(productos);
        orden.sort(Comparator.comparing((Producto p) -> p.categoria.isEmpty() ? "\uffff" : Palabras.norm(p.categoria))
                .thenComparing(p -> Palabras.norm(p.nombre)));
        for (Producto p : orden) {
            BigDecimal stock = p.stock.max(BigDecimal.ZERO);
            String estado = p.stock.signum() <= 0 ? "SIN_STOCK" : p.stockBajo() ? "BAJO" : "OK";
            if (estado.equals("SIN_STOCK")) sin++; else if (estado.equals("BAJO")) bajo++;
            BigDecimal valorVenta = Num.dinero(p.precio.multiply(stock));
            BigDecimal valorCosto = p.costo == null ? null : Num.dinero(p.costo.multiply(stock));
            if (valorCosto == null) sinCosto++;
            else { costoTotal = costoTotal.add(valorCosto); ventaConCosto = ventaConCosto.add(valorVenta); }
            ventaTotal = ventaTotal.add(valorVenta);
            filas.add(new FilaValorizada(p.nombre, p.categoria, p.codigo, p.unidad, p.stock, p.stockMin, estado, p.costo,
                    valorCosto, p.precio, valorVenta));
        }
        return new Valorizado(filas, filas.size(), sinCosto, bajo, sin, Num.dinero(costoTotal), Num.dinero(ventaTotal),
                Num.dinero(ventaConCosto.subtract(costoTotal)));
    }

    static Panel panel(List<Comprobante> todos, List<Producto> productos, LocalDate hoy, int dias) {
        dias = Math.min(Math.max(dias, 7), 366);
        LocalDate inicioRango = hoy.minusDays(dias - 1L);
        Map<String, String> ultimaVenta = new HashMap<>();
        for (Comprobante c : todos) {
            if (c.anulado) continue;
            for (Venta v : c.items) ultimaVenta.merge(Palabras.norm(v.producto), c.fecha, (a, b) -> a.compareTo(b) >= 0 ? a : b);
        }

        Map<String, DiaPanel> porDia = new LinkedHashMap<>();
        for (LocalDate d = inicioRango; !d.isAfter(hoy); d = d.plusDays(1))
            porDia.put(d.toString(), new DiaPanel(d.toString(), 0, Num.dinero(BigDecimal.ZERO), Num.dinero(BigDecimal.ZERO)));
        List<Comprobante> delRango = new ArrayList<>();
        for (Comprobante c : todos) {
            if (c.anulado || !enPeriodo(c.fecha, inicioRango.toString(), hoy.toString())) continue;
            delRango.add(c);
            BigDecimal[] g = ganancia(c);
            porDia.computeIfPresent(c.fecha, (k, d) -> new DiaPanel(k, d.ventas() + 1, d.total().add(c.total), d.ganancia().add(g[1])));
        }
        List<MasVendido> top = masVendidos(delRango, 10);

        List<Alerta> alertas = new ArrayList<>();
        List<SinMovimiento> quietos = new ArrayList<>();
        String limiteQuieto = hoy.minusDays(DIAS_SIN_MOVIMIENTO).toString();
        for (Producto p : productos) {
            if (p.stock.signum() <= 0) alertas.add(new Alerta(p.nombre, p.unidad, p.stock, p.stockMin, "SIN_STOCK"));
            else if (p.stockBajo()) alertas.add(new Alerta(p.nombre, p.unidad, p.stock, p.stockMin, "BAJO"));
            if (p.stock.signum() > 0) {
                String ultima = ultimaVenta.get(Palabras.norm(p.nombre));
                if (ultima == null || ultima.compareTo(limiteQuieto) < 0)
                    quietos.add(new SinMovimiento(p.nombre, p.unidad, p.stock, Num.dinero(p.precio.multiply(p.stock)), ultima));
            }
        }
        alertas.sort(Comparator.comparing((Alerta a) -> a.estado().equals("SIN_STOCK") ? 0 : 1)
                .thenComparing(a -> a.stock().subtract(a.stockMin()))
                .thenComparing(a -> Palabras.norm(a.producto())));
        quietos.sort(Comparator.comparing(SinMovimiento::valorVenta).reversed().thenComparing(s -> Palabras.norm(s.producto())));

        LocalDate lunes = hoy.minusDays(hoy.getDayOfWeek().getValue() - 1L);
        return new Panel(hoy.toString(), periodo(todos, hoy, hoy), periodo(todos, lunes, hoy),
                periodo(todos, hoy.withDayOfMonth(1), hoy), periodo(todos, inicioRango, hoy), new ArrayList<>(porDia.values()),
                top, alertas, quietos.size() > 20 ? quietos.subList(0, 20) : quietos, DIAS_SIN_MOVIMIENTO);
    }

    static List<MasVendido> masVendidos(List<Comprobante> validos, int limite) {
        Map<String, BigDecimal[]> vendidos = new HashMap<>();
        Map<String, String[]> nombres = new HashMap<>();
        for (Comprobante c : validos) {
            BigDecimal factor = factorBase(c);
            for (Venta v : c.items) {
                String clave = Palabras.norm(v.producto);
                nombres.putIfAbsent(clave, new String[]{v.producto, v.unidad});
                BigDecimal[] acc = vendidos.computeIfAbsent(clave, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, null});
                acc[0] = acc[0].add(v.cantidad);
                acc[1] = acc[1].add(v.monto);
                if (v.costoUnitario != null)
                    acc[2] = (acc[2] == null ? BigDecimal.ZERO : acc[2]).add(v.monto.multiply(factor).subtract(v.costoUnitario.multiply(v.cantidad)));
            }
        }
        return vendidos.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, BigDecimal[]> e) -> e.getValue()[1]).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(Math.max(limite, 1))
                .map(e -> new MasVendido(nombres.get(e.getKey())[0], nombres.get(e.getKey())[1], Num.cantidad(e.getValue()[0]),
                        Num.dinero(e.getValue()[1]), Num.dinero(e.getValue()[2])))
                .toList();
    }

    static Periodo periodo(List<Comprobante> todos, LocalDate desde, LocalDate hasta) {
        String d = desde.toString(), h = hasta.toString();
        int n = 0, anuladas = 0, sinCosto = 0;
        BigDecimal total = BigDecimal.ZERO, base = BigDecimal.ZERO, costo = BigDecimal.ZERO, ganancia = BigDecimal.ZERO;
        for (Comprobante c : todos) {
            if (!enPeriodo(c.fecha, d, h)) continue;
            if (c.anulado) { anuladas++; continue; }
            n++;
            total = total.add(c.total);
            base = base.add(c.base);
            BigDecimal[] g = ganancia(c);
            costo = costo.add(g[0]);
            ganancia = ganancia.add(g[1]);
            sinCosto += c.items.stream().filter(v -> v.costoUnitario == null).count();
        }
        return new Periodo(d, h, n, anuladas, Num.dinero(total), Num.dinero(base), Num.dinero(costo), Num.dinero(ganancia), sinCosto);
    }

    /** Parte de cada importe que es venta sin IGV (el costo de compra también se guarda sin IGV). */
    private static BigDecimal factorBase(Comprobante c) {
        BigDecimal suma = c.items.stream().map(v -> v.monto).reduce(BigDecimal.ZERO, BigDecimal::add);
        return suma.signum() == 0 ? BigDecimal.ONE : c.base.divide(suma, 10, RoundingMode.HALF_UP);
    }

    /** [costo, ganancia] del comprobante, solo con las líneas que tienen costo registrado. */
    private static BigDecimal[] ganancia(Comprobante c) {
        BigDecimal factor = factorBase(c), costo = BigDecimal.ZERO, ganancia = BigDecimal.ZERO;
        for (Venta v : c.items) {
            if (v.costoUnitario == null) continue;
            BigDecimal cl = v.costoUnitario.multiply(v.cantidad);
            costo = costo.add(cl);
            ganancia = ganancia.add(v.monto.multiply(factor).subtract(cl));
        }
        return new BigDecimal[]{Num.dinero(costo), Num.dinero(ganancia)};
    }

    static boolean enPeriodo(String fecha, String desde, String hasta) {
        String f = fecha == null ? "" : fecha;
        return (desde == null || desde.isBlank() || f.compareTo(desde.trim()) >= 0)
                && (hasta == null || hasta.isBlank() || f.compareTo(hasta.trim()) <= 0);
    }
}
