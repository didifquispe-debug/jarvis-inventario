/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis;

import com.jarvis.core.JarvisService;
import com.jarvis.inventory.Compras;
import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.Producto;
import com.jarvis.security.ClientSession;
import com.jarvis.security.Rol;
import com.jarvis.skills.CompraPorVozSkill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false", "jarvis.voz.confirmar=true",
        "jarvis.inventory.file=${java.io.tmpdir}/jarvis-compra-voz-${random.uuid}.json"})
class CompraPorVozTest {

    private static final AtomicInteger SESION = new AtomicInteger();

    @Autowired JarvisService jarvis;
    @Autowired InventoryService inv;
    @Autowired CompraPorVozSkill compraVoz;

    private String sesion;

    private static BigDecimal n(String s) { return new BigDecimal(s); }

    private String voz(String orden) { return como(Rol.ADMIN, orden); }

    private String como(Rol rol, String orden) {
        return ClientSession.como(sesion, "prueba", rol, () -> jarvis.process(orden, true));
    }

    private BigDecimal stock(String nombre) {
        return inv.productos().stream().filter(p -> p.nombre.equals(nombre)).findFirst().orElseThrow().stock;
    }

    @BeforeEach void datos() {
        sesion = "compra-" + SESION.incrementAndGet();
        if (inv.productos().stream().anyMatch(p -> p.nombre.equals("Cemento Sol"))) return;
        assertTrue(inv.crearProducto("Cemento Sol", n("32"), n("10"), "bolsas", n("5"), null,
                new Producto.Ficha("Construcción", null, null, n("25"))).ok());
        assertTrue(inv.crearProducto("Fierro corrugado", n("45"), n("20"), "varillas", n("5"), null).ok());
        assertTrue(inv.registrarCompra(new Compras.NuevaCompra("20100047218", "Distribuidora Andina SAC", "factura", "F001-1", null,
                "incluido", null, List.of(new Compras.LineaCompra("Cemento Sol", n("1"), n("25"))))).ok());
    }

    @Test void compraCompletaPorPartesConNumerosEnPalabras() {
        BigDecimal cemento = stock("Cemento Sol"), fierro = stock("Fierro corrugado");
        String r = voz("Jarvis, registra una compra a Andina");
        assertTrue(r.startsWith("Compra a Distribuidora Andina SAC (RUC 20100047218)."), r);
        assertTrue(r.contains("factura F001 452"), "pide el documento: " + r);

        r = voz("factura efe cero cero uno cuatro cinco dos");
        assertTrue(r.startsWith("Anotado: factura F001-452."), r);

        r = voz("diez cemento sol a veinticinco con cincuenta");
        assertTrue(r.startsWith("Anotado: 10 bolsas de Cemento Sol a 25.50 soles (255.00)."), r);
        assertFalse(r.contains("¿Lo hago"), "dentro de la compra no se confirma línea por línea");

        r = voz("y 5 varillas de fierro corrugado a 40");
        assertTrue(r.contains("5 varillas de Fierro corrugado a 40.00"), r);
        assertTrue(r.contains("Van 2 productos, total 455.00 soles."), r);

        assertEquals(cemento, stock("Cemento Sol"), "nada entra al stock antes de registrar");
        r = voz("cómo va la compra");
        assertTrue(r.startsWith("Compra a Distribuidora Andina SAC, factura F001-452. 10 Cemento Sol a 25.50; 5 Fierro corrugado a 40.00."), r);

        r = voz("registra la compra");
        assertTrue(r.endsWith("¿La registro?"), r);
        r = voz("sí");
        assertTrue(r.startsWith("Compra CP-") && r.contains("factura F001-452 de Distribuidora Andina SAC por 455.00 soles"), r);
        assertEquals(cemento.add(n("10")), stock("Cemento Sol"));
        assertEquals(fierro.add(n("5")), stock("Fierro corrugado"));
        assertFalse(ClientSession.como(sesion, "prueba", Rol.ADMIN, compraVoz::abierta), "la compra ya se cerró");
    }

    @Test void todoEnUnaFraseYProveedorNuevoConRucDictado() {
        String r = voz("registra una compra a ferreteria el sol factura f001 77");
        assertTrue(r.contains("No tengo registrado a FERRETERIA EL SOL"), r);
        r = voz("ruc dos cero cuatro cinco seis siete ocho nueve uno dos tres");
        assertTrue(r.startsWith("Anotado: RUC 20456789123 (FERRETERIA EL SOL)."), r);
        r = voz("factura f001 77");
        assertTrue(r.startsWith("Anotado: factura F001-77."), r);
        voz("3 cemento sol a 26");
        assertTrue(voz("registra la compra").endsWith("¿La registro?"));
        r = voz("si");
        assertTrue(r.contains("de FERRETERIA EL SOL"), r);
        assertTrue(inv.compras().proveedor("20456789123").isPresent(), "el proveedor nuevo quedó registrado");
    }

    @Test void sinCostoPreguntaYNoMueveElStock() {
        voz("nueva compra a andina");
        BigDecimal antes = stock("Cemento Sol");
        String r = voz("agrega 10 cemento sol");
        assertTrue(r.startsWith("¿A qué costo?"), r);
        assertEquals(antes, stock("Cemento Sol"));
        assertTrue(voz("registra la compra").startsWith("Aún no puedo registrarla"), "falta el documento y los productos");
        assertEquals("Listo, descarté la compra; no se registró nada.", voz("cancela la compra"));
    }

    @Test void decirNoDejaLaCompraAbiertaYQuitarUnProducto() {
        voz("registra una compra a andina factura f001 900");
        voz("2 cemento sol a 25");
        voz("4 fierro corrugado a 40");
        String r = voz("quita el fierro corrugado");
        assertTrue(r.startsWith("Quité Fierro corrugado de la compra. Van 1 producto"), r);
        voz("registra la compra");
        assertTrue(voz("no").startsWith("Bien, no la registré"));
        voz("cancela la compra");
    }

    @Test void otrasPreguntasSiguenFuncionandoConLaCompraAbierta() {
        voz("registra una compra a andina");
        String r = voz("¿cuánto cemento sol hay?");
        assertTrue(r.contains("Cemento Sol"), "responde el stock: " + r);
        voz("cancela la compra");
    }

    @Test void elCajeroNoRegistraCompras() {
        assertTrue(como(Rol.CAJERO, "registra una compra a andina").contains("no tiene permiso"));
    }
}
