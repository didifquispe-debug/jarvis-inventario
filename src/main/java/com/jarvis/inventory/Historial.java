package com.jarvis.inventory;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Archivos de historial junto al inventario, uno por mes y en formato JSON Lines (un objeto por
 * línea). Solo se AÑADEN líneas: nunca se reescribe un archivo grande y un corte de luz como
 * mucho deja a medias la última línea, que al leer se ignora.
 * <ul>
 *   <li>{@code movimientos-AAAA-MM.jsonl}: auditoría de todo lo que cambia el inventario.</li>
 *   <li>{@code ventas-AAAA-MM.jsonl}: ventas antiguas que salen del archivo principal para que no crezca sin límite.</li>
 * </ul>
 */
class Historial {

    private static final Logger log = LoggerFactory.getLogger(Historial.class);

    private final Path dir;
    private final ObjectMapper json = new ObjectMapper().enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    Historial(Path dir) { this.dir = dir; }

    Path dir() { return dir; }

    /** Añade un movimiento. Si falla, solo se avisa en el log: la operación ya se hizo. */
    void registrar(Movimiento m) {
        try {
            anadir("movimientos-" + mes(m.fecha()) + ".jsonl", List.of(json.writeValueAsString(m)));
        } catch (Exception e) {
            log.error("No pude escribir el historial de movimientos en {}: {}", dir, e.toString());
        }
    }

    /** Los últimos {@code n} movimientos, del más reciente al más antiguo. */
    List<Movimiento> ultimos(int n) {
        List<Movimiento> out = new ArrayList<>();
        if (n <= 0 || !Files.isDirectory(dir)) return out;
        List<Path> archivos;
        try (Stream<Path> s = Files.list(dir)) {
            archivos = s.filter(p -> p.getFileName().toString().matches("movimientos-\\d{4}-\\d{2}\\.jsonl"))
                    .sorted(Collections.reverseOrder()).toList();
        } catch (IOException e) {
            return out;
        }
        for (Path p : archivos) {
            try {
                List<String> lineas = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = lineas.size() - 1; i >= 0 && out.size() < n; i--) {
                    try {
                        if (!lineas.get(i).isBlank()) out.add(json.readValue(lineas.get(i), Movimiento.class));
                    } catch (Exception ignored) { /* línea a medias por un corte: se salta */ }
                }
            } catch (IOException e) {
                log.warn("No pude leer {}: {}", p, e.getMessage());
            }
            if (out.size() >= n) break;
        }
        return out;
    }

    /** Copia las ventas al archivo del mes de cada una. Lanza excepción si no pudo (y entonces no se deben borrar). */
    void archivarVentas(List<InventoryService.Venta> ventas) throws IOException {
        Map<String, List<String>> porMes = new TreeMap<>();
        for (InventoryService.Venta v : ventas)
            porMes.computeIfAbsent(mes(v.fecha), k -> new ArrayList<>()).add(json.writeValueAsString(v));
        for (Map.Entry<String, List<String>> e : porMes.entrySet()) anadir("ventas-" + e.getKey() + ".jsonl", e.getValue());
    }

    private void anadir(String archivo, List<String> lineas) throws IOException {
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder();
        for (String l : lineas) sb.append(l).append('\n');
        Files.writeString(dir.resolve(archivo), sb, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
    }

    /** "2026-10-21..." → "2026-10" (fechas ISO). */
    private static String mes(String fechaIso) {
        return fechaIso != null && fechaIso.length() >= 7 ? fechaIso.substring(0, 7) : "sin-fecha";
    }
}
