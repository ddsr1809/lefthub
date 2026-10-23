package com.voces.backend.config;

import com.voces.backend.seguridad.EstadoDeSesiones;
import com.voces.backend.auth.ServicioJwt;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lee el token de sesion de la cabecera Authorization y monta la autenticacion
 * de Spring.
 *
 * Una firma valida no basta: la cuenta tiene que seguir existiendo, y nadie
 * tiene que haber cerrado sus sesiones despues de emitir el token (ver
 * seguridad/EstadoDeSesiones). ROLE_ADMIN exige las dos cosas: que el token
 * se emitiera con el rol (claim 'adm', solo al entrar con Google o Apple) y
 * que la cuenta lo siga teniendo en la base. Quitar el rol en el panel surte
 * efecto en segundos, no cuando caduca el token.
 *
 * Un token que no pasa se trata como una peticion sin sesion: las rutas que
 * la exigen contestan 401 y la app abre una nueva.
 */
public class FiltroJwt extends OncePerRequestFilter {

    private static final String PREFIJO = "Bearer ";

    private final ServicioJwt jwt;
    private final EstadoDeSesiones estados;

    public FiltroJwt(ServicioJwt jwt, EstadoDeSesiones estados) {
        this.jwt = jwt;
        this.estados = estados;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest peticion,
            @NonNull HttpServletResponse respuesta,
            @NonNull FilterChain cadena
    ) throws ServletException, IOException {

        String cabecera = peticion.getHeader("Authorization");

        if (cabecera != null && cabecera.startsWith(PREFIJO)) {
            jwt.verificar(cabecera.substring(PREFIJO.length()).trim())
               .ifPresent(sesion -> {
                   // Si la base no responde, la excepcion sube y la peticion
                   // falla con 500: mejor que un 401, que haria a la app
                   // olvidar una sesion que sigue siendo buena.
                   EstadoDeSesiones.Estado estado = estados.de(sesion.usuarioId()).orElse(null);
                   if (estado == null || estado.revoca(sesion.emitido())) return;

                   List<SimpleGrantedAuthority> permisos = new ArrayList<>();
                   permisos.add(new SimpleGrantedAuthority("ROLE_USUARIO"));
                   if (sesion.esAdmin() && estado.esAdmin()) {
                       permisos.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                   }
                   SecurityContextHolder.getContext().setAuthentication(
                           new UsernamePasswordAuthenticationToken(
                                   sesion.usuarioId(), null, permisos));
               });
        }

        cadena.doFilter(peticion, respuesta);
    }

    /** El webhook no lleva token: se autentica por HMAC. */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest peticion) {
        return peticion.getServletPath().startsWith("/websub");
    }
}
