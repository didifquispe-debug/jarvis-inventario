package com.jarvis.api;

import com.jarvis.inventory.Compras;
import com.jarvis.inventory.InventoryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proveedores y compras. La clave la comprueba ApiAuthFilter.
 *
 *   GET    /api/proveedores?q=andina&limite=50    -> proveedores (por RUC si q son dígitos, si no por palabras de la razón social)
 *   GET    /api/proveedores/{ruc}                 -> un proveedor (404 si no existe)
 *   POST   /api/proveedores                       { "ruc": "20100047218", "razonSocial": "…", "direccion": "…", "telefono": "…" }
 *   PATCH  /api/proveedores/{ruc}                 { solo lo que cambia; "" borra dirección o teléfono }
 *   DELETE /api/proveedores/{ruc}                 (400 si tiene compras)
 *
 *   GET    /api/compras?q=&desde=AAAA-MM-DD&hasta=AAAA-MM-DD&limite=100  -> { compras: […], totales: {compras, base, igv, total} }
 *   GET    /api/compras/{codigo}                  -> la compra con su detalle (CP-00000012 o 12)
 *   POST   /api/compras                           { "ruc": "20100047218", "razonSocial": "…(solo si el RUC es nuevo)",
 *                                                    "tipoDocumento": "factura", "numeroDocumento": "F001-452", "fechaDocumento": "2026-10-20",
 *                                                    "igv": "incluido", "observacion": "…",
 *                                                    "items": [ { "producto": "Polera roja M", "cantidad": 24, "costo": 18.5 } ] }
 *          -> { ok, reply, compra }   (suma stock y recalcula el costo promedio; nombres de producto exactos)
 *   POST   /api/compras/{codigo}/anular           (400 si parte de la mercadería ya se vendió)
 */
@RestController
@CrossOrigin
public class ComprasController {

    private final InventoryService inv;
    private final Compras compras;

    public ComprasController(InventoryService inv) {
        this.inv = inv;
        this.compras = inv.compras();
    }

    // ---------------------------------------------------------------- proveedores

    @GetMapping("/api/proveedores")
    public List<Compras.Proveedor> proveedores(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "50") int limite) {
        return compras.buscarProveedores(q, limite);
    }

    @GetMapping("/api/proveedores/{ruc}")
    public ResponseEntity<?> proveedor(@PathVariable String ruc) {
        return compras.proveedor(ruc).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> noExiste("No encontré un proveedor con el RUC " + ruc + "."));
    }

    @PostMapping("/api/proveedores")
    public ResponseEntity<Map<String, Object>> crearProveedor(@RequestBody Compras.DatosProveedor datos) {
        return InventoryController.responder(compras.crearProveedor(datos));
    }

    @PatchMapping("/api/proveedores/{ruc}")
    public ResponseEntity<Map<String, Object>> editarProveedor(@PathVariable String ruc, @RequestBody Compras.DatosProveedor datos) {
        return InventoryController.responder(compras.editarProveedor(ruc, datos));
    }

    @DeleteMapping("/api/proveedores/{ruc}")
    public ResponseEntity<Map<String, Object>> eliminarProveedor(@PathVariable String ruc) {
        return InventoryController.responder(compras.eliminarProveedor(ruc));
    }

    // ---------------------------------------------------------------- compras

    @GetMapping("/api/compras")
    public Map<String, Object> listar(@RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "") String desde,
                                      @RequestParam(defaultValue = "") String hasta, @RequestParam(defaultValue = "100") int limite) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("compras", compras.listar(q, desde, hasta, limite));
        body.put("totales", compras.totalizar(q, desde, hasta));
        return body;
    }

    @GetMapping("/api/compras/{codigo}")
    public ResponseEntity<?> compra(@PathVariable String codigo) {
        return compras.obtener(codigo).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> noExiste("No encontré la compra " + codigo + "."));
    }

    @PostMapping("/api/compras")
    public ResponseEntity<Map<String, Object>> registrar(@RequestBody Compras.NuevaCompra req) {
        InventoryService.Resultado r = inv.registrarCompra(req);
        ResponseEntity<Map<String, Object>> res = InventoryController.responder(r);
        if (!r.ok()) return res;
        Map<String, Object> body = new LinkedHashMap<>(res.getBody());
        compras.listar("", "", "", 1).stream().findFirst()
                .flatMap(c -> compras.obtener(c.codigo())).ifPresent(c -> body.put("compra", c));
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/compras/{codigo}/anular")
    public ResponseEntity<Map<String, Object>> anular(@PathVariable String codigo) {
        return InventoryController.responder(inv.anularCompra(codigo));
    }

    private static ResponseEntity<?> noExiste(String mensaje) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("ok", false, "reply", mensaje));
    }
}
