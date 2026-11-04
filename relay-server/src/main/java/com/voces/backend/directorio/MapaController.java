package com.voces.backend.directorio;

import com.voces.backend.config.SeguridadConfig.Sesion;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.modelo.Usuario;
import com.voces.backend.youtube.SuscripcionesYouTubeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * El mapa de selección de la cuenta: ver {@link MapaDeSeleccion}.
 *
 * <p>La app manda la huella del mapa que tiene guardado. Si sigue siendo esa,
 * la respuesta es un 204 sin cuerpo; si no, el mapa entero. Va como parámetro
 * y no como ETag porque un proxy que comprime, como el Apache de delante,
 * puede reescribir el ETag, y entonces nunca coincidiría.
 */
@RestController
@RequestMapping("/api/mapa")
public class MapaController {

    private final Repositorios.Usuarios usuarios;
    private final Catalogo catalogo;
    private final SuscripcionesYouTubeService youtube;

    public MapaController(Repositorios.Usuarios usuarios, Catalogo catalogo,
                          SuscripcionesYouTubeService youtube) {
        this.usuarios = usuarios;
        this.catalogo = catalogo;
        this.youtube = youtube;
    }

    @GetMapping
    public ResponseEntity<Dtos.Mapa> leer(@RequestParam(required = false) String huella) {
        Usuario usuario = usuarios.findById(Sesion.exigir())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Tu sesión ya no es válida. Vuelve a abrir la app."));

        Dtos.Mapa mapa = MapaDeSeleccion.de(usuario, catalogo.vista(), youtube.guardadas(usuario.getId()));

        return mapa.huella().equals(huella)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(mapa);
    }
}
