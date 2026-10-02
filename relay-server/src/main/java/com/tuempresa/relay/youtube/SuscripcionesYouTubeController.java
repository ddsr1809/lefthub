package com.tuempresa.relay.youtube;

import com.tuempresa.relay.config.SeguridadConfig.Sesion;
import com.tuempresa.relay.modelo.Dtos;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Suscripciones de YouTube del usuario que tiene la sesión.
 *
 * Las tres rutas caen bajo /api/**, así que exigen sesión sin tocar
 * SeguridadConfig. La cuenta sale siempre del token de Relé, nunca de un
 * parámetro: nadie puede consultar ni borrar las de otra persona.
 */
@RestController
@RequestMapping("/api/youtube/suscripciones")
public class SuscripcionesYouTubeController {

    private final SuscripcionesYouTubeService servicio;

    public SuscripcionesYouTubeController(SuscripcionesYouTubeService servicio) {
        this.servicio = servicio;
    }

    /** Lo último comprobado. La app lo pinta mientras llega la comprobación nueva. */
    @GetMapping
    public Dtos.SuscripcionesYouTube guardadas() {
        return servicio.guardadas(Sesion.exigir());
    }

    /** La app lo llama al abrirse, con un token de acceso recién sacado. */
    @PostMapping
    public Dtos.SuscripcionesYouTube verificar(@Valid @RequestBody Dtos.VerificarYouTube peticion) {
        return servicio.verificar(Sesion.exigir(), peticion.accessToken());
    }

    @DeleteMapping
    public Dtos.RespuestaSimple olvidar() {
        servicio.olvidar(Sesion.exigir());
        return Dtos.RespuestaSimple.de("Dejamos de consultar tus suscripciones de YouTube.");
    }
}
