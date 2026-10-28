/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.ia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.Palabras;
import com.jarvis.inventory.Producto;
import com.jarvis.core.OrdenesPorConfirmar;
import com.jarvis.security.ClientSession;
import com.jarvis.skills.InventorySkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

@Component
public class AsistenteIA {

    private static final Logger log = LoggerFactory.getLogger(AsistenteIA.class);

    private static final int MAX_HISTORIAL = 8;
    private static final int MAX_PRODUCTOS = 40;
    private static final long INACTIVA_MS = 30 * 60 * 1000L;
    private static final long ESPERA_SIN_SERVIDOR_MS = 60 * 1000L;

    static final String NO_PUDE = "No supe cómo hacer eso, señor. Dígalo de otra forma o diga \"ayuda\".";

    private static final List<ClienteLLM.Mensaje> EJEMPLOS = List.of(
            new ClienteLLM.Mensaje("user", "despacha tres gorras negras"),
            new ClienteLLM.Mensaje("assistant", "{\"orden\": \"vendí 3 gorra negra\"}"),
            new ClienteLLM.Mensaje("user", "me llegaron 5 cajas de leche, a 40 soles cada una"),
            new ClienteLLM.Mensaje("assistant", "{\"orden\": \"agrega 5 caja de leche a 40 soles\"}"),
            new ClienteLLM.Mensaje("user", "fíjate cuántos polos blancos me quedan"),
            new ClienteLLM.Mensaje("assistant", "{\"orden\": \"cuántas tengo de polo blanco\"}"),
            new ClienteLLM.Mensaje("user", "hola, cómo estás"),
            new ClienteLLM.Mensaje("assistant", "{\"respuesta\": \"Muy bien, señor. ¿En qué le ayudo?\"}"));

    private static final java.util.regex.Pattern AFIRMA_ACCION = java.util.regex.Pattern.compile(
            "\\b(vendi|vendido|vendida|vendidos|vendidas|se vendio|se vendieron|registre|registrado|registrada|registrados"
            + "|agregue|agregado|agregados|anadi|anadido|elimine|eliminado|eliminada|borre|borrado|anule|anulado|anulada"
            + "|envie|enviado|enviados|enviadas|despache|despachado|despachados|actualice|cambie el precio"
            + "|he hecho|ya esta hecho)\\b");

    private static class Conversacion {
        final Deque<ClienteLLM.Mensaje> historial = new ArrayDeque<>();
        long usada;
    }

    private final InventoryService inv;
    private final Predicate<String> soloConsulta;
    private final ClienteLLM cliente;
    private final String owner;
    private final String negocio;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Conversacion> conversaciones = new HashMap<>();
    private final OrdenesPorConfirmar porConfirmar;
    private volatile long sinServidorHasta;

    public AsistenteIA(InventoryService inv, InventorySkill reglasInventario, OrdenesPorConfirmar porConfirmar,
                       @Value("${jarvis.ia.enabled:true}") boolean enabled,
                       @Value("${jarvis.ia.base-url:http://localhost:11434/v1}") String baseUrl,
                       @Value("${jarvis.ia.model:llama3.2:3b}") String modelo,
                       @Value("${jarvis.ia.timeout-segundos:20}") int timeoutSegundos,
                       @Value("${jarvis.owner:señor}") String owner,
                       @Value("${jarvis.negocio:}") String negocio) {
        this.inv = inv;
        this.soloConsulta = reglasInventario::soloConsulta;
        this.porConfirmar = porConfirmar;
        this.owner = owner == null || owner.isBlank() ? "señor" : owner.trim();
        this.negocio = negocio == null ? "" : negocio.trim();
        ClienteLLM c = null;
        if (!enabled) {
            log.info("Asistente con IA apagado (jarvis.ia.enabled=false): se usan solo las reglas.");
        } else {
            try {
                c = new ClienteLLM(baseUrl, modelo, Duration.ofSeconds(Math.max(3, timeoutSegundos)));
            } catch (IllegalArgumentException e) {
                log.warn("Asistente con IA apagado: {}. Se usan solo las reglas.", e.getMessage());
            }
        }
        this.cliente = c;
        if (cliente != null) {
            Thread t = new Thread(this::comprobarServidor, "jarvis-ia-arranque");
            t.setDaemon(true);
            t.start();
        }
    }

    private void comprobarServidor() {
        try {
            List<String> modelos = cliente.modelos();
            if (!modelos.isEmpty() && modelos.stream().noneMatch(m -> m.equals(cliente.modelo()) || m.equals(cliente.modelo() + ":latest"))) {
                log.warn("El servidor de IA local ({}) no tiene el modelo {} (tiene {}). Ejecute: ollama pull {}",
                        cliente.baseUrl(), cliente.modelo(), modelos, cliente.modelo());
                sinServidorHasta = System.currentTimeMillis() + ESPERA_SIN_SERVIDOR_MS;
                return;
            }
            cliente.chat(List.of(new ClienteLLM.Mensaje("user", "hola")), false, 1, Duration.ofMinutes(2));
            log.info("Asistente con IA local listo ({} en {}).", cliente.modelo(), cliente.baseUrl());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            sinServidorHasta = System.currentTimeMillis() + ESPERA_SIN_SERVIDOR_MS;
            log.info("Asistente con IA local no disponible en {} ({}). Se usan las reglas; lo vuelvo a intentar luego. "
                    + "Para activarlo abra Ollama y ejecute: ollama pull {}", cliente.baseUrl(), e.toString(), cliente.modelo());
        }
    }

    public boolean disponible() {
        return cliente != null && System.currentTimeMillis() >= sinServidorHasta;
    }

    public Optional<String> responder(String texto, Function<String, String> reglas) {
        if (!disponible() || texto == null || texto.isBlank()) return Optional.empty();
        Conversacion c = conversacion();
        String contenido;
        synchronized (c) {
            List<ClienteLLM.Mensaje> mensajes = new ArrayList<>();
            mensajes.add(new ClienteLLM.Mensaje("system", sistema()));
            mensajes.addAll(EJEMPLOS);
            mensajes.addAll(c.historial);
            mensajes.add(new ClienteLLM.Mensaje("user", texto.trim()));
            try {
                contenido = cliente.chat(mensajes, true, 300, null);
            } catch (ConnectException | HttpTimeoutException e) {
                sinServidorHasta = System.currentTimeMillis() + ESPERA_SIN_SERVIDOR_MS;
                log.warn("El servidor de IA local no responde ({}): uso las reglas por un minuto.", e.toString());
                return Optional.empty();
            } catch (ClienteLLM.ErrorServidor e) {
                if (e.status == 404) log.warn("El servidor de IA local no tiene el modelo {}. Ejecute: ollama pull {}",
                        cliente.modelo(), cliente.modelo());
                else log.warn("La IA local respondió con error: {}. Uso las reglas.", e.getMessage());
                sinServidorHasta = System.currentTimeMillis() + ESPERA_SIN_SERVIDOR_MS;
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception e) {
                log.warn("Falló el asistente con IA local: {}. Uso las reglas.", e.toString());
                return Optional.empty();
            }
            String r = interpretar(contenido, reglas);
            JsonNode j = leerJson(contenido);
            if (r != null && !r.equals(NO_PUDE) && (j == null || textoDe(j, "orden") == null)) {
                recordar(c, new ClienteLLM.Mensaje("user", texto.trim()));
                recordar(c, new ClienteLLM.Mensaje("assistant", contenido));
            }
            return Optional.ofNullable(r);
        }
    }

    String interpretar(String contenido, Function<String, String> reglas) {
        JsonNode r = leerJson(contenido);
        String orden = r == null ? null : textoDe(r, "orden");
        String respuesta = r == null ? null : textoDe(r, "respuesta");
        if (orden != null) {
            if (soloConsulta.test(orden)) {
                String res = reglas.apply(orden);
                return com.jarvis.core.JarvisService.NO_ENTENDI.equals(res) ? NO_PUDE : res;
            }
            return porConfirmar.proponer(orden);
        }
        if (respuesta == null && r == null && contenido != null && !contenido.isBlank() && !contenido.strip().startsWith("{"))
            respuesta = contenido.strip();
        if (respuesta == null) return null;
        if (AFIRMA_ACCION.matcher(Palabras.norm(respuesta)).find()) {
            log.debug("IA: descarto una respuesta que dice que hizo algo: {}", respuesta);
            return NO_PUDE;
        }
        return respuesta;
    }

    private JsonNode leerJson(String contenido) {
        if (contenido == null) return null;
        int a = contenido.indexOf('{'), b = contenido.lastIndexOf('}');
        if (a < 0 || b <= a) return null;
        try {
            JsonNode n = json.readTree(contenido.substring(a, b + 1));
            return n.isObject() ? n : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String textoDe(JsonNode r, String campo) {
        JsonNode v = r.get(campo);
        if (v == null || v.isNull() || !v.isValueNode()) return null;
        String s = v.asText().strip();
        return s.isEmpty() ? null : s;
    }

    private synchronized Conversacion conversacion() {
        long ahora = System.currentTimeMillis();
        conversaciones.values().removeIf(x -> ahora - x.usada > INACTIVA_MS);
        Conversacion c = conversaciones.computeIfAbsent(ClientSession.id(), k -> new Conversacion());
        c.usada = ahora;
        return c;
    }

    private static void recordar(Conversacion c, ClienteLLM.Mensaje m) {
        c.historial.addLast(m);
        while (c.historial.size() > MAX_HISTORIAL) c.historial.removeFirst();
    }

    String sistema() {
        String n = negocio.isEmpty() ? "un negocio" : negocio;
        return """
                Eres J.A.R.V.I.S., el asistente de inventario y ventas de %s. Tratas al usuario de usted y lo llamas "%s". \
                Respondes SIEMPRE con un objeto JSON y nada más, de una de estas dos formas:
                {"orden": "..."}  cuando el usuario quiere hacer o consultar algo del inventario o la caja.
                {"respuesta": "..."}  cuando solo conversa, saluda o pide un consejo.

                La "orden" es una frase corta en el idioma de JARVIS. Ejemplos de órdenes válidas:
                "vendí 2 polera roja M" | "vendí 1 gorra negra con nota de venta" | "agrega 10 polera roja M a 25 soles" |
                "cuántas tengo de zapatilla nike 40" | "cuánto cuesta la polera roja M" | "lista de precios" | "inventario" |
                "cambia el precio de la polera roja M a 30" | "stock mínimo de la polera roja M en 5" |
                "último comprobante" | "anula la última venta" | "cierre de caja" | "nueva venta" | "cobrar" |
                "elimina el producto polera roja M" | "nuevo inventario" | "anota llamar al proveedor" |
                "aprende que chompa significa polera".
                Usa el nombre exacto del producto de la lista. Escribe los números con cifras. \
                Una orden por respuesta. Nunca inventes productos ni cantidades que el usuario no dijo.
                Tú no puedes vender, registrar ni cambiar nada: si el usuario pide hacer algo, responde con una orden. \
                Nunca digas que ya hiciste algo.

                La "respuesta" se lee en voz alta: en español, una a tres frases cortas, sin markdown ni emojis. \
                Montos en soles ("25.50 soles"). Usa solo los datos de abajo; si no sabes algo, dilo.

                Productos (nombre | stock | precio):
                %s""".formatted(n, owner, productos());
    }

    private String productos() {
        List<Producto> ps = inv.productos();
        if (ps.isEmpty()) return "(el inventario está vacío)";
        StringBuilder sb = new StringBuilder();
        List<Producto> orden = new ArrayList<>(ps);
        orden.sort(Comparator.comparing((Producto p) -> !p.stockBajo()));
        for (Producto p : orden.subList(0, Math.min(MAX_PRODUCTOS, orden.size()))) {
            sb.append("- ").append(p.nombre).append(" | ").append(p.stock.toPlainString()).append(' ').append(p.unidad)
                    .append(p.stockBajo() ? " (stock bajo)" : "").append(" | ").append(p.precio.toPlainString()).append(" soles\n");
        }
        if (ps.size() > MAX_PRODUCTOS) sb.append("(y ").append(ps.size() - MAX_PRODUCTOS).append(" productos más)\n");
        return sb.toString().trim();
    }
}
