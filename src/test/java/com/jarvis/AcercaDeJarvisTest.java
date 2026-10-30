/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis;

import com.jarvis.core.AcercaDeJarvis;
import com.jarvis.core.JarvisService;
import com.jarvis.inventory.Ejemplos;
import com.jarvis.inventory.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false", "jarvis.negocio=Zapatería Mi Paso",
        "jarvis.inventory.file=${java.io.tmpdir}/jarvis-test-acerca-${random.uuid}.json"})
class AcercaDeJarvisTest {

    @Autowired JarvisService jarvis;
    @Autowired InventoryService inv;
    @Autowired AcercaDeJarvis acercaDe;

    @BeforeEach void preparar() {
        Ejemplos.sembrar(inv);
        jarvis.process("cancelar venta");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "¿Qué eres?|Soy J.A.R.V.I.S., el asistente inteligente del sistema de inventario y ventas de Zapatería Mi Paso",
            "Jarvis, ¿quién eres?|Soy J.A.R.V.I.S.",
            "preséntate|Soy J.A.R.V.I.S.",
            "¿cómo te llamas?|Soy J.A.R.V.I.S.",
            "¿eres una inteligencia artificial?|Soy J.A.R.V.I.S.",
            "¿qué significa Jarvis?|Just A Rather Very Intelligent System",
            "¿Qué puedes hacer?|Esto es lo que puedo hacer",
            "¿para qué sirves?|Esto es lo que puedo hacer",
            "¿en qué me puedes ayudar?|Esto es lo que puedo hacer",
            "ayuda|Esto es lo que puedo hacer",
            "¿quién te creó?|proyecto JARVIS",
            "¿cómo hago una venta?|Para vender hay tres formas",
            "¿cómo vendo?|Para vender hay tres formas",
            "¿cómo saco el PDF de una boleta?|boleta (serie B001)",
            "¿cómo registro un cliente?|Nuevo cliente",
            "¿cómo registro una compra?|Nueva compra",
            "¿dónde veo el kardex?|botón de kardex",
            "¿cómo exporto a Excel?|Exportar Excel",
            "¿cómo veo la ganancia?|valor al costo",
            "¿cómo cargo muchos productos?|Cargar lista",
            "¿cómo agrego un producto?|Agregar producto",
            "¿cómo cambio un precio?|cambia el precio",
            "¿cómo anulo una venta?|anula la última venta",
            "¿cómo hago el cierre de caja?|cierre de caja",
            "¿cómo uso la voz?|Manos libres",
            "¿cómo lo uso desde el celular?|misma red Wi-Fi",
            "¿funcionas sin internet?|Funciono sin internet",
            "¿dónde se guardan mis datos?|jarvis-inventario.db",
            "¿cómo te enseño una palabra?|aprende que chompa",
            "¿usas inteligencia artificial?|sin inteligencia artificial"})
    void respondePreguntasSobreSiMismo(String pregunta, String esperado) {
        String r = jarvis.process(pregunta);
        assertTrue(r.contains(esperado), pregunta + " → " + r);
    }

    @Test void lasOrdenesSiguenSiendoOrdenes() {
        BigDecimal antes = inv.productos().stream().filter(p -> p.nombre.startsWith("Cemento")).findFirst().orElseThrow().stock;
        assertTrue(jarvis.process("vendí 2 bolsas de cemento").startsWith("Venta registrada"), "una venta no es una pregunta");
        assertEquals(antes.subtract(new BigDecimal("2")),
                inv.productos().stream().filter(p -> p.nombre.startsWith("Cemento")).findFirst().orElseThrow().stock);
        assertTrue(jarvis.process("¿cuántas tengo de foco?").startsWith("Quedan"));
        assertTrue(jarvis.process("cierre de caja").startsWith("Cierre del día"));
        assertTrue(jarvis.process("¿qué hora es?").matches("(?s).*\\d.*"), "la hora sigue funcionando");
        jarvis.process("nueva venta");
        jarvis.process("2 focos");
        assertTrue(jarvis.process("¿cuánto va?").startsWith("Llevamos"), "en la caja \"cuánto va\" es el total parcial");
        jarvis.process("cancelar venta");
    }

    @Test void unaPreguntaSiNoPendienteNoSePierde() {
        jarvis.process("vendí 1 disco");
        assertTrue(jarvis.process("anula la última venta").startsWith("¿Confirma"));
        assertTrue(jarvis.process("¿qué eres?").startsWith("Antes necesito que confirme"), "primero el sí o no");
        assertEquals("Operación cancelada, señor.", jarvis.process("no"));
        assertTrue(jarvis.process("¿qué eres?").startsWith("Soy J.A.R.V.I.S."));
    }

    @Test void conocimientoParaLaIa() {
        String c = acercaDe.conocimiento();
        assertTrue(c.contains("Soy J.A.R.V.I.S.") && c.contains("Nueva compra") && c.contains("kardex"), c);
    }
}
