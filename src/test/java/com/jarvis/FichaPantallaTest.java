/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis;

import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.Producto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false", "jarvis.token=clave-ficha",
        "jarvis.inventory.file=${java.io.tmpdir}/jarvis-test-ficha-${random.uuid}.json"})
@AutoConfigureMockMvc
class FichaPantallaTest {

    private static final String CLAVE = "clave-ficha";

    @Autowired MockMvc mvc;
    @Autowired InventoryService inv;
    @Autowired com.jarvis.core.JarvisService jarvis;

    private ResultActions json(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String cuerpo) throws Exception {
        return mvc.perform(req.header("X-Jarvis-Token", CLAVE).contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private ResultActions lote(String lista) throws Exception {
        return mvc.perform(post("/api/inventory/productos/lote").header("X-Jarvis-Token", CLAVE)
                .contentType(MediaType.TEXT_PLAIN).content(lista.getBytes(StandardCharsets.UTF_8)));
    }

    private Producto producto(String nombre) {
        return inv.productos().stream().filter(p -> p.nombre.equals(nombre)).findFirst().orElse(null);
    }

    @Test void creaYEditaConFichaPorLaApi() throws Exception {
        json(post("/api/inventory/productos"), """
                {"nombre":"Casaca api A","precio":120,"unidad":"unidades","categoria":"Casacas","codigo":"CAS-A",
                 "codigoBarras":"0001112223334","costo":70}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true));
        mvc.perform(get("/api/inventory").header("X-Jarvis-Token", CLAVE))
                .andExpect(jsonPath("$[?(@.nombre == 'Casaca api A')].codigoBarras").value("0001112223334"))
                .andExpect(jsonPath("$[?(@.nombre == 'Casaca api A')].costo").value(70.0));

        json(patch("/api/inventory/productos"), "{\"nombre\":\"Casaca api A\",\"categoria\":\"\",\"quitarCosto\":true}")
                .andExpect(status().isOk());
        Producto p = producto("Casaca api A");
        assertEquals("", p.categoria);
        assertNull(p.costo);
        assertEquals("CAS-A", p.codigo, "lo que no se envía no cambia");
    }

    @Test void codigoRepetidoEsError400() throws Exception {
        json(post("/api/inventory/productos"), "{\"nombre\":\"Casaca api B\",\"precio\":1,\"unidad\":\"unidades\",\"codigo\":\"CAS-B\"}")
                .andExpect(status().isOk());
        json(post("/api/inventory/productos"), "{\"nombre\":\"Casaca api C\",\"precio\":1,\"unidad\":\"unidades\",\"codigo\":\"cas-b\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.reply").value(containsString("Casaca api B")));
    }

    @Test void cargarListaConColumnasNuevas() throws Exception {
        lote("""
                Nombre; Precio; Stock; Unidad; Mínimo; Costo; Categoría; Código; Código de barras
                Media lote A; 5; 20; pares; 4; 2,50; Medias; MED-A; 7750000000011
                Media lote B; 5; 20; pares
                Media lote C; 5; 1; pares; ; ; ; MED-A
                Media lote D; 5; 1; pares; ; -1
                Media lote E; 5; 1; pares; ; dos""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agregados").value(2))
                .andExpect(jsonPath("$.errores[0].linea").value(4))
                .andExpect(jsonPath("$.errores[0].motivo").value(containsString("MED-A")))
                .andExpect(jsonPath("$.errores[1].motivo").value(containsString("costo")))
                .andExpect(jsonPath("$.errores[2].motivo").value(containsString("costo no es un número")));
        Producto a = producto("Media lote A");
        assertEquals(new BigDecimal("2.50"), a.costo, "decimal con coma usando punto y coma de separador");
        assertEquals("Medias", a.categoria);
        assertEquals("7750000000011", a.codigoBarras);
        assertEquals("", producto("Media lote B").codigo, "las listas de antes (4 columnas) siguen funcionando");
    }

    @Test void ordenesConCodigoDeBarrasOCodigoInterno() throws Exception {
        lote("Gorra roja cmd; 15; 10; unidades; 0; 8; Gorras; GOR-A; 7750000000042\n"
                + "Gorra azul cmd; 15; 10; unidades; 0; 8; Gorras; 2").andExpect(jsonPath("$.agregados").value(2));
        String r = jarvis.process("vendí 2 7750000000042");
        assertEquals(new BigDecimal("8"), producto("Gorra roja cmd").stock, "escaneado: no se lee como cantidad. " + r);
        assertTrue(jarvis.process("cuántas tengo de gor-a").contains("8"));
        jarvis.process("vendí 2 gorra roja cmd");
        assertEquals(new BigDecimal("6"), producto("Gorra roja cmd").stock,
                "un producto con código \"2\" no convierte la cantidad 2 en ese producto");
        assertEquals(new BigDecimal("10"), producto("Gorra azul cmd").stock);
    }

    @Test void volverACargarLaMismaListaOmiteEnVezDeDarError() throws Exception {
        String lista = "Media lote F; 5; 2; pares; 1; 2; Medias; MED-F; 7750000000028";
        lote(lista).andExpect(jsonPath("$.agregados").value(1));
        lote(lista).andExpect(jsonPath("$.agregados").value(0)).andExpect(jsonPath("$.omitidos").value(1))
                .andExpect(jsonPath("$.errores").isEmpty());
    }
}
