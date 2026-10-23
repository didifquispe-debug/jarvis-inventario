package com.jarvis.inventory;

import com.jarvis.ai.EntityExtractor;
import com.jarvis.skills.InventorySkill;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Regresión: "docena", "media docena" y "par" se leían como 1, 0.5 y 1. */
class MultiplicadoresTest {

    @TempDir Path dir;

    private static BigDecimal cantidad(String frase) {
        return Palabras.buscarCantidad(Arrays.asList(Palabras.norm(frase).split(" "))).valor();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "una docena                       | 12",
            "media docena                     | 6",
            "un par                           | 2",
            "dos pares                        | 4",
            "tres docenas                     | 36",
            "docena                           | 12",
            "medio ciento                     | 50",
            "ciento                           | 100",
            "un millar                        | 1000",
            "dos docenas y media              | 30",
            "vendi un par de zapatillas       | 2",
            "llegaron media docena de panes   | 6",
            "vendi una docena de medias       | 12",
            // lo que ya funcionaba sigue igual
            "treinta y cinco                  | 35",
            "dos y medio                      | 2.5",
            "20.00                            | 20",
            "cien                             | 100",
            "vendi una bolsa de cemento       | 1",
            "vendi 3 tubos de media pulgada   | 3"})
    void multiplicadores(String frase, String esperado) {
        assertEquals(new BigDecimal(esperado), cantidad(frase), frase);
    }

    @Test void elMultiplicadorNoEntraEnElProducto() {
        assertEquals("panes", EntityExtractor.extraer("llegaron media docena de panes").producto());
        assertEquals("zapatillas", EntityExtractor.extraer("vendi un par de zapatillas").producto());
        assertEquals("medias", EntityExtractor.extraer("vendi tres docenas de medias").producto());
    }

    @Test void elPluralSueltoNoEsCantidad() {
        assertNull(cantidad("cuantos pares de zapatillas quedan"));
    }

    @Test void productoContadoEnParesNoSeDuplica() {
        InventoryService inv = new InventoryService(dir.resolve("inv.json").toString(), 90);
        InventorySkill voz = new InventorySkill(inv);
        assertTrue(inv.crearProducto("Zapatilla Nike Air 40", new BigDecimal("250"), new BigDecimal("6"), "pares", null, null).ok());
        assertTrue(inv.crearProducto("Medias blancas", new BigDecimal("5"), new BigDecimal("50"), "unidades", null, null).ok());

        String r = voz.handle(Palabras.norm("vendí un par de zapatillas nike air 40"), "").orElseThrow();
        assertTrue(r.startsWith("Venta registrada: 1 pares de Zapatilla Nike Air 40"), r);
        r = voz.handle(Palabras.norm("llegaron dos pares de zapatillas nike air 40"), "").orElseThrow();
        assertTrue(r.startsWith("Entrada registrada: 2 pares de Zapatilla Nike Air 40"), r);
        assertEquals(new BigDecimal("7"), stock(inv, "Zapatilla Nike Air 40"));

        r = voz.handle(Palabras.norm("vendí una docena de medias blancas"), "").orElseThrow();
        assertTrue(r.startsWith("Venta registrada: 12 unidades de Medias blancas"), r);
        r = voz.handle(Palabras.norm("vendí un par de medias blancas"), "").orElseThrow();
        assertTrue(r.startsWith("Venta registrada: 2 unidades de Medias blancas"), r);
        assertEquals(new BigDecimal("36"), stock(inv, "Medias blancas"));
    }

    @Test void productoNuevoEnParesTomaLosParesDichos() {
        InventoryService inv = new InventoryService(dir.resolve("inv.json").toString(), 90);
        String r = new InventorySkill(inv).handle(Palabras.norm("llegaron 3 pares de zapatillas puma 41"), "").orElseThrow();
        assertTrue(r.startsWith("Producto nuevo registrado"), r);
        assertEquals(new BigDecimal("3"), inv.productos().get(0).stock);
        assertEquals("pares", inv.productos().get(0).unidad);
    }

    private static BigDecimal stock(InventoryService inv, String nombre) {
        return inv.productos().stream().filter(p -> p.nombre.equals(nombre)).findFirst().orElseThrow().stock;
    }
}
