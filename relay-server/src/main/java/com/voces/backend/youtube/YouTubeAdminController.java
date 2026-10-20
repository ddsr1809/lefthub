package com.voces.backend.youtube;

import com.voces.backend.config.SeguridadConfig.Sesion;
import com.voces.backend.directorio.AjustesService;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.modelo.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Elegir desde el panel las cuentas a las que se ofrece "Conectar con
 * YouTube" cuando el alcance está en "elegidos".
 */
@RestController
@RequestMapping("/api/admin")
public class YouTubeAdminController {

    private static final Logger log = LoggerFactory.getLogger(YouTubeAdminController.class);

    private final Repositorios.Usuarios usuarios;
    private final AjustesService ajustes;

    public YouTubeAdminController(Repositorios.Usuarios usuarios, AjustesService ajustes) {
        this.usuarios = usuarios;
        this.ajustes = ajustes;
    }

    /**
     * Elige o deja de elegir una cuenta. Solo cuenta mientras el alcance esté
     * en "elegidos"; la marca se guarda igual para no tener que repetirla si
     * se cambia de alcance y se vuelve.
     */
    @PostMapping("/usuarios/{id}/youtube")
    @Transactional
    public Dtos.RespuestaSimple elegir(@PathVariable UUID id, @RequestParam boolean valor) {
        Usuario usuario = usuarios.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Esa cuenta ya no existe."));
        usuario.setYoutubeHabilitado(valor);
        log.info("YouTube {} para {} por {}", valor ? "activado" : "desactivado", id, Sesion.exigir());

        String nota = switch (ajustes.youtube()) {
            case AjustesService.RECOMENDAR_ELEGIDOS -> valor
                    ? " Verá «Conectar con YouTube» en Ajustes la próxima vez que abra la app, si guardó su cuenta con Google."
                    : " Ya no lo verá en la app.";
            case AjustesService.RECOMENDAR_TODOS -> " Ahora mismo se ofrece a todos, así que no cambia nada hasta que el alcance sea «Administradores y cuentas elegidas».";
            default -> " No lo verá mientras el alcance de YouTube no sea «Administradores y cuentas elegidas».";
        };
        return Dtos.RespuestaSimple.de((valor ? "Cuenta elegida para YouTube." : "Cuenta quitada de YouTube.") + nota);
    }
}
