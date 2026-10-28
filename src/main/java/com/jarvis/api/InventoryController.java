/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.api;

import com.jarvis.inventory.ComprobantePdf;
import com.jarvis.inventory.Igv;
import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.TipoComprobante;
import org.springframework.beans.factory.annotation.Value;
import com.jarvis.inventory.ListaProductos;
import com.jarvis.inventory.Movimiento;
import com.jarvis.inventory.Producto;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
@CrossOrigin
public class InventoryController {

    private final InventoryService inv;
    private final ComprobantePdf.Emisor emisor;
    private final Map<String, String> negocio;

    public InventoryController(InventoryService inv,
                               @Value("${jarvis.negocio:}") String nombre,
                               @Value("${jarvis.negocio.ruc:}") String ruc,
                               @Value("${jarvis.negocio.direccion:}") String direccion,
                               @Value("${jarvis.negocio.telefono:}") String telefono,
                               @Value("${jarvis.negocio.email:}") String email) {
        this.inv = inv;
        this.emisor = new ComprobantePdf.Emisor(nombre.trim(), ruc.trim(), direccion.trim(), telefono.trim(), email.trim());
        this.negocio = Map.of("nombre", nombre.isBlank() ? "Mi Negocio" : nombre.trim(), "ruc", ruc.trim(), "direccion", direccion.trim());
    }

    public record CorreccionVenta(String producto, BigDecimal cantidad) {}
    public record NuevoProducto(String nombre, BigDecimal precio, BigDecimal stock, String unidad, BigDecimal stockMin, List<String> alias,
                                String categoria, String codigo, String codigoBarras, BigDecimal costo) {
        Producto.Ficha ficha() { return new Producto.Ficha(categoria, codigo, codigoBarras, costo); }
    }
    public record EdicionProducto(String nombre, String nuevoNombre, BigDecimal precio, BigDecimal stock, BigDecimal stockMin,
                                  String unidad, List<String> alias, String categoria, String codigo, String codigoBarras,
                                  BigDecimal costo, Boolean quitarCosto) {}
    public record AjusteStock(String nombre, BigDecimal cantidad) {}
    public record NuevaVenta(List<InventoryService.Pedido> items, String tipo, String cliente, String igv) {}
    public record OpcionesVenta(String tipo, String cliente) {}

    @GetMapping
    public List<Producto> list() {
        return inv.productos();
    }

    @GetMapping("/venta")
    public InventoryService.Comprobante venta() {
        return inv.carritoActual();
    }

    @GetMapping("/movimientos")
    public List<Movimiento> movimientos(@RequestParam(defaultValue = "50") int limite) {
        return inv.movimientos(limite);
    }

    @PostMapping("/ventas/ultima/anular")
    public ResponseEntity<Map<String, Object>> anularUltima() {
        return responder(inv.anularUltimaVenta());
    }

    @PutMapping("/ventas/ultima")
    public ResponseEntity<Map<String, Object>> corregirUltima(@RequestBody CorreccionVenta req) {
        return responder(inv.corregirUltimaVenta(req.producto(), req.cantidad(), true));
    }

    @PostMapping("/productos")
    public ResponseEntity<Map<String, Object>> crear(@RequestBody NuevoProducto req) {
        return responder(inv.crearProducto(req.nombre(), req.precio(), req.stock(), req.unidad(), req.stockMin(), req.alias(), req.ficha()));
    }

    @PatchMapping("/productos")
    public ResponseEntity<Map<String, Object>> editar(@RequestBody EdicionProducto req) {
        return responder(inv.editarProducto(req.nombre(), true, new InventoryService.Cambios(
                req.nuevoNombre(), req.precio(), req.stock(), req.stockMin(), req.unidad(), req.alias(),
                new Producto.Ficha(req.categoria(), req.codigo(), req.codigoBarras(), req.costo()),
                Boolean.TRUE.equals(req.quitarCosto()))));
    }

    @PostMapping("/productos/ajuste")
    public ResponseEntity<Map<String, Object>> ajustar(@RequestBody AjusteStock req) {
        return responder(inv.ajustarStock(req.nombre(), req.cantidad()));
    }

    @PostMapping(path = "/productos/lote", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> crearLote(@RequestBody(required = false) List<NuevoProducto> req) {
        List<ListaProductos.Fila> filas = new ArrayList<>();
        List<ListaProductos.ErrorLinea> errores = new ArrayList<>();
        List<NuevoProducto> lista = req == null ? List.of() : req;
        for (int i = 0; i < lista.size(); i++) {
            NuevoProducto p = lista.get(i);
            if (p == null) errores.add(new ListaProductos.ErrorLinea(i + 1, "producto vacío"));
            else filas.add(new ListaProductos.Fila(i + 1, p.nombre(), p.precio(), p.stock(), p.unidad(), p.stockMin(), p.ficha()));
        }
        return responderLote(new ListaProductos.Lectura(filas, errores));
    }

    @PostMapping(path = "/productos/lote", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Map<String, Object>> crearLoteTexto(@RequestBody(required = false) byte[] cuerpo) {
        return responderLote(ListaProductos.leer(texto(cuerpo)));
    }

    private ResponseEntity<Map<String, Object>> responderLote(ListaProductos.Lectura lectura) {
        if (lectura.total() == 0)
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                    "reply", "La lista está vacía: escriba una línea por producto (Nombre, Precio, Stock, Unidad)."));
        if (lectura.total() > ListaProductos.MAX_FILAS)
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                    "reply", "La lista es muy larga: cargue como máximo " + ListaProductos.MAX_FILAS + " productos a la vez."));
        InventoryService.ResultadoLote r = inv.crearProductos(lectura.filas(), lectura.errores());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("reply", r.mensaje());
        body.put("agregados", r.agregados());
        body.put("omitidos", r.omitidos());
        body.put("omitidosDetalle", r.omitidosDetalle());
        body.put("errores", r.errores());
        return ResponseEntity.ok(body);
    }

    private static String texto(byte[] cuerpo) {
        if (cuerpo == null) return "";
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(cuerpo)).toString();
        } catch (CharacterCodingException e) {
            return new String(cuerpo, Charset.forName("windows-1252"));
        }
    }

    @PostMapping("/ventas")
    public ResponseEntity<Map<String, Object>> vender(@RequestBody NuevaVenta req) {
        TipoComprobante tipo = req.tipo() == null || req.tipo().isBlank() ? null : TipoComprobante.de(req.tipo());
        if (req.tipo() != null && !req.tipo().isBlank() && tipo == null)
            return ResponseEntity.badRequest().body(Map.of("ok", false, "reply", "El tipo de comprobante es boleta o nota de venta."));
        InventoryService.Resultado r = inv.vender(req.items(), new InventoryService.Opciones(tipo, req.cliente(),
                req.igv() == null || req.igv().isBlank() ? null : Igv.Modo.de(req.igv())));
        ResponseEntity<Map<String, Object>> res = responder(r);
        InventoryService.Comprobante c = r.ok() ? inv.tomarComprobanteNuevo() : null;
        if (c == null) return res;
        Map<String, Object> body = new LinkedHashMap<>(res.getBody());
        body.put("recibo", Map.of("comprobante", c, "negocio", negocio));
        return ResponseEntity.ok(body);
    }

    @PutMapping("/venta/opciones")
    public ResponseEntity<Map<String, Object>> opcionesVenta(@RequestBody OpcionesVenta req) {
        TipoComprobante tipo = req.tipo() == null || req.tipo().isBlank() ? null : TipoComprobante.de(req.tipo());
        String msg = inv.opcionesVenta(new InventoryService.Opciones(tipo, req.cliente(), null));
        boolean ok = !msg.startsWith("No tengo");
        return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.BAD_REQUEST).body(Map.of("ok", ok, "reply", msg));
    }

    @GetMapping("/resumen")
    public InventoryService.Resumen resumen() {
        return inv.resumen();
    }

    @GetMapping("/comprobantes")
    public List<InventoryService.FilaComprobante> comprobantes(@RequestParam(defaultValue = "100") int limite,
                                                               @RequestParam(defaultValue = "") String desde,
                                                               @RequestParam(defaultValue = "") String hasta) {
        return inv.comprobantes(limite, desde, hasta);
    }

    @GetMapping("/comprobantes/{numero}")
    public ResponseEntity<?> comprobante(@PathVariable int numero) {
        InventoryService.Comprobante c = inv.comprobantePorNumero(numero);
        return c != null ? ResponseEntity.ok(c)
                : ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("ok", false, "reply", "No existe esa venta."));
    }

    @GetMapping("/comprobantes/{numero}/pdf")
    public ResponseEntity<?> pdf(@PathVariable int numero) {
        InventoryService.Comprobante c = inv.comprobantePorNumero(numero);
        if (c == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("ok", false, "reply", "No existe esa venta."));
        String archivo = (c.codigo.isEmpty() ? "venta-" + numero : c.codigo) + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"" + archivo + "\"")
                .body(ComprobantePdf.generar(c, emisor));
    }

    @DeleteMapping("/productos")
    public ResponseEntity<Map<String, Object>> eliminar(@RequestParam String nombre) {
        return responder(inv.eliminarProducto(nombre, true));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> datosInvalidos() {
        return ResponseEntity.badRequest().body(Map.of("ok", false,
                "reply", "Datos inválidos: revise que el precio y el stock sean números (use punto para decimales)."));
    }

    static ResponseEntity<Map<String, Object>> responder(InventoryService.Resultado r) {
        HttpStatus status = switch (r.estado()) {
            case OK -> HttpStatus.OK;
            case ERROR -> HttpStatus.BAD_REQUEST;
            case NO_ENCONTRADO -> HttpStatus.NOT_FOUND;
        };
        return ResponseEntity.status(status).body(Map.of("ok", r.ok(), "reply", r.mensaje()));
    }
}
