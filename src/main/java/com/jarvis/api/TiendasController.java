/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.api;

import com.jarvis.inventory.Tiendas;
import com.jarvis.security.ClientSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lista, crea y renombra tiendas. Crear y renombrar es solo del administrador (ver ApazaApiAuthFilter). */
@RestController
@RequestMapping("/api/tiendas")
public class TiendasController {

    private final Tiendas tiendas;

    public TiendasController(Tiendas tiendas) { this.tiendas = tiendas; }

    public record Datos(String nombre) {}

    @GetMapping
    public Map<String, Object> listar() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actual", ClientSession.tienda());
        m.put("tiendas", tiendas.listar());
        return m;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> crear(@RequestBody(required = false) Datos d) {
        return InventoryController.responder(tiendas.crear(d == null ? null : d.nombre()));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> renombrar(@PathVariable int id, @RequestBody(required = false) Datos d) {
        return InventoryController.responder(tiendas.renombrar(id, d == null ? null : d.nombre()));
    }
}
