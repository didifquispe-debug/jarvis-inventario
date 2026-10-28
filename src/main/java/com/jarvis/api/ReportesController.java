/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.api;

import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.ReportesExcel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

@RestController
@CrossOrigin
public class ReportesController {

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final InventoryService inv;
    private final String negocio;

    public ReportesController(InventoryService inv, @Value("${jarvis.negocio:}") String negocio) {
        this.inv = inv;
        this.negocio = negocio.trim();
    }

    @GetMapping("/api/inventory/valorizado")
    public InventoryService.Valorizado valorizado() {
        return inv.valorizado();
    }

    @GetMapping("/api/inventory/kardex")
    public ResponseEntity<?> kardex(@RequestParam String nombre, @RequestParam(defaultValue = "500") int limite) {
        InventoryService.Kardex k = inv.kardex(nombre, limite);
        return k != null ? ResponseEntity.ok(k) : noExiste(nombre);
    }

    @GetMapping("/api/reportes/inventario.xlsx")
    public ResponseEntity<byte[]> inventarioExcel() {
        return excel("inventario-" + LocalDate.now(), ReportesExcel.inventario(inv.valorizado(), inv.kardexCompleto(), negocio));
    }

    @GetMapping("/api/reportes/kardex.xlsx")
    public ResponseEntity<?> kardexExcel(@RequestParam String nombre) {
        InventoryService.Kardex k = inv.kardex(nombre, 100_000);
        if (k == null) return noExiste(nombre);
        return excel("kardex-" + k.producto(), ReportesExcel.kardex(k, negocio));
    }

    @GetMapping("/api/reportes/ventas.xlsx")
    public ResponseEntity<?> ventasExcel(@RequestParam(defaultValue = "") String desde, @RequestParam(defaultValue = "") String hasta) {
        if (!fechaValida(desde) || !fechaValida(hasta)) return fechaInvalida();
        return excel("ventas" + sufijo(desde, hasta), ReportesExcel.ventas(inv.ventasPeriodo(desde, hasta), desde, hasta, negocio));
    }

    @GetMapping("/api/reportes/compras.xlsx")
    public ResponseEntity<?> comprasExcel(@RequestParam(defaultValue = "") String desde, @RequestParam(defaultValue = "") String hasta,
                                          @RequestParam(defaultValue = "") String q) {
        if (!fechaValida(desde) || !fechaValida(hasta)) return fechaInvalida();
        return excel("compras" + sufijo(desde, hasta),
                ReportesExcel.compras(inv.compras().listarConDetalle(q, desde, hasta, 1000), desde, hasta, negocio));
    }

    private static ResponseEntity<byte[]> excel(String nombre, byte[] contenido) {
        String archivo = nombre.replaceAll("[\\\\/:*?\"<>|]", "-") + ".xlsx";
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header("Content-Disposition", "attachment; filename=\"" + archivo.replaceAll("[^\\x20-\\x7E]", "_")
                        + "\"; filename*=UTF-8''" + URLEncoder.encode(archivo, StandardCharsets.UTF_8).replace("+", "%20"))
                .body(contenido);
    }

    private static String sufijo(String desde, String hasta) {
        return (desde.isBlank() ? "" : "-" + desde) + (hasta.isBlank() ? "" : "-al-" + hasta);
    }

    private static boolean fechaValida(String f) {
        if (f == null || f.isBlank()) return true;
        try {
            LocalDate.parse(f.trim());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static ResponseEntity<?> fechaInvalida() {
        return ResponseEntity.badRequest().body(Map.of("ok", false, "reply", "Las fechas van como AAAA-MM-DD."));
    }

    private static ResponseEntity<?> noExiste(String nombre) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("ok", false, "reply", "No existe el producto " + nombre + "."));
    }
}
