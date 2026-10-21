package com.jarvis.api;

import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.Producto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Devuelve el inventario para el panel de la pantalla. */
@RestController
@RequestMapping("/api/inventory")
@CrossOrigin
public class InventoryController {

    private final InventoryService inv;
    private final String token;

    public InventoryController(InventoryService inv, @Value("${jarvis.token}") String token) {
        this.inv = inv;
        this.token = token;
    }

    @GetMapping
    public ResponseEntity<List<Producto>> list(@RequestHeader(value = "X-Jarvis-Token", required = false) String t) {
        if (!token.equals(t)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(inv.productos());
    }

    /** Venta con varios productos en curso (cuerpo vacío si no hay). */
    @GetMapping("/venta")
    public ResponseEntity<InventoryService.Comprobante> venta(@RequestHeader(value = "X-Jarvis-Token", required = false) String t) {
        if (!token.equals(t)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(inv.carritoActual());
    }
}
