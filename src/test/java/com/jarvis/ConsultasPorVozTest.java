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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false", "jarvis.voz.confirmar=true",
        "jarvis.inventory.file=${java.io.tmpdir}/jarvis-consultas-${random.uuid}.json"})
class ConsultasPorVozTest {

    @Autowired JarvisService jarvis;
    @Autowired InventoryService inv;

    private static BigDecimal n(String s) { return new BigDecimal(s); }

    private String como(Rol rol, String orden) {
        return ClientSession.como("consultas-" + rol, "prueba", rol, () -> jarvis.process(orden, true));
    }

    @BeforeEach void datos() {
        if (inv.productos().stream().anyMatch(p -> p.nombre.equals("Polera azul"))) return;
        assertTrue(inv.crearProducto("Polera azul", n("59"), n("10"), "unidades", n("3"), null,
                new Producto.Ficha("Poleras", null, null, n("30"))).ok());
        assertTrue(inv.crearProducto("Gorra", n("20"), n("2"), "unidades", n("5"), null).ok());
        assertTrue(inv.crearProducto("Taza", n("15"), n("8"), "unidades", n("2"), null).ok());
        assertTrue(inv.registrarCompra(new Compras.NuevaCompra("20100047218", "Distribuidora Andina SAC", "factura", "F001-77", null,
                "incluido", null, List.of(new Compras.LineaCompra("Taza", n("4"), n("9"))))).ok());
        assertTrue(inv.salida(n("2"), "polera azul", false).startsWith("Venta registrada"));
    }

    @Test void cuantoGaneNoRegistraUnaVenta() {
        BigDecimal stock = inv.productos().stream().filter(p -> p.nombre.equals("Polera azul")).findFirst().orElseThrow().stock;
        String r = como(Rol.ADMIN, "Jarvis, ¿cuánto gané hoy?");
        assertTrue(r.startsWith("Ganancia de hoy: 40.00 soles"), r);
        String v = como(Rol.ADMIN, "cuánto vendí esta semana");
        assertTrue(v.matches("Ventas de esta semana: \\d+ ventas? por \\d+\\.\\d\\d soles\\..*"), v);
        assertFalse(v.contains("¿Confirma"), "una pregunta no pide confirmación por voz");
        assertEquals(stock, inv.productos().stream().filter(p -> p.nombre.equals("Polera azul")).findFirst().orElseThrow().stock);
    }

    @Test void ventasDeUnProducto() {
        assertEquals("De Polera azul se vendieron 2 unidades este mes, por 118.00 soles.", como(Rol.ADMIN, "cuánto vendí de polera azul este mes"));
        assertEquals("No se vendió Gorra hoy, señor.", como(Rol.ADMIN, "cuanto vendi de gorra hoy"));
    }

    @Test void loMasVendidoYLoQueSeAcaba() {
        assertTrue(como(Rol.CAJERO, "qué es lo más vendido del mes").startsWith("Lo más vendido este mes: Polera azul (2 unidades"));
        String a = como(Rol.CAJERO, "qué productos se están acabando");
        assertTrue(a.contains("Por acabarse: Gorra (quedan 2)"), a);
    }

    @Test void comprasAUnProveedor() {
        String r = como(Rol.ALMACEN, "qué le compré a Andina");
        assertTrue(r.startsWith("Compras a Distribuidora Andina SAC: 1 por "), r);
        assertTrue(r.contains("CP-00000001 del " + LocalDate.now().toString().substring(8) + "/"), r);
        assertTrue(como(Rol.CAJERO, "qué le compré a Andina").contains("no tiene permiso"));
    }

    @Test void elCajeroNoVeGanancias() {
        assertTrue(como(Rol.CAJERO, "cuánto gané este mes").contains("no tiene permiso"));
        assertTrue(como(Rol.CAJERO, "cuánto vendí hoy").startsWith("Cierre del día"), "lo de hoy sí, es su cierre de caja");
        assertTrue(como(Rol.CAJERO, "cuánto vendí este mes").contains("no tiene permiso"));
        String valor = como(Rol.CAJERO, "cuánto vale mi inventario");
        assertTrue(valor.startsWith("Su inventario vale"), valor);
        assertFalse(valor.contains("al costo"), valor);
        assertTrue(como(Rol.ALMACEN, "cuánto vale mi inventario").contains("al costo"));
    }

    @Test void mercaderiaParada() {
        String r = como(Rol.ALMACEN, "qué productos no se venden");
        assertTrue(r.startsWith("Sin ventas en 30 días:"), r);
        assertTrue(r.contains("Taza") && !r.contains("Polera"), r);
    }

    @Test void siguenFuncionandoLasOrdenes() {
        assertTrue(inv.crearProducto("Vela aromática", n("8"), n("20"), "unidades", n("2"), null).ok());
        assertTrue(ClientSession.como("orden", () -> jarvis.process("compré 3 velas aromáticas")).startsWith("Entrada registrada"), "sigue siendo una entrada");
        assertTrue(ClientSession.como("orden", () -> jarvis.process("vendí 1 vela aromática")).startsWith("Venta registrada"), "sigue siendo una venta");
    }
}
