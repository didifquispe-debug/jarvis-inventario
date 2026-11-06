/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.api;

import com.jarvis.core.DatosNegocio;
import com.jarvis.core.JarvisService;
import com.jarvis.inventory.InventoryService;
import com.jarvis.skills.GreetingSkill;
import com.jarvis.system.SystemMonitor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
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
    private final DatosNegocio negocio;

    public JarvisController(JarvisService jarvis, SystemMonitor monitor, InventoryService inv, GreetingSkill saludo,
                            @Value("${server.port:8081}") int port, DatosNegocio negocio) {
        this.jarvis = jarvis;
        this.monitor = monitor;
        this.inv = inv;
        this.saludo = saludo;
        this.port = port;
        this.negocio = negocio;
    }

    public record CommandRequest(String text, Boolean voz) {}

    @PostMapping("/command")
    public Map<String, Object> command(@RequestBody CommandRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reply", jarvis.process(req.text(), Boolean.TRUE.equals(req.voz())));
        InventoryService.Comprobante c = inv.tomarComprobanteNuevo();
        if (c != null) body.put("recibo", Map.of("comprobante", c, "negocio", negocio.paraRecibo()));
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
        DatosNegocio.Datos d = negocio.datos();
        data.put("negocio", d.nombre());
        data.put("negocioRuc", d.ruc());
        data.put("negocioDireccion", d.direccion());
        data.put("monedaSimbolo", d.monedaSimbolo());
        data.put("monedaNombre", d.monedaNombre());
        return data;
    }

    @GetMapping("/negocio")
    public DatosNegocio.Datos datosNegocio() {
        return negocio.datos();
    }

    @PutMapping("/negocio")
    public ResponseEntity<Map<String, Object>> guardarNegocio(@RequestBody DatosNegocio.Datos datos) {
        return InventoryController.responder(negocio.guardar(datos));
    }

    @GetMapping("/status")
    public Map<String, String> status() {
        return Map.of("status", "online");
    }
}
