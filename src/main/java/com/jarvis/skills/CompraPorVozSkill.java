/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.skills;

import com.jarvis.core.OrdenesPorConfirmar;
import com.jarvis.inventory.Compras;
import com.jarvis.inventory.DYSAInventoryService;
import com.jarvis.inventory.Moneda;
import com.jarvis.inventory.Num;
import com.jarvis.inventory.Palabras;
import com.jarvis.inventory.Producto;
import com.jarvis.security.ClientSession;
import com.jarvis.security.Rol.Permiso;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registrar una compra a un proveedor conversando, por partes:
 * "registra una compra a Andina" → "factura F001 452" → "10 cemento sol a 25" (las veces que haga falta) →
 * "registra la compra" → JARVIS lee el resumen y espera un "sí". Cada usuario tiene su propio borrador.
 */
@Component
@Order(-21)
public class CompraPorVozSkill implements Skill {

    static final long VIGENCIA_MS = 30 * 60 * 1000L;

    private static final Pattern EMPEZAR = Pattern.compile(
            "^(?:registra|registre|registrar|anota|anote|anotar|nueva|empieza|empezar|abre|abrir|inicia|iniciar|hacer|haz)"
            + "(?: (?:una|la|otra|nueva))* compra(?: (?:a|al|de|del|con)(?: (?:el|la))?(?: proveedor| empresa)? (.+))?$");
    private static final Pattern CORTE_PROVEEDOR = Pattern.compile(
            "\\b(?:ruc|factura|boleta|guia|nota de venta|con)\\b");
    private static final Pattern RUC = Pattern.compile("\\bruc(?: numero)? ([\\d ]+)");
    private static final Pattern SE_LLAMA = Pattern.compile(
            "^(?:se llama|la empresa (?:se llama|es)|razon social(?: es)?|el proveedor (?:se llama|es)|proveedor) (.+)$");
    private static final Pattern DOCUMENTO = Pattern.compile(
            "\\b(factura|boleta(?: de venta)?|guia(?: de remision)?|nota de venta)(?: (?:numero|nro|n))? ([a-z]?[\\da-z][\\da-z -]{0,30})$");
    private static final Pattern LINEA = Pattern.compile(
            "^(?:(?:agrega|agregue|anota|anote|suma|sume|mas|y|ademas|tambien|ingresa|entraron|llegaron) )*"
            + "(\\d+(?:\\.\\d+)?|un|una|uno) (.+?) a (\\d+(?:\\.\\d+)?)(?: (?:soles?|dolares?|pesos?))?"
            + "(?: (?:cada (?:uno|una|bolsa|caja|kilo|unidad|par|docena|metro|litro|saco|paquete)|c/u|la unidad|por unidad|el kilo|el par))?$");
    private static final Pattern LINEA_SIN_COSTO = Pattern.compile(
            "^(?:(?:agrega|agregue|anota|anote|suma|sume|mas|y|ademas|tambien|ingresa|entraron|llegaron) )*(\\d+(?:\\.\\d+)?|un|una|uno) (.+)$");
    private static final Pattern QUITAR = Pattern.compile("^(?:quita|quite|quitar|saca|saque|elimina|borra|borre|retira)(?: (?:el|la|los|las))? (.+)$");
    private static final Pattern VER = Pattern.compile(
            "\\b(como va|cuanto va|cuanto llevo|ver|resumen de|que tiene|que lleva|lee|leeme)(?: (?:la|esta|mi))? compra\\b|^como va$|^cuanto va$");
    private static final Pattern TERMINAR = Pattern.compile(
            "^(?:registra|registre|registrar|guarda|guarde|guardar|termina|termine|terminar|cierra|cierre|cerrar|finaliza|graba|grabar)"
            + "(?: (?:la|esta|mi))? compra$|^(?:eso es todo|es todo|seria todo|listo|ya esta|nada mas)$");
    private static final Pattern CANCELAR = Pattern.compile(
            "^(?:cancela|cancele|cancelar|anula|anule|borra|borre|descarta|descarte|olvida|deja)(?: (?:la|esta|mi))? compra$");
    private static final Pattern SIN_IGV = Pattern.compile("\\b(?:sin igv|mas igv|igv aparte|no incluye(?:n)? igv|precios? sin igv)\\b");
    private static final Pattern CON_IGV = Pattern.compile("\\b(?:con igv|igv incluido|incluye(?:n)? igv|precios? con igv)\\b");
    private static final Pattern CON_CENTIMOS = Pattern.compile("\\b(\\d+) con (\\d{1,2})\\b");

    static final class Borrador {
        String ruc = "", razon = "", tipo = "factura", numero = "", igv = "incluido";
        final Map<String, BigDecimal[]> lineas = new LinkedHashMap<>();
        boolean confirmando;
        long usado = System.currentTimeMillis();
    }

    private final DYSAInventoryService inv;
    private final Map<String, Borrador> borradores = new HashMap<>();

    public CompraPorVozSkill(DYSAInventoryService inv) { this.inv = inv; }

    @Override public String name() { return "Compras por voz"; }

    @Override public String example() {
        return "Registra una compra a Andina / Factura F001 452 / 10 cemento sol a 25 / ¿Cómo va la compra? / Registra la compra";
    }

    /** Si este usuario tiene una compra a medias: sus frases van a la compra y no se confirman una por una. */
    public synchronized boolean abierta() { return borrador() != null; }

    private Borrador borrador() {
        long ahora = System.currentTimeMillis();
        borradores.values().removeIf(b -> ahora - b.usado > VIGENCIA_MS);
        return borradores.get(clave());
    }

    /** Cada usuario tiene su borrador en cada tienda. */
    private static String clave() { return ClientSession.id() + "@" + ClientSession.tienda(); }

    @Override
    public synchronized Optional<String> handle(String dicho, String original) {
        String cmd = numeros(dicho.replaceAll("[?,;:]", " ").replaceAll("\\s+", " ").trim());
        Borrador b = borrador();
        if (b == null) {
            Matcher m = EMPEZAR.matcher(cmd);
            return m.matches() ? Optional.of(empezar(m.group(1))) : Optional.empty();
        }
        b.usado = System.currentTimeMillis();
        if (b.confirmando) {
            b.confirmando = false;
            if (OrdenesPorConfirmar.esSi(cmd)) return Optional.of(registrar(b));
            if (OrdenesPorConfirmar.esNo(cmd)) return Optional.of("Bien, no la registré; la compra sigue abierta. " + siguiente(b));
        }
        if (CANCELAR.matcher(cmd).matches()) {
            borradores.remove(clave());
            return Optional.of("Listo, descarté la compra; no se registró nada.");
        }
        if (TERMINAR.matcher(cmd).matches()) return Optional.of(terminar(b));
        if (VER.matcher(cmd).find()) return Optional.of(resumen(b) + " " + siguiente(b));
        Matcher q = QUITAR.matcher(cmd);
        if (q.matches() && !b.lineas.isEmpty()) return Optional.of(quitar(b, q.group(1)));

        List<String> hecho = new ArrayList<>();
        Matcher ruc = RUC.matcher(cmd);
        if (ruc.find()) {
            String r = ruc.group(1).replace(" ", "");
            if (!r.matches("(10|15|17|20)\\d{9}")) return Optional.of("El RUC tiene 11 dígitos y empieza con 10, 15, 17 o 20. Entendí " + r
                    + "; dígamelo otra vez, número por número.");
            usarRuc(b, r);
            hecho.add("RUC " + b.ruc + (b.razon.isEmpty() ? "" : " (" + b.razon + ")"));
        }
        Matcher llama = SE_LLAMA.matcher(cmd);
        if (llama.matches() && !ruc.find(0)) {
            String nombre = llama.group(1).trim();
            if (b.ruc.isEmpty()) return Optional.of(buscarProveedor(b, nombre));
            b.razon = GestionSkill.nombrePropio(nombre).toUpperCase(Locale.ROOT);
            hecho.add("proveedor " + b.razon);
        }
        Matcher doc = DOCUMENTO.matcher(cmd);
        if (doc.find()) {
            b.tipo = doc.group(1).startsWith("bol") ? "boleta" : doc.group(1).startsWith("guia") ? "guia" : doc.group(1).startsWith("nota") ? "nota" : "factura";
            b.numero = numeroDocumento(doc.group(2));
            hecho.add(tipoTexto(b) + " " + b.numero);
        }
        if (SIN_IGV.matcher(cmd).find()) { b.igv = "agregado"; hecho.add("costos sin IGV (se suma el 18%)"); }
        else if (CON_IGV.matcher(cmd).find()) { b.igv = "incluido"; hecho.add("costos con IGV incluido"); }
        Matcher l = LINEA.matcher(cmd);
        if (hecho.isEmpty() && l.matches()) return Optional.of(agregar(b, l.group(1), l.group(2), l.group(3)));
        if (!hecho.isEmpty()) return Optional.of("Anotado: " + String.join(", ", hecho) + ". " + siguiente(b));
        Matcher sinCosto = LINEA_SIN_COSTO.matcher(cmd);
        if (sinCosto.matches() && inv.nombreDe(sinCosto.group(2)) != null)
            return Optional.of("¿A qué costo? Dígalo así: " + sinCosto.group(1) + " " + sinCosto.group(2) + " a 25 (el costo de cada uno).");
        return Optional.empty();
    }

    // ------------------------------------------------------------------ pasos

    private String empezar(String proveedorDicho) {
        String no = ClientSession.sinPermiso(Permiso.COMPRAS);
        if (no != null) return no;
        if (inv.ventaAbierta()) return "Hay una venta en curso, señor. Primero cóbrela o cancélela y luego empezamos la compra.";
        Borrador b = new Borrador();
        borradores.put(clave(), b);
        if (proveedorDicho == null || proveedorDicho.isBlank())
            return "Empecemos la compra. ¿A qué proveedor? Dígame su nombre o su RUC.";
        String resto = proveedorDicho.trim();
        Matcher corte = CORTE_PROVEEDOR.matcher(resto);
        String nombre = corte.find() ? resto.substring(0, corte.start()).trim() : resto;
        String despues = corte.find(0) ? resto.substring(corte.start()).trim() : "";
        String r = nombre.isEmpty() ? "" : buscarProveedor(b, nombre);
        if (despues.isEmpty()) return r.isEmpty() ? "Empecemos la compra. " + siguiente(b) : r;
        boolean resuelto = !b.ruc.isEmpty() && !b.razon.isEmpty();
        String mas = handle(despues, despues).orElse(siguiente(b));
        return (resuelto ? "Compra a " + b.razon + " (RUC " + b.ruc + "). " : r.isEmpty() ? "" : r + " ") + mas;
    }

    private String buscarProveedor(Borrador b, String nombreDicho) {
        String nombre = nombreDicho.replaceAll("^(?:la |el )", "").trim();
        String soloDigitos = nombre.replace(" ", "");
        if (soloDigitos.matches("\\d{11}")) {
            usarRuc(b, soloDigitos);
            return "Compra a " + (b.razon.isEmpty() ? "RUC " + b.ruc : b.razon) + ". " + siguiente(b);
        }
        List<Compras.Proveedor> l = inv.compras().buscarProveedores(nombre, 4);
        if (l.size() == 1) {
            b.ruc = l.get(0).ruc();
            b.razon = l.get(0).razonSocial();
            return "Compra a " + b.razon + " (RUC " + b.ruc + "). " + siguiente(b);
        }
        if (l.size() > 1) return "Tengo varios proveedores parecidos: "
                + String.join(", ", l.stream().map(Compras.Proveedor::razonSocial).toList()) + ". ¿Cuál? Dígame el nombre completo o el RUC.";
        b.razon = GestionSkill.nombrePropio(nombre).toUpperCase(Locale.ROOT);
        return "No tengo registrado a " + b.razon + ". Dígame su RUC, número por número, y lo registro con la compra.";
    }

    private void usarRuc(Borrador b, String ruc) {
        b.ruc = ruc;
        inv.compras().proveedor(ruc).ifPresent(p -> b.razon = p.razonSocial());
    }

    private String agregar(Borrador b, String cantidadDicha, String productoDicho, String costoDicho) {
        BigDecimal cantidad = cantidadDicha.matches("un|una|uno") ? BigDecimal.ONE : new BigDecimal(cantidadDicha);
        BigDecimal costo = new BigDecimal(costoDicho);
        String texto = productoDicho.replaceAll("^(?:(?:bolsas?|cajas?|unidades|unidad|kilos?|sacos?|paquetes?|docenas?|metros?|litros?|pares?) )?(?:de )?", "").trim();
        String nombre = inv.nombreDe(texto);
        if (nombre == null) nombre = inv.nombreDe(productoDicho);
        if (nombre == null) return "No tengo el producto " + productoDicho + ". Regístrelo primero en Inventario o dígalo con otro nombre. "
                + "Lo demás de la compra sigue anotado.";
        if (cantidad.signum() <= 0 || costo.signum() <= 0) return "La cantidad y el costo deben ser mayores que cero.";
        String unidad = unidadDe(nombre);
        BigDecimal[] previa = b.lineas.get(nombre);
        if (previa != null && previa[1].compareTo(costo) == 0) cantidad = cantidad.add(previa[0]);
        b.lineas.put(nombre, new BigDecimal[]{cantidad, costo});
        return "Anotado: " + Num.txt(cantidad) + " " + unidad + " de " + nombre + " a " + Num.dinero(costo) + " "
                + Moneda.nombre() + " (" + Num.dinero(cantidad.multiply(costo)) + "). " + totalTxt(b) + " " + siguiente(b);
    }

    private String quitar(Borrador b, String texto) {
        String nombre = inv.nombreDe(texto);
        if (nombre == null || b.lineas.remove(nombre) == null)
            return "En la compra no hay " + texto + ". Lleva: " + String.join(", ", b.lineas.keySet()) + ".";
        return "Quité " + nombre + " de la compra. " + (b.lineas.isEmpty() ? "" : totalTxt(b) + " ") + siguiente(b);
    }

    private String terminar(Borrador b) {
        String falta = falta(b);
        if (falta != null) return "Aún no puedo registrarla: " + falta;
        b.confirmando = true;
        return resumen(b) + " ¿La registro?";
    }

    private String registrar(Borrador b) {
        List<Compras.LineaCompra> items = new ArrayList<>();
        b.lineas.forEach((n, v) -> items.add(new Compras.LineaCompra(n, v[0], v[1])));
        DYSAInventoryService.Resultado r = inv.registrarCompra(new Compras.NuevaCompra(b.ruc, b.razon, b.tipo, b.numero, null, b.igv,
                "Registrada por voz", items));
        if (r.ok()) borradores.remove(clave());
        return r.ok() ? r.mensaje() : "No la registré: " + r.mensaje() + " La compra sigue abierta para corregirla.";
    }

    // ------------------------------------------------------------------ textos

    private String siguiente(Borrador b) {
        if (b.ruc.isEmpty() && b.razon.isEmpty()) return "¿A qué proveedor? Dígame su nombre o su RUC.";
        if (b.ruc.isEmpty()) return "Dígame el RUC de " + b.razon + ", número por número.";
        if (b.razon.isEmpty()) return "El RUC " + b.ruc + " no está registrado. ¿Cómo se llama la empresa? Diga: se llama…";
        if (b.numero.isEmpty()) return "Dígame el documento del proveedor, por ejemplo: factura F001 452.";
        if (b.lineas.isEmpty()) return "Ahora los productos, uno por uno: cantidad, producto y costo. Por ejemplo: 10 cemento a 25.";
        return "¿Algo más? Cuando termine diga: registra la compra.";
    }

    private String falta(Borrador b) {
        if (b.ruc.isEmpty() || b.razon.isEmpty() || b.numero.isEmpty() || b.lineas.isEmpty()) return siguiente(b);
        return null;
    }

    private String resumen(Borrador b) {
        StringBuilder s = new StringBuilder("Compra a " + (b.razon.isEmpty() ? "(sin proveedor)" : b.razon));
        if (!b.numero.isEmpty()) s.append(", ").append(tipoTexto(b)).append(' ').append(b.numero);
        s.append('.');
        if (b.lineas.isEmpty()) return s.append(" Aún sin productos.").toString();
        List<String> l = new ArrayList<>();
        b.lineas.forEach((n, v) -> l.add(Num.txt(v[0]) + " " + n + " a " + Num.dinero(v[1])));
        return s.append(' ').append(String.join("; ", l)).append(". ").append(totalTxt(b)).toString();
    }

    private String totalTxt(Borrador b) {
        BigDecimal suma = BigDecimal.ZERO;
        for (BigDecimal[] v : b.lineas.values()) suma = suma.add(v[0].multiply(v[1]));
        if ("agregado".equals(b.igv)) suma = suma.multiply(new BigDecimal("1.18"));
        int n = b.lineas.size();
        return "Van " + n + (n == 1 ? " producto" : " productos") + ", total " + Num.dinero(suma.setScale(2, RoundingMode.HALF_UP)) + " "
                + Moneda.nombre() + ("agregado".equals(b.igv) ? " con IGV." : ".");
    }

    private static String tipoTexto(Borrador b) {
        return switch (b.tipo) { case "boleta" -> "boleta"; case "guia" -> "guía"; case "nota" -> "nota de venta"; default -> "factura"; };
    }

    private String unidadDe(String nombre) {
        return inv.productos().stream().filter(p -> p.nombre.equals(nombre)).map(p -> p.unidad).findFirst().orElse("");
    }

    // ------------------------------------------------------------------ números dichos

    /** "f cero cero uno cuatro cinco dos" → "F001-452"; "f001 452" → "F001-452". */
    static String numeroDocumento(String dicho) {
        List<String> t = new ArrayList<>();
        for (String w : dicho.trim().split("[ -]+")) {
            String d = digito(w).toUpperCase(Locale.ROOT);
            if (d.equals("EFE")) d = "F"; else if (d.equals("BE")) d = "B"; else if (d.equals("E")) d = "E";
            if (!d.isEmpty()) t.add(d);
        }
        if (t.isEmpty()) return "";
        String primero = t.get(0);
        if (primero.matches("[A-Z]{1,2}\\d+") && t.size() > 1)                       // F001 452
            return primero + "-" + String.join("", t.subList(1, t.size()));
        if (primero.matches("[A-Z]{1,2}") && t.size() > 1 && t.subList(1, t.size()).stream().allMatch(x -> x.matches("\\d+"))) {
            List<String> resto = t.subList(1, t.size());
            if (resto.stream().allMatch(x -> x.length() == 1) && resto.size() > 3)   // F 0 0 1 4 5 2: dictado dígito a dígito
                return primero + String.join("", resto.subList(0, 3)) + "-" + String.join("", resto.subList(3, resto.size()));
            if (resto.size() > 1 && resto.get(0).length() <= 3)                     // F 1 452
                return primero + String.format("%03d", Integer.parseInt(resto.get(0))) + "-" + String.join("", resto.subList(1, resto.size()));
        }
        return String.join("", t);
    }

    private static final Map<String, String> DIGITOS = Map.of("cero", "0", "uno", "1", "dos", "2", "tres", "3", "cuatro", "4",
            "cinco", "5", "seis", "6", "siete", "7", "ocho", "8", "nueve", "9");

    private static String digito(String t) { return DIGITOS.getOrDefault(t, t); }

    /** Después de estas palabras los números se dictan dígito por dígito (RUC, serie y número del documento). */
    private static final Set<String> CLAVES_DOCUMENTO = Set.of("ruc", "factura", "boleta", "guia", "nota");

    /**
     * Pasa a cifras los números dichos en palabras: cantidades y costos ("diez", "veinticinco con cincuenta" → 25.50),
     * y el RUC dígito por dígito ("ruc dos cero uno..." → "ruc 2 0 1...").
     */
    static String numeros(String cmd) {
        List<String> ts = new ArrayList<>(Arrays.asList(cmd.split(" ")));
        List<String> out = new ArrayList<>();
        boolean digitoADigito = false;
        for (int i = 0; i < ts.size(); i++) {
            String t = ts.get(i);
            if (CLAVES_DOCUMENTO.contains(t)) { digitoADigito = true; out.add(t); continue; }
            if (digitoADigito) {
                if (DIGITOS.containsKey(t)) { out.add(digito(t)); continue; }
                if (t.matches("\\d+|[a-z]{1,3}\\d*|numero|nro|de|remision|venta|de venta")) { out.add(t); continue; }
                digitoADigito = false;
            }
            if (Palabras.esInicioCantidad(t) && !Palabras.esNumero(t) && !List.of("un", "una", "uno").contains(t)) {
                Palabras.Cantidad c = Palabras.buscarCantidad(ts.subList(i, ts.size()));
                if (c.hay() && c.inicio() == 0 && c.fin() > 0) {
                    out.add(Num.txt(c.valor()));
                    i += c.fin() - 1;
                    continue;
                }
            }
            if (t.equals("cero")) { out.add("0"); continue; }
            out.add(t);
        }
        String r = String.join(" ", out);
        Matcher m = CON_CENTIMOS.matcher(r);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, m.group(1) + "." + (m.group(2).length() == 1 ? m.group(2) + "0" : m.group(2)));
        m.appendTail(sb);
        return sb.toString();
    }
}
