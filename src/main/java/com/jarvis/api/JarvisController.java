package com.jarvis.api;

import com.jarvis.core.JarvisService;
import com.jarvis.inventory.InventoryService;
import com.jarvis.system.SystemMonitor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * API REST: cualquier dispositivo (navegador, app Android, otro programa) habla con JARVIS aquí.
 *
 *   POST /api/command   { "text": "qué hora es" }   cabecera  X-Jarvis-Token: <tu clave>
 *   -> { "reply": "Son las 9:30 p. m." }
 *   Si la orden fue una venta, además trae "recibo": el comprobante de pago para mostrar/imprimir,
 *   y siempre "carrito": la venta con varios productos en curso (null si no hay).
 *
 *   GET  /api/system    (misma cabecera) -> CPU, RAM, disco, IP… para los paneles de la HUD
 */
@RestController
@RequestMapping("/api")
@CrossOrigin
public class JarvisController {

    private final JarvisService jarvis;
    private final SystemMonitor monitor;
    private final InventoryService inv;
    private final String token;
    private final int port;
    private final Map<String, String> negocio;

    public JarvisController(JarvisService jarvis, SystemMonitor monitor, InventoryService inv,
                            @Value("${jarvis.token}") String token,
                            @Value("${server.port:8080}") int port,
                            @Value("${jarvis.negocio.nombre:Mi Negocio}") String nombre,
                            @Value("${jarvis.negocio.ruc:}") String ruc,
                            @Value("${jarvis.negocio.direccion:}") String direccion) {
        this.jarvis = jarvis;
        this.monitor = monitor;
        this.inv = inv;
        this.token = token;
        this.port = port;
        this.negocio = Map.of("nombre", nombre, "ruc", ruc, "direccion", direccion);
    }

    public record CommandRequest(String text) {}

    @PostMapping("/command")
    public ResponseEntity<Map<String, Object>> command(@RequestHeader(value = "X-Jarvis-Token", required = false) String t,
                                                       @RequestBody CommandRequest req) {
        if (!token.equals(t)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("reply", "Acceso denegado. Clave incorrecta."));
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("reply", jarvis.process(req.text()));
        InventoryService.Comprobante c = inv.tomarComprobanteNuevo();
        if (c != null) body.put("recibo", Map.of("comprobante", c, "negocio", negocio));
        body.put("carrito", inv.carritoActual());   // venta en curso (null si no hay)
        return ResponseEntity.ok(body);
    }

    @GetMapping("/system")
    public ResponseEntity<Map<String, Object>> system(@RequestHeader(value = "X-Jarvis-Token", required = false) String t) {
        if (!token.equals(t)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        Map<String, Object> data = new java.util.LinkedHashMap<>(monitor.snapshot());
        data.put("port", port);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/status")
    public Map<String, String> status() {
        return Map.of("status", "online");
    }
}
