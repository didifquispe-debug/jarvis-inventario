package com.jarvis.ia;

import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.Producto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Las herramientas del asistente con IA, sin llamar a la IA: se simula lo que ella pediría. */
class HerramientasIATest {

    @TempDir Path dir;

    private InventoryService inv;
    private HerramientasIA h;
    private final List<String> ordenes = new ArrayList<>();
    private final Function<String, String> reglas = o -> { ordenes.add(o); return "respuesta de las reglas a: " + o; };

    @BeforeEach
    void preparar() {
        inv = new InventoryService(dir.resolve("inv.json").toString());
        h = new HerramientasIA(inv);
        assertTrue(inv.crearProducto("Polera roja M", new BigDecimal("25"), new BigDecimal("10"), "unidades", null, null,
                new Producto.Ficha("Poleras", "POL-R-M", null, new BigDecimal("12"))).ok());
        assertTrue(inv.crearProducto("Zapatilla Nike 40", new BigDecimal("180"), new BigDecimal("1"), "pares",
                new BigDecimal("2"), null).ok());
    }

    private String usar(String herramienta, Map<String, Object> datos, String dijo, long turno) {
        return h.ejecutar(herramienta, datos, dijo, turno, reglas);
    }

    private Producto producto(String nombre) {
        return inv.productos().stream().filter(p -> p.nombre.equals(nombre)).findFirst().orElse(null);
    }

    @Test void hayUnaDefinicionPorHerramienta() {
        assertEquals(List.of("ejecutar_orden", "ver_productos", "crear_producto", "editar_producto", "eliminar_producto",
                "nuevo_inventario"), h.definiciones().stream().map(t -> t.name()).toList());
    }

    @Test void ejecutarOrdenUsaLasReglasDeSiempre() {
        assertEquals("respuesta de las reglas a: vendí 2 polera roja M",
                usar("ejecutar_orden", Map.of("orden", "vendí 2 polera roja M"), "véndeme dos poleras rojas", 1));
        assertEquals(List.of("vendí 2 polera roja M"), ordenes);
    }

    @Test void verProductosDaLosDatosReales() {
        String todo = usar("ver_productos", Map.of(), "qué tengo", 1);
        assertTrue(todo.contains("\"nombre\":\"Polera roja M\"") && todo.contains("\"costo\":12.00"), todo);
        assertTrue(todo.contains("\"stock_bajo\":true"), "la zapatilla tiene 1 y mínimo 2: " + todo);
        String filtrado = usar("ver_productos", Map.of("filtro", "poleras"), "", 1);
        assertTrue(filtrado.contains("Polera roja M") && !filtrado.contains("Zapatilla"), "filtra por categoría: " + filtrado);
        assertTrue(usar("ver_productos", Map.of("filtro", "xyz"), "", 1).contains("Ningún producto"));
    }

    @Test void creaYEditaConLasMismasValidaciones() {
        String r = usar("crear_producto", Map.of("nombre", "Gorra negra", "precio", 15, "unidad", "unidades",
                "stock", "4", "categoria", "Gorras", "costo", 7.5), "", 1);
        assertTrue(r.startsWith("Producto registrado"), r);
        assertEquals(new BigDecimal("7.50"), producto("Gorra negra").costo, "números como texto o número");
        assertTrue(usar("crear_producto", Map.of("nombre", "Gorra azul", "precio", -1, "unidad", "unidades"), "", 1)
                .contains("negativo"), "las reglas de siempre siguen valiendo");
        assertNull(producto("Gorra azul"));

        usar("editar_producto", Map.of("nombre", "Polera roja M", "precio", 27.5), "", 2);
        assertEquals(new BigDecimal("27.50"), producto("Polera roja M").precio);
        assertTrue(usar("editar_producto", Map.of("nombre", "Gorra negra", "codigo", "POL-R-M"), "", 2).contains("ya es de"));
    }

    @Test void eliminarPideConfirmacionYSoloHaceCasoAlSiDelTurnoSiguiente() {
        String r = usar("eliminar_producto", Map.of("nombre", "polera roja m"), "elimina la polera roja", 1);
        assertTrue(r.startsWith(HerramientasIA.CONFIRMAR), r);
        assertNotNull(producto("Polera roja M"), "la primera vez no se borra nada");

        usar("eliminar_producto", Map.of("nombre", "Polera roja M"), "elimina la polera roja", 1);
        assertNotNull(producto("Polera roja M"), "en el mismo turno no hay un sí del usuario");

        usar("eliminar_producto", Map.of("nombre", "Polera roja M"), "no, mejor no", 2);
        assertNotNull(producto("Polera roja M"), "si dice que no, no se borra");

        usar("eliminar_producto", Map.of("nombre", "Zapatilla Nike 40"), "sí", 3);
        assertNotNull(producto("Zapatilla Nike 40"), "un sí no vale para algo que no se le preguntó");

        String hecho = usar("eliminar_producto", Map.of("nombre", "Zapatilla Nike 40"), "sí, hazlo", 4);
        assertNull(producto("Zapatilla Nike 40"), hecho);
    }

    @Test void eliminarAlgoQueNoExisteNoPreguntaNada() {
        assertTrue(usar("eliminar_producto", Map.of("nombre", "Casaca"), "", 1).contains("No existe"));
    }

    @Test void nuevoInventarioGuardaUnaCopiaYEmpiezaVacio() throws Exception {
        inv.salida(new BigDecimal("1"), "Polera roja M", false);
        int comprobante = inv.tomarComprobanteNuevo().numero;

        String pregunta = usar("nuevo_inventario", Map.of(), "vamos a hacer un nuevo inventario", 1);
        assertTrue(pregunta.contains("2 productos"), pregunta);
        assertEquals(2, inv.productos().size(), "sin el sí no se borra nada");

        String r = usar("nuevo_inventario", Map.of(), "sí", 2);
        assertTrue(r.contains("inventario nuevo"), r);
        assertTrue(inv.productos().isEmpty());
        try (Stream<Path> s = Files.list(dir)) {
            Path copia = s.filter(p -> p.getFileName().toString().startsWith("inv.db.antes-de-nuevo-")).findFirst().orElseThrow();
            assertEquals(List.of("Polera roja M", "Zapatilla Nike 40"), nombresEn(copia), "la copia tiene el inventario anterior");
        }
        assertEquals("NUEVO_INVENTARIO", inv.movimientos(1).get(0).tipo());

        assertTrue(inv.crearProducto("Vela", BigDecimal.ONE, BigDecimal.TEN, "unidades", null, null).ok());
        inv.salida(BigDecimal.ONE, "Vela", false);
        assertEquals(comprobante + 1, inv.tomarComprobanteNuevo().numero, "los números de comprobante no se repiten");
        assertEquals(1, new InventoryService(dir.resolve("inv.json").toString()).productos().size(), "queda guardado");
    }

    /** Los productos guardados en otra base de datos (la copia de respaldo). */
    private static List<String> nombresEn(Path base) throws Exception {
        List<String> out = new ArrayList<>();
        try (var cx = java.sql.DriverManager.getConnection("jdbc:sqlite:" + base.toAbsolutePath());
             var rs = cx.createStatement().executeQuery("SELECT nombre FROM producto ORDER BY id")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    @ParameterizedTest
    @ValueSource(strings = {"sí", "Si", "sí, hazlo", "claro", "claro que sí", "ok", "dale", "Confirmo."})
    void reconoceElSi(String dicho) { assertTrue(HerramientasIA.esSi(dicho), dicho); }

    @ParameterizedTest
    @ValueSource(strings = {"no", "no, sí lo pensé mejor", "elimina la polera", "", "simplemente no"})
    void noConfundeOtraCosaConUnSi(String dicho) { assertFalse(HerramientasIA.esSi(dicho), dicho); }
}
