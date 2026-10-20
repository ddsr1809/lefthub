package com.voces.backend.recomendaciones;

import com.voces.backend.config.SeguridadConfig.Sesion;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.modelo.Usuario;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Recomendar un canal", del lado de la app.
 *
 * La app solo enseña la sección cuando el perfil trae {@code recomendar} en
 * true. Aun así cada ruta lo vuelve a comprobar: si el equipo lo apaga con la
 * pantalla abierta, lo siguiente que intente la persona recibe un 403 con un
 * mensaje que se puede enseñar tal cual.
 */
@RestController
@RequestMapping("/api/recomendaciones")
public class RecomendacionesController {

    /** Lo que manda la app. El motivo es opcional. */
    public record Recomendar(String texto, String motivo) {}

    private final RecomendacionesService servicio;
    private final Repositorios.Usuarios usuarios;

    public RecomendacionesController(RecomendacionesService servicio, Repositorios.Usuarios usuarios) {
        this.servicio = servicio;
        this.usuarios = usuarios;
    }

    /** Las que mandó esta cuenta y cuántas puede mandar todavía hoy. */
    @GetMapping
    public RecomendacionesService.MisRecomendaciones mias() {
        return servicio.mias(usuarioActual());
    }

    @PostMapping
    public RecomendacionesService.Enviada recomendar(@RequestBody Recomendar peticion) {
        return servicio.recomendar(usuarioActual(), peticion.texto(), peticion.motivo());
    }

    private Usuario usuarioActual() {
        return usuarios.findById(Sesion.exigir())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Tu sesión ya no es válida. Vuelve a abrir la app."));
    }
}
