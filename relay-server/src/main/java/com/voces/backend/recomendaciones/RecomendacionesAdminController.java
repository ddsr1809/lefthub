package com.voces.backend.recomendaciones;

import com.voces.backend.config.SeguridadConfig.Sesion;
import com.voces.backend.directorio.AjustesService;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Recomendacion;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.modelo.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sección "Recomendaciones" del panel.
 *
 * Vive bajo /api/admin, así que SeguridadConfig ya exige el rol de
 * administrador en cada llamada. El alcance (apagado, admins, elegidos,
 * todos) se cambia con PUT /api/admin/ajustes, como los demás ajustes; aquí
 * se elige cuenta por cuenta y se revisa lo que llega.
 */
@RestController
@RequestMapping("/api/admin")
public class RecomendacionesAdminController {

    private static final Logger log = LoggerFactory.getLogger(RecomendacionesAdminController.class);

    private static final int MAX_LISTA = 500;

    /** Una recomendación como la ve el equipo, con quién la mandó. */
    public record RecomendacionAdminDto(
            Long id,
            UUID usuarioId,
            /** Correo de quien la mandó; null si es una cuenta de invitado. */
            String email,
            String proveedor,
            String texto,
            String motivo,
            String channelId,
            String titulo,
            String handle,
            String fotoUrl,
            String estado,
            boolean enDirectorio,
            Instant creadoEn,
            Instant revisadoEn
    ) {}

    /** Una cuenta elegida para recomendar, para la lista de la sección. */
    public record CuentaElegida(UUID id, String proveedor, String email, boolean esAdmin, Instant vistoEn) {}

    public record PanelRecomendaciones(
            /** apagado | admins | elegidos | todos */
            String alcance,
            long pendientes,
            List<CuentaElegida> elegidas,
            List<RecomendacionAdminDto> recomendaciones
    ) {}

    /** Varias a la vez: el panel agrupa las del mismo canal y las revisa juntas. */
    public record CambiarEstado(List<Long> ids, String estado) {}

    public record Borrar(List<Long> ids) {}

    private final RecomendacionesService servicio;
    private final Repositorios.Recomendaciones recomendaciones;
    private final Repositorios.Usuarios usuarios;
    private final AjustesService ajustes;

    public RecomendacionesAdminController(RecomendacionesService servicio,
                                          Repositorios.Recomendaciones recomendaciones,
                                          Repositorios.Usuarios usuarios,
                                          AjustesService ajustes) {
        this.servicio = servicio;
        this.recomendaciones = recomendaciones;
        this.usuarios = usuarios;
        this.ajustes = ajustes;
    }

    /** Todo lo que enseña la sección, de una vez. */
    @GetMapping("/recomendaciones")
    @Transactional(readOnly = true)
    public PanelRecomendaciones panel() {
        List<Recomendacion> lista = recomendaciones.findAllByOrderByCreadoEnDesc(PageRequest.of(0, MAX_LISTA));
        Set<String> delDirectorio = servicio.idsDelDirectorio();

        Set<UUID> autores = lista.stream().map(Recomendacion::getUsuarioId).collect(Collectors.toSet());
        Map<UUID, Usuario> porId = usuarios.findAllById(autores).stream()
                .collect(Collectors.toMap(Usuario::getId, Function.identity()));

        List<RecomendacionAdminDto> dtos = lista.stream().map(r -> {
            Usuario u = porId.get(r.getUsuarioId());
            return new RecomendacionAdminDto(r.getId(), r.getUsuarioId(),
                    u != null ? u.getEmail() : null, u != null ? u.getProveedor() : null,
                    r.getTexto(), r.getMotivo(), r.getChannelId(), r.getTitulo(), r.getHandle(),
                    r.getFotoUrl(), r.getEstado(),
                    r.getChannelId() != null && delDirectorio.contains(r.getChannelId()),
                    r.getCreadoEn(), r.getRevisadoEn());
        }).toList();

        List<CuentaElegida> elegidas = usuarios.findByRecomendarHabilitadoTrueOrderByVistoEnDesc().stream()
                .map(u -> new CuentaElegida(u.getId(), u.getProveedor(), u.getEmail(), u.isEsAdmin(), u.getVistoEn()))
                .toList();

        return new PanelRecomendaciones(ajustes.recomendaciones(),
                recomendaciones.countByEstado(Recomendacion.PENDIENTE), elegidas, dtos);
    }

    /** Para el número junto a la sección en el menú. */
    @GetMapping("/recomendaciones/cuantas")
    @Transactional(readOnly = true)
    public Map<String, Object> cuantas() {
        return Map.of("pendientes", recomendaciones.countByEstado(Recomendacion.PENDIENTE),
                "alcance", ajustes.recomendaciones());
    }

    @PostMapping("/recomendaciones/estado")
    public Dtos.RespuestaSimple cambiarEstado(@RequestBody CambiarEstado peticion) {
        int n = servicio.cambiarEstado(peticion.ids(), peticion.estado(), Sesion.exigir());
        boolean una = n == 1;
        String que = switch (peticion.estado()) {
            case Recomendacion.AGREGADO -> una ? "marcada como agregada" : "marcadas como agregadas";
            case Recomendacion.DESCARTADO -> una ? "descartada" : "descartadas";
            default -> una ? "pendiente otra vez" : "pendientes otra vez";
        };
        return Dtos.RespuestaSimple.de((una ? "Recomendación " : n + " recomendaciones ") + que + ".");
    }

    @PostMapping("/recomendaciones/borrar")
    public Dtos.RespuestaSimple borrar(@RequestBody Borrar peticion) {
        int n = servicio.borrar(peticion.ids());
        log.info("{} recomendaciones borradas por {}", n, Sesion.exigir());
        return Dtos.RespuestaSimple.de(n == 1 ? "Recomendación borrada." : n + " recomendaciones borradas.");
    }

    /**
     * Elige o deja de elegir una cuenta para recomendar. Solo cuenta mientras
     * el alcance esté en "elegidos"; la marca se guarda igual para no tener
     * que repetirla si se cambia de alcance y se vuelve.
     */
    @PostMapping("/usuarios/{id}/recomendar")
    @Transactional
    public Dtos.RespuestaSimple elegir(@PathVariable UUID id, @RequestParam boolean valor) {
        Usuario usuario = usuarios.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Esa cuenta ya no existe."));
        usuario.setRecomendarHabilitado(valor);
        log.info("Recomendar canales {} para {} por {}", valor ? "activado" : "desactivado", id, Sesion.exigir());

        String alcance = ajustes.recomendaciones();
        String nota = switch (alcance) {
            case AjustesService.RECOMENDAR_ELEGIDOS -> valor
                    ? " Lo verá en Ajustes de la app la próxima vez que la abra."
                    : " Ya no lo verá en la app.";
            case AjustesService.RECOMENDAR_TODOS -> " Ahora mismo pueden recomendar todos, así que no cambia nada hasta que cambies el alcance a «Administradores y cuentas elegidas».";
            default -> " No lo verá mientras el alcance no sea «Administradores y cuentas elegidas».";
        };
        return Dtos.RespuestaSimple.de((valor ? "Cuenta elegida para recomendar canales." : "Cuenta quitada de las que recomiendan.") + nota);
    }
}
