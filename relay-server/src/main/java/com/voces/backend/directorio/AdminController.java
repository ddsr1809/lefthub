package com.voces.backend.directorio;

import com.voces.backend.directorio.CanalesService.Cambio;
import com.voces.backend.config.SeguridadConfig;
import com.voces.backend.modelo.*;
import com.voces.backend.push.Emisores;
import com.voces.backend.push.PushService;
import com.voces.backend.youtube.YouTubeClient;
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
    private final Repositorios.Canales canales;
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
    private final AjustesService ajustes;
    private final FotosService fotos;
    private final PerfilesService perfiles;
    private final BorradoService borrado;

    public AdminController(Repositorios.Creadores creadores,
                           Repositorios.Productoras productoras,
                           Repositorios.Canales canales,
                           Repositorios.Publicaciones publicaciones,
                           Repositorios.Usuarios usuarios,
                           Repositorios.Suscripciones suscripciones,
                           Repositorios.Reportes reportes,
                           YouTubeClient youtube, PushService push, Emisores emisores,
                           CreadoresService servicio, ProductorasService servicioDeProductoras,
                           CanalesService canalesService, Catalogo catalogo,
                           ReplicaService replica, AjustesService ajustes,
                           FotosService fotos, PerfilesService perfiles,
                           BorradoService borrado) {
        this.creadores = creadores;
        this.productoras = productoras;
        this.canales = canales;
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
        this.ajustes = ajustes;
        this.fotos = fotos;
        this.perfiles = perfiles;
        this.borrado = borrado;
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

        return vista.creadores().stream()
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
                            suyos.stream().map(k -> canalAdmin(k, vista, estados)).toList(),
                            List.copyOf(c.getProductoras()),
                            vista.compartidosCon(c.getId()).stream()
                                    .map(k -> canalAdmin(k, vista, estados)).toList());
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

        return vista.productoras().stream()
                .sorted(Comparator.comparing(Productora::getNombre, String.CASE_INSENSITIVE_ORDER))
                .map(p -> new Dtos.ProductoraAdminDto(
                        p.getId(), p.getNombre(), p.getDescripcion(), p.getLogoUrl(), p.isActivo(),
                        vista.canalesDeProductora(p.getId()).stream()
                                .map(k -> canalAdmin(k, vista, estados))
                                .toList(),
                        vista.creadores().stream()
                                .filter(c -> c.getProductoras().contains(p.getId()))
                                .sorted(Comparator.comparing(Creador::getNombre, String.CASE_INSENSITIVE_ORDER))
                                .map(Creador::getId)
                                .toList(),
                        usuarios.cuantosSiguenProductora(p.getId()),
                        p.isEnDirectorio(), p.getCategoria()))
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
                        "Ese medio ya no existe."));

        darDeBaja(servicioDeProductoras.retirar(productora));
        return Dtos.RespuestaSimple.de("Medio retirado del directorio.");
    }

    // -------------------------------------------------------------------------
    // Canales de YouTube
    // -------------------------------------------------------------------------

    /**
     * Todos los canales de YouTube del directorio, cada uno con su dueño, su
     * productora, los demás creadores con los que aparece y el estado de su
     * suscripción al hub.
     */
    @GetMapping("/canales")
    @Transactional(readOnly = true)
    public List<Dtos.CanalAdminDto> listarCanales() {
        Catalogo.Vista vista = catalogo.vista();
        Map<String, Suscripcion> estados = estadosDelHub();

        return canales.deYouTube().stream().map(k -> canalAdmin(k, vista, estados)).toList();
    }

    /**
     * Alta o edición de la ficha de un canal de YouTube: de quién es, de qué
     * productora y con qué otros creadores aparece. Lo que publique les llega
     * a quienes siguen a cualquiera de ellos.
     */
    @PostMapping("/canales")
    @Transactional
    public Dtos.CanalGuardado guardarCanal(@Valid @RequestBody Dtos.GuardarFichaDeCanal peticion) {
        CanalesService.Ficha ficha = canalesService.guardarFicha(peticion);

        String avisoSuscripcion = null;
        try {
            canalesService.sincronizarWebSub(ficha.cambio());
        } catch (Exception e) {
            log.error("No se pudo sincronizar la suscripción del canal {}", ficha.canal().getId(), e);
            avisoSuscripcion = e.getMessage();
        }

        return new Dtos.CanalGuardado(ficha.canal().getId(), avisoSuscripcion, copiarDuenos(ficha));
    }

    @DeleteMapping("/canales/{id}")
    @Transactional
    public Dtos.RespuestaSimple borrarCanal(@PathVariable UUID id) {
        CanalesService.Ficha ficha = canalesService.eliminarFicha(id);

        darDeBaja(ficha.cambio());
        copiarDuenos(ficha);
        return Dtos.RespuestaSimple.de("Canal retirado del directorio.");
    }

    /**
     * Un canal no viaja solo a testing: va dentro de su dueño. Aquí se
     * vuelven a copiar el de ahora y, si cambió, el de antes.
     *
     * @return el primer motivo de fallo, o null si todo se copió.
     */
    private String copiarDuenos(CanalesService.Ficha ficha) {
        String aviso = null;

        for (Productora p : productoras.findAllById(ficha.productoras())) {
            String fallo = replica.enviar(p);
            if (aviso == null) aviso = fallo;
        }
        for (Creador c : creadores.findAllById(ficha.creadores())) {
            String fallo = replica.enviar(c);
            if (aviso == null) aviso = fallo;
        }
        return aviso;
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

    private static Dtos.CanalAdminDto canalAdmin(Canal k, Catalogo.Vista vista,
                                                 Map<String, Suscripcion> estados) {
        Suscripcion s = k.getCanalDeYouTube() != null ? estados.get(k.getCanalDeYouTube()) : null;
        Creador dueno = vista.creador(k.getCreadorId());

        return new Dtos.CanalAdminDto(k.getId(), k.getPlataforma(), k.getNombre(), k.getUrl(),
                k.getHandle(), k.getChannelId(), k.getCreadorId(),
                dueno != null ? dueno.getNombre() : null,
                k.getProductoraId(),
                s != null ? s.getEstado() : null,
                s != null ? s.getExpiraEn() : null,
                List.copyOf(k.getVinculados()));
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

    /**
     * La foto de perfil de una cuenta del creador, para ponérsela.
     *
     * De YouTube devuelve la dirección de la foto del canal. De las demás
     * redes guarda una copia aquí y devuelve la dirección de la copia. No
     * cambia a ningún creador: el panel pone la dirección en el formulario y
     * se guarda con lo demás.
     */
    @GetMapping("/foto")
    public Dtos.FotoDto foto(@RequestParam(required = false) String plataforma,
                             @RequestParam(required = false) String url,
                             @RequestParam(required = false) String channelId) {
        if (plataforma == null || plataforma.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta decir de qué red es la cuenta.");
        }
        return fotos.traer(plataforma, url, channelId);
    }

    /**
     * Nombre, descripción y foto de una cuenta, para rellenar la ficha de un
     * creador sin teclear. Devuelve lo que se haya podido leer; no guarda
     * nada en ningún creador.
     */
    @GetMapping("/cuenta")
    public Dtos.CuentaDto cuenta(@RequestParam(required = false) String plataforma,
                                 @RequestParam(required = false) String url,
                                 @RequestParam(required = false) String channelId) {
        if (plataforma == null || plataforma.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta decir de qué red es la cuenta.");
        }
        return perfiles.leer(plataforma, url, channelId);
    }

    // -------------------------------------------------------------------------
    // Borrar de golpe una parte del directorio
    // -------------------------------------------------------------------------

    /**
     * Primer paso: cuenta lo que se perdería y da el número que hay que
     * escribir para confirmar. No borra nada.
     */
    @PostMapping("/borrado/preparar")
    public Dtos.BorradoPreparado prepararBorrado(@RequestBody(required = false) Dtos.PedirBorrado peticion) {
        return borrado.preparar(SeguridadConfig.Sesion.exigir(),
                peticion == null ? null : peticion.partes());
    }

    /** Segundo paso: borra, si el número es el que se dio en el primero. */
    @PostMapping("/borrado")
    public Dtos.BorradoHecho borrarDeGolpe(@RequestBody(required = false) Dtos.ConfirmarBorrado peticion) {
        return borrado.ejecutar(SeguridadConfig.Sesion.exigir(),
                peticion == null ? null : peticion.partes(),
                peticion == null ? null : peticion.codigo());
    }

    // -------------------------------------------------------------------------
    // Publicaciones y redirección de emergencia
    // -------------------------------------------------------------------------

    @GetMapping("/publicaciones")
    @Transactional(readOnly = true)
    public List<Dtos.PublicacionDto> recientes(@RequestParam(defaultValue = "40") int limite,
                                               @RequestParam(required = false) String tipo) {
        PageRequest pagina = PageRequest.of(0, Math.max(1, Math.min(limite, 200)));

        // Sin `tipo`, todo junto, como siempre: el panel marca cuáles son cortos.
        List<Publicacion> lista =
                "cortos".equals(tipo) ? publicaciones.findByTipoOrderByPublicadoEnDesc(Publicacion.TIPO_CORTO, pagina)
                : "videos".equals(tipo) ? publicaciones.findByTipoNotOrderByPublicadoEnDesc(Publicacion.TIPO_CORTO, pagina)
                : publicaciones.findAllByOrderByPublicadoEnDesc(pagina);

        return DirectorioController.aDtos(lista, catalogo.vista());
    }

    /**
     * Corrige a mano si un video es corto o no.
     *
     * El servidor lo decide solo al recibir el video, y casi siempre acierta,
     * pero la última palabra es de quien lo está viendo. No avisa a nadie:
     * solo cambia en qué lista sale.
     */
    @PutMapping("/videos/{videoId}/tipo")
    @Transactional
    public Dtos.RespuestaSimple cambiarTipo(@PathVariable String videoId,
                                            @Valid @RequestBody Dtos.CambiarTipo peticion) {

        Publicacion p = publicaciones.findByVideoId(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ese video no está en el directorio."));

        // Se comprueba también aquí, no solo con la anotación: un tipo que no
        // sea uno de estos dos dejaría el video fuera de las dos listas.
        if (!Publicacion.TIPO_VIDEO.equals(peticion.tipo())
                && !Publicacion.TIPO_CORTO.equals(peticion.tipo())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El tipo tiene que ser video o short.");
        }

        p.setTipo(peticion.tipo());
        publicaciones.save(p);

        return Dtos.RespuestaSimple.de(p.esCorto()
                ? "Marcado como video corto." : "Marcado como video normal.");
    }

    /**
     * Vuelve a preguntarle a YouTube por los últimos videos guardados como
     * cortos, y corrige los que en realidad son videos normales.
     *
     * Hasta ahora se decidía solo por la duración, así que entre lo guardado
     * hay videos normales de menos de tres minutos marcados como cortos. Los
     * que YouTube no aclara se dejan como están.
     */
    @PostMapping("/videos/revisar-cortos")
    public Dtos.RevisionDeCortos revisarCortos(@RequestParam(defaultValue = "40") int limite) {
        List<Publicacion> lista = publicaciones.findByTipoOrderByPublicadoEnDesc(
                Publicacion.TIPO_CORTO, PageRequest.of(0, Math.max(1, Math.min(limite, 100))));

        int corregidos = 0;
        int sinRespuesta = 0;

        for (Publicacion p : lista) {
            Boolean esShort = youtube.esShort(p.getVideoId());
            if (esShort == null) {
                sinRespuesta++;
            } else if (!esShort) {
                p.setTipo(Publicacion.TIPO_VIDEO);
                publicaciones.save(p);
                corregidos++;
            }
        }
        return new Dtos.RevisionDeCortos(lista.size(), corregidos, sinRespuesta);
    }

    // -------------------------------------------------------------------------
    // Ajustes generales
    // -------------------------------------------------------------------------

    @GetMapping("/ajustes")
    public Dtos.AjustesDto ajustes() {
        return new Dtos.AjustesDto(ajustes.cortos(), ajustes.anuncios(), ajustes.youtube());
    }

    /** Solo cambia lo que llega; lo demás se queda como estaba. */
    @PutMapping("/ajustes")
    public Dtos.AjustesDto cambiarAjustes(@RequestBody Dtos.CambiarAjustes peticion) {
        if (peticion.cortos() != null) ajustes.ponerCortos(peticion.cortos());
        if (peticion.anuncios() != null) ajustes.ponerAnuncios(peticion.anuncios());
        if (peticion.youtube() != null) ajustes.ponerYoutube(peticion.youtube());
        return ajustes();
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

        // De un video corto solo se avisa a quienes los ven; con los cortos
        // apagados no lo vio nadie, y no hay a quién decirle que se movió.
        boolean corto = p.esCorto();

        if (peticion.debeAvisar() && (!corto || ajustes.cortos())) {
            // A quienes siguen a su creador y, si el canal es de una
            // productora, también a quienes la siguen a ella.
            emisores.paraAvisoManual(p).ifPresent(emisor ->
                    push.avisarContenidoMovido(emisor, videoId, p.getTitulo(),
                            peticion.url(), peticion.plataformaDestino(), corto));
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
