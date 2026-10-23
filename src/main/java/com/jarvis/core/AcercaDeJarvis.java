package com.jarvis.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Lo que JARVIS sabe de sí mismo: qué es, qué puede hacer y cómo se usa cada parte del sistema.
 * Responde solo PREGUNTAS ("¿qué eres?", "¿qué puedes hacer?", "¿cómo registro una compra?"), nunca órdenes
 * ("vendí 2 poleras" sigue yendo al inventario). Va antes que las demás reglas para que "¿cómo vendo?" explique en
 * lugar de intentar una venta sin datos. El asistente con IA recibe este mismo texto para responder igual.
 */
@Component
public class AcercaDeJarvis {

    /** Un tema: cuándo se pregunta por él y qué se responde. */
    private record Tema(Pattern pregunta, String respuesta) {}

    // Preguntas sobre JARVIS mismo (no hace falta "cómo": "¿qué eres?" ya es una pregunta).
    private static final Pattern IDENTIDAD = Pattern.compile(
            "\\b(que|quien) eres\\b|\\bque es jarvis\\b|\\bquien es jarvis\\b|\\bpresentate\\b|\\bcomo te llamas\\b|\\btu nombre\\b"
                    + "|\\beres (una|un) (ia|inteligencia|robot|programa|asistente|maquina|persona|humano)\\b|\\beres humano\\b|\\beres real\\b");
    private static final Pattern NOMBRE = Pattern.compile("\\bque significa (jarvis|tu nombre)\\b|\\bpor que te llamas\\b");
    private static final Pattern CAPACIDADES = Pattern.compile(
            "^ayuda$|\\bayudame\\b$|\\bque (puedes|sabes|podes) hacer\\b|\\bque haces\\b|\\bpara que sirves\\b|\\ben que (me )?(puedes ayudar|ayudas)\\b"
                    + "|\\bque funciones\\b|\\bque (puedo|se puede) hacer (contigo|con jarvis|aqui)\\b|\\bcomo me ayudas\\b|\\bcuales son tus funciones\\b"
                    + "|\\bque (cosas|ordenes|comandos) (entiendes|puedes|sabes|acepta)");
    private static final Pattern CREADOR = Pattern.compile("\\bquien te (creo|hizo|programo|desarrollo|diseno)\\b|\\bcomo (fuiste|te) (creado|hicieron)\\b"
            + "|\\ben que (lenguaje|tecnologia) (estas|fuiste)\\b");
    // Pregunta de "cómo se hace": hace falta una palabra de pregunta además del tema.
    // También los verbos dirigidos a JARVIS: "¿funcionas sin internet?", "¿usas inteligencia artificial?".
    private static final Pattern COMO = Pattern.compile("\\b(como|donde|puedo|se puede|que hago|explicame|ensename|necesito saber|quiero saber"
            + "|cual es la forma|funcionas|trabajas|necesitas|usas|tienes|guardas)\\b");
    // Frases de la caja que parecen preguntas pero no lo son: las atienden las reglas de inventario.
    private static final Pattern NO_ES_PREGUNTA = Pattern.compile("\\bcomo va\\b|\\bcuanto va\\b|\\bcuanto llevo\\b");

    private final String owner;
    private final String negocio;
    private final List<Tema> temas;

    public AcercaDeJarvis(@Value("${jarvis.owner:señor}") String owner, @Value("${jarvis.negocio:}") String negocio) {
        this.owner = owner == null || owner.isBlank() ? "señor" : owner.trim();
        this.negocio = negocio == null ? "" : negocio.trim();
        this.temas = temas();
    }

    /**
     * Responde si {@code cmd} (normalizado: minúsculas, sin tildes) es una pregunta sobre JARVIS; vacío si no.
     *
     * @param iaActiva si el asistente con IA está disponible (cambia lo que se dice de la IA)
     */
    public Optional<String> responder(String cmd, boolean iaActiva) {
        if (cmd == null || cmd.isBlank() || NO_ES_PREGUNTA.matcher(cmd).find()) return Optional.empty();
        if (IDENTIDAD.matcher(cmd).find()) return Optional.of(identidad(iaActiva));
        if (NOMBRE.matcher(cmd).find())
            return Optional.of("J.A.R.V.I.S. significa Just A Rather Very Intelligent System: \"un sistema bastante inteligente\", "
                    + "como el asistente de la película. Aquí soy el asistente de su inventario, " + owner + ".");
        if (CREADOR.matcher(cmd).find())
            return Optional.of("Fui desarrollado como el proyecto JARVIS: un sistema de inventario y ventas en Java con Spring Boot, "
                    + "con base de datos SQLite y una pantalla web con voz. Funciono en su propia computadora, " + owner + ".");
        if (CAPACIDADES.matcher(cmd).find()) return Optional.of(capacidades(iaActiva));
        if (!COMO.matcher(cmd).find()) return Optional.empty();
        for (Tema t : temas) if (t.pregunta().matcher(cmd).find()) return Optional.of(t.respuesta());
        if (cmd.matches(".*\\b(ia|inteligencia artificial)\\b.*")) return Optional.of(ia(iaActiva));
        return Optional.empty();
    }

    /** Todo lo que JARVIS sabe de sí mismo, para las instrucciones del asistente con IA. */
    public String conocimiento() {
        StringBuilder sb = new StringBuilder(identidad(true)).append("\n\n").append(capacidades(true)).append("\n\nCómo se usa:\n");
        for (Tema t : temas) sb.append("- ").append(t.respuesta()).append('\n');
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- respuestas

    private String identidad(boolean iaActiva) {
        String de = negocio.isEmpty() ? "de su negocio" : "de " + negocio;
        return "Soy J.A.R.V.I.S., el asistente inteligente del sistema de inventario y ventas " + de + ", " + owner + ". "
                + "Llevo el control de sus productos y su stock, las ventas con boleta o nota de venta e IGV, los clientes, "
                + "las compras a proveedores con su costo promedio, el kardex y los reportes en Excel. "
                + "Me puede hablar o escribir, desde esta computadora o desde el celular, y sus datos se quedan en su computadora. "
                + (iaActiva ? "Además uso inteligencia artificial para entender lo que me pida con sus propias palabras."
                            : "Entiendo órdenes como \"vendí 2 poleras M\" o \"cierre de caja\"; pregúnteme \"¿qué puedes hacer?\" para ver más.");
    }

    private String capacidades(boolean iaActiva) {
        return "Esto es lo que puedo hacer, " + owner + ":\n"
                + "• Vender: \"vendí 2 poleras M\", con \"nota de venta\" o \"para el DNI 12345678\"; o una venta con carrito: "
                + "\"nueva venta\", los productos y \"cobrar\". En la pantalla Vender también puede escanear códigos.\n"
                + "• Consultar el inventario: \"¿cuántas zapatillas 40 quedan?\", \"lista de precios\", \"inventario\"; le aviso del stock bajo.\n"
                + "• Registrar mercadería: \"agrega 10 gorras negras a 15 soles\", o una compra con factura del proveedor en Compras.\n"
                + "• Comprobantes: boleta y nota de venta con IGV y PDF; \"último comprobante\", \"anula la última venta\", "
                + "\"corrige la última venta, eran 3\".\n"
                + "• Caja: \"cierre de caja\" con las ventas y el total del día.\n"
                + "• En la pantalla: clientes, proveedores, kardex de cada producto, inventario valorizado y reportes en Excel.\n"
                + "• Productos: precios, stock mínimo, unidades, cargar listas desde Excel, enseñarme palabras (\"aprende que chompa "
                + "significa polera\") y empezar un \"nuevo inventario\".\n"
                + "• Otras cosas: la hora, notas, cálculos, abrir páginas y programas, y el estado de la computadora.\n"
                + (iaActiva ? "También puede pedírmelo con sus propias palabras: uso inteligencia artificial.\n" : "")
                + "Pregúnteme, por ejemplo, \"¿cómo registro una compra?\" o \"¿cómo veo el kardex?\" y se lo explico.";
    }

    private String ia(boolean iaActiva) {
        return iaActiva
                ? "Sí, " + owner + ": uso inteligencia artificial (Claude) para entender frases libres y ejecutar lo que me pide. "
                  + "Sin internet sigo funcionando con mis órdenes de siempre."
                : "Ahora funciono con mis órdenes de siempre, sin inteligencia artificial. Para activarla, guarde una clave de "
                  + "Anthropic en el archivo jarvis-ia-clave.txt de su carpeta de usuario y reinícieme: así entenderé frases libres.";
    }

    private List<Tema> temas() {
        return List.of(
                tema("\\b(anul|corrig|devol|me equivoque|error en la venta)", "Para una venta diga \"anula la última venta\" o \"corrige la última "
                        + "venta, eran 3 poleras\"; también puede anular la última desde Comprobantes. Una compra se anula desde su detalle en Compras."),
                tema("\\b(vend|venta|vender|cobr)", "Para vender hay tres formas: en la pantalla Vender, busque o escanee los productos y "
                        + "toque Cobrar; o dígame \"vendí 2 poleras M\" (puede añadir \"con nota de venta\" o \"para el DNI\" del cliente); "
                        + "o para varios productos diga \"nueva venta\", luego cada producto, y al final \"cobrar\"."),
                tema("\\b(boleta|comprobante|factura de venta|igv|pdf|imprim|ticket)", "Cada venta emite una boleta (serie B001) o una nota "
                        + "de venta (NV01), con el IGV del 18 por ciento separado. Para verla, imprimirla o sacar el PDF, toque el comprobante "
                        + "en la pantalla Comprobantes, o dígame \"último comprobante\"."),
                tema("\\b(client|dni)", "En la pantalla Clientes toque Nuevo cliente y escriba su DNI de 8 dígitos o su RUC de 11 y su nombre. "
                        + "Luego, al vender, escriba su DNI en la venta o dígame \"para el DNI\" seguido del número."),
                tema("\\b(compra|proveedor|mercaderia|factura|abastec)", "En la pantalla Compras toque Nueva compra: el RUC del proveedor, el "
                        + "tipo y número de su documento, y los productos con su cantidad y costo. La compra suma el stock y actualiza el "
                        + "costo promedio. Se puede anular mientras esa mercadería no se haya vendido."),
                tema("\\bkardex|\\bmovimientos\\b|\\bhistorial\\b", "En la pantalla Inventario toque el botón de kardex del producto: verá cada "
                        + "entrada y salida con el saldo que quedó y el costo promedio. Desde esa ventana lo exporta a Excel."),
                tema("\\b(excel|reporte|exportar|descargar)", "Hay botones Exportar Excel en Inventario (inventario valorizado y kardex), en "
                        + "Comprobantes (ventas del periodo con su costo y ganancia) y en Compras (compras del periodo)."),
                tema("\\b(valorizado|valor del inventario|cuanto vale|ganancia|margen|costo promedio)", "En la pantalla Inventario ve el valor "
                        + "al costo, el valor a precio de venta y el margen. La ganancia de cada venta está en el Excel de Comprobantes, "
                        + "calculada sin IGV con el costo que tenía el producto ese día."),
                tema("\\b(cargar|importar|lista de productos|muchos productos|desde excel)", "En la pantalla Inventario toque Cargar lista: "
                        + "pegue su lista desde Excel (nombre, precio, stock, unidad) o elija un archivo CSV, o díctela. Los que ya existen no se tocan."),
                tema("\\b(agreg|registr|crea|nuevo)\\w* (un |una |el |los |mis )?productos?\\b|\\bproducto nuevo", "En la pantalla Inventario toque Agregar producto, "
                        + "o dígame \"agrega 10 gorras negras a 15 soles\". Para muchos productos use Cargar lista."),
                tema("\\bprecio", "Dígame \"cambia el precio de la gorra negra a 18\", o edite el producto en la pantalla Inventario."),
                tema("\\b(stock (bajo|minimo)|reponer|alerta)", "Cada producto tiene un stock mínimo: cuando queda poco sale en ámbar en el "
                        + "inventario y en Reponer pronto, en Inicio. Lo cambia con \"pon el stock mínimo de la gorra en 5\" o editando el producto."),
                tema("\\bcierre|\\bcaja\\b|vendi hoy|ventas de hoy", "Dígame \"cierre de caja\" y le doy las ventas y el total del día. "
                        + "En la pantalla Inicio ve lo mismo, con las últimas ventas."),
                tema("\\b(nuevo inventario|empezar de cero|vaciar)", "Dígame \"nuevo inventario\": le pido confirmación, guardo una copia del "
                        + "actual y empiezo vacío. Los números de comprobante no se reinician."),
                tema("\\b(voz|habl|microfono|manos libres|escuch|dict)", "Toque el micrófono o mi orbe y hable. Con Manos libres solo diga "
                        + "\"Jarvis\" y su orden. La voz funciona en Chrome o Edge; en el celular necesita HTTPS (está en el manual)."),
                tema("\\b(celular|telefono movil|tablet|otro dispositivo|otra computadora)", "Conecte el celular a la misma red Wi-Fi y abra "
                        + "la dirección que aparece en Equipo, al pie del menú. La primera vez le pedirá la clave de JARVIS."),
                tema("\\b(internet|sin conexion|offline|sin red)", "Funciono sin internet: el inventario, las ventas, los comprobantes y los "
                        + "reportes son locales. Solo el reconocimiento de voz del navegador y la inteligencia artificial usan internet."),
                tema("\\b(guard|dato|respaldo|copia de seguridad|base de datos|se pierde|backup)", "Guardo todo en el archivo "
                        + "jarvis-inventario.db de su carpeta de usuario, con un respaldo automático por día de los últimos 7 días. "
                        + "Cada operación se guarda entera o no se guarda."),
                tema("\\b(clave|contrasena|seguridad|acceso)", "La clave protege el acceso desde otros dispositivos. Se cambia en "
                        + "application.properties (jarvis.token); en la pantalla, Cambiar clave pide escribirla de nuevo."),
                tema("\\b(ensen|palabra|diccionario|aprend)", "Dígame \"aprende que chompa significa polera\" y desde entonces lo entenderé; "
                        + "\"¿qué palabras aprendiste?\" las lista y \"olvida la palabra chompa\" la borra."));
    }

    private static Tema tema(String regex, String respuesta) {
        return new Tema(Pattern.compile(regex), respuesta);
    }
}
