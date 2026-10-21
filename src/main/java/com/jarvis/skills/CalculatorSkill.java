package com.jarvis.skills;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Calcula expresiones: "cuánto es 25 por 4 más 10". */
@Component
@Order(5)
public class CalculatorSkill implements Skill {

    @Override public String name() { return "Calculadora"; }
    @Override public String example() { return "Cuánto es 25 por 4 más 10"; }

    @Override
    public Optional<String> handle(String cmd, String original) {
        if (!(cmd.startsWith("cuanto es") || cmd.startsWith("calcula"))) return Optional.empty();
        String expr = cmd.replaceFirst("^(cuanto es|calcula)", "")
                .replace("multiplicado por", "*").replace("dividido entre", "/").replace("dividido por", "/")
                .replace(" por ", " * ").replace(" x ", " * ").replace("mas", "+").replace("menos", "-")
                .replace(" entre ", " / ").replace(",", ".").replace("?", "").trim();
        try {
            double r = new Parser(expr).parse();
            String txt = (r == Math.rint(r) && Math.abs(r) < 1e15) ? String.valueOf((long) r) : String.format("%.4f", r);
            return Optional.of("El resultado es " + txt + ".");
        } catch (RuntimeException e) {
            return Optional.of("No pude entender la operación.");
        }
    }

    /** Parser recursivo simple: + - * / ^ y paréntesis. */
    private static class Parser {
        private final String s; private int pos = -1, ch;
        Parser(String s) { this.s = s; next(); }
        void next() { ch = (++pos < s.length()) ? s.charAt(pos) : -1; }
        boolean eat(int c) { while (ch == ' ') next(); if (ch == c) { next(); return true; } return false; }
        double parse() { double x = expr(); if (pos < s.length()) throw new RuntimeException(); return x; }
        double expr() { double x = term(); for (;;) { if (eat('+')) x += term(); else if (eat('-')) x -= term(); else return x; } }
        double term() { double x = factor(); for (;;) { if (eat('*')) x *= factor(); else if (eat('/')) x /= factor(); else return x; } }
        double factor() {
            if (eat('+')) return factor();
            if (eat('-')) return -factor();
            double x; int start = pos;
            if (eat('(')) { x = expr(); eat(')'); }
            else if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.') next();
                x = Double.parseDouble(s.substring(start, pos).trim());
            } else throw new RuntimeException();
            if (eat('^')) x = Math.pow(x, factor());
            return x;
        }
    }
}
