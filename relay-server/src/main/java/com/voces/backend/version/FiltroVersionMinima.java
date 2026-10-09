package com.voces.backend.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voces.backend.config.RelayProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Corta el paso a las apps que ya no se atienden: las anteriores a la versión
 * mínima y las versiones dadas de baja desde el panel.
 *
 * Mientras APP_VERSION_MINIMA_ANDROID y APP_VERSION_MINIMA_IOS estén vacías y
 * no haya ninguna versión dada de baja, no hace nada. Si no, la app recibe un 426
 * con un mensaje pensado para leerse en el teléfono: todas las versiones de
 * la app leen el campo {@code message} de un error.
 *
 * Qué peticiones cuentan y cómo se comparan las versiones está en
 * {@link VersionDeApp}.
 */
public class FiltroVersionMinima extends OncePerRequestFilter {

    static final String MENSAJE =
            "Esta versión de la app ya no funciona. Actualízala desde la tienda para seguir usándola.";

    private final RelayProperties.Apps apps;
    private final AppsService bajas;
    private final ObjectMapper json = new ObjectMapper();

    public FiltroVersionMinima(RelayProperties.Apps apps, AppsService bajas) {
        this.apps = apps;
        this.bajas = bajas;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest peticion,
            @NonNull HttpServletResponse respuesta,
            @NonNull FilterChain cadena
    ) throws ServletException, IOException {

        boolean rechazada = VersionDeApp.rechazada(
                apps.minimaAndroid(), apps.minimaIos(), bajas.bajas(),
                peticion.getMethod(), peticion.getServletPath(),
                peticion.getHeader(VersionDeApp.CABECERA_PLATAFORMA),
                peticion.getHeader(VersionDeApp.CABECERA_VERSION));

        if (!rechazada) {
            cadena.doFilter(peticion, respuesta);
            return;
        }

        // El mismo cuerpo que arma ManejadorDeErrores, que aquí todavía no
        // interviene: los filtros corren antes que los controladores.
        HttpStatus estado = HttpStatus.UPGRADE_REQUIRED;
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("timestamp", Instant.now().toString());
        cuerpo.put("status", estado.value());
        cuerpo.put("error", estado.getReasonPhrase());
        cuerpo.put("message", MENSAJE);
        cuerpo.put("path", peticion.getRequestURI());

        respuesta.setStatus(estado.value());
        respuesta.setContentType(MediaType.APPLICATION_JSON_VALUE);
        respuesta.setCharacterEncoding("UTF-8");
        json.writeValue(respuesta.getWriter(), cuerpo);
    }

    /** Sin mínima ni versiones dadas de baja no hay nada que mirar. */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest peticion) {
        return !apps.hayMinima() && bajas.bajas().isEmpty();
    }
}
