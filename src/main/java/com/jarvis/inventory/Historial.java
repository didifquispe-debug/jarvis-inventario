/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

final class Historial {

    private static final Logger log = LoggerFactory.getLogger(Historial.class);

    private final Path dir;
    private final ObjectMapper json = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    Historial(Path dir) { this.dir = dir; }

    List<Movimiento> movimientos() { return leer("movimientos", Movimiento.class); }

    List<InventoryService.Venta> ventasArchivadas() { return leer("ventas", InventoryService.Venta.class); }

    private <T> List<T> leer(String prefijo, Class<T> tipo) {
        List<T> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        List<Path> archivos;
        try (Stream<Path> s = Files.list(dir)) {
            archivos = s.filter(p -> p.getFileName().toString().matches(prefijo + "-\\d{4}-\\d{2}\\.jsonl")).sorted().toList();
        } catch (IOException e) {
            log.warn("No pude leer el historial antiguo en {}: {}", dir, e.getMessage());
            return out;
        }
        for (Path p : archivos) {
            try {
                for (String linea : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    try {
                        if (!linea.isBlank()) out.add(json.readValue(linea, tipo));
                    } catch (Exception ignorada) { }
                }
            } catch (IOException e) {
                log.warn("No pude leer {}: {}", p, e.getMessage());
            }
        }
        return out;
    }
}
