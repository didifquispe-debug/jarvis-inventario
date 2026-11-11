/*
 * Proyecto DYSA - JARVIS Inventario
 * Autores: D: Didier · Y: Yoel · S: Sumi · A: Apaza
 */
package com.jarvis.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.inventory.InventoryService;
import com.jarvis.inventory.Usuarios;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class ApiAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthFilter.class);

    public static final String CABECERA_USUARIO = "X-Jarvis-Usuario";
    public static final String NOMBRE_LAPTOP = "Laptop";
    private static final Set<String> LOCALES = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AccessToken token;
    private final FailureLimiter limiter;
    private final Usuarios usuarios;
    private final boolean laptopAdmin;

    public ApiAuthFilter(AccessToken token, InventoryService inv,
                         @Value("${jarvis.security.max-failures-per-minute:10}") int maxFallos,
                         @Value("${jarvis.usuarios.laptop-admin:true}") boolean laptopAdmin) {
        this.token = token;
        this.limiter = new FailureLimiter(maxFallos, 60_000);
        this.usuarios = inv.usuarios();
        this.laptopAdmin = laptopAdmin;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        return !path.startsWith("/api/") || path.equals("/api/status") || "OPTIONS".equals(req.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String ip = req.getRemoteAddr();
        if (limiter.bloqueado(ip)) {
            res.setHeader("Retry-After", String.valueOf(limiter.segundosParaReintentar(ip)));
            responder(res, 429, "Demasiados intentos con clave incorrecta. Espere un minuto, señor.");
            return;
        }
        if (!token.matches(req.getHeader("X-Jarvis-Token"))) {
            limiter.fallo(ip);
            if (limiter.bloqueado(ip)) log.warn("IP {} bloqueada un minuto por demasiados intentos de clave fallidos.", ip);
            responder(res, 401, "Acceso denegado. Clave incorrecta.");
            return;
        }
        String path = req.getRequestURI().substring(req.getContextPath().length());
        String nombre = null;
        Rol rol = null;
        if (usuarios.hayUsuarios()) {
            Optional<Usuarios.Sesion> s = usuarios.sesion(req.getHeader(CABECERA_USUARIO));
            if (s.isPresent()) {
                nombre = s.get().nombre();
                rol = s.get().rol();
            } else if (laptopAdmin && esLocal(ip)) {
                nombre = NOMBRE_LAPTOP;
                rol = Rol.ADMIN;
            } else if (!path.startsWith("/api/sesion")) {
                res.setHeader("X-Jarvis-Login", "requerido");
                responder(res, 401, "Inicie sesión con su usuario y PIN.");
                return;
            }
            Rol.Permiso necesario = permisoPara(req.getMethod(), path);
            if (rol != null && necesario != null && !rol.permisos.contains(necesario)) {
                responder(res, 403, "Su usuario (" + rol.descripcion.toLowerCase() + ") no tiene permiso para " + necesario.accion + ".");
                return;
            }
        }
        ClientSession.abrir(req.getHeader(ClientSession.CABECERA), ip, nombre, rol);
        try {
            chain.doFilter(req, res);
        } finally {
            ClientSession.cerrar();
        }
    }

    public static boolean esLocal(String ip) { return ip != null && LOCALES.contains(ip); }

    /** Permiso que pide cada parte de la API; null si basta con haber iniciado sesión. */
    static Rol.Permiso permisoPara(String metodo, String path) {
        boolean lee = "GET".equals(metodo);
        if (path.startsWith("/api/usuarios") || path.startsWith("/api/respaldos")
                || ((path.equals("/api/negocio") || path.equals("/api/impresion")) && !lee))
            return Rol.Permiso.ADMINISTRAR;
        if (path.equals("/api/reportes/panel") || path.equals("/api/reportes/ventas.xlsx")) return Rol.Permiso.REPORTES;
        if (path.equals("/api/reportes/compras.xlsx") || path.startsWith("/api/compras") || path.startsWith("/api/proveedores"))
            return Rol.Permiso.COMPRAS;
        if (path.startsWith("/api/reportes/") || path.startsWith("/api/inventory/valorizado") || path.startsWith("/api/inventory/kardex"))
            return Rol.Permiso.VER_COSTOS;
        if (path.startsWith("/api/inventory/productos") || path.startsWith("/api/inventory/movimientos")) return Rol.Permiso.INVENTARIO;
        if (path.startsWith("/api/inventory/ventas/ultima")) return Rol.Permiso.ANULAR_VENTAS;
        if (path.startsWith("/api/inventory/ventas") || (path.startsWith("/api/inventory/venta") && !lee)) return Rol.Permiso.VENDER;
        if (path.startsWith("/api/inventory/comprobantes")) return Rol.Permiso.COMPROBANTES;
        if (path.startsWith("/api/clientes")) return Rol.Permiso.CLIENTES;
        return null;
    }

    private static void responder(HttpServletResponse res, int status, String mensaje) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(JSON.writeValueAsString(Map.of("ok", false, "reply", mensaje, "error", mensaje)));
    }
}
