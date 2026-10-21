package com.voces.backend.editor;

import com.voces.backend.config.SeguridadConfig.Sesion;
import com.voces.backend.modelo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sección "Editores" del panel: quién puede agregar al directorio desde la
 * app y qué agregó.
 *
 * Vive bajo /api/admin, así que SeguridadConfig ya exige el rol de
 * administrador en cada llamada.
 */
@RestController
@RequestMapping("/api/admin")
public class EditoresAdminController {

    private static final Logger log = LoggerFactory.getLogger(EditoresAdminController.class);

    private static final int MAX_LISTA = 300;

    public record CuentaEditora(UUID id, String proveedor, String email, boolean esAdmin,
                                Instant vistoEn, long altas) {}

    public record AltaAdminDto(
            Long id,
            /** creador | medio | canal */
            String tipo,
            UUID objetoId,
            String nombre,
            String channelId,
            UUID usuarioId,
            /** Correo de quien lo agregó, tal como era en ese momento. */
            String autor,
            Instant creadoEn,
            /** Sigue en el directorio. False si el equipo lo borró después. */
            boolean existe
    ) {}

    public record PanelEditores(List<CuentaEditora> editores, List<AltaAdminDto> altas) {}

    private final Repositorios.Usuarios usuarios;
    private final Repositorios.AltasDesdeApp altas;
    private final Repositorios.Creadores creadores;
    private final Repositorios.Productoras productoras;
    private final Repositorios.Canales canales;

    public EditoresAdminController(Repositorios.Usuarios usuarios, Repositorios.AltasDesdeApp altas,
                                   Repositorios.Creadores creadores, Repositorios.Productoras productoras,
                                   Repositorios.Canales canales) {
        this.usuarios = usuarios;
        this.altas = altas;
        this.creadores = creadores;
        this.productoras = productoras;
        this.canales = canales;
    }

    @GetMapping("/editores")
    @Transactional(readOnly = true)
    public PanelEditores panel() {
        List<AltaDesdeApp> lista = altas.findAllByOrderByCreadoEnDesc(PageRequest.of(0, MAX_LISTA));

        Set<UUID> vivos = new HashSet<>();
        vivos.addAll(ids(creadores.findAllById(delTipo(lista, AltaDesdeApp.CREADOR)), Creador::getId));
        vivos.addAll(ids(productoras.findAllById(delTipo(lista, AltaDesdeApp.MEDIO)), Productora::getId));
        vivos.addAll(ids(canales.findAllById(delTipo(lista, AltaDesdeApp.CANAL)), Canal::getId));

        List<AltaAdminDto> dtos = lista.stream().map(a -> new AltaAdminDto(a.getId(), a.getTipo(),
                a.getObjetoId(), a.getNombre(), a.getChannelId(), a.getUsuarioId(), a.getAutor(),
                a.getCreadoEn(), vivos.contains(a.getObjetoId()))).toList();

        List<CuentaEditora> editores = usuarios.findByEditorHabilitadoTrueOrderByVistoEnDesc().stream()
                .map(u -> new CuentaEditora(u.getId(), u.getProveedor(), u.getEmail(), u.isEsAdmin(),
                        u.getVistoEn(), altas.countByUsuarioId(u.getId())))
                .toList();

        return new PanelEditores(editores, dtos);
    }

    /** Da o quita el permiso de agregar al directorio desde la app. */
    @PostMapping("/usuarios/{id}/editor")
    @Transactional
    public Dtos.RespuestaSimple cambiar(@PathVariable UUID id, @RequestParam boolean valor) {
        Usuario usuario = usuarios.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Esa cuenta ya no existe."));
        if (valor && usuario.esAnonimo()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Es una cuenta de invitado. Primero tiene que guardarla con Google o Apple desde Ajustes de la app.");
        }
        usuario.setEditorHabilitado(valor);
        log.info("Agregar desde la app {} para {} por {}", valor ? "activado" : "desactivado", id, Sesion.exigir());

        return Dtos.RespuestaSimple.de(valor
                ? "Listo. Verá «Agregar al directorio» en Ajustes de la app la próxima vez que la abra. Lo que agregue sale en el acto, sin pasar por aquí."
                : "Ya no puede agregar al directorio desde la app. Lo que ya agregó se queda.");
    }

    private static List<UUID> delTipo(List<AltaDesdeApp> lista, String tipo) {
        return lista.stream().filter(a -> tipo.equals(a.getTipo())).map(AltaDesdeApp::getObjetoId).distinct().toList();
    }

    private static <T> Set<UUID> ids(List<T> lista, java.util.function.Function<T, UUID> id) {
        return lista.stream().map(id).collect(Collectors.toSet());
    }
}
