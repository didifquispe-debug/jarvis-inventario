/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.inventory;

import com.jarvis.security.ClientSession;
import com.jarvis.security.Rol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;

class UsuariosTest {

    @TempDir Path dir;

    private InventoryService nuevo() { return new InventoryService(dir.resolve("inv.json").toString()); }

    private static BigDecimal n(String s) { return new BigDecimal(s); }

    private static <T> T como(Rol rol, java.util.function.Supplier<T> accion) {
        return ClientSession.como("prueba", "prueba@test", rol, accion);
    }

    @Test void sinUsuariosTodoSigueIgual() {
        InventoryService inv = nuevo();
        assertFalse(inv.usuarios().hayUsuarios());
        assertTrue(inv.crearProducto("Vela", n("2"), n("10"), "unidades", null, null).ok());
    }

    @Test void elPrimeroDebeSerAdministrador() {
        Usuarios u = nuevo().usuarios();
        assertFalse(u.crear("Yoel", "cajero", "4826").ok());
        assertTrue(u.crear("Didier", "admin", "2580").ok());
        assertTrue(u.hayUsuarios());
        assertTrue(u.crear("Yoel", "cajero", "4826").ok());
        assertFalse(u.crear("yoel", "almacen", "4826").ok(), "el nombre no distingue mayúsculas");
    }

    @Test void pinesDebiles() {
        assertNotNull(Usuarios.errorPin("12"));
        assertNotNull(Usuarios.errorPin("1234567"));
        assertNotNull(Usuarios.errorPin("abcd"));
        assertNotNull(Usuarios.errorPin("1111"));
        assertNotNull(Usuarios.errorPin("1234"));
        assertNotNull(Usuarios.errorPin("6543"));
        assertNull(Usuarios.errorPin("2580"));
        assertNull(Usuarios.errorPin(" 482613 "));
    }

    @Test void elPinNoSeGuardaEnTexto() throws Exception {
        assertTrue(nuevo().usuarios().crear("Didier", "admin", "2580").ok());
        try (Connection cx = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("inv.db").toAbsolutePath());
             ResultSet rs = cx.createStatement().executeQuery("SELECT pin_hash FROM usuario")) {
            assertTrue(rs.next());
            assertTrue(rs.getString(1).startsWith("pbkdf2$"));
            assertFalse(rs.getString(1).contains("2580"));
        }
        assertTrue(Files.exists(dir.resolve("inv.db")));
    }

    @Test void ingresoYBloqueo() {
        Usuarios u = nuevo().usuarios();
        u.crear("Didier", "admin", "2580");
        Usuarios.Intento ok = u.iniciar("didier", "2580", "10.0.0.1");
        assertEquals(Usuarios.Ingreso.OK, ok.estado());
        assertEquals(Rol.ADMIN, u.sesion(ok.sesion().token()).orElseThrow().rol());
        for (int i = 0; i < 4; i++) assertEquals(Usuarios.Ingreso.PIN_INCORRECTO, u.iniciar("Didier", "9999", "10.0.0.1").estado());
        assertEquals(Usuarios.Ingreso.BLOQUEADO, u.iniciar("Didier", "9999", "10.0.0.1").estado());
        assertEquals(Usuarios.Ingreso.BLOQUEADO, u.iniciar("Didier", "2580", "10.0.0.1").estado(), "bloqueado aunque ahora acierte");
        assertEquals(Usuarios.Ingreso.PIN_INCORRECTO, u.iniciar("Nadie", "2580", "10.0.0.1").estado());
        u.cerrar(ok.sesion().token());
        assertTrue(u.sesion(ok.sesion().token()).isEmpty());
    }

    @Test void siempreQuedaUnAdministrador() {
        Usuarios u = nuevo().usuarios();
        u.crear("Didier", "admin", "2580");
        u.crear("Yoel", "cajero", "4826");
        long didier = u.listar().stream().filter(x -> x.nombre().equals("Didier")).findFirst().orElseThrow().id();
        assertFalse(u.editar(didier, "cajero", null, null).ok());
        assertFalse(u.editar(didier, null, null, false).ok());
        assertFalse(u.eliminar(didier).ok());
        assertTrue(u.crear("Sumi", "admin", "7391").ok());
        assertTrue(u.editar(didier, "cajero", null, null).ok(), "ya hay otro administrador");
    }

    @Test void desactivarCierraSuSesion() {
        Usuarios u = nuevo().usuarios();
        u.crear("Didier", "admin", "2580");
        u.crear("Yoel", "cajero", "4826");
        String token = u.iniciar("Yoel", "4826", "ip").sesion().token();
        long yoel = u.sesion(token).orElseThrow().usuarioId();
        assertTrue(u.editar(yoel, null, null, false).ok());
        assertTrue(u.sesion(token).isEmpty());
        assertEquals(Usuarios.Ingreso.PIN_INCORRECTO, u.iniciar("Yoel", "4826", "ip").estado(), "un usuario inactivo no entra");
    }

    @Test void elCajeroVendePeroNoTocaElInventario() {
        InventoryService inv = nuevo();
        assertTrue(inv.crearProducto("Polera", n("40"), n("10"), "unidades", n("2"), null).ok());
        assertTrue(como(Rol.CAJERO, () -> inv.salida(n("1"), "polera", false)).startsWith("Venta registrada"));
        assertTrue(como(Rol.CAJERO, () -> inv.entrada(n("5"), "polera", null, null, false)).contains("no tiene permiso"));
        assertTrue(como(Rol.CAJERO, () -> inv.cambiarPrecio("polera", n("1"))).contains("no tiene permiso"));
        assertEquals(InventoryService.Estado.PROHIBIDO, como(Rol.CAJERO, inv::anularUltimaVenta).estado());
        assertTrue(como(Rol.CAJERO, inv::pedirNuevoInventario).contains("no tiene permiso"));
        assertEquals(n("40.00"), inv.productos().get(0).precio, "no cambió nada");
        assertEquals(n("9"), inv.productos().get(0).stock);
    }

    @Test void almacenNoVende() {
        InventoryService inv = nuevo();
        assertTrue(inv.crearProducto("Polera", n("40"), n("10"), "unidades", n("2"), null).ok());
        assertTrue(como(Rol.ALMACEN, () -> inv.salida(n("1"), "polera", false)).contains("no tiene permiso para vender"));
        assertTrue(como(Rol.ALMACEN, () -> inv.entrada(n("5"), "polera", null, null, false)).startsWith("Entrada registrada"));
        assertTrue(como(Rol.ALMACEN, inv::pedirNuevoInventario).contains("no tiene permiso"));
        assertEquals(n("15"), inv.productos().get(0).stock);
    }
}
