package com.jarvis.security;

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

/**
 * Puerta de entrada de toda la API (/api/**, salvo /api/status que solo dice "online").
 * Verifica la clave en tiempo constante y frena la fuerza bruta: tras N fallos por minuto
 * desde una IP, responde 429 a esa IP (aunque luego mande la clave correcta) hasta que pase el minuto.
 * Centralizarlo aquí evita que un endpoint nuevo se olvide de comprobar la clave.
 */
@Component
public class ApiAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthFilter.class);

    private final AccessToken token;
    private final FailureLimiter limiter;

    public ApiAuthFilter(AccessToken token,
                         @Value("${jarvis.security.max-failures-per-minute:10}") int maxFallos) {
        this.token = token;
        this.limiter = new FailureLimiter(maxFallos, 60_000);
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
        ClientSession.abrir(req.getHeader(ClientSession.CABECERA), ip);
        try {
            chain.doFilter(req, res);
        } finally {
            ClientSession.cerrar();   // los hilos de Tomcat se reutilizan: no dejar la sesión pegada
        }
    }

    /** El panel web muestra "reply", así que los errores usan el mismo formato que /api/command. */
    private static void responder(HttpServletResponse res, int status, String mensaje) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json");
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write("{\"reply\":\"" + mensaje + "\",\"error\":\"" + mensaje + "\"}");
    }
}
