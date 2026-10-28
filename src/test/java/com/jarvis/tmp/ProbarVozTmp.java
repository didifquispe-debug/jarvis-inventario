/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.tmp;

import com.jarvis.voz.VozLocal;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

class ProbarVozTmp {
    @Test void probar() throws Exception {
        VozLocal voz = new VozLocal(true, System.getProperty("user.home") + "/jarvis-models/vosk-es", "auto");
        voz.iniciar();
        while (voz.cargando()) Thread.sleep(50);
        Set<String> vocab = new TreeSet<>();
        for (String l : new String(getClass().getResourceAsStream("/voz-vocabulario.txt").readAllBytes(), StandardCharsets.UTF_8).split("\n"))
            if (!l.startsWith("#") && !l.isBlank()) vocab.add(l.trim());
        vocab.addAll(List.of("cemento", "foco", "focos", "led", "disco", "discos", "corte", "pantalón", "pantalones", "polo", "polos", "bolsa", "bolsas"));
        Path dir = Path.of(System.getProperty("vozdir"));
        try (var s = Files.list(dir)) {
            for (Path p : s.sorted().toList()) {
                byte[] w = Files.readAllBytes(p);
                int i = 12; byte[] d = null;
                while (i + 8 <= w.length) {
                    String id = new String(w, i, 4);
                    int n = (w[i+4]&255) | (w[i+5]&255)<<8 | (w[i+6]&255)<<16 | (w[i+7]&255)<<24;
                    if (id.equals("data")) { d = Arrays.copyOfRange(w, i+8, Math.min(w.length, i+8+n)); break; }
                    i += 8 + n + (n & 1);
                }
                long t = System.currentTimeMillis();
                String r = voz.reconocer(d, vocab) + "   | general: " + voz.reconocer(d);
                long t2 = System.currentTimeMillis() - t;
                Files.writeString(Path.of(System.getProperty("vozdir") + "-out.txt"), p.getFileName() + " (" + t2 + " ms) = " + r + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        }
        voz.cerrar();
    }
}
