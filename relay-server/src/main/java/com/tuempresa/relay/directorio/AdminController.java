package com.tuempresa.relay.directorio;

import com.tuempresa.relay.directorio.CanalesService.Cambio;
import com.tuempresa.relay.modelo.*;
import com.tuempresa.relay.push.Emisores;
import com.tuempresa.relay.push.PushService;
import com.tuempresa.relay.youtube.YouTubeClient;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Panel de moderación.
 *
 * Todo bajo /api/admin exige ROLE_ADMIN, que sale del claim del token de
 * sesión. El primer administrador se nombra con una sentencia SQL; los
 * siguientes, desde aquí.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final Pattern ID_CANAL = Pattern.compile("(UC[\\w-]{22})");
    private static final Pattern HANDLE = Pattern.compile("@([\\w.-]+)");

    private final Repositorios.Creadores creadores;
    private final Repositorios.Productoras productoras;
    private final Repositorios.Publicaciones publicaciones;
    private final Repositorios.Usuarios usuarios;
    private final Repositorios.Suscripciones suscripciones;
    private final Repositorios.Reportes reportes;
    private final YouTubeClient youtube;
    private final PushService push;
    private final Emisores emisores;
    private final CreadoresService servicio;
    private final ProductorasService servicioDeProductoras;
    private final CanalesService canalesService;
    private final Catalogo catalogo;
    private final ReplicaService replica;

    public AdminController(Repositorios.Creadores creadores,
                           Repositorios.Productoras productoras,
                           Repositorios.Publicaciones publicaciones,
                           Repositorios.Usuarios usuarios,
                           Repositorios.Suscripciones suscripciones,
                           Repositorios.Reportes reportes,
                           YouTubeClient youtube, PushService push, Emisores emisores,
                           CreadoresService servicio, ProductorasService servicioDeProductoras,
                           CanalesService canalesService, Catalogo catalogo,
                           ReplicaService replica) {
        this.creadores = creadores;
        this.productoras = productoras;
        this.publicaciones = publicaciones;
        this.usuarios = usuarios;
        this.suscripciones = suscripciones;
        this.reportes = reportes;
        this.youtube = youtube;
        this.push = push;
        this.emisores = emisores;
        this.servicio = servicio;
        this.servicioDeProductoras = servicioDeProductoras;
        this.canalesService = canalesService;
        this.catalogo = catalogo;
        this.replica = replica;
    }

    // -------------------------------------------------------------------------
    // Creadores
    // -------------------------------------------------------------------------

    /** Listado con el estado de la suscripción, que es lo que pinta el testigo. */
    @GetMapping("/creadores")
    @Transactional(readOnly = true)
    public List<Dtos.CreadorAdminDto> listar() {
        Catalogo.Vista vista = catalogo.vista();
        Map<String, Suscripcion> estados = estadosDelHub();

        return creadores.findAll().stream()
                .sorted(Comparator.comparing(Creador::getNombre))
                .map(c -> {
                    List<Canal> suyos = vista.canalesDe(c.getId());
                    Suscripcion s = resumenDe(suyos, estados);

                    return new Dtos.CreadorAdminDto(
                            c.getId(), c.getNombre(), c.getCategoria(), c.getBio(),
                            c.getFotoUrl(), c.isActivo(),
                            Dtos.ConexionDto.principales(suyos),
                            s != null ? s.getEstado() : null,
                            s != null ? s.getExpiraEn() : null,
                            usuarios.cuantosSiguen(c.getId()),
                            suyos.stream().map(k -> canalAdmin(k, c.getNombre(), estados)).toList(),
                            List.copyOf(c.getProductoras()));
                })
                .toList();
    }

    @PostMapping("/creadores")
    @Transactional
    public Dtos.CreadorGuardado guardar(@Valid @RequestBody Dtos.GuardarCreador peticion) {
        Creador creador = peticion.id() != null
                ? creadores.findById(peticion.id()).orElseGet(Creador::new)
                : new Creador();

        Cambio cambio = servicio.aplicar(creador, peticion);

        // Sincronizar WebSub: bajas de los canales que dejó, altas de los que
        // tiene, o baja de todos si quedó oculto.
        String avisoSuscripcion = null;
        try {
            canalesService.sincronizarWebSub(cambio);
        } catch (Exception e) {
            // El creador queda guardado aunque el hub falle; la renovación
            // programada vuelve a intentarlo en el siguiente ciclo.
            log.error("No se pudo sincronizar la suscripción de {}", creador.getId(), e);
            avisoSuscripcion = e.getMessage();
        }

        // En producción, copiar a testing. En los demás ambientes no hace
        // nada. Tampoco lanza: si testing falla, el panel lo avisa y ya.
        String avisoReplica = replica.enviar(creador);

        return new Dtos.CreadorGuardado(creador.getId(), avisoSuscripcion, avisoReplica);
    }

    @DeleteMapping("/creadores/{id}")
    @Transactional
    public Dtos.RespuestaSimple borrar(@PathVariable UUID id) {
        Creador creador = creadores.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ese creador ya no existe."));

        darDeBaja(servicio.alRetirar(creador));

        // Sus canales, sus publicaciones y quién lo sigue se van en cascada
        // por las claves foráneas.
        creadores.delete(creador);
        return Dtos.RespuestaSimple.de("Creador retirado del directorio.");
    }

    // -------------------------------------------------------------------------
    // Productoras
    // -------------------------------------------------------------------------

    @GetMapping("/productoras")
    @Transactional(readOnly = true)
    public List<Dtos.ProductoraAdminDto> listarProductoras() {
        Catalogo.Vista vista = catalogo.vista();
        Map<String, Suscripcion> estados = estadosDelHub();

        List<Creador> todos = creadores.findAll();
        Map<UUID, String> nombres = new HashMap<>();
        todos.forEach(c -> nombres.put(c.getId(), c.getNombre()));

        return productoras.findAll().stream()
                .sorted(Comparator.comparing(Productora::getNombre, String.CASE_INSENSITIVE_ORDER))
                .map(p -> new Dtos.ProductoraAdminDto(
                        p.getId(), p.getNombre(), p.getDescripcion(), p.getLogoUrl(), p.isActivo(),
                        vista.canalesDeProductora(p.getId()).stream()
                                .map(k -> canalAdmin(k, nombres.get(k.getCreadorId()), estados))
                                .toList(),
                        todos.stream()
                                .filter(c -> c.getProductoras().contains(p.getId()))
                                .sorted(Comparator.comparing(Creador::getNombre, String.CASE_INSENSITIVE_ORDER))
                                .map(Creador::getId)
                                .toList(),
                        usuarios.cuantosSiguenProductora(p.getId())))
                .toList();
    }

    /**
     * Alta o edición de una productora, con sus canales propios y, si vienen,
     * los creadores que figuran en ella. Que un canal de un creador sea de la
     * productora se marca al guardar ese creador.
     */
    @PostMapping("/productoras")
    @Transactional
    public Dtos.ProductoraGuardada guardarProductora(@Valid @RequestBody Dtos.GuardarProductora peticion) {
        Productora productora = peticion.id() != null
                ? productoras.findById(peticion.id()).orElseGet(Productora::new)
                : new Productora();

        Cambio cambio = servicioDeProductoras.aplicar(productora, peticion);

        String avisoSuscripcion = null;
        try {
            canalesService.sincronizarWebSub(cambio);
        } catch (Exception e) {
            log.error("No se pudo sincronizar la suscripción de la productora {}", productora.getId(), e);
            avisoSuscripcion = e.getMessage();
        }

        String avisoReplica = replica.enviar(productora);

        return new Dtos.ProductoraGuardada(productora.getId(), avisoSuscripcion, avisoReplica);
    }

    @DeleteMapping("/productoras/{id}")
    @Transactional
    public Dtos.RespuestaSimple borrarProductora(@PathVariable UUID id) {
        Productora productora = productoras.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Esa productora ya no existe."));

        darDeBaja(servicioDeProductoras.retirar(productora));
        return Dtos.RespuestaSimple.de("Productora retirada del directorio.");
    }

    // -------------------------------------------------------------------------

    /** Bajas del hub al retirar algo. Si fallan, lo retirado se retira igual. */
    private void darDeBaja(Cambio cambio) {
        try {
            canalesService.sincronizarWebSub(cambio);
        } catch (Exception e) {
            log.error("Baja del hub fallida: {}", e.getMessage());
        }
    }

    private Map<String, Suscripcion> estadosDelHub() {
        Map<String, Suscripcion> estados = new HashMap<>();
        suscripciones.findAll().forEach(s -> estados.put(s.getChannelId(), s));
        return estados;
    }

    private static Dtos.CanalAdminDto canalAdmin(Canal k, String creadorNombre,
                                                 Map<String, Suscripcion> estados) {
        Suscripcion s = k.getCanalDeYouTube() != null ? estados.get(k.getCanalDeYouTube()) : null;

        return new Dtos.CanalAdminDto(k.getId(), k.getPlataforma(), k.getNombre(), k.getUrl(),
                k.getHandle(), k.getChannelId(), k.getCreadorId(), creadorNombre,
                k.getProductoraId(),
                s != null ? s.getEstado() : null,
                s != null ? s.getExpiraEn() : null);
    }

    /**
     * Una sola suscripción que represente a todos los canales de YouTube del
     * creador, para el testigo de la lista: si alguna no está activa, esa, que
     * es la que hay que mirar; si todas lo están, la que vence antes.
     */
    static Suscripcion resumenDe(List<Canal> canales, Map<String, Suscripcion> estados) {
        Suscripcion resumen = null;

        for (Canal k : canales) {
            String canal = k.getCanalDeYouTube();
            Suscripcion s = canal != null ? estados.get(canal) : null;
            if (s == null) continue;

            if (resumen == null) {
                resumen = s;
                continue;
            }

            boolean activa = Suscripcion.ACTIVA.equals(s.getEstado());
            boolean resumenActiva = Suscripcion.ACTIVA.equals(resumen.getEstado());

            if (resumenActiva && !activa) {
                resumen = s;
            } else if (resumenActiva && activa && s.getExpiraEn() != null
                    && (resumen.getExpiraEn() == null || s.getExpiraEn().isBefore(resumen.getExpiraEn()))) {
                resumen = s;
            }
        }
        return resumen;
    }

    /**
     * Manda un aviso de prueba a quienes siguen a este creador. Aísla el tramo
     * servidor → FCM → teléfono del tramo YouTube → servidor.
     */
    @PostMapping("/creadores/{id}/aviso-de-prueba")
    public Dtos.RespuestaSimple avisoDePrueba(@PathVariable UUID id) {
        Creador creador = creadores.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ese creador ya no existe."));

        String fallo = push.avisarPrueba(creador);
        if (fallo != null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, fallo);
        }
        return Dtos.RespuestaSimple.de("FCM aceptó el aviso para el topic "
                + PushService.topicDe(creador.getId()) + ".");
    }

    /**
     * Busca los datos públicos de un canal para prellenar el formulario.
     * Acepta un ID UC..., un @handle o una URL completa.
     */
    @GetMapping("/canal")
    public Dtos.DatosDeCanal buscarCanal(@RequestParam String query) {
        String entrada = query.trim();
        if (entrada.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Escribe un ID de canal, un @handle o una URL.");
        }

        // WebSub exige el ID canónico: los handles no valen como hub.topic.
        String channelId;
        Matcher idDirecto = ID_CANAL.matcher(entrada);

        if (idDirecto.find()) {
            channelId = idDirecto.group(1);
        } else {
            Matcher conArroba = HANDLE.matcher(entrada);
            String handle = conArroba.find()
                    ? conArroba.group(1)
                    : (entrada.startsWith("@") ? entrada.substring(1) : entrada);
            channelId = youtube.resolverHandle(handle);
        }

        if (channelId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No se encontró ese canal. Prueba con el ID que empieza por UC.");
        }

        Dtos.DatosDeCanal datos = youtube.detallesDeCanal(channelId);
        if (datos == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "El canal existe pero YouTube no devolvió datos.");
        }
        return datos;
    }

    // -------------------------------------------------------------------------
    // Publicaciones y redirección de emergencia
    // -------------------------------------------------------------------------

    @GetMapping("/publicaciones")
    @Transactional(readOnly = true)
    public List<Dtos.PublicacionDto> recientes(@RequestParam(defaultValue = "40") int limite) {
        List<Publicacion> lista = publicaciones.findAllByOrderByPublicadoEnDesc(
                PageRequest.of(0, Math.min(limite, 200)));

        return DirectorioController.aDtos(lista, catalogo.vista(), creadores);
    }

    /**
     * Cuando una plataforma tumba un video por un falso positivo, el destino
     * se reemplaza y la audiencia recibe el enlace nuevo. La entidad del
     * creador nunca se pierde: es lo que evita la caída catastrófica de
     * audiencia cuando alguien es desterrado.
     */
    @PostMapping("/videos/{videoId}/mover")
    @Transactional
    public Dtos.RespuestaSimple mover(@PathVariable String videoId,
                                      @Valid @RequestBody Dtos.MoverContenido peticion) {

        Publicacion p = publicaciones.findByVideoId(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ese video no está en el directorio."));

        p.setEstado(Publicacion.MOVIDO);
        p.setDestinoUrl(peticion.url());
        p.setDestinoPlataforma(peticion.plataformaDestino());
        publicaciones.save(p);

        if (peticion.debeAvisar()) {
            // A quienes siguen a su creador y, si el canal es de una
            // productora, también a quienes la siguen a ella.
            emisores.paraAvisoManual(p).ifPresent(emisor ->
                    push.avisarContenidoMovido(emisor, videoId, p.getTitulo(),
                            peticion.url(), peticion.plataformaDestino()));
        }

        return Dtos.RespuestaSimple.de("Destino cambiado.");
    }

    // -------------------------------------------------------------------------
    // Reportes
    // -------------------------------------------------------------------------

    @GetMapping("/reportes")
    public List<Reporte> pendientes(@RequestParam(defaultValue = "50") int limite) {
        return reportes.findByResueltoFalseOrderByCreadoEnDesc(
                PageRequest.of(0, Math.min(limite, 200)));
    }

    @PostMapping("/reportes/{id}/resolver")
    @Transactional
    public Dtos.RespuestaSimple resolver(@PathVariable Long id) {
        Reporte reporte = reportes.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ese reporte no existe."));

        reporte.setResuelto(true);
        return Dtos.RespuestaSimple.de("Reporte marcado como resuelto.");
    }

    // -------------------------------------------------------------------------
    // Administradores
    // -------------------------------------------------------------------------

    @PostMapping("/administradores")
    @Transactional
    public Dtos.RespuestaSimple nombrar(@RequestParam String correo) {
        Usuario usuario = usuarios.findByEmail(correo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ese correo no tiene cuenta todavía. Pide que entre una vez primero."));

        usuario.setEsAdmin(true);
        log.info("Rol de administrador otorgado a {}", usuario.getId());

        return Dtos.RespuestaSimple.de(
                "Listo. Pide a esa persona que cierre sesión y vuelva a entrar.");
    }
}
