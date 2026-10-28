/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.api;

import com.jarvis.core.JarvisService;
import com.jarvis.inventory.InventoryService;
import com.jarvis.skills.GreetingSkill;
import com.jarvis.system.SystemMonitor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin
public class JarvisController {

    private final JarvisService jarvis;
    private final SystemMonitor monitor;
    private final InventoryService inv;
    private final GreetingSkill saludo;
    private final int port;
    private final Map<String, String> negocio;
    private final String nombreNegocio;

    public JarvisController(JarvisService jarvis, SystemMonitor monitor, InventoryService inv, GreetingSkill saludo,
                            @Value("${server.port:8081}") int port,
                            @Value("${jarvis.negocio:}") String negocio,
                            @Value("${jarvis.negocio.nombre:}") String nombre,
                            @Value("${jarvis.negocio.ruc:}") String ruc,
                            @Value("${jarvis.negocio.direccion:}") String direccion) {
        this.jarvis = jarvis;
        this.monitor = monitor;
        this.inv = inv;
        this.saludo = saludo;
        this.port = port;
        this.nombreNegocio = negocio.isBlank() ? nombre.trim() : negocio.trim();
        this.negocio = Map.of("nombre", nombreNegocio.isEmpty() ? "Mi Negocio" : nombreNegocio, "ruc", ruc, "direccion", direccion);
    }

    public record CommandRequest(String text, Boolean voz) {}

    @PostMapping("/command")
    public Map<String, Object> command(@RequestBody CommandRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reply", jarvis.process(req.text(), Boolean.TRUE.equals(req.voz())));
        InventoryService.Comprobante c = inv.tomarComprobanteNuevo();
        if (c != null) body.put("recibo", Map.of("comprobante", c, "negocio", negocio));
        body.put("carrito", inv.carritoActual());
        return body;
    }

    @GetMapping("/bienvenida")
    public Map<String, String> bienvenida() {
        return Map.of("reply", saludo.bienvenida());
    }

    @GetMapping("/system")
    public Map<String, Object> system() {
        Map<String, Object> data = new LinkedHashMap<>(monitor.snapshot());
        data.put("port", port);
        data.put("negocio", nombreNegocio);
        data.put("negocioRuc", negocio.get("ruc"));
        data.put("negocioDireccion", negocio.get("direccion"));
        return data;
    }

    @GetMapping("/status")
    public Map<String, String> status() {
        return Map.of("status", "online");
    }
}
