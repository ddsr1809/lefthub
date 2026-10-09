package com.voces.backend.directorio;

import com.voces.backend.modelo.Dtos;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Las etiquetas: lo que lee la app (/api/etiquetas) y lo que maneja el panel
 * (/api/admin/etiquetas). Quién puede entrar a cada ruta lo decide
 * SeguridadConfig por el prefijo, como en el resto.
 */
@RestController
public class EtiquetasController {

    private final EtiquetasService etiquetas;

    public EtiquetasController(EtiquetasService etiquetas) {
        this.etiquetas = etiquetas;
    }

    /** Las etiquetas encendidas, con lo visible que lleva cada una. Sin ninguna, lista vacía. */
    @GetMapping("/api/etiquetas")
    public List<Dtos.EtiquetaDto> paraLaApp() {
        return etiquetas.paraLaApp();
    }

    @GetMapping("/api/admin/etiquetas")
    public List<Dtos.EtiquetaAdminDto> todas() {
        return etiquetas.todas();
    }

    @PostMapping("/api/admin/etiquetas")
    public Dtos.EtiquetaAdminDto guardar(@RequestBody Dtos.GuardarEtiqueta peticion) {
        return etiquetas.guardar(peticion);
    }

    @DeleteMapping("/api/admin/etiquetas/{id}")
    public Dtos.RespuestaSimple borrar(@PathVariable UUID id) {
        etiquetas.borrar(id);
        return Dtos.RespuestaSimple.de("Etiqueta eliminada.");
    }

    /** Las etiquetas de un creador, de una productora o de un canal: las deja como se piden. */
    @PutMapping("/api/admin/etiquetas/de/{tipo}/{id}")
    public Dtos.EtiquetasAsignadas asignar(@PathVariable String tipo, @PathVariable UUID id,
                                           @RequestBody Dtos.EtiquetasAsignadas peticion) {
        return new Dtos.EtiquetasAsignadas(etiquetas.asignar(tipo, id, peticion.etiquetas()));
    }
}
