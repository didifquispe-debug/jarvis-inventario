package com.jarvis;

import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.Movimiento;
import com.jarvis.inventory.Producto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Alta masiva: POST /api/inventory/productos/lote ("Cargar lista"). Cada prueba usa nombres propios. */
@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false", "jarvis.token=clave-lote",
        "jarvis.inventory.file=${java.io.tmpdir}/jarvis-test-lote-${random.uuid}.json"})
@AutoConfigureMockMvc
class CargaMasivaTest {

    private static final String CLAVE = "clave-lote";

    @Autowired MockMvc mvc;
    @Autowired InventoryService inv;

    private ResultActions texto(String lista) throws Exception {
        return texto(lista.getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions texto(byte[] lista) throws Exception {
        return mvc.perform(post("/api/inventory/productos/lote").header("X-Jarvis-Token", CLAVE)
                .contentType(MediaType.TEXT_PLAIN).content(lista));
    }

    private ResultActions json(String arreglo) throws Exception {
        return mvc.perform(post("/api/inventory/productos/lote").header("X-Jarvis-Token", CLAVE)
                .contentType(MediaType.APPLICATION_JSON).content(arreglo));
    }

    private Producto producto(String nombre) {
        return inv.productos().stream().filter(p -> p.nombre.equals(nombre)).findFirst().orElse(null);
    }

    @Test void listaValidaEnAmbosSeparadores() throws Exception {
        texto("""
                # mi mercadería
                Polo blanco M, 25, 10, unidades

                Zapatilla Nike 40; 180; 5; pares; 2
                Tela drill; 12,50; 30.5; metros
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agregados").value(3))
                .andExpect(jsonPath("$.omitidos").value(0))
                .andExpect(jsonPath("$.errores", hasSize(0)))
                .andExpect(jsonPath("$.reply").value(containsString("3 agregados")));
        Producto polo = producto("Polo blanco M"), zapa = producto("Zapatilla Nike 40"), tela = producto("Tela drill");
        assertEquals(new BigDecimal("25.00"), polo.precio);
        assertEquals(new BigDecimal("10"), polo.stock);
        assertEquals("pares", zapa.unidad);
        assertEquals(new BigDecimal("2"), zapa.stockMin);
        assertEquals(new BigDecimal("12.50"), tela.precio, "con punto y coma, la coma es decimal");
        assertEquals(new BigDecimal("30.5"), tela.stock);
    }

    @Test void lineasConErroresNoSeRegistranYLasDemasSi() throws Exception {
        texto("""
                Gorra lote A, 15, 4
                Sin precio lote A, , 5, unidades
                Correa lote A, diez, 3
                , 10, 2
                Medias lote A, -5, 2
                Casaca lote A, 90, -1
                Cable lote A, 2, 2.5, unidades
                Lapicero lote A, 1.5, 100, unidades, 10, 1, Útiles, LAP-A, 7750000000035, sobra
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agregados").value(1))
                .andExpect(jsonPath("$.errores", hasSize(7)))
                .andExpect(jsonPath("$.errores[0].linea").value(2))
                .andExpect(jsonPath("$.errores[0].motivo").value("precio vacío"))
                .andExpect(jsonPath("$.errores[1].linea").value(3))
                .andExpect(jsonPath("$.errores[1].motivo").value(containsString("no es un número")))
                .andExpect(jsonPath("$.errores[2].motivo").value("nombre vacío"))
                .andExpect(jsonPath("$.errores[3].motivo").value(containsString("precio no puede ser negativo")))
                .andExpect(jsonPath("$.errores[4].motivo").value(containsString("stock no puede ser negativo")))
                .andExpect(jsonPath("$.errores[5].motivo").value(containsString("no puede tener decimales")))
                .andExpect(jsonPath("$.errores[6].linea").value(8))
                .andExpect(jsonPath("$.errores[6].motivo").value(containsString("sobran campos")));
        assertNotNull(producto("Gorra lote A"));
        assertNull(producto("Medias lote A"));
        assertNull(producto("Casaca lote A"));
        assertTrue(inv.productos().stream().noneMatch(p -> p.nombre.isBlank()), "nunca registra productos en blanco");
    }

    @Test void repetidoDentroDeLaListaSeRegistraElPrimero() throws Exception {
        texto("""
                Chompa lote B, 40, 3
                CHOMPA  lote b, 45, 9
                Chómpa lote B, 50, 1
                """)
                .andExpect(jsonPath("$.agregados").value(1))
                .andExpect(jsonPath("$.omitidos").value(2))
                .andExpect(jsonPath("$.omitidosDetalle[0].linea").value(2))
                .andExpect(jsonPath("$.omitidosDetalle[0].motivo").value("repetido: ya viene en la línea 1"));
        assertEquals(new BigDecimal("40.00"), producto("Chompa lote B").precio);
    }

    @Test void productoQueYaExisteNoSePisa() throws Exception {
        assertTrue(inv.crearProducto("Pantalón jean lote C", new BigDecimal("70"), new BigDecimal("8"), "unidades", null, null).ok());
        texto("""
                pantalon JEAN lote c, 10, 99
                Short lote C, 30, 2
                """)
                .andExpect(jsonPath("$.agregados").value(1))
                .andExpect(jsonPath("$.omitidos").value(1))
                .andExpect(jsonPath("$.omitidosDetalle[0].motivo").value("ya existe: Pantalón jean lote C"));
        Producto p = producto("Pantalón jean lote C");
        assertEquals(new BigDecimal("70.00"), p.precio, "no se pisa el precio");
        assertEquals(new BigDecimal("8"), p.stock, "ni el stock");
    }

    @Test void unidadYStockFaltantesTomanValoresPorDefecto() throws Exception {
        texto("Vela lote D, 2\nFosforo lote D; 0.5;;\n").andExpect(jsonPath("$.agregados").value(2));
        Producto vela = producto("Vela lote D"), fosforo = producto("Fosforo lote D");
        assertEquals("unidades", vela.unidad);
        assertEquals(BigDecimal.ZERO, vela.stock);
        assertEquals("unidades", fosforo.unidad);
        assertEquals(BigDecimal.ZERO, fosforo.stock);
    }

    @Test void formatoJson() throws Exception {
        json("""
                [ {"nombre": "Bolso lote E", "precio": 60, "stock": 2, "unidad": "unidades", "stockMin": 1},
                  {"nombre": "Billetera lote E", "precio": 35},
                  {"nombre": "", "precio": 10},
                  null ]
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agregados").value(2))
                .andExpect(jsonPath("$.errores", hasSize(2)))
                .andExpect(jsonPath("$.errores[0].linea").value(3))
                .andExpect(jsonPath("$.errores[1].motivo").value("producto vacío"));
        assertEquals("unidades", producto("Billetera lote E").unidad);
        assertEquals(new BigDecimal("1"), producto("Bolso lote E").stockMin);
    }

    @Test void encabezadoTabuladoresYComillasDeExcel() throws Exception {
        texto("Nombre\tPrecio\tStock\tUnidad\nArroz lote F\t4,20\t50\tkilos\n").andExpect(jsonPath("$.agregados").value(1));
        assertEquals(new BigDecimal("4.20"), producto("Arroz lote F").precio);
        texto("\"Polo, rayas lote F\",25,3,unidades\n").andExpect(jsonPath("$.agregados").value(1));
        assertNotNull(producto("Polo, rayas lote F"));
    }

    /** Lo que escribe el micrófono de "Cargar lista" (lineaDictada en index.html): punto y coma y coma decimal. */
    @Test void lineasEscritasPorElDictado() throws Exception {
        texto("""
                Zapatilla nike 41; 180; 5; pares; 2
                Tela drill lote I; 12,50; 30; metros
                Gorra negra lote I; 15; 8
                Chompa de lana lote I
                """)
                .andExpect(jsonPath("$.agregados").value(3))
                .andExpect(jsonPath("$.errores[0].linea").value(4))
                .andExpect(jsonPath("$.errores[0].motivo").value("precio vacío"));
        assertEquals(new BigDecimal("12.50"), producto("Tela drill lote I").precio);
        assertEquals(new BigDecimal("2"), producto("Zapatilla nike 41").stockMin);
    }

    @Test void archivoGuardadoComoAnsiConservaLasTildes() throws Exception {
        texto("Camión juguete lote G, 20, 1\n".getBytes(Charset.forName("windows-1252"))).andExpect(jsonPath("$.agregados").value(1));
        assertNotNull(producto("Camión juguete lote G"));
    }

    @Test void historialYUnSoloGuardado() throws Exception {
        texto("Taza lote H, 8, 12\nPlato lote H, 6, 0\n").andExpect(jsonPath("$.agregados").value(2));
        var movs = inv.movimientos(500);
        assertTrue(movs.stream().anyMatch(m -> m.tipo().equals("NUEVO_PRODUCTO") && m.producto().equals("Taza lote H")));
        assertTrue(movs.stream().anyMatch(m -> m.tipo().equals("NUEVO_PRODUCTO") && m.producto().equals("Plato lote H")));
        Movimiento entrada = movs.stream().filter(m -> m.tipo().equals("ENTRADA") && m.producto().equals("Taza lote H")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("12"), entrada.cantidad());
        // quedó en disco: otro servicio que lee el mismo archivo los ve
        Path historial = inv.carpetaHistorial();   // <archivo>-historial, junto al archivo del inventario
        Path archivo = historial.resolveSibling(historial.getFileName().toString().replaceFirst("-historial$", "") + ".json");
        InventoryService releido = new InventoryService(archivo.toString(), 90);
        assertTrue(releido.productos().stream().anyMatch(p -> p.nombre.equals("Plato lote H")));
    }

    @Test void listaVaciaEs400() throws Exception {
        texto("\n# solo comentarios\n\n").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reply").value(containsString("vacía")));
        json("[]").andExpect(status().isBadRequest());
    }

    @Test void pideClave() throws Exception {
        mvc.perform(post("/api/inventory/productos/lote").contentType(MediaType.TEXT_PLAIN).content("Intruso, 1, 1"))
                .andExpect(status().isUnauthorized());
        assertNull(producto("Intruso"));
    }
}
