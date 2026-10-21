package com.voces.backend.editor;

import com.voces.backend.config.SeguridadConfig.Sesion;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.modelo.Usuario;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * "Agregar al directorio", del lado de la app.
 *
 * La app solo enseña la sección cuando el perfil trae {@code editor} en
 * true, pero cada ruta lo vuelve a comprobar: si el equipo quita el permiso
 * con la pantalla abierta, lo siguiente que intente la persona recibe un 403
 * con un mensaje que se puede enseñar tal cual.
 */
@RestController
@RequestMapping("/api/editor")
public class EditorController {

    private final EditorService servicio;
    private final Repositorios.Usuarios usuarios;

    public EditorController(EditorService servicio, Repositorios.Usuarios usuarios) {
        this.servicio = servicio;
        this.usuarios = usuarios;
    }

    /** Lo que agregó esta cuenta y cuántas altas le quedan hoy. */
    @GetMapping
    public EditorService.LoQueAgregue loQueAgregue() {
        return servicio.loQueAgregue(usuarioActual());
    }

    /** Un canal de YouTube por su enlace, su @handle o su ID, y si ya está en el directorio. */
    @GetMapping("/canal")
    public EditorService.CanalEncontrado buscar(@RequestParam String query) {
        return servicio.buscar(usuarioActual(), query);
    }

    @PostMapping("/creadores")
    public EditorService.Agregado creador(@RequestBody EditorService.NuevoCreador pedido) {
        return servicio.agregarCreador(usuarioActual(), pedido);
    }

    @PostMapping("/medios")
    public EditorService.Agregado medio(@RequestBody EditorService.NuevoMedio pedido) {
        return servicio.agregarMedio(usuarioActual(), pedido);
    }

    @PostMapping("/canales")
    public EditorService.Agregado canal(@RequestBody EditorService.NuevoCanal pedido) {
        return servicio.agregarCanal(usuarioActual(), pedido);
    }

    private Usuario usuarioActual() {
        return usuarios.findById(Sesion.exigir())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Tu sesión ya no es válida. Vuelve a abrir la app."));
    }
}
