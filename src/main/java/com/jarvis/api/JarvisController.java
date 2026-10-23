package com.jarvis.api;

import com.jarvis.core.JarvisService;
import com.jarvis.inventory.InventoryService;
import com.jarvis.system.SystemMonitor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * API REST: cualquier dispositivo (navegador, app Android, otro programa) habla con JARVIS aquí.
 *
 *   POST /api/command   { "text": "qué hora es" }   cabecera  X-Jarvis-Token: <tu clave>
 *   -> { "reply": "Son las 9:30 p. m." }
 *   Si la orden fue una venta, además trae "recibo": el comprobante de pago para mostrar/imprimir,
 *   y siempre "carrito": la venta con varios productos en curso (null si no hay).
 *
 *   GET  /api/system    (misma cabecera) -> CPU, RAM, disco, IP… para los paneles de la HUD,
 *                       y "negocio" (jarvis.negocio) para el título de la pantalla
 *
 * La clave la comprueba ApiAuthFilter antes de llegar aquí.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin
public class JarvisController {

    private final JarvisService jarvis;
    private final SystemMonitor monitor;
    private final InventoryService inv;
    private final int port;
    private final Map<String, String> negocio;
    private final String nombreNegocio;   // vacío si no se configuró: la pantalla muestra solo JARVIS

    public JarvisController(JarvisService jarvis, SystemMonitor monitor, InventoryService inv,
                            @Value("${server.port:8081}") int port,
                            @Value("${jarvis.negocio:}") String negocio,
                            @Value("${jarvis.negocio.nombre:}") String nombre,
                            @Value("${jarvis.negocio.ruc:}") String ruc,
                            @Value("${jarvis.negocio.direccion:}") String direccion) {
        this.jarvis = jarvis;
        this.monitor = monitor;
        this.inv = inv;
        this.port = port;
        // jarvis.negocio.nombre se mantiene por compatibilidad con configuraciones antiguas
        this.nombreNegocio = negocio.isBlank() ? nombre.trim() : negocio.trim();
        this.negocio = Map.of("nombre", nombreNegocio.isEmpty() ? "Mi Negocio" : nombreNegocio, "ruc", ruc, "direccion", direccion);
    }

    public record CommandRequest(String text) {}

    @PostMapping("/command")
    public Map<String, Object> command(@RequestBody CommandRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reply", jarvis.process(req.text()));
        InventoryService.Comprobante c = inv.tomarComprobanteNuevo();
        if (c != null) body.put("recibo", Map.of("comprobante", c, "negocio", negocio));
        body.put("carrito", inv.carritoActual());   // venta en curso (null si no hay)
        return body;
    }

    @GetMapping("/system")
    public Map<String, Object> system() {
        Map<String, Object> data = new LinkedHashMap<>(monitor.snapshot());
        data.put("port", port);
        data.put("negocio", nombreNegocio);
        return data;
    }

    /** Público (sin clave): solo sirve para saber si JARVIS ya está encendido. */
    @GetMapping("/status")
    public Map<String, String> status() {
        return Map.of("status", "online");
    }
}
