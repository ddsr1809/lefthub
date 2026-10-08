package com.tuempresa.relay.directorio;

import com.tuempresa.relay.modelo.Foto;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.UUID;

/**
 * Sirve las fotos de perfil que se guardaron aquí.
 *
 * Es la única ruta de /api que no pide sesión: quien carga la imagen es el
 * teléfono o el navegador, que no mandan el token al pedir una foto. No hay
 * nada que proteger: son fotos de perfil públicas de gente del directorio.
 */
@RestController
public class FotosController {

    private final FotosService fotos;

    public FotosController(FotosService fotos) {
        this.fotos = fotos;
    }

    @GetMapping("/api/fotos/{id}")
    public ResponseEntity<byte[]> foto(@PathVariable String id) {
        // Es una ruta abierta: lo que no sea un id de foto es un 404, sin más.
        UUID clave;
        try {
            clave = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Esa foto no existe.");
        }

        Foto foto = fotos.porId(clave).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Esa foto ya no existe."));

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(foto.getTipo()))
                // La dirección lleva la versión (?v=), así que se puede
                // guardar en caché todo el tiempo que se quiera.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
                .body(foto.getDatos());
    }
}
