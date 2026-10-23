package com.jarvis.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Importe en letras para la leyenda "SON: ... SOLES" del comprobante. Adaptado del sistema de referencia. */
public final class NumeroALetras {

    private static final String[] UNIDADES = {"", "UNO", "DOS", "TRES", "CUATRO", "CINCO", "SEIS", "SIETE",
            "OCHO", "NUEVE", "DIEZ", "ONCE", "DOCE", "TRECE", "CATORCE", "QUINCE", "DIECISEIS", "DIECISIETE",
            "DIECIOCHO", "DIECINUEVE", "VEINTE", "VEINTIUNO", "VEINTIDOS", "VEINTITRES", "VEINTICUATRO",
            "VEINTICINCO", "VEINTISEIS", "VEINTISIETE", "VEINTIOCHO", "VEINTINUEVE"};
    private static final String[] DECENAS = {"", "", "", "TREINTA", "CUARENTA", "CINCUENTA", "SESENTA",
            "SETENTA", "OCHENTA", "NOVENTA"};
    private static final String[] CENTENAS = {"", "CIENTO", "DOSCIENTOS", "TRESCIENTOS", "CUATROCIENTOS",
            "QUINIENTOS", "SEISCIENTOS", "SETECIENTOS", "OCHOCIENTOS", "NOVECIENTOS"};

    private NumeroALetras() {}

    /** 1250.50 → "MIL DOSCIENTOS CINCUENTA CON 50/100 SOLES". */
    public static String importe(BigDecimal valor) {
        BigDecimal redondeado = valor.setScale(2, RoundingMode.HALF_UP);
        long entero = redondeado.longValue();
        int centimos = redondeado.remainder(BigDecimal.ONE).movePointRight(2).abs().intValue();
        String letras = entero == 0 ? "CERO" : convertir(entero);
        return letras + " CON " + String.format("%02d", centimos) + "/100 SOLES";
    }

    static String convertir(long n) {
        if (n >= 1_000_000) {
            long millones = n / 1_000_000;
            String prefijo = millones == 1 ? "UN MILLON" : apocopar(convertir(millones)) + " MILLONES";
            long resto = n % 1_000_000;
            return resto == 0 ? prefijo : prefijo + " " + convertir(resto);
        }
        if (n >= 1000) {
            long miles = n / 1000;
            String prefijo = miles == 1 ? "MIL" : apocopar(convertir(miles)) + " MIL";
            long resto = n % 1000;
            return resto == 0 ? prefijo : prefijo + " " + convertir(resto);
        }
        return menorQueMil((int) n);
    }

    private static String menorQueMil(int n) {
        if (n == 100) return "CIEN";
        int resto = n % 100;
        String decena = resto < 30 ? UNIDADES[resto] : DECENAS[resto / 10] + (resto % 10 == 0 ? "" : " Y " + UNIDADES[resto % 10]);
        return (CENTENAS[n / 100] + " " + decena).trim();
    }

    /** "UNO" delante de MIL/MILLONES se dice "UN" (veintiún mil, treinta y un mil). */
    private static String apocopar(String texto) {
        return texto.endsWith("UNO") ? texto.substring(0, texto.length() - 1) : texto;
    }
}
