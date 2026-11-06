/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.skills;

import com.jarvis.inventory.Compras;
import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.Moneda;
import com.jarvis.inventory.Num;
import com.jarvis.security.ClientSession;
import com.jarvis.security.Rol.Permiso;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Preguntas sobre el negocio: ventas y ganancia de un periodo, lo más vendido, lo que se acaba, la mercadería parada,
 * las compras a un proveedor y el valor del inventario. Va antes que InventorySkill, que tomaría
 * "cuánto vendí" como una venta y "qué le compré" como una entrada.
 */
@Component
@Order(-15)
public class ConsultasSkill implements Skill {

    private static final Pattern GANANCIA = Pattern.compile("\\b(gane|ganamos|ganado|ganancias?|utilidad(es)?)\\b");
    private static final Pattern VENTAS = Pattern.compile(
            "\\bcuanto (he |hemos |se |me )?(vendi|vendimos|vendido|vendio|vendieron|hemos vendido)\\b"
            + "|\\b(total de |las )?ventas (de|del) (hoy|ayer|la semana|esta semana|el mes|este mes|este ano|semana|mes)\\b"
            + "|\\bcomo (van|fueron|estuvieron|estan) las ventas\\b|\\bcomo (me|nos) (fue|va)\\b");
    private static final Pattern VENDIDO_DE = Pattern.compile("\\bvend\\w* (?:de |del |de la |de los |de las )(.+)$");
    private static final Pattern MAS_VENDIDO = Pattern.compile(
            "\\bmas vendid[oa]s?\\b|\\b(que|cual) (es lo que |producto )?(se vende|vende|sale|salen|se venden) mas\\b"
            + "|\\bque (es lo que )?mas (se vende|vendo|vendemos|sale)\\b");
    private static final Pattern ACABANDO = Pattern.compile(
            "\\bse (esta|estan) (acabando|terminando|agotando)\\b|\\bpor (acabarse|agotarse|terminarse)\\b"
            + "|\\bse (acabo|acabaron|agoto|agotaron)\\b|\\bagotad[oa]s\\b|\\bhay que reponer\\b|\\b(debo|tengo que|toca) reponer\\b"
            + "|\\bque (me )?falta\\b|\\b(productos|que hay) (con|en) stock bajo\\b|\\bque reponer\\b|\\bproductos sin stock\\b");
    private static final Pattern QUIETOS = Pattern.compile(
            "\\bsin movimiento\\b|\\bno se (vende|venden|mueve|mueven)\\b|\\bno (sale|salen)\\b|\\bmercaderia parada\\b"
            + "|\\bproductos? parados?\\b|\\bestancad[oa]s?\\b");
    private static final Pattern COMPRAS = Pattern.compile(
            "\\b(que|cuanto) (le |les )?(he |hemos )?(compre|compramos|comprado)\\b|\\bultimas compras\\b|\\bcompras (a|al|de|del) ");
    private static final Pattern PROVEEDOR = Pattern.compile("\\b(?:a|al|de|del) (?:proveedor |la empresa )?(.+)$");
    private static final Pattern VALOR = Pattern.compile(
            "\\b(cuanto vale|valor (de|del)|cuanto tengo en) (mi |el |todo el |la )?(inventario|stock|almacen|mercaderia)\\b");
    private static final Pattern PERIODO = Pattern.compile(
            "\\b(hoy|ayer|(?:la )?semana pasada|esta semana|la semana|semana|semanal|(?:el )?mes pasado|este mes|el mes|mes|mensual"
            + "|este ano|el ano)\\b");

    private final InventoryService inv;

    public ConsultasSkill(InventoryService inv) { this.inv = inv; }

    @Override public String name() { return "Consultas del negocio"; }
    @Override public String example() {
        return "¿Cuánto gané esta semana? / ¿Qué es lo más vendido del mes? / ¿Qué productos se están acabando? / ¿Qué le compré a Andina?";
    }

    record Periodo(LocalDate desde, LocalDate hasta, String nombre) {}

    /** Si {@code cmd} es una de estas preguntas (solo lee: no hace falta confirmarla por voz). */
    public boolean entiende(String cmd) {
        return (GANANCIA.matcher(cmd).find() && !cmd.matches(".*\\b(precio|cuesta|vale)\\b.*"))
                || java.util.stream.Stream.of(MAS_VENDIDO, VENTAS, ACABANDO, QUIETOS, COMPRAS, VALOR).anyMatch(p -> p.matcher(cmd).find());
    }

    @Override
    public Optional<String> handle(String cmd, String original) {
        if (inv.hayPendiente()) return Optional.empty();
        LocalDate hoy = LocalDate.now();
        if (GANANCIA.matcher(cmd).find() && !cmd.matches(".*\\b(precio|cuesta|vale)\\b.*")) return Optional.of(ganancia(periodo(cmd, hoy, "hoy")));
        if (MAS_VENDIDO.matcher(cmd).find()) return Optional.of(masVendido(periodo(cmd, hoy, "este mes")));
        if (VENTAS.matcher(cmd).find()) return Optional.of(ventas(cmd, periodo(cmd, hoy, "hoy")));
        if (ACABANDO.matcher(cmd).find()) return Optional.of(acabando());
        if (QUIETOS.matcher(cmd).find()) return Optional.of(quietos());
        if (COMPRAS.matcher(cmd).find()) return Optional.of(compras(cmd));
        if (VALOR.matcher(cmd).find()) return Optional.of(valor());
        return Optional.empty();
    }

    static Periodo periodo(String cmd, LocalDate hoy, String porDefecto) {
        Matcher m = PERIODO.matcher(cmd);
        String p = m.find() ? m.group(1).replaceFirst("^(la|el) (?=(semana|mes) pasad)", "") : porDefecto;
        LocalDate lunes = hoy.minusDays(hoy.getDayOfWeek().getValue() - 1L);
        return switch (p) {
            case "ayer" -> new Periodo(hoy.minusDays(1), hoy.minusDays(1), "ayer");
            case "semana pasada" -> new Periodo(lunes.minusWeeks(1), lunes.minusDays(1), "la semana pasada");
            case "esta semana", "la semana", "semana", "semanal" -> new Periodo(lunes, hoy, "esta semana");
            case "mes pasado" -> new Periodo(hoy.withDayOfMonth(1).minusMonths(1), hoy.withDayOfMonth(1).minusDays(1), "el mes pasado");
            case "este mes", "el mes", "mes", "mensual" -> new Periodo(hoy.withDayOfMonth(1), hoy, "este mes");
            case "este ano", "el ano" -> new Periodo(hoy.withDayOfYear(1), hoy, "este año");
            default -> new Periodo(hoy, hoy, "hoy");
        };
    }

    private String ganancia(Periodo p) {
        String no = ClientSession.sinPermiso(Permiso.REPORTES);
        if (no != null) return no;
        InventoryService.Periodo v = inv.ventasDe(p.desde(), p.hasta());
        if (v.ventas() == 0) return "No hubo ventas " + p.nombre() + ", así que no hay ganancia que reportar, señor.";
        String r = String.format("Ganancia de %s: %s, con %s por %s (%s sin IGV, costo %s).", p.nombre(), soles(v.ganancia()),
                ventasTxt(v.ventas()), soles(v.total()), soles(v.base()), soles(v.costo()));
        if (v.lineasSinCosto() > 0)
            r += " Ojo: " + v.lineasSinCosto() + (v.lineasSinCosto() == 1 ? " línea vendida no tiene" : " líneas vendidas no tienen")
                    + " costo registrado y no suman ganancia.";
        return r;
    }

    private String ventas(String cmd, Periodo p) {
        boolean soloHoy = p.desde().equals(p.hasta()) && p.hasta().equals(LocalDate.now());
        String no = ClientSession.puede(Permiso.REPORTES) || (soloHoy && ClientSession.puede(Permiso.VENDER)) ? null
                : ClientSession.sinPermiso(Permiso.REPORTES);
        if (no != null) return no;
        Matcher de = VENDIDO_DE.matcher(cmd);
        if (de.find()) {
            String producto = inv.nombreDe(PERIODO.matcher(de.group(1)).replaceAll("").replaceAll("\\b(esta|este|el|la|del|de)\\s*$", "").trim());
            if (producto != null) {
                Optional<InventoryService.MasVendido> x = inv.masVendidos(p.desde(), p.hasta(), 100_000).stream()
                        .filter(m -> m.producto().equalsIgnoreCase(producto)).findFirst();
                return x.map(m -> String.format("De %s se vendieron %s %s %s, por %s.", m.producto(), Num.txt(m.cantidad()), m.unidad(),
                                p.nombre(), soles(m.total())))
                        .orElse("No se vendió " + producto + " " + p.nombre() + ", señor.");
            }
        }
        if (soloHoy) return inv.cierre();
        InventoryService.Periodo v = inv.ventasDe(p.desde(), p.hasta());
        if (v.ventas() == 0) return "No hubo ventas " + p.nombre() + ", señor." + anuladas(v);
        return String.format("Ventas de %s: %s por %s.", p.nombre(), ventasTxt(v.ventas()), soles(v.total())) + anuladas(v);
    }

    private String masVendido(Periodo p) {
        if (!ClientSession.puede(Permiso.REPORTES) && !ClientSession.puede(Permiso.VENDER))
            return ClientSession.sinPermiso(Permiso.REPORTES);
        List<InventoryService.MasVendido> top = inv.masVendidos(p.desde(), p.hasta(), 3);
        if (top.isEmpty()) return "No hubo ventas " + p.nombre() + ", señor.";
        StringBuilder sb = new StringBuilder("Lo más vendido " + p.nombre() + ": ");
        for (int i = 0; i < top.size(); i++) {
            InventoryService.MasVendido m = top.get(i);
            if (i > 0) sb.append(i == top.size() - 1 ? " y " : ", ");
            sb.append(m.producto()).append(" (").append(Num.txt(m.cantidad())).append(' ').append(m.unidad())
                    .append(", ").append(soles(m.total())).append(')');
        }
        return sb.append('.').toString();
    }

    private String acabando() {
        List<InventoryService.Alerta> a = inv.panel(7).alertas();
        if (a.isEmpty()) return "Ningún producto está por debajo de su stock mínimo, señor.";
        List<String> sin = a.stream().filter(x -> x.estado().equals("SIN_STOCK")).map(InventoryService.Alerta::producto).toList();
        List<String> bajo = a.stream().filter(x -> x.estado().equals("BAJO"))
                .map(x -> x.producto() + " (quedan " + Num.txt(x.stock()) + ")").toList();
        StringBuilder sb = new StringBuilder();
        if (!sin.isEmpty()) sb.append("Sin stock: ").append(lista(sin, 8)).append(". ");
        if (!bajo.isEmpty()) sb.append("Por acabarse: ").append(lista(bajo, 8)).append('.');
        return sb.toString().trim();
    }

    private String quietos() {
        if (!ClientSession.puede(Permiso.INVENTARIO) && !ClientSession.puede(Permiso.REPORTES))
            return ClientSession.sinPermiso(Permiso.INVENTARIO);
        InventoryService.Panel p = inv.panel(7);
        List<InventoryService.SinMovimiento> q = p.sinMovimiento();
        if (q.isEmpty()) return "Todo lo que tiene stock se vendió en los últimos " + p.diasSinMovimiento() + " días, señor.";
        BigDecimal valor = q.stream().map(InventoryService.SinMovimiento::valorVenta).reduce(BigDecimal.ZERO, BigDecimal::add);
        return String.format("Sin ventas en %d días: %s. Son %s a precio de venta.", p.diasSinMovimiento(),
                lista(q.stream().map(s -> s.producto() + " (" + Num.txt(s.stock()) + " " + s.unidad() + ")").toList(), 6), soles(valor));
    }

    private String compras(String cmd) {
        String no = ClientSession.sinPermiso(Permiso.COMPRAS);
        if (no != null) return no;
        Periodo p = PERIODO.matcher(cmd).find() ? periodo(cmd, LocalDate.now(), "este mes") : null;
        String sinPeriodo = PERIODO.matcher(cmd).replaceAll("").replaceAll("\\s+", " ").trim();
        Matcher m = PROVEEDOR.matcher(sinPeriodo.replaceFirst(".*?\\b(compre|compramos|comprado|compras)\\b", ""));
        String texto = m.find() ? m.group(1).replaceAll("\\b(esta|este|el|la)$", "").trim() : "";
        String desde = p == null ? "" : p.desde().toString(), hasta = p == null ? "" : p.hasta().toString();
        List<Compras.Compra> l = inv.compras().listar(texto, desde, hasta, 5).stream().filter(c -> !c.anulada()).toList();
        if (l.isEmpty())
            return "No encontré compras" + (texto.isEmpty() ? "" : " a " + texto) + (p == null ? "" : " " + p.nombre()) + ", señor.";
        boolean unSolo = l.stream().map(Compras.Compra::proveedor).distinct().count() == 1;
        String de = texto.isEmpty() ? "" : " a " + (unSolo ? l.get(0).proveedor() : texto);
        Compras.Totales t = inv.compras().totalizar(texto, desde, hasta);
        StringBuilder sb = new StringBuilder(String.format("Compras%s%s: %d por %s. ", de, p == null ? "" : " " + p.nombre(),
                t.compras(), soles(t.total())));
        sb.append(l.size() == 1 ? "La última: " : "Las últimas: ");
        sb.append(lista(l.stream().map(c -> c.codigo() + " del " + fecha(c.fecha()) + " a " + c.proveedor() + " por " + soles(c.total()))
                .toList(), 5)).append('.');
        return sb.toString();
    }

    private String valor() {
        InventoryService.Valorizado v = inv.valorizado();
        if (v.productos() == 0) return "El inventario está vacío, señor.";
        String r = String.format("Su inventario vale %s a precio de venta", soles(v.valorVenta()));
        if (ClientSession.puede(Permiso.VER_COSTOS) && v.valorCosto().signum() > 0)
            r += String.format(" y %s al costo", soles(v.valorCosto())) + (v.sinCosto() > 0 ? " (" + v.sinCosto()
                    + (v.sinCosto() == 1 ? " producto sin costo)" : " productos sin costo)") : "");
        return r + ".";
    }

    private static String anuladas(InventoryService.Periodo v) {
        return v.anuladas() == 0 ? "" : " Anuladas: " + v.anuladas() + ".";
    }

    private static String ventasTxt(int n) { return n == 1 ? "1 venta" : n + " ventas"; }

    private static String soles(BigDecimal v) { return Moneda.hablado(v); }

    private static String fecha(String iso) {
        String[] p = iso == null ? new String[0] : iso.split("-");
        return p.length == 3 ? p[2] + "/" + p[1] : String.valueOf(iso);
    }

    private static String lista(List<String> items, int max) {
        List<String> l = items.size() > max ? items.subList(0, max) : items;
        String r = l.size() == 1 ? l.get(0) : String.join(", ", l.subList(0, l.size() - 1)) + " y " + l.get(l.size() - 1);
        return items.size() > max ? r + " (y " + (items.size() - max) + " más)" : r;
    }
}
