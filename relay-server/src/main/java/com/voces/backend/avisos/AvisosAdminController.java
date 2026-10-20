package com.voces.backend.avisos;

import com.voces.backend.config.SeguridadConfig;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Avisos del equipo desde el panel: el historial y el envío.
 *
 * Todo bajo /api/admin exige ROLE_ADMIN (SeguridadConfig).
 */
@RestController
@RequestMapping("/api/admin/avisos")
public class AvisosAdminController {

    private final AvisosService servicio;

    public AvisosAdminController(AvisosService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<AvisosService.AvisoDto> recientes(@RequestParam(defaultValue = "50") int limite) {
        return servicio.recientes(limite);
    }

    /**
     * Manda un aviso y lo deja en el historial. Si FCM lo rechaza, responde
     * igual con el aviso guardado: {@code enviado} en false y el motivo en
     * {@code error}.
     */
    @PostMapping
    public AvisosService.AvisoDto enviar(@RequestBody AvisosService.Peticion peticion) {
        return servicio.enviar(peticion, SeguridadConfig.Sesion.uidActual());
    }
}
