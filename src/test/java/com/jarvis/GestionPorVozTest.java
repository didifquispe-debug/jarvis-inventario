/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis;

import com.jarvis.core.DYSAJarvisService;
import com.jarvis.inventory.Compras;
import com.jarvis.inventory.DYSAInventoryService;
import com.jarvis.inventory.Producto;
import com.jarvis.security.ClientSession;
import com.jarvis.security.Rol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"jarvis.desktop.enabled=false", "jarvis.intent.enabled=false", "jarvis.voz.confirmar=true",
        "jarvis.inventory.file=${java.io.tmpdir}/jarvis-gestion-${random.uuid}.json"})
class GestionPorVozTest {

    @Autowired DYSAJarvisService jarvis;
    @Autowired DYSAInventoryService inv;

    private static BigDecimal n(String s) { return new BigDecimal(s); }

    private String como(Rol rol, String orden, boolean voz) {
        return ClientSession.como("gestion-" + rol, "prueba", rol, () -> jarvis.process(orden, voz));
    }

    private String admin(String orden) { return como(Rol.ADMIN, orden, false); }

    @BeforeEach void datos() {
        if (inv.productos().stream().anyMatch(p -> p.nombre.equals("Cemento Sol"))) return;
        assertTrue(inv.crearProducto("Cemento Sol", n("32"), n("50"), "bolsas", n("10"), null,
                new Producto.Ficha("Construcción", null, null, n("25"))).ok());
        assertTrue(inv.registrarCompra(new Compras.NuevaCompra("20100047218", "Distribuidora Andina SAC", "factura", "F001-9", null,
                "incluido", null, List.of(new Compras.LineaCompra("Cemento Sol", n("10"), n("25"))))).ok());
        assertTrue(inv.salida(n("3"), "cemento sol", false).startsWith("Venta registrada"));
    }

    @Test void copiaDeSeguridadPorChatYSoloAdmin() {
        String r = admin("Jarvis, haz una copia de seguridad");
        assertTrue(r.startsWith("Copia de seguridad lista"), r);
        assertTrue(como(Rol.CAJERO, "haz una copia de seguridad", false).contains("no tiene permiso"));
    }

    @Test void kardexDeUnProducto() {
        String r = admin("kardex del cemento sol");
        assertTrue(r.startsWith("Kardex de Cemento Sol, stock actual 57 bolsas."), r);
        assertTrue(r.contains("compra +10"), r);
        assertTrue(r.contains("venta −3, quedan 57"), r);
        assertTrue(como(Rol.CAJERO, "movimientos del cemento sol", false).contains("no tiene permiso"));
    }

    @Test void registrarYConsultarClientes() {
        String r = admin("Registra al cliente ana TORRES de la cruz con DNI 45678912");
        assertEquals("Cliente registrado: Ana Torres de la Cruz, DNI 45678912.", r);
        assertEquals("El DNI 45678912 es de Ana Torres de la Cruz.", admin("¿Quién es el cliente 45678912?"));
        assertTrue(admin("cuántos clientes tengo").startsWith("Tiene "), "cuenta los clientes");
        assertTrue(admin("busca al cliente con DNI 11111111").startsWith("No tengo un cliente con el documento 11111111"));
        assertTrue(admin("registra al cliente Ana Torres con DNI 45678912").startsWith("Ya hay un cliente"));
    }

    @Test void registrarClientePorVozPideConfirmar() {
        String r = como(Rol.ADMIN, "agrega al cliente Luis Rojas con DNI 7 0 1 2 3 4 5 6", true);
        assertTrue(r.startsWith("Entendí: agrega al cliente Luis Rojas"), r);
        assertTrue(inv.clientes().porDocumento("70123456").isEmpty(), "no se guarda hasta confirmar");
        assertEquals("Cliente registrado: Luis Rojas, DNI 70123456.", como(Rol.ADMIN, "sí", true));
    }

    @Test void unaVentaConClienteSigueSiendoVenta() {
        assertTrue(admin("registra al cliente Rosa Paz con DNI 40404040").startsWith("Cliente registrado"));
        String r = admin("vende 1 cemento sol al cliente 40404040");
        assertFalse(r.startsWith("El DNI"), "no se confunde con una consulta: " + r);
    }

    @Test void proveedores() {
        assertEquals("Tiene 1 proveedor: Distribuidora Andina SAC.", admin("¿cuáles son mis proveedores?"));
        assertEquals("Distribuidora Andina SAC, RUC 20100047218.", admin("datos del proveedor andina"));
        assertTrue(admin("datos del proveedor xyz").startsWith("No encontré un proveedor"));
        assertTrue(como(Rol.CAJERO, "lista de proveedores", false).contains("no tiene permiso"));
    }
}
