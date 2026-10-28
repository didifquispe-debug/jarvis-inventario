/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.api;

import com.jarvis.inventory.Clientes;
import com.jarvis.inventory.InventoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/clientes")
@CrossOrigin
public class ClientesController {

    private final Clientes clientes;

    public ClientesController(InventoryService inv) {
        this.clientes = inv.clientes();
    }

    @GetMapping
    public List<Clientes.Cliente> buscar(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "50") int limite) {
        return clientes.buscar(q, limite);
    }

    @GetMapping("/{documento}")
    public ResponseEntity<?> uno(@PathVariable String documento) {
        return clientes.porDocumento(documento).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("ok", false, "reply", "No encontré un cliente con el documento " + documento + ".")));
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> crear(@RequestBody Clientes.Datos datos) {
        return InventoryController.responder(clientes.crear(datos));
    }

    @PatchMapping("/{documento}")
    public ResponseEntity<Map<String, Object>> editar(@PathVariable String documento, @RequestBody Clientes.Datos datos) {
        return InventoryController.responder(clientes.editar(documento, datos));
    }

    @DeleteMapping("/{documento}")
    public ResponseEntity<Map<String, Object>> eliminar(@PathVariable String documento) {
        return InventoryController.responder(clientes.eliminar(documento));
    }
}
