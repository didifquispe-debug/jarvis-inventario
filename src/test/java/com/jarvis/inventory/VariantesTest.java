package com.jarvis.inventory;

import com.jarvis.skills.InventorySkill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ropa y calzado: variantes con nombre compuesto que solo cambian en talla o color. */
class VariantesTest {

    @TempDir Path dir;
    InventoryService inv;
    InventorySkill voz;

    @BeforeEach void tienda() {
        inv = new InventoryService(dir.resolve("inv.json").toString());
        voz = new InventorySkill(inv);
        crear("Zapatilla Nike Air 39", "250", "5", "pares");
        crear("Zapatilla Nike Air 40", "250", "6", "pares");
        crear("Polera roja M", "25", "10", "unidades");
        crear("Polera roja L", "25", "10", "unidades");
        crear("Polera azul M", "25", "10", "unidades");
    }

    private void crear(String nombre, String precio, String stock, String unidad) {
        assertTrue(inv.crearProducto(nombre, new BigDecimal(precio), new BigDecimal(stock), unidad, null, null).ok());
    }

    private String decir(String frase) { return voz.handle(Palabras.norm(frase), frase).orElse("(nada)"); }

    private int stock(String nombre) {
        return inv.productos().stream().filter(p -> p.nombre.equals(nombre)).findFirst().orElseThrow().stock.intValueExact();
    }

    @Test void laTallaDecideElProducto() {
        String r = decir("vendí 2 zapatillas nike air 40");
        assertTrue(r.startsWith("Venta registrada: 2 pares de Zapatilla Nike Air 40"), r);
        assertEquals(4, stock("Zapatilla Nike Air 40"));
        assertEquals(5, stock("Zapatilla Nike Air 39"), "la 39 no se toca");
        assertTrue(decir("vendí 1 zapatilla 39").startsWith("Venta registrada: 1 pares de Zapatilla Nike Air 39"));
        assertTrue(decir("vendí 1 par de zapatillas talla 39").contains("Zapatilla Nike Air 39"));
    }

    @Test void sinTallaPreguntaCual() {
        String r = decir("vendí 1 zapatilla nike air");
        assertTrue(r.startsWith("Tengo varios parecidos: Zapatilla Nike Air 39 y Zapatilla Nike Air 40."), r);
        assertEquals(5, stock("Zapatilla Nike Air 39"));
        assertEquals(6, stock("Zapatilla Nike Air 40"));
        assertNull(inv.tomarComprobanteNuevo(), "no se vendió nada");
    }

    @Test void tallaQueNoExisteNoSeConfunde() {
        assertTrue(decir("vendí 1 zapatilla nike air 41").startsWith("No encontré"));
        String r = decir("llegaron 3 pares de zapatillas nike air 41");
        assertTrue(r.startsWith("Producto nuevo registrado"), "es otra talla: producto nuevo, no suma a la 40 -> " + r);
        assertEquals(6, stock("Zapatilla Nike Air 40"));
    }

    @Test void colorYTallaDeRopa() {
        assertTrue(decir("vendí 3 poleras rojas M").contains("Polera roja M"));
        assertEquals(7, stock("Polera roja M"));
        assertEquals(10, stock("Polera roja L"));
        assertEquals(10, stock("Polera azul M"));
        assertTrue(decir("vendí 1 polera azul talla m").contains("Polera azul M"));
        assertTrue(decir("vendí 1 polera verde M").startsWith("No encontré"), "ningún color parecido se vende por error");
        assertTrue(decir("vendí 1 polera M").startsWith("Tengo varios parecidos: Polera roja M y Polera azul M."));
    }

    @Test void consultaPorTallaOPorModelo() {
        assertEquals("Quedan 5 pares de Zapatilla Nike Air 39.", decir("¿cuántas zapatillas nike air 39 quedan?"));
        String r = decir("¿cuántas zapatillas nike quedan?");
        assertTrue(r.contains("Zapatilla Nike Air 39: 5 pares") && r.contains("Zapatilla Nike Air 40: 6 pares"), r);
    }

    @Test void nombreExactoGanaAlMasLargo() {
        crear("Polera roja M manga larga", "35", "2", "unidades");
        assertTrue(decir("vendí 1 polera roja m").contains("de Polera roja M."), "el nombre exacto no es ambiguo");
        assertEquals(9, stock("Polera roja M"));
    }

    @Test void variasVariantesEnUnaFrase() {
        String r = decir("vendí 2 poleras rojas L y 1 zapatilla 40");
        assertTrue(r.startsWith("Venta registrada: 2 productos"), r);
        assertEquals(8, stock("Polera roja L"));
        assertEquals(5, stock("Zapatilla Nike Air 40"));
        InventoryService.Comprobante c = inv.tomarComprobanteNuevo();
        assertEquals(List.of("Polera roja L", "Zapatilla Nike Air 40"), c.items.stream().map(v -> v.producto).toList());
    }
}
