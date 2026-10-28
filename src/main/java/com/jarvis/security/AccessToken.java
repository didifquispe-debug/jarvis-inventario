/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
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

@Component
public class AccessToken {

    private static final Logger log = LoggerFactory.getLogger(AccessToken.class);

    public static final String CLAVE_DE_FABRICA = "cambia-esta-clave";
    public static final String ARCHIVO_POR_DEFECTO = "${user.home}/.jarvis-clave";

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
        log.warn("\n==================================================================\n"
                + "  JARVIS generó una CLAVE DE ACCESO nueva (la de fábrica no es segura):\n\n"
                + "      {}\n\n"
                + "  Escríbala en el celular cuando la pida. {}\n"
                + "  Para usar una propia, ponga jarvis.token=... en application.properties.\n"
                + "==================================================================",
                value, persistida ? "Quedó guardada en " + ruta + "." : "No pude guardarla: cambiará al reiniciar.");
    }

    public String value() { return value; }

    public boolean generada() { return generada; }

    public boolean matches(String candidate) {
        return !value.isEmpty() && constantTimeEquals(value, candidate);
    }

    static boolean constantTimeEquals(String expected, String candidate) {
        if (candidate == null) candidate = "";
        return MessageDigest.isEqual(sha256(expected), sha256(candidate));
    }

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
            throw new IllegalStateException(e);
        }
    }
}
