/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.api;

import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.Usuarios;
import com.jarvis.security.ApiAuthFilter;
import com.jarvis.security.ClientSession;
import com.jarvis.security.Rol;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin
public class UsuariosController {

    private final Usuarios usuarios;

    public UsuariosController(InventoryService inv) {
        this.usuarios = inv.usuarios();
    }

    public record Ingreso(String usuario, String pin) {}
    public record NuevoUsuario(String nombre, String rol, String pin) {}
    public record CambioUsuario(String rol, String pin, Boolean activo) {}

    /** Si hay usuarios, quién es este aparato y qué puede hacer. Lo usa la pantalla para pedir el PIN. */
    @GetMapping("/api/sesion")
    public Map<String, Object> estado() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean hay = usuarios.hayUsuarios();
        body.put("usuarios", hay);
        body.put("nombres", hay ? usuarios.nombresActivos() : List.of());
        Rol rol = ClientSession.rol();
        body.put("yo", hay && rol != null ? yo(nombre(), rol) : null);
        return body;
    }

    @PostMapping("/api/sesion")
    public ResponseEntity<Map<String, Object>> iniciar(@RequestBody Ingreso req, HttpServletRequest http) {
        Usuarios.Intento i = usuarios.iniciar(req.usuario(), req.pin(), http.getRemoteAddr());
        return switch (i.estado()) {
            case OK -> ResponseEntity.ok(Map.of("ok", true, "token", i.sesion().token(),
                    "yo", yo(i.sesion().nombre(), i.sesion().rol()), "reply", "Bienvenido, " + i.sesion().nombre() + "."));
            case PIN_INCORRECTO -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("ok", false, "reply", "Usuario o PIN incorrecto."));
            case BLOQUEADO -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(i.segundos()))
                    .body(Map.of("ok", false, "reply", "Demasiados intentos. Espere " + Math.max(1, (i.segundos() + 59) / 60) + " minuto(s)."));
        };
    }

    @DeleteMapping("/api/sesion")
    public Map<String, Object> salir(@RequestHeader(value = ApiAuthFilter.CABECERA_USUARIO, required = false) String token) {
        usuarios.cerrar(token);
        return Map.of("ok", true, "reply", "Sesión cerrada.");
    }

    @GetMapping("/api/usuarios")
    public List<Usuarios.Usuario> listar() {
        return usuarios.listar();
    }

    @PostMapping("/api/usuarios")
    public ResponseEntity<Map<String, Object>> crear(@RequestBody NuevoUsuario req, HttpServletRequest http) {
        if (!usuarios.hayUsuarios() && !ApiAuthFilter.esLocal(http.getRemoteAddr()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("ok", false, "reply", "El primer usuario (el administrador) se crea desde la laptop de JARVIS."));
        return InventoryController.responder(usuarios.crear(req.nombre(), req.rol(), req.pin()));
    }

    @PatchMapping("/api/usuarios/{id}")
    public ResponseEntity<Map<String, Object>> editar(@PathVariable long id, @RequestBody CambioUsuario req) {
        return InventoryController.responder(usuarios.editar(id, req.rol(), req.pin(), req.activo()));
    }

    @DeleteMapping("/api/usuarios/{id}")
    public ResponseEntity<Map<String, Object>> eliminar(@PathVariable long id) {
        return InventoryController.responder(usuarios.eliminar(id));
    }

    private static String nombre() {
        String u = ClientSession.usuario();
        int arroba = u.lastIndexOf('@');
        return arroba > 0 ? u.substring(0, arroba) : u;
    }

    private static Map<String, Object> yo(String nombre, Rol rol) {
        return Map.of("nombre", nombre, "rol", rol.name(), "rolDescripcion", rol.descripcion,
                "permisos", rol.permisos.stream().map(Enum::name).toList());
    }
}
