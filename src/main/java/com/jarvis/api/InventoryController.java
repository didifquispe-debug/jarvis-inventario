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

/**
 * Inventario para el panel de la pantalla y correcciones por API. La clave la comprueba ApiAuthFilter.
 *
 *   GET    /api/inventory                          -> productos
 *   GET    /api/inventory/venta                    -> venta con varios productos en curso (de esta sesión)
 *   GET    /api/inventory/movimientos?limite=50    -> historial de auditoría, del más reciente al más antiguo
 *   POST   /api/inventory/ventas/ultima/anular     -> anula el último comprobante y devuelve el stock
 *   PUT    /api/inventory/ventas/ultima            { "producto": "Polera roja M", "cantidad": 3 }  (producto opcional si hay una sola línea)
 *   POST   /api/inventory/productos                { "nombre": "Polera roja M", "precio": 25, "stock": 10, "unidad": "unidades",
 *                                                    "stockMin": 2, "alias": ["polo rojo"], "categoria": "Polos", "codigo": "POL-R-M",
 *                                                    "codigoBarras": "7750182000123", "costo": 14.5 }  (solo nombre, precio y unidad son obligatorios)
 *   PATCH  /api/inventory/productos                { "nombre": "Polera roja M", "nuevoNombre": "…", "precio": 28, "stock": 12,
 *                                                    "stockMin": 3, "unidad": "unidades", "alias": ["…"], "categoria": "…", "codigo": "…",
 *                                                    "codigoBarras": "…", "costo": 15, "quitarCosto": true }  (solo lo que cambia;
 *                                                    categoria/codigo/codigoBarras "" los borra)
 *   POST   /api/inventory/productos/ajuste         { "nombre": "Polera roja M", "cantidad": -1 }  (botones +/-, sin comprobante)
 *   POST   /api/inventory/productos/lote           alta masiva ("Cargar lista"), en uno de dos formatos:
 *            application/json  [ { "nombre": "Polo blanco M", "precio": 25, "stock": 10, "unidad": "unidades", "stockMin": 2,
 *                                  "costo": 14, "categoria": "Polos", "codigo": "…", "codigoBarras": "…" }, … ]
 *            text/plain        una línea por producto: Nombre, Precio, Stock, Unidad, [StockMín], [Costo], [Categoría], [Código],
 *                              [Código de barras]  (ver ListaProductos)
 *          -> { ok, reply, agregados, omitidos, omitidosDetalle: [{linea, motivo}], errores: [{linea, motivo}] }
 *   DELETE /api/inventory/productos?nombre=Polera roja M
 *   POST   /api/inventory/ventas                   { "items": [ { "nombre": "Polera roja M", "cantidad": 2 } ],
 *                                                    "tipo": "boleta" | "nota de venta", "cliente": "12345678",
 *                                                    "igv": "incluido" | "agregado" | "exonerado" }  (solo items es obligatorio)
 *          -> { ok, reply, recibo }  (nombres exactos; 404 si un producto no existe)
 *   PUT    /api/inventory/venta/opciones           { "tipo": "boleta", "cliente": "12345678" }  comprobante y cliente de la venta en curso
 *   GET    /api/inventory/comprobantes/{numero}     -> comprobante (numero = el número interno de la venta, el de "recibo")
 *   GET    /api/inventory/comprobantes/{numero}/pdf -> el comprobante en PDF (A4)
 *
 * Las altas y correcciones responden { ok, reply } (200, 400 si falta algo o no se pudo, 404 si no existe). Aquí los nombres
 * de producto deben ser exactos (sin búsqueda difusa): un programa no debe borrar "el más parecido".
 */
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

    /** Venta con varios productos en curso (cuerpo vacío si no hay). */
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

    /** Alta masiva con un arreglo JSON. La línea de cada error es la posición en el arreglo (desde 1). */
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

    /** Alta masiva con el texto pegado (o el contenido de un .csv/.txt). */
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

    /** UTF-8; si no lo es, Windows-1252 (como guardan Excel y el Bloc de notas antiguos un .csv con tildes). */
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

    /** Cifras de la pantalla de inicio: ventas de hoy, stock bajo, valor del inventario… */
    @GetMapping("/resumen")
    public InventoryService.Resumen resumen() {
        return inv.resumen();
    }

    /** Los últimos comprobantes, del más reciente al más antiguo. */
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

    /** Cuerpo que no es JSON o números escritos como texto ("precio": "diez"): 400 con mensaje claro. */
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
