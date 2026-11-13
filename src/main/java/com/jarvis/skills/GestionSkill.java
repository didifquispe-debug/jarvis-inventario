/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.skills;

import com.jarvis.inventory.Clientes;
import com.jarvis.inventory.Compras;
import com.jarvis.inventory.FilaKardex;
import com.jarvis.inventory.DYSAInventoryService;
import com.jarvis.inventory.Num;
import com.jarvis.security.ClientSession;
import com.jarvis.security.Rol.Permiso;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El resto del negocio por voz o chat: copias de seguridad, kardex de un producto, clientes y proveedores.
 * Va antes que ConsultasSkill e InventorySkill, que tomarían "agrega al cliente..." como una entrada de mercadería.
 */
@Component
@Order(-16)
public class GestionSkill implements Skill {

    private static final Pattern RESPALDO = Pattern.compile(
            "\\b(haz|haga|hacer|hazme|crea|cree|crear|genera|genere|generar|saca|saque|sacar|guarda|guarde|guardar)\\b"
            + "(?: (?:me|una|un|la|el|otra|otro|ahora))* (copia de seguridad|copia del inventario|respaldo|backup)\\b"
            + "|^(copia de seguridad|respaldo|backup)( ahora)?$");
    private static final Pattern KARDEX = Pattern.compile(
            "\\b(?:kardex|movimientos|historial)(?: (?:de|del|de la|de los|de las))? (.+)$");
    private static final Pattern CUANTOS_CLIENTES = Pattern.compile("\\bcuantos clientes\\b");
    private static final Pattern REGISTRAR_CLIENTE = Pattern.compile(
            "^(?:registra|registre|registrar|agrega|agregue|agregar|a[nñ]ade|a[nñ]adir|crea|cree|crear|guarda|guarde)"
            + "(?: (?:a|al|el|un|una|nuevo|nueva))* cliente (.+?),? con (?:el )?(?:dni|ruc|documento)(?: numero)? ([\\d ]{8,16})$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CLIENTE_DOC = Pattern.compile(
            "^(?:quien es|busca|buscar|busque|datos del|datos de|como se llama|nombre del|informacion del)(?: (?:a|al|el|la))? cliente"
            + "(?: con)?(?: (?:el|la))?(?: (?:dni|ruc|documento))?(?: numero)? ((?:\\d ?){8}(?:(?:\\d ?){3})?)$");
    private static final Pattern PROVEEDORES = Pattern.compile(
            "\\b(cuantos|lista de|listado de|mis|que|cuales son (?:mis|los)) proveedores\\b");
    private static final Pattern PROVEEDOR = Pattern.compile(
            "\\b(?:datos|telefono|ruc|direccion|contacto)(?: (?:de|del))?(?: la empresa| el proveedor| proveedor)? (.+)$");

    private final DYSAInventoryService inv;

    public GestionSkill(DYSAInventoryService inv) { this.inv = inv; }

    @Override public String name() { return "Copias, kardex, clientes y proveedores"; }

    @Override public String example() {
        return "Haz una copia de seguridad / Kardex del cemento / Registra al cliente Ana Torres con DNI 45678912"
                + " / ¿Quién es el cliente 45678912? / Datos del proveedor Andina";
    }

    /** Órdenes que cambian datos y, dichas por voz, conviene confirmar (un número mal oído crea un cliente equivocado). */
    public boolean delicadaPorVoz(String original) {
        return REGISTRAR_CLIENTE.matcher(sinJarvis(original)).matches();
    }

    @Override
    public Optional<String> handle(String dicho, String original) {
        if (inv.hayPendiente()) return Optional.empty();
        String cmd = dicho.replaceAll("[?,;:]", " ").replaceAll("\\s+", " ").trim();
        if (RESPALDO.matcher(cmd).find()) return Optional.of(inv.respaldarAhora().mensaje());
        Matcher reg = REGISTRAR_CLIENTE.matcher(sinJarvis(original));
        if (reg.matches()) return Optional.of(registrarCliente(reg.group(1), reg.group(2)));
        if (CUANTOS_CLIENTES.matcher(cmd).find()) return Optional.of(cuantosClientes());
        Matcher doc = CLIENTE_DOC.matcher(cmd);
        if (doc.find()) return Optional.of(cliente(doc.group(1).replace(" ", "")));
        if (PROVEEDORES.matcher(cmd).find()) return Optional.of(proveedores());
        if (cmd.contains("proveedor") || cmd.contains("empresa")) {
            Matcher p = PROVEEDOR.matcher(cmd);
            if (p.find()) return Optional.of(proveedor(p.group(1)));
        }
        Matcher k = KARDEX.matcher(cmd);
        if (k.find()) return kardex(k.group(1));
        return Optional.empty();
    }

    private static String sinJarvis(String original) {
        return (original == null ? "" : original).trim().replaceFirst("(?i)^(oye |hey )?jarvis[,.]?\\s*", "").replaceAll("[.!¡¿?]+$", "").trim();
    }

    private String registrarCliente(String nombreDicho, String documentoDicho) {
        String no = ClientSession.sinPermiso(Permiso.CLIENTES);
        if (no != null) return no;
        String documento = documentoDicho.replace(" ", "");
        String nombre = nombrePropio(nombreDicho);
        DYSAInventoryService.Resultado r = inv.clientes().crear(new Clientes.Datos(documento, nombre, "", "", ""));
        return r.mensaje();
    }

    /** "ana TORRES de la cruz" → "Ana Torres de la Cruz". */
    static String nombrePropio(String texto) {
        List<String> out = new ArrayList<>();
        for (String w : texto.trim().replaceAll("\\s+", " ").split(" ")) {
            String l = w.toLowerCase(new Locale("es"));
            boolean enlace = !out.isEmpty() && List.of("de", "del", "la", "las", "los", "y").contains(l);
            out.add(enlace || l.isEmpty() ? l : Character.toUpperCase(l.charAt(0)) + l.substring(1));
        }
        return String.join(" ", out);
    }

    private String cuantosClientes() {
        String no = ClientSession.sinPermiso(Permiso.CLIENTES);
        if (no != null) return no;
        int n = inv.clientes().contar();
        return n == 0 ? "Aún no hay clientes registrados, señor." : "Tiene " + n + (n == 1 ? " cliente registrado." : " clientes registrados.");
    }

    private String cliente(String documento) {
        String no = ClientSession.sinPermiso(Permiso.CLIENTES);
        if (no != null) return no;
        return inv.clientes().porDocumento(documento)
                .map(c -> {
                    StringBuilder s = new StringBuilder("El " + c.tipoDocumento() + " " + c.documento() + " es de " + c.nombre() + ".");
                    if (!c.telefono().isBlank()) s.append(" Teléfono: ").append(c.telefono()).append('.');
                    if (!c.direccion().isBlank()) s.append(" Dirección: ").append(c.direccion()).append('.');
                    return s.toString();
                })
                .orElse("No tengo un cliente con el documento " + documento + ". Puede registrarlo diciendo: registra al cliente"
                        + " (nombre) con DNI " + documento + ".");
    }

    private String proveedores() {
        String no = ClientSession.sinPermiso(Permiso.COMPRAS);
        if (no != null) return no;
        int n = inv.compras().contarProveedores();
        if (n == 0) return "Aún no hay proveedores registrados, señor.";
        List<String> nombres = inv.compras().buscarProveedores("", 5).stream().map(Compras.Proveedor::razonSocial).toList();
        return "Tiene " + n + (n == 1 ? " proveedor: " : " proveedores: ") + String.join(", ", nombres) + (n > nombres.size() ? ", entre otros." : ".");
    }

    private String proveedor(String texto) {
        String no = ClientSession.sinPermiso(Permiso.COMPRAS);
        if (no != null) return no;
        String t = texto.replaceAll("^(?:el |la |los |las )", "").trim();
        List<Compras.Proveedor> l = inv.compras().buscarProveedores(t.replace(" ", "").matches("\\d{6,11}") ? t.replace(" ", "") : t, 3);
        if (l.isEmpty()) return "No encontré un proveedor que se llame " + t + ", señor.";
        if (l.size() > 1) return "Hay varios proveedores parecidos: "
                + String.join(", ", l.stream().map(Compras.Proveedor::razonSocial).toList()) + ". ¿Cuál de ellos?";
        Compras.Proveedor p = l.get(0);
        StringBuilder s = new StringBuilder(p.razonSocial() + ", RUC " + p.ruc() + ".");
        if (p.telefono() != null && !p.telefono().isBlank()) s.append(" Teléfono: ").append(p.telefono()).append('.');
        if (p.direccion() != null && !p.direccion().isBlank()) s.append(" Dirección: ").append(p.direccion()).append('.');
        return s.toString();
    }

    private Optional<String> kardex(String texto) {
        String producto = inv.nombreDe(texto.replaceAll("\\b(este|esta|del|de la|de los|de las|de|el|la|los|las) *$", "").trim());
        if (producto == null) return Optional.empty();
        if (!ClientSession.puede(Permiso.INVENTARIO) && !ClientSession.puede(Permiso.VER_COSTOS))
            return Optional.of(ClientSession.sinPermiso(Permiso.INVENTARIO));
        DYSAInventoryService.Kardex k = inv.kardex(producto, 5);
        if (k == null) return Optional.empty();
        if (k.filas().isEmpty()) return Optional.of(k.producto() + " aún no tiene movimientos. Stock: " + Num.txt(k.stock()) + " " + k.unidad() + ".");
        List<String> partes = new ArrayList<>();
        for (FilaKardex f : k.filas()) partes.add(movimiento(f));
        return Optional.of("Kardex de " + k.producto() + ", stock actual " + Num.txt(k.stock()) + " " + k.unidad() + ". Últimos movimientos: "
                + String.join("; ", partes) + ". El detalle completo está en Inventario, botón Kardex.");
    }

    private static String movimiento(FilaKardex f) {
        String fecha = f.fecha() == null ? "" : f.fecha().replace('T', ' ');
        if (fecha.length() >= 16) fecha = fecha.substring(8, 10) + "/" + fecha.substring(5, 7) + " " + fecha.substring(11, 16);
        String tipo = f.tipo() == null ? "" : f.tipo().toLowerCase(Locale.ROOT).replace('_', ' ');
        BigDecimal cant = f.entrada() != null && f.entrada().signum() != 0 ? f.entrada() : f.salida();
        String signo = f.entrada() != null && f.entrada().signum() != 0 ? "+" : "−";
        return fecha + " " + tipo + (cant == null ? "" : " " + signo + Num.txt(cant)) + (f.saldo() == null ? "" : ", quedan " + Num.txt(f.saldo()));
    }
}
