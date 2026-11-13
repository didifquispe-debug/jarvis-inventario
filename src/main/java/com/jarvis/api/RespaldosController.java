/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.api;

import com.jarvis.core.DatosNegocio;
import com.jarvis.inventory.DYSAInventoryService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Copias de seguridad del inventario: ver, hacer, descargar y restaurar. Solo el administrador (ver ApazaApiAuthFilter). */
@RestController
@RequestMapping("/api/respaldos")
public class RespaldosController {

    private final DYSAInventoryService inv;
    private final DatosNegocio negocio;

    public RespaldosController(DYSAInventoryService inv, DatosNegocio negocio) {
        this.inv = inv;
        this.negocio = negocio;
    }

    public record Pedido(String nombre) {}

    public record Carpeta(String carpeta) {}

    @GetMapping
    public ResponseEntity<Map<String, Object>> listar() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            body.put("respaldos", inv.respaldos());
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("ok", false, "reply", "No pude leer la carpeta de respaldos: " + e.getMessage()));
        }
        body.put("carpeta", inv.archivoBaseDatos().toAbsolutePath().getParent().toString());
        body.put("carpetaExtra", inv.carpetaExtra());
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> hacer() {
        return InventoryController.responder(inv.respaldarAhora());
    }

    @PostMapping("/restaurar")
    public ResponseEntity<Map<String, Object>> restaurar(@RequestBody(required = false) Pedido req) {
        DYSAInventoryService.Resultado r = inv.restaurar(req == null ? null : req.nombre());
        if (r.ok()) negocio.recargar();
        return InventoryController.responder(r);
    }

    @PutMapping("/carpeta")
    public ResponseEntity<Map<String, Object>> carpeta(@RequestBody(required = false) Carpeta req) {
        return InventoryController.responder(inv.usarCarpetaExtra(req == null ? "" : req.carpeta()));
    }

    @GetMapping("/descargar")
    public ResponseEntity<?> descargar(@RequestParam String nombre) throws IOException {
        Path p = inv.archivoRespaldo(nombre);
        if (p == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("ok", false, "reply", "No existe ese respaldo."));
        String archivo = "jarvis-" + p.getFileName().toString().replaceAll("^.*?\\.(respaldo|antes)", "$1") + ".db";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(archivo, StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(p));
    }
}
