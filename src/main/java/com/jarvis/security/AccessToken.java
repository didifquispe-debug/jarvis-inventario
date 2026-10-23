package com.jarvis.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * La clave de acceso que deben enviar los dispositivos (cabecera X-Jarvis-Token).
 *
 * <p>Si en application.properties sigue la clave de fábrica ("cambia-esta-clave") o está vacía,
 * JARVIS NO arranca con ella: genera una clave aleatoria, la guarda en {@code jarvis.token-file}
 * (por defecto ~/.jarvis-clave) y la muestra una sola vez en el log y en la bandeja. En los
 * siguientes arranques reutiliza la guardada, así el celular no tiene que volver a escribirla.</p>
 */
@Component
public class AccessToken {

    private static final Logger log = LoggerFactory.getLogger(AccessToken.class);

    public static final String CLAVE_DE_FABRICA = "cambia-esta-clave";
    public static final String ARCHIVO_POR_DEFECTO = "${user.home}/.jarvis-clave";

    // Sin 0/o, 1/l/i: la clave se dicta o se copia a mano en el celular
    private static final String ALFABETO = "abcdefghjkmnpqrstuvwxyz23456789";
    private static final int LARGO_MINIMO_GUARDADA = 12;

    private final String value;
    private final boolean generada;

    public AccessToken(@Value("${jarvis.token:}") String configured,
                       @Value("${jarvis.token-file:" + ARCHIVO_POR_DEFECTO + "}") String archivo) {
        String conf = configured == null ? "" : configured.trim();
        if (esPropia(conf)) {
            if (conf.length() < 8) log.warn("La clave jarvis.token es muy corta ({} caracteres). Use al menos 12.", conf.length());
            this.value = conf;
            this.generada = false;
            return;
        }
        Path ruta = Paths.get(archivo);
        String guardada = leerGuardada(ruta);
        if (guardada != null) {
            log.info("Usando la clave de acceso guardada en {} (jarvis.token sigue con el valor de fábrica).", ruta);
            this.value = guardada;
            this.generada = false;
            return;
        }
        this.value = generar();
        this.generada = true;
        boolean persistida = guardar(ruta, value);
        // Única vez que la clave aparece en el log: al crearla.
        log.warn("\n==================================================================\n"
                + "  JARVIS generó una CLAVE DE ACCESO nueva (la de fábrica no es segura):\n\n"
                + "      {}\n\n"
                + "  Escríbala en el celular cuando la pida. {}\n"
                + "  Para usar una propia, ponga jarvis.token=... en application.properties.\n"
                + "==================================================================",
                value, persistida ? "Quedó guardada en " + ruta + "." : "No pude guardarla: cambiará al reiniciar.");
    }

    public String value() { return value; }

    /** true si la clave se acaba de crear en este arranque (para mostrarla una vez en la bandeja). */
    public boolean generada() { return generada; }

    /** ¿La clave recibida es la correcta? Comparación en tiempo constante. */
    public boolean matches(String candidate) {
        return !value.isEmpty() && constantTimeEquals(value, candidate);
    }

    /**
     * Compara los SHA-256 de ambas claves con MessageDigest.isEqual: el tiempo no depende de
     * cuántos caracteres coinciden (evita adivinar la clave letra a letra midiendo tiempos)
     * y, al comparar hashes de 32 bytes, tampoco revela el largo de la clave.
     */
    static boolean constantTimeEquals(String expected, String candidate) {
        if (candidate == null) candidate = "";
        return MessageDigest.isEqual(sha256(expected), sha256(candidate));
    }

    /**
     * La clave que usaría el servidor, sin generar una nueva. La usa {@code main} para abrir la
     * ventana cuando JARVIS ya está encendido. Devuelve "" si aún no hay clave.
     */
    public static String resolverSinGenerar(String configured, String archivo) {
        String conf = configured == null ? "" : configured.trim();
        if (esPropia(conf)) return conf;
        String guardada = leerGuardada(Paths.get(archivo));
        return guardada == null ? "" : guardada;
    }

    private static boolean esPropia(String conf) {
        return !conf.isEmpty() && !conf.equals(CLAVE_DE_FABRICA);
    }

    private static String leerGuardada(Path ruta) {
        try {
            if (!Files.isRegularFile(ruta)) return null;
            String s = Files.readString(ruta, StandardCharsets.UTF_8).trim();
            return s.length() >= LARGO_MINIMO_GUARDADA ? s : null;
        } catch (Exception e) {
            log.warn("No pude leer la clave guardada en {}: {}", ruta, e.getMessage());
            return null;
        }
    }

    private static boolean guardar(Path ruta, String clave) {
        try {
            if (ruta.getParent() != null) Files.createDirectories(ruta.getParent());
            Files.writeString(ruta, clave + System.lineSeparator(), StandardCharsets.UTF_8);
            return true;
        } catch (Exception e) {
            log.error("No pude guardar la clave en {}: {}", ruta, e.getMessage());
            return false;
        }
    }

    /** 4 grupos de 4 caracteres (31^16 ≈ 2^79 combinaciones; con el límite de 10 intentos/min es inviable adivinarla). */
    static String generar() {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0 && i % 4 == 0) sb.append('-');
            sb.append(ALFABETO.charAt(rnd.nextInt(ALFABETO.length())));
        }
        return sb.toString();
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 viene en todo JDK
        }
    }
}
