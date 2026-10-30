/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis;

import com.jarvis.inventory.Ejemplos;
import com.jarvis.inventory.InventoryService;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false", "jarvis.token=clave-reportes",
        "jarvis.negocio=Ferretería El Clavo", "jarvis.inventory.file=${java.io.tmpdir}/jarvis-test-reportes-${random.uuid}.json"})
@AutoConfigureMockMvc
class ReportesApiTest {

    private static final String CLAVE = "clave-reportes";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired MockMvc mvc;
    @Autowired InventoryService inv;

    @BeforeEach void preparar() {
        Ejemplos.sembrar(inv);
        inv.salida(BigDecimal.ONE, "foco", false);
    }

    @Test void valorizadoYKardex() throws Exception {
        mvc.perform(get("/api/inventory/valorizado").header("X-Jarvis-Token", CLAVE))
                .andExpect(jsonPath("$.productos").value(5))
                .andExpect(jsonPath("$.filas[0].producto").exists());
        mvc.perform(get("/api/inventory/kardex").param("nombre", "Foco LED 9W").header("X-Jarvis-Token", CLAVE))
                .andExpect(jsonPath("$.producto").value("Foco LED 9W"))
                .andExpect(jsonPath("$.filas[0].tipo").value("ENTRADA"))
                .andExpect(jsonPath("$.filas[1].tipo").value("VENTA"));
        mvc.perform(get("/api/inventory/kardex").param("nombre", "no existe").header("X-Jarvis-Token", CLAVE))
                .andExpect(status().isNotFound());
    }

    @Test void descargasEnExcel() throws Exception {
        for (String url : new String[]{"/api/reportes/inventario.xlsx", "/api/reportes/ventas.xlsx", "/api/reportes/compras.xlsx",
                "/api/reportes/kardex.xlsx?nombre=Foco LED 9W"}) {
            byte[] archivo = mvc.perform(get(url).header("X-Jarvis-Token", CLAVE))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(XLSX))
                    .andExpect(header().string("Content-Disposition", containsString(".xlsx")))
                    .andReturn().getResponse().getContentAsByteArray();
            try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(archivo))) {
                assertEquals("J.A.R.V.I.S.", libro.getProperties().getCoreProperties().getCreator(), url);
            }
        }
        mvc.perform(get("/api/reportes/ventas.xlsx").param("desde", "23/10/2026").header("X-Jarvis-Token", CLAVE))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/reportes/kardex.xlsx").param("nombre", "no existe").header("X-Jarvis-Token", CLAVE))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/reportes/inventario.xlsx")).andExpect(status().isUnauthorized());
    }
}
