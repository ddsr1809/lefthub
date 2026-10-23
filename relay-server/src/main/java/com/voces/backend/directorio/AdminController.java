package com.voces.backend.directorio;

import com.voces.backend.seguridad.CifradorDeDatos;
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

import java.time.Duration;
import java.time.Instant;
import java.util.*;

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
    private final TitulosDeCanales titulos;
    private final RevisionDeVideos revisionDeVideos;
    private final CifrasDeVideos cifras;
    private final ConsultaDePublicaciones consulta;
    private final GuardadoDelDirectorio guardado;

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
                           BorradoService borrado, TitulosDeCanales titulos,
                           RevisionDeVideos revisionDeVideos, CifrasDeVideos cifras,
                           ConsultaDePublicaciones consulta, GuardadoDelDirectorio guardado) {
        this.guardado = guardado;
        this.cifras = cifras;
        this.consulta = consulta;
        this.titulos = titulos;
        this.revisionDeVideos = revisionDeVideos;
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
                                    .map(k -> canalAdmin(k, vista, estados)).toList(),
                            c.getAnunciadoEn());
                })
                .toList();
    }

    @PostMapping("/creadores")
    @Transactional
    public Dtos.CreadorGuardado guardar(@Valid @RequestBody Dtos.GuardarCreador peticion) {
        return guardado.guardarCreador(peticion);
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
        return guardado.guardarProductora(peticion);
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
     * Pregunta ya a YouTube el nombre de los canales que no lo tienen, sin
     * esperar a la revisión de cada diez minutos.
     */
    @PostMapping("/canales/titulos")
    public java.util.Map<String, Integer> completarTitulos() {
        return java.util.Map.of("revisados", titulos.completar());
    }

    /**
     * Alta o edición de la ficha de un canal de YouTube: de quién es, de qué
     * productora y con qué otros creadores aparece. Lo que publique les llega
     * a quienes siguen a cualquiera de ellos.
     */
    @PostMapping("/canales")
    @Transactional
    public Dtos.CanalGuardado guardarCanal(@Valid @RequestBody Dtos.GuardarFichaDeCanal peticion) {
        return guardado.guardarCanal(peticion);
    }

    @DeleteMapping("/canales/{id}")
    @Transactional
    public Dtos.RespuestaSimple borrarCanal(@PathVariable UUID id) {
        CanalesService.Ficha ficha = canalesService.eliminarFicha(id);

        darDeBaja(ficha.cambio());
        guardado.copiarDuenos(ficha);
        return Dtos.RespuestaSimple.de("Canal retirado del directorio.");
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
                List.copyOf(k.getVinculados()),
                k.getTitulo());
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
        return guardado.buscarCanal(query);
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

    /**
     * Los videos, con sus cifras.
     *
     * @param orden "recientes" (lo de siempre) o "vistas": los más vistos de
     *              los publicados en los últimos {@code dias} (0 = todos). Las
     *              cifras son las guardadas: aquí no se pregunta a YouTube,
     *              para que la lista cargue rápido (ver CifrasDeVideos).
     */
    /**
     * Los videos, con sus cifras, filtrados y en el orden que pida el panel.
     *
     * @param orden   publicado | vistas | meGusta | comentarios | duracion |
     *                titulo | creador | estado. "recientes" es "publicado"
     *                (lo de siempre, para quien no manda nada)
     * @param dir     asc | desc. Sin él, desc, salvo título y creador (de la A a la Z)
     * @param desde   instante ISO-8601 ("2026-10-01T07:00:00Z"): publicados desde entonces
     * @param hasta   instante ISO-8601: publicados antes de entonces
     * @param dias    atajo de una versión anterior del panel: los últimos N días
     * @param creador solo los de ese creador; canal y medio, igual
     *
     * Las cifras son las guardadas: aquí no se pregunta a YouTube, para que
     * la lista cargue rápido (ver CifrasDeVideos).
     */
    @GetMapping("/publicaciones")
    @Transactional(readOnly = true)
    public List<Dtos.PublicacionAdminDto> recientes(@RequestParam(defaultValue = "40") int limite,
                                                    @RequestParam(required = false) String tipo,
                                                    @RequestParam(defaultValue = "publicado") String orden,
                                                    @RequestParam(required = false) String dir,
                                                    @RequestParam(required = false) String desde,
                                                    @RequestParam(required = false) String hasta,
                                                    @RequestParam(defaultValue = "0") int dias,
                                                    @RequestParam(required = false) UUID creador,
                                                    @RequestParam(required = false) UUID canal,
                                                    @RequestParam(required = false) UUID medio) {
        if ("recientes".equals(orden)) orden = "publicado";
        boolean ascendente = dir != null ? "asc".equals(dir) : ("titulo".equals(orden) || "creador".equals(orden));

        List<Publicacion> lista = consulta.videos(
                filtro(tipo, desde, hasta, dias, creador, canal, medio),
                orden, ascendente, Math.max(1, Math.min(limite, 500)));
        return paraElPanel(lista, false);
    }

    /**
     * Lo que suman los videos de cada creador, canal o medio en el periodo:
     * cuántos videos, vistas, "me gusta" y comentarios. Un video cuenta para
     * un medio si salió de un canal de ese medio. Los que ya no están en el
     * directorio salen sin nombre.
     */
    @GetMapping("/publicaciones/totales")
    @Transactional(readOnly = true)
    public List<Dtos.TotalDePublicaciones> totales(@RequestParam String por,
                                                   @RequestParam(required = false) String tipo,
                                                   @RequestParam(required = false) String desde,
                                                   @RequestParam(required = false) String hasta,
                                                   @RequestParam(defaultValue = "0") int dias) {
        if (!ConsultaDePublicaciones.GRUPOS.containsKey(por)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agrupa por creador, canal o medio.");
        }
        Catalogo.Vista vista = catalogo.vista();
        return consulta.totales(filtro(tipo, desde, hasta, dias, null, null, null), por).stream()
                .map(t -> Dtos.TotalDePublicaciones.de(t, por, vista))
                .toList();
    }

    private static ConsultaDePublicaciones.Filtro filtro(String tipo, String desde, String hasta, int dias,
                                                         UUID creador, UUID canal, UUID medio) {
        Instant inicio = instante(desde);
        if (inicio == null && dias > 0) inicio = Instant.now().minus(Duration.ofDays(Math.min(dias, 3650)));
        return new ConsultaDePublicaciones.Filtro(tipo, inicio, instante(hasta), creador, canal, medio);
    }

    private static Instant instante(String texto) {
        if (texto == null || texto.isBlank()) return null;
        try {
            return Instant.parse(texto.trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La fecha no es válida: " + texto);
        }
    }

    /**
     * La ficha de un video. Quien la abre quiere el número de ahora: si las
     * cifras tienen más de diez minutos, se piden otra vez (1 unidad).
     */
    @GetMapping("/videos/{videoId}")
    @Transactional
    public Dtos.PublicacionAdminDto video(@PathVariable String videoId) {
        Publicacion p = publicaciones.findByVideoId(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ese video no está en el directorio."));
        cifras.refrescarSiHaceFalta(p);
        return paraElPanel(List.of(p), true).get(0);
    }

    /**
     * Pide ahora a YouTube las cifras de los videos que el panel tiene en
     * pantalla. 1 unidad de cuota por cada 50; como mucho 200 videos.
     */
    @PostMapping("/videos/cifras")
    public Dtos.CifrasActualizadas actualizarCifras(@Valid @RequestBody Dtos.PedirCifras peticion) {
        return cifras.deVideos(peticion.videoIds() != null ? peticion.videoIds() : List.of());
    }

    private List<Dtos.PublicacionAdminDto> paraElPanel(List<Publicacion> lista, boolean conDescripcion) {
        Catalogo.Vista vista = catalogo.vista();
        List<Dtos.PublicacionDto> base = DirectorioController.aDtos(lista, vista);
        List<Dtos.PublicacionAdminDto> salida = new ArrayList<>(lista.size());
        for (int i = 0; i < lista.size(); i++) {
            Canal canal = vista.canal(lista.get(i).getCanalId());
            String canalNombre = canal == null ? null
                    : canal.getNombre() != null && !canal.getNombre().isBlank() ? canal.getNombre()
                    : canal.getHandle();
            salida.add(Dtos.PublicacionAdminDto.de(base.get(i), lista.get(i), canalNombre, conDescripcion));
        }
        return salida;
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
        // Elegido a mano: la revisión automática ya no se lo cambia.
        p.setTipoAMano(true);
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

    /**
     * Una pasada de la revisión de los videos que llegaron sin sus datos de
     * YouTube, sin esperar a la de cada diez minutos. Va de pocos en pocos para
     * contestar rápido: el panel la repite mientras queden.
     */
    @PostMapping("/videos/completar")
    public Dtos.RevisionDeVideos completarVideos(@RequestParam(defaultValue = "25") int limite) {
        return revisionDeVideos.completar(Math.max(1, Math.min(limite, 100)));
    }

    /** Cuántos videos esperan sus datos: lo enseña el panel en Publicaciones. */
    @GetMapping("/videos/sin-datos")
    public java.util.Map<String, Long> videosSinDatos() {
        return java.util.Map.of("quedan", publicaciones.cuantosSinDatos());
    }

    // -------------------------------------------------------------------------
    // Ajustes generales
    // -------------------------------------------------------------------------

    @GetMapping("/ajustes")
    public Dtos.AjustesDto ajustes() {
        String youtube = ajustes.youtube();
        return new Dtos.AjustesDto(ajustes.cortos(), ajustes.anuncios(),
                AjustesService.RECOMENDAR_TODOS.equals(youtube), ajustes.recomendaciones(), youtube,
                ajustes.stickers());
    }

    /** Solo cambia lo que llega; lo demás se queda como estaba. */
    @PutMapping("/ajustes")
    public Dtos.AjustesDto cambiarAjustes(@RequestBody Dtos.CambiarAjustes peticion) {
        if (peticion.cortos() != null) ajustes.ponerCortos(peticion.cortos());
        if (peticion.anuncios() != null) ajustes.ponerAnuncios(peticion.anuncios());
        if (peticion.youtube() != null) ajustes.ponerYoutube(peticion.youtube().booleanValue());
        if (peticion.youtubeAlcance() != null) {
            if (!AjustesService.alcanceValido(peticion.youtubeAlcance())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A quién se ofrece YouTube: apagado, admins, elegidos o todos.");
            }
            ajustes.ponerYoutube(peticion.youtubeAlcance());
        }
        if (peticion.recomendaciones() != null) {
            if (!AjustesService.alcanceValido(peticion.recomendaciones())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Quién puede recomendar: apagado, admins, elegidos o todos.");
            }
            ajustes.ponerRecomendaciones(peticion.recomendaciones());
        }
        if (peticion.stickers() != null) {
            if (!AjustesService.alcanceValido(peticion.stickers())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Quién ve los stickers: apagado, admins, elegidos o todos.");
            }
            ajustes.ponerStickers(peticion.stickers());
        }
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
        // El correo está cifrado en la base: se busca por su huella. Si lo
        // tienen dos cuentas (Google y Apple), vale la que entró más
        // recientemente.
        String huella = CifradorDeDatos.actual().huella(correo, CifradorDeDatos.CORREO);
        Usuario usuario = usuarios.findByEmailHuella(huella == null ? "" : huella).stream()
                .max(java.util.Comparator.comparing(Usuario::getVistoEn))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ese correo no tiene cuenta todavía. Pide que entre una vez primero."));

        usuario.setEsAdmin(true);
        log.info("Rol de administrador otorgado a {}", usuario.getId());

        return Dtos.RespuestaSimple.de(
                "Listo. Pide a esa persona que cierre sesión y vuelva a entrar.");
    }
}
