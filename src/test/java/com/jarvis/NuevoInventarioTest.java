package com.jarvis;

import com.jarvis.core.JarvisService;
import com.jarvis.inventory.InventoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** "Nuevo inventario" sin IA (sin internet o sin clave): por las reglas, con sí/no. */
@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false",
        "jarvis.inventory.file=${java.io.tmpdir}/jarvis-test-nuevo-${random.uuid}.json"})
class NuevoInventarioTest {

    @Autowired JarvisService jarvis;
    @Autowired InventoryService inv;

    @Test void preguntaYSoloVaciaConElSi() {
        assertTrue(inv.crearProducto("Polo nuevo inv", BigDecimal.TEN, BigDecimal.ONE, "unidades", null, null).ok());

        String pregunta = jarvis.process("Jarvis, vamos a hacer un nuevo inventario");
        assertTrue(pregunta.startsWith("¿Confirma empezar un inventario nuevo"), pregunta);
        assertEquals("Operación cancelada, señor.", jarvis.process("no"));
        assertFalse(inv.productos().isEmpty());

        jarvis.process("empezar el inventario de cero");
        assertTrue(jarvis.process("sí").contains("inventario nuevo"));
        assertTrue(inv.productos().isEmpty());
        assertTrue(jarvis.process("vaciar el inventario").contains("ya está vacío"));
    }
}
