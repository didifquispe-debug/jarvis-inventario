/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import com.jarvis.ai.EntityExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class DineroYCantidadesTest {

    @TempDir Path dir;

    private InventoryService inv() { return Ejemplos.sembrar(new InventoryService(dir.resolve("inv.json").toString())); }

    private static BigDecimal stock(InventoryService inv, String prefijo) {
        return inv.productos().stream().filter(p -> p.nombre.startsWith(prefijo)).findFirst().orElseThrow().stock;
    }

    @Test void montosExactos() {
        assertEquals(new BigDecimal("0.30"), Num.monto(new BigDecimal("0.10"), new BigDecimal("3")));
        assertEquals(new BigDecimal("8.75"), Num.monto(new BigDecimal("3.50"), new BigDecimal("2.5")));
        assertEquals(new BigDecimal("6.65"), Num.monto(new BigDecimal("4.99"), new BigDecimal("1.333")), "redondeo a céntimos");
        assertEquals(new BigDecimal("0.13"), Num.dinero(new BigDecimal("0.125")), "HALF_UP, como en caja");
    }

    @Test void cierreDeCajaSinErroresDeRedondeo() {
        InventoryService inv = inv();
        for (int i = 0; i < 30; i++) inv.salida(BigDecimal.ONE, "clavo", false);
        inv.salida(new BigDecimal("2.5"), "cable", false);
        assertTrue(inv.cierre().contains("31 ventas por un total de 11.75 soles"), inv.cierre());
    }

    @Test void stockSeMuestraSinDecimalesSobrantes() {
        InventoryService inv = inv();
        assertEquals("Quedan 50 metros de Cable Vulcanizado.", inv.consulta("cable"));
        inv.salida(new BigDecimal("2.5"), "cable", false);
        assertEquals("Quedan 47.5 metros de Cable Vulcanizado.", inv.consulta("cable"));
        assertEquals("50", Num.txt(new BigDecimal("50.000")));
        assertEquals("47.5", Num.txt(new BigDecimal("47.50")));
    }

    @Test void decimalesSoloDondeLaUnidadLoPermite() {
        InventoryService inv = inv();
        String r = inv.salida(new BigDecimal("1.5"), "foco", false);
        assertTrue(r.contains("no admite cantidades con decimales"), r);
        assertEquals(new BigDecimal("30"), stock(inv, "Foco"), "no se tocó el stock");
        assertTrue(inv.entrada(new BigDecimal("3.25"), "cable", null, null, false).startsWith("Entrada registrada: 3.25 metros"));
    }

    @Test void productoNuevoTomaLaUnidadDicha() {
        InventoryService inv = inv();
        String r = inv.entrada(new BigDecimal("12.5"), "alambre negro", null, "kilos", false);
        assertTrue(r.startsWith("Producto nuevo registrado: Alambre negro. Entrada de 12.5 kilos"), r);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "vendi 2.5 metros de cable          | 2.5",
            "vendi 2,5 metros de cable          | 2.5",
            "vendi dos y medio metros de cable  | 2.5",
            "vendi 3 y media kilos de clavos    | 3.5",
            "vendi medio kilo de clavos         | 0.5",
            "vendi treinta y cinco clavos       | 35",
            "vendi 3 tubos de media pulgada     | 3"})
    void parserEntiendeDecimales(String frase, String esperado) {
        Palabras.Cantidad q = Palabras.buscarCantidad(Arrays.asList(Palabras.norm(frase).split(" ")));
        assertEquals(new BigDecimal(esperado), q.valor(), frase);
    }

    @Test void productoNoSeComeLaMediaPulgada() {
        assertEquals("tubos media pulgada", EntityExtractor.extraer("vendi 3 tubos de media pulgada").producto());
        assertEquals("cable", EntityExtractor.extraer("vendi dos y medio metros de cable").producto());
    }

    @Test void precioConDecimalesYaNoSeParte() {
        assertEquals(new BigDecimal("28.50"), EntityExtractor.extraer("llegaron 20 bolsas de cemento a 28.50 soles").precio());
        assertEquals(new BigDecimal("28.50"), EntityExtractor.extraer("llegaron 20 bolsas de cemento a 28,50 soles").precio());
    }
}
