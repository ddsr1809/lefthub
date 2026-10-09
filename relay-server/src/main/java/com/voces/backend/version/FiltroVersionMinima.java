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
 * mínima y las versiones dadas de baja desde el panel. Y a todas, mientras el
 * servidor esté en mantenimiento: entonces la respuesta es un 503 con
 * {@code "mantenimiento": true} y el mensaje que se escribió en el panel.
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
    private final MantenimientoService mantenimiento;
    private final ObjectMapper json = new ObjectMapper();

    public FiltroVersionMinima(RelayProperties.Apps apps, AppsService bajas,
                               MantenimientoService mantenimiento) {
        this.apps = apps;
        this.bajas = bajas;
        this.mantenimiento = mantenimiento;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest peticion,
            @NonNull HttpServletResponse respuesta,
            @NonNull FilterChain cadena
    ) throws ServletException, IOException {

        String plataforma = peticion.getHeader(VersionDeApp.CABECERA_PLATAFORMA);

        // Primero la versión: a quien tiene que actualizar no le sirve de
        // nada esperar a que termine el mantenimiento.
        boolean rechazada = VersionDeApp.rechazada(
                apps.minimaAndroid(), apps.minimaIos(), bajas.bajas(),
                peticion.getMethod(), peticion.getServletPath(), plataforma,
                peticion.getHeader(VersionDeApp.CABECERA_VERSION));
        if (rechazada) {
            responder(peticion, respuesta, HttpStatus.UPGRADE_REQUIRED, MENSAJE, false);
            return;
        }

        MantenimientoService.Estado estado = mantenimiento.estado();
        if (estado.activo()
                && VersionDeApp.deUnaApp(peticion.getMethod(), peticion.getServletPath(), plataforma)) {
            responder(peticion, respuesta, HttpStatus.SERVICE_UNAVAILABLE, estado.mensaje(), true);
            return;
        }

        cadena.doFilter(peticion, respuesta);
    }

    /**
     * El mismo cuerpo que arma ManejadorDeErrores, que aquí todavía no
     * interviene: los filtros corren antes que los controladores.
     */
    private void responder(HttpServletRequest peticion, HttpServletResponse respuesta,
                           HttpStatus estado, String mensaje, boolean enMantenimiento) throws IOException {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("timestamp", Instant.now().toString());
        cuerpo.put("status", estado.value());
        cuerpo.put("error", estado.getReasonPhrase());
        cuerpo.put("message", mensaje);
        cuerpo.put("path", peticion.getRequestURI());
        // La app distingue así nuestro mantenimiento de un 503 del proxy,
        // que es lo que hay cuando el servidor está caído.
        if (enMantenimiento) cuerpo.put("mantenimiento", true);

        respuesta.setStatus(estado.value());
        respuesta.setContentType(MediaType.APPLICATION_JSON_VALUE);
        respuesta.setCharacterEncoding("UTF-8");
        json.writeValue(respuesta.getWriter(), cuerpo);
    }

    /** Sin mínima, sin versiones dadas de baja y sin mantenimiento no hay nada que mirar. */
    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest peticion) {
        return !apps.hayMinima() && bajas.bajas().isEmpty() && !mantenimiento.estado().activo();
    }
}
