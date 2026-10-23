package com.jarvis.ia;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.*;
import com.jarvis.inventory.InventoryService;
import com.jarvis.security.ClientSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/**
 * Asistente con IA (Claude): entiende lo que el usuario pide con sus propias palabras y lo hace con las
 * herramientas de {@link HerramientasIA}.
 *
 * <p>Modo mixto: solo se usa si hay clave de API (archivo {@code jarvis.ia.clave-archivo} o la variable de entorno
 * ANTHROPIC_API_KEY). Si no hay clave, no hay internet o la API falla, {@link #responder} devuelve vacío y
 * JARVIS sigue con sus reglas de siempre. Tras un corte de red espera un minuto antes de volver a intentarlo,
 * para que cada orden sin internet no tarde en fallar.</p>
 *
 * <p>Cada dispositivo (sesión) tiene su propia conversación; se empieza una nueva tras un rato sin hablar o si se
 * hace muy larga.</p>
 */
@Component
public class AsistenteIA {

    private static final Logger log = LoggerFactory.getLogger(AsistenteIA.class);

    private static final int MAX_PASOS = 12;                  // llamadas a herramientas por orden, como máximo
    private static final int MAX_MENSAJES = 60;               // conversación más larga: se empieza otra
    private static final long INACTIVA_MS = 30 * 60 * 1000L;  // 30 min sin hablar: se empieza otra
    private static final long ESPERA_SIN_RED_MS = 60 * 1000L;

    /** Conversación de una sesión. Solo se agrega al final (nunca se edita lo anterior). */
    private static class Conversacion {
        final List<MessageParam> mensajes = new ArrayList<>();
        long usada;
        long turnos;
    }

    private final HerramientasIA herramientas;
    private final List<Tool> herramientasApi;  // siempre las mismas y en el mismo orden (la API las guarda en caché)
    private final AnthropicClient client;     // null = sin clave: IA apagada
    private final String modelo;
    private final String sistema;
    private final boolean fallbackServidor;
    private final Map<String, Conversacion> conversaciones = new HashMap<>();
    private volatile long sinRedHasta;
    private volatile boolean claveRechazada;

    public AsistenteIA(InventoryService inv, com.jarvis.core.AcercaDeJarvis acercaDe,
                       @Value("${jarvis.ia.enabled:true}") boolean enabled,
                       @Value("${jarvis.ia.modelo:claude-opus-5-5}") String modelo,
                       @Value("${jarvis.ia.clave-archivo:${user.home}/jarvis-ia-clave.txt}") String archivoClave,
                       @Value("${jarvis.ia.fallback-servidor:true}") boolean fallbackServidor,
                       @Value("${jarvis.owner:señor}") String owner,
                       @Value("${jarvis.negocio:}") String negocio) {
        this.herramientas = new HerramientasIA(inv);
        this.herramientasApi = herramientas.definiciones();
        this.modelo = modelo;
        this.fallbackServidor = fallbackServidor;
        this.sistema = sistema(owner, negocio) + "\n\nSobre ti mismo (úsalo cuando te pregunten qué eres, qué puedes hacer o "
                + "cómo se usa algo; responde en pocas frases):\n" + acercaDe.conocimiento();
        String clave = enabled ? clave(archivoClave) : null;
        this.client = clave == null ? null : AnthropicOkHttpClient.builder()
                .apiKey(clave)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(1)
                .build();
        if (!enabled) log.info("Asistente con IA apagado (jarvis.ia.enabled=false): se usan solo las reglas.");
        else if (client == null) log.info("Asistente con IA sin clave: guarde su clave de Anthropic en {} para activarlo. "
                + "Mientras tanto se usan solo las reglas.", archivoClave);
        else log.info("Asistente con IA listo (modelo {}).", modelo);
    }

    /** Clave del archivo (primera línea no vacía) o de ANTHROPIC_API_KEY; null si no hay. Nunca se escribe en el log. */
    private static String clave(String archivo) {
        try {
            Path p = Path.of(archivo);
            if (Files.isRegularFile(p)) {
                for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String c = l.replace("﻿", "").trim();
                    if (!c.isEmpty() && !c.startsWith("#")) return c;
                }
            }
        } catch (Exception e) {
            log.warn("No pude leer el archivo de la clave de IA {}: {}", archivo, e.getMessage());
        }
        String env = System.getenv("ANTHROPIC_API_KEY");
        return env == null || env.isBlank() ? null : env.trim();
    }

    private static String sistema(String owner, String negocio) {
        String n = negocio == null || negocio.isBlank() ? "un negocio" : negocio.trim();
        return """
                Eres J.A.R.V.I.S., el asistente de inventario y ventas de %s. Atiendes al dueño o a su personal; \
                llámalo "%s".

                Tus respuestas se leen en voz alta: responde en español, en una a tres frases cortas, sin markdown, \
                sin listas con viñetas ni emojis. Los montos van en soles ("25.50 soles").

                Haz lo que te pidan con tus herramientas, sin pedir permiso para lo que es claro. Nunca inventes \
                productos, precios, stock ni ventas: consúltalos con ver_productos o ejecutar_orden. Si falta un dato \
                que no puedes deducir (por ejemplo el precio de un producto nuevo), pregúntalo en una sola frase.

                Para vender, registrar mercadería, consultar, comprobantes, cierre de caja, notas y abrir programas \
                usa ejecutar_orden. Para cambios en varios productos (por ejemplo "sube 10%% el precio del calzado"), \
                primero ver_productos, luego un editar_producto por cada uno, y al final resume qué cambió. \
                Cuando una herramienta responda CONFIRMACION_REQUERIDA, haz esa pregunta y espera la respuesta. \
                Cuando una herramienta devuelva un error, explícalo en palabras simples.

                Puedes conversar y aconsejar sobre el negocio (qué reponer, qué se vende más) usando los datos reales."""
                .formatted(n, owner == null || owner.isBlank() ? "señor" : owner.trim());
    }

    /** ¿Hay clave y la API está a mano? (sin red o con la clave rechazada, se usan las reglas) */
    public boolean disponible() {
        return client != null && !claveRechazada && System.currentTimeMillis() >= sinRedHasta;
    }

    /**
     * Responde una orden con la IA. Vacío si la IA no está disponible o falló: entonces JARVIS usa sus reglas.
     *
     * @param reglas el procesador clásico de órdenes (lo usa la herramienta ejecutar_orden)
     */
    public Optional<String> responder(String texto, Function<String, String> reglas) {
        if (!disponible() || texto == null || texto.isBlank()) return Optional.empty();
        Conversacion c = conversacion();
        synchronized (c) {
            int tamañoAntes = c.mensajes.size();
            try {
                return Optional.of(turno(c, texto.trim(), reglas));
            } catch (UnauthorizedException | PermissionDeniedException e) {
                claveRechazada = true;
                log.error("Anthropic rechazó la clave de IA ({}). Revise el archivo de la clave y reinicie JARVIS. "
                        + "Mientras tanto se usan solo las reglas.", e.statusCode());
            } catch (AnthropicIoException e) {
                sinRedHasta = System.currentTimeMillis() + ESPERA_SIN_RED_MS;
                log.warn("Sin conexión con la IA ({}): uso las reglas por un minuto.", e.getMessage());
            } catch (AnthropicServiceException e) {
                log.warn("La IA respondió con error {}: {}. Uso las reglas.", e.statusCode(), e.getMessage());
            } catch (RuntimeException e) {
                log.warn("Falló el asistente con IA: {}. Uso las reglas.", e.toString());
            }
            // la orden no se completó: se descarta este turno a medias para que la conversación siga siendo válida
            while (c.mensajes.size() > tamañoAntes) c.mensajes.remove(c.mensajes.size() - 1);
            return Optional.empty();
        }
    }

    private synchronized Conversacion conversacion() {
        long ahora = System.currentTimeMillis();
        conversaciones.values().removeIf(x -> ahora - x.usada > INACTIVA_MS);
        Conversacion c = conversaciones.computeIfAbsent(ClientSession.id(), k -> new Conversacion());
        if (c.mensajes.size() > MAX_MENSAJES) {
            c = new Conversacion();
            conversaciones.put(ClientSession.id(), c);
        }
        c.usada = ahora;
        return c;
    }

    /** Un turno completo: el mensaje del usuario y todas las herramientas que la IA quiera usar hasta responder. */
    private String turno(Conversacion c, String texto, Function<String, String> reglas) {
        c.turnos++;
        c.mensajes.add(MessageParam.builder().role(MessageParam.Role.USER).content(texto).build());
        for (int paso = 0; paso < MAX_PASOS; paso++) {
            Message r = client.messages().create(peticion(c.mensajes));
            c.mensajes.add(r.toParam());   // tal cual (incluye el razonamiento): no se edita después

            StopReason motivo = r.stopReason().orElse(StopReason.END_TURN);
            if (motivo.equals(StopReason.REFUSAL)) return "Lo siento, señor, no puedo ayudar con eso.";
            List<ToolUseBlock> usos = r.content().stream().flatMap(b -> b.toolUse().stream()).toList();
            if (!motivo.equals(StopReason.TOOL_USE) || usos.isEmpty()) {
                String respuesta = textoDe(r);
                return respuesta.isBlank() ? "Listo, señor." : respuesta;
            }
            List<ContentBlockParam> resultados = new ArrayList<>();
            for (ToolUseBlock uso : usos) {
                String resultado;
                boolean error = false;
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> entrada = uso._input().convert(Map.class);
                    resultado = herramientas.ejecutar(uso.name(), entrada, texto, c.turnos, reglas);
                } catch (RuntimeException e) {
                    resultado = "Error al ejecutar " + uso.name() + ": " + e.getMessage();
                    error = true;
                }
                log.debug("IA usó {} -> {}", uso.name(), resultado);
                resultados.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(uso.id()).content(resultado).isError(error).build()));
            }
            c.mensajes.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(resultados).build());
        }
        return "Hice varios pasos pero no terminé, señor. ¿Puede decirlo de otra forma?";
    }

    private MessageCreateParams peticion(List<MessageParam> mensajes) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(modelo)
                .maxTokens(8000L)
                .system(sistema)
                .messages(mensajes)
                // respuestas rápidas: es una caja registradora, no un problema de matemáticas
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .cacheControl(CacheControlEphemeral.builder().build());
        herramientasApi.forEach(b::addTool);
        if (fallbackServidor) {
            // si el modelo declina una petición por sus filtros de seguridad, la API la reintenta con otro modelo
            b.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return b.build();
    }

    private static String textoDe(Message r) {
        StringBuilder sb = new StringBuilder();
        r.content().forEach(b -> b.text().ifPresent(t -> sb.append(t.text())));
        return sb.toString().trim();
    }
}
