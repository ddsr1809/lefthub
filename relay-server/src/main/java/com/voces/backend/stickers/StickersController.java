package com.voces.backend.stickers;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Los stickers para la app.
 *
 * A diferencia de las fotos de perfil, las imágenes piden sesión: mientras el
 * equipo no los encienda para una cuenta, esa cuenta no los recibe. La app
 * las baja con su token y las guarda en su carpeta; el panel, igual.
 */
@RestController
@RequestMapping("/api/stickers")
public class StickersController {

    private final StickersService servicio;

    public StickersController(StickersService servicio) {
        this.servicio = servicio;
    }

    /** Los paquetes publicados. 403 si esta cuenta no ve los stickers. */
    @GetMapping
    public List<StickersDtos.PaqueteApp> paquetes() {
        return servicio.paraLaApp();
    }

    @GetMapping("/{id}/imagen")
    public ResponseEntity<byte[]> imagen(@PathVariable String id) {
        return archivo(servicio.imagen(uuid(id)));
    }

    @GetMapping("/paquetes/{id}/icono")
    public ResponseEntity<byte[]> icono(@PathVariable String id) {
        return archivo(servicio.icono(uuid(id)));
    }

    private static ResponseEntity<byte[]> archivo(StickersService.Archivo a) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(a.tipo()))
                // La dirección lleva la versión del paquete (?v=): si cambia,
                // es otra dirección. Privada: pide sesión.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .body(a.datos());
    }

    private static UUID uuid(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese sticker no existe.");
        }
    }
}
