package com.voces.backend.directorio;

import com.voces.backend.config.SeguridadConfig.Sesion;
import com.voces.backend.modelo.Dtos;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Los videos que la persona ya abrió o marcó como vistos, guardados con su
 * cuenta. La app los lee al arrancar y al cambiar de cuenta, y los apunta al
 * abrir un video o al tocar "Ya lo vi" en su ficha.
 *
 * <ul>
 *   <li>GET  /api/vistos: todos (los 500 más recientes).</li>
 *   <li>PUT  /api/vistos/{videoId}?aMano=true|false: apuntar uno.</li>
 *   <li>POST /api/vistos: apuntar varios de una vez. Lo usa la app al
 *       actualizarse para subir lo que tenía guardado en el teléfono, y para
 *       mandar lo que apuntó sin conexión.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/vistos")
public class VistosController {

    private final VideosVistos vistos;

    public VistosController(VideosVistos vistos) {
        this.vistos = vistos;
    }

    @GetMapping
    public Dtos.Vistos listar() {
        List<VideosVistos.Marca> marcas = vistos.de(Sesion.exigir());
        return new Dtos.Vistos(
                marcas.stream().map(VideosVistos.Marca::videoId).toList(),
                marcas.stream().filter(VideosVistos.Marca::aMano).map(VideosVistos.Marca::videoId).toList()
        );
    }

    @PutMapping("/{videoId}")
    public Dtos.RespuestaSimple marcar(@PathVariable String videoId,
                                       @RequestParam(defaultValue = "false") boolean aMano) {
        if (!VideosVistos.idValido(videoId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ese video no es válido.");
        }
        vistos.marcar(Sesion.exigir(), List.of(videoId), aMano);
        return Dtos.RespuestaSimple.de(aMano ? "Lo marcamos como visto." : "Apuntado.");
    }

    @PostMapping
    public Dtos.RespuestaSimple marcarVarios(@RequestBody Dtos.MarcarVistos peticion) {
        UUID uid = Sesion.exigir();
        if (peticion == null || peticion.videos() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Faltan los videos.");
        }
        if (peticion.videos().size() > VideosVistos.TOPE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Son demasiados videos de una vez.");
        }
        int n = vistos.marcar(uid, peticion.videos(), Boolean.TRUE.equals(peticion.aMano()));
        return Dtos.RespuestaSimple.de(n == 1 ? "Un video apuntado." : n + " videos apuntados.");
    }
}
