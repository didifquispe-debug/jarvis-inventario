/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis;

import com.jarvis.core.Diccionario;
import com.jarvis.core.JarvisService;
import com.jarvis.inventory.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false",
        "jarvis.inventory.file=${java.io.tmpdir}/jarvis-test-voz-${random.uuid}.json",
        "jarvis.diccionario.file=${java.io.tmpdir}/jarvis-test-voz-dic-${random.uuid}.txt"})
class VozTest {

    @Autowired JarvisService jarvis;
    @Autowired InventoryService inv;
    @Autowired Diccionario diccionario;

    @BeforeEach void tienda() {
        inv.cancelarVenta(); inv.resolver(false); inv.tomarComprobanteNuevo();
        crear("Polera roja M", "25", "10", "unidades");
        crear("Gorra negra", "15", "5", "unidades");
        crear("Zapatilla Nike Air 40", "250", "6", "pares");
    }

    private void crear(String nombre, String precio, String stock, String unidad) {
        if (existe(nombre)) return;
        assertTrue(inv.crearProducto(nombre, new BigDecimal(precio), new BigDecimal(stock), unidad, null, null).ok());
    }

    private boolean existe(String nombre) { return inv.productos().stream().anyMatch(p -> p.nombre.equals(nombre)); }

    private int stock(String nombre) {
        return inv.productos().stream().filter(p -> p.nombre.equals(nombre)).findFirst().orElseThrow().stock.intValueExact();
    }

    @Test void eliminarSinDecirProducto() {
        String pregunta = jarvis.process("Jarvis, elimina la polera roja M.");
        assertTrue(pregunta.startsWith("¿Confirma eliminar Polera roja M del inventario?"), pregunta);
        assertTrue(existe("Polera roja M"), "no se borra sin confirmar");
        assertTrue(jarvis.process("Sí.").startsWith("Eliminé Polera roja M"));
        assertFalse(existe("Polera roja M"));
    }

    @Test void otrasFormasDeDecirEliminar() {
        for (String frase : List.of("borra la gorra negra", "suprime la gorra negra", "remueve la gorra negra",
                "quita la gorra negra del inventario", "da de baja la gorra negra", "eliminar gorra negra")) {
            String r = jarvis.process(frase);
            assertTrue(r.startsWith("¿Confirma eliminar Gorra negra"), frase + " -> " + r);
            assertEquals("Operación cancelada, señor.", jarvis.process("no"));
        }
        assertTrue(existe("Gorra negra"), "con \"no\" no se borra nada");
    }

    @Test void eliminarSinProductoOInexistente() {
        assertEquals("¿Qué producto elimino del inventario, señor?", jarvis.process("elimina"));
        assertTrue(jarvis.process("elimina la casaca azul").startsWith("No encontré"));
        assertEquals(JarvisService.NO_ENTENDI, jarvis.process("borra el cielo estrellado"));
    }

    @Test void duranteUnaVentaEliminarQuitaDelCarrito() {
        jarvis.process("nueva venta");
        jarvis.process("2 gorras negras");
        assertTrue(jarvis.process("elimina la gorra negra").startsWith("Quitado"));
        assertTrue(existe("Gorra negra"));
        jarvis.process("cancelar venta");
    }

    @Test void aprenderUsarYOlvidarUnaPalabra() throws Exception {
        assertTrue(jarvis.process("Jarvis, aprende que chompa significa polera").startsWith("Aprendido"));
        assertTrue(Files.readString(diccionario.archivo()).contains("chompa = polera"), "queda guardada en el archivo");
        int antes = stock("Polera roja M");
        String r = jarvis.process("vendí 2 chompas rojas talla M");
        assertTrue(r.startsWith("Venta registrada: 2 unidades de Polera roja M"), r);
        assertEquals(antes - 2, stock("Polera roja M"));
        assertTrue(jarvis.process("¿qué palabras aprendiste?").contains("chompa = polera"));

        assertTrue(jarvis.process("cuando diga chompa entiende gorra").startsWith("Actualizado"), "la nueva reemplaza a la vieja");
        assertEquals(1, Files.readAllLines(diccionario.archivo()).stream().filter(l -> l.startsWith("chompa")).count());

        assertTrue(jarvis.process("olvida la palabra chompa").startsWith("Listo"));
        assertTrue(Files.readAllLines(diccionario.archivo()).stream().noneMatch(l -> l.startsWith("chompa")));
        assertFalse(diccionario.propias().containsKey("chompa"));
    }

    @Test void frasesDelManual() {
        assertTrue(jarvis.process("¿A cuánto está la gorra negra?").startsWith("Gorra negra cuesta 15.00 soles"));
        assertTrue(jarvis.process("¿cuánto sale la gorra negra?").startsWith("Gorra negra cuesta 15.00 soles"));
        assertTrue(jarvis.process("nos trajeron 2 gorras negras").startsWith("Entrada registrada: 2 unidades de Gorra negra"));
        assertTrue(jarvis.process("aprende que jalé significa vendí").startsWith("Aprendido"));
        assertTrue(jarvis.process("jalé 1 gorra negra").startsWith("Venta registrada: 1 unidades de Gorra negra"));
        jarvis.process("olvida la palabra jalé");
    }

    @Test void noDejaCambiarPalabrasClave() {
        assertTrue(jarvis.process("aprende que si significa no").startsWith("No puedo cambiar"));
        assertTrue(diccionario.propias().isEmpty() || !diccionario.propias().containsKey("si"));
    }

    @Test void archivoEditadoAManoSeLeeSinReiniciar() throws Exception {
        Files.writeString(diccionario.archivo(), "# mis palabras\nzapas = zapatillas   # así les dicen\n", java.nio.charset.StandardCharsets.UTF_8);
        Files.setLastModifiedTime(diccionario.archivo(), FileTime.from(Instant.now().plusSeconds(5)));
        assertEquals("Quedan 6 pares de Zapatilla Nike Air 40.", jarvis.process("¿cuántas zapas nike 40 quedan?"));
        assertEquals("zapatillas", diccionario.propias().get("zapas"));
    }

    @Test void tallasDichasEnVozAlta() {
        assertEquals("vendi 1 polera roja talla m", diccionario.aplicar("vendi 1 polera roja talla eme"));
        assertEquals("vendi 1 polo talla xl", diccionario.aplicar("vendi 1 polo talla equis ele"));
        assertEquals("ese polo talla m", diccionario.aplicar("ese polo talla eme"), "\"ese\" suelto no se toca");
        assertEquals("llegaron 5 gorras", diccionario.aplicar("nos trajeron 5 gorras"), "las frases largas van primero");
        assertEquals("llegaron 5 gorras", diccionario.aplicar("trajeron 5 gorras"));
        assertEquals("suprimidos", diccionario.aplicar("suprimidos"), "nunca reemplaza pedazos de otra palabra");
    }
}
