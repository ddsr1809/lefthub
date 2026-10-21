package com.voces.backend.stickers;

import com.voces.backend.modelo.Dtos;
import com.voces.backend.stickers.StickersDtos.*;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Sección "Stickers" del panel: los paquetes de cada creador y quién los ve.
 *
 * Vive bajo /api/admin, así que SeguridadConfig ya exige el rol de
 * administrador en cada llamada. El alcance (apagado, admins, elegidos,
 * todos) se cambia con PUT /api/admin/ajustes, como los demás ajustes.
 */
@RestController
@RequestMapping("/api/admin")
public class StickersAdminController {

    private final StickersService servicio;

    public StickersAdminController(StickersService servicio) {
        this.servicio = servicio;
    }

    @GetMapping("/stickers")
    public PanelStickers panel() {
        return servicio.panel();
    }

    @PostMapping("/stickers/paquetes")
    public Hecho crear(@RequestBody CrearPaquete pedido) {
        return servicio.crear(pedido);
    }

    @PutMapping("/stickers/paquetes/{id}")
    public Hecho cambiar(@PathVariable UUID id, @RequestBody CambiarPaquete pedido) {
        return servicio.cambiar(id, pedido);
    }

    @DeleteMapping("/stickers/paquetes/{id}")
    public Dtos.RespuestaSimple borrar(@PathVariable UUID id) {
        servicio.borrar(id);
        return Dtos.RespuestaSimple.de("Paquete borrado. Quien ya lo había agregado a WhatsApp lo conserva.");
    }

    @PutMapping("/stickers/paquetes/{id}/icono")
    public Hecho icono(@PathVariable UUID id, @RequestBody SubirImagen pedido) {
        return servicio.ponerIcono(id, pedido);
    }

    @PostMapping("/stickers/paquetes/{id}/stickers")
    public Hecho agregar(@PathVariable UUID id, @RequestBody SubirImagen pedido) {
        return servicio.agregar(id, pedido);
    }

    @PutMapping("/stickers/paquetes/{id}/orden")
    public Hecho ordenar(@PathVariable UUID id, @RequestBody Orden pedido) {
        return servicio.ordenar(id, pedido);
    }

    @PutMapping("/stickers/{id}")
    public Hecho cambiarSticker(@PathVariable UUID id, @RequestBody CambiarSticker pedido) {
        return servicio.cambiarSticker(id, pedido);
    }

    @DeleteMapping("/stickers/{id}")
    public Hecho quitarSticker(@PathVariable UUID id) {
        return servicio.quitarSticker(id);
    }

    /** Elige o deja de elegir una cuenta. Solo cuenta con el alcance en "elegidos". */
    @PostMapping("/usuarios/{id}/stickers")
    public Dtos.RespuestaSimple elegir(@PathVariable UUID id, @RequestParam boolean valor) {
        return Dtos.RespuestaSimple.de(servicio.elegir(id, valor));
    }
}
