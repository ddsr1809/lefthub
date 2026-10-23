package com.voces.backend.seguridad;

import com.voces.backend.acceso.RegistroDeAcceso;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cuántas veces por minuto se puede entrar desde una misma IP.
 *
 * Cada llamada a /api/auth/anonimo con un identificador nuevo crea una cuenta.
 * Sin límite, un script podía llenar la tabla de usuarios, inflar las cifras
 * del panel o probar tokens de Google y Apple sin parar. Aplica solo a las
 * rutas de entrar (/api/auth/...), no al resto de la app.
 *
 * Es generoso a propósito: en México muchos teléfonos salen a internet por la
 * misma IP de su compañía (CGNAT), y una persona mayor que no puede entrar no
 * vuelve. 60 de margen y una más cada dos segundos (unas 43 000 al día por
 * IP). Corta una inundación; no estorba a nadie que use la app.
 *
 * Vive en memoria, como anuncios/Intentos: hay un solo servidor, y si se
 * reinicia lo peor que pasa es que todas las IPs recuperan su margen.
 */
public class LimiteDePeticiones extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LimiteDePeticiones.class);

    static final int MARGEN = 60;
    static final Duration RECARGA = Duration.ofSeconds(2);
    private static final int TOPE = 20_000;
    private static final String RUTAS = "/api/auth/";

    private final Clock reloj;
    private final Map<String, Cubeta> cubetas = new ConcurrentHashMap<>();

    public LimiteDePeticiones() {
        this(Clock.systemUTC());
    }

    LimiteDePeticiones(Clock reloj) {
        this.reloj = reloj;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest peticion) {
        return !peticion.getServletPath().startsWith(RUTAS);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest peticion,
                                    @NonNull HttpServletResponse respuesta,
                                    @NonNull FilterChain cadena) throws ServletException, IOException {
        String ip = RegistroDeAcceso.ipDelCliente(peticion);
        if (ip != null && !permitir(ip, reloj.instant())) {
            log.warn("Demasiados inicios de sesión desde una misma IP; se frena un momento");
            respuesta.setStatus(429);
            respuesta.setHeader("Retry-After", String.valueOf(RECARGA.toSeconds() * 5));
            respuesta.setContentType("application/json");
            respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());
            respuesta.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\","
                    + "\"message\":\"Hay demasiadas personas entrando desde tu red. Vuelve a intentarlo en unos segundos.\"}");
            return;
        }
        cadena.doFilter(peticion, respuesta);
    }

    /** ¿Le queda margen a esta IP? Si sí, lo gasta. */
    boolean permitir(String ip, Instant ahora) {
        if (cubetas.size() >= TOPE) {
            cubetas.values().removeIf(c -> c.llena(ahora));
            if (cubetas.size() >= TOPE) cubetas.clear();
        }
        return cubetas.computeIfAbsent(ip, k -> new Cubeta(ahora)).gastar(ahora);
    }

    private static final class Cubeta {

        private double fichas = MARGEN;
        private Instant ultima;

        Cubeta(Instant ahora) {
            this.ultima = ahora;
        }

        synchronized boolean gastar(Instant ahora) {
            recargar(ahora);
            if (fichas < 1) return false;
            fichas -= 1;
            return true;
        }

        synchronized boolean llena(Instant ahora) {
            recargar(ahora);
            return fichas >= MARGEN;
        }

        private void recargar(Instant ahora) {
            long transcurrido = Duration.between(ultima, ahora).toMillis();
            if (transcurrido <= 0) return;
            fichas = Math.min(MARGEN, fichas + transcurrido / (double) RECARGA.toMillis());
            ultima = ahora;
        }
    }
}
