package com.voces.backend.avisos;

import com.voces.backend.directorio.AjustesService;
import com.voces.backend.modelo.*;
import com.voces.backend.push.Emisores;
import com.voces.backend.push.PushService;
import com.voces.backend.push.PushService.AvisoDelEquipo;
import com.voces.backend.push.PushService.Destino;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/**
 * Los avisos que manda el equipo desde el panel.
 *
 * Hay dos clases de público:
 *
 *  · Todos. Un creador, medio o canal nuevo no lo sigue nadie todavía: el
 *    aviso va al topic que toda la app tiene (PushService.TOPIC_TODOS). Un
 *    mensaje general también puede ir ahí.
 *  · Quienes siguen a alguien. Un video retirado, una cuenta bloqueada o un
 *    mensaje sobre un creador van a sus seguidores, por los mismos topics que
 *    sus videos.
 *
 * Al tocarlo, la app abre lo que toca: la ficha del creador, del medio o del
 * canal, o el enlace si el aviso trae uno. Eso viaja en `data`.
 *
 * Todo aviso se guarda, haya salido o no: es el historial del panel.
 */
@Service
public class AvisosService {

    private static final Logger log = LoggerFactory.getLogger(AvisosService.class);

    static final int MAX_TITULO = 80;
    static final int MAX_CUERPO = 300;

    private final Repositorios.Avisos avisos;
    private final Repositorios.Creadores creadores;
    private final Repositorios.Productoras productoras;
    private final Repositorios.Canales canales;
    private final Repositorios.Publicaciones publicaciones;
    private final Repositorios.Usuarios usuarios;
    private final Emisores emisores;
    private final PushService push;
    private final AjustesService ajustes;

    public AvisosService(Repositorios.Avisos avisos, Repositorios.Creadores creadores,
                         Repositorios.Productoras productoras, Repositorios.Canales canales,
                         Repositorios.Publicaciones publicaciones, Repositorios.Usuarios usuarios,
                         Emisores emisores, PushService push, AjustesService ajustes) {
        this.avisos = avisos;
        this.creadores = creadores;
        this.productoras = productoras;
        this.canales = canales;
        this.publicaciones = publicaciones;
        this.usuarios = usuarios;
        this.emisores = emisores;
        this.push = push;
        this.ajustes = ajustes;
    }

    /** Lo que pide el panel. */
    public record Peticion(
            String tipo,
            /** todos | creador | productora | canal. Solo para cuenta_bloqueada y mensaje. */
            String para,
            /** El creador, medio o canal del que se habla. */
            UUID id,
            /** Solo para video_retirado. */
            String videoId,
            String titulo,
            String cuerpo,
            /** https, opcional: tocar el aviso lo abre. */
            String enlace,
            /** video_retirado: además, quitarlo de la app. */
            Boolean retirarVideo
    ) {}

    /** Un aviso tal como lo ve el historial del panel. */
    public record AvisoDto(
            Long id, String tipo, String titulo, String cuerpo, String enlace, String destino,
            UUID creadorId, UUID productoraId, UUID canalId, String videoId,
            boolean enviado, String error, String autor, Instant creadoEn
    ) {}

    /** A quién va, y qué lleva para que la app sepa qué abrir. */
    private record Plan(List<Destino> destinos, String destino, Map<String, String> datos,
                        String imagen, UUID creadorId, UUID productoraId, UUID canalId) {}

    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<AvisoDto> recientes(int limite) {
        List<Aviso> lista = avisos.findAllByOrderByCreadoEnDesc(PageRequest.of(0, Math.min(Math.max(limite, 1), 200)));

        Map<UUID, String> correos = new HashMap<>();
        List<UUID> autores = lista.stream().map(Aviso::getAutorId).filter(Objects::nonNull).distinct().toList();
        usuarios.findAllById(autores).forEach(u -> correos.put(u.getId(), u.getEmail()));

        return lista.stream().map(a -> dto(a, correos.get(a.getAutorId()))).toList();
    }

    @Transactional
    public AvisoDto enviar(Peticion p, UUID autor) {
        String tipo = p.tipo() == null ? "" : p.tipo().trim();
        String titulo = texto(p.titulo(), "título", MAX_TITULO);
        String cuerpo = texto(p.cuerpo(), "texto", MAX_CUERPO);
        String enlace = enlace(p.enlace());

        Creador anunciado = null;
        Publicacion retirada = null;

        Plan plan = switch (tipo) {
            case Aviso.NUEVO_CREADOR -> {
                anunciado = creadorParaAnunciar(p.id());
                yield aTodos(datos("creador", anunciado.getId(), null, null),
                        https(anunciado.getFotoUrl()), anunciado.getId(), null, null);
            }
            case Aviso.NUEVO_MEDIO -> {
                Productora medio = productoras.findById(exigir(p.id(), "medio"))
                        .orElseThrow(() -> noExiste("Ese medio"));
                if (!medio.isActivo()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Ese medio está oculto. Hazlo visible antes de avisar de él.");
                }
                yield aTodos(datos("productora", null, medio.getId(), null),
                        https(medio.getLogoUrl()), null, medio.getId(), null);
            }
            case Aviso.NUEVO_CANAL -> {
                Canal canal = canales.findById(exigir(p.id(), "canal"))
                        .orElseThrow(() -> noExiste("Ese canal"));
                if (emisores.de(canal).isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "El dueño de ese canal está oculto: la app no lo enseña. Hazlo visible antes de avisar.");
                }
                yield aTodos(datos("canal", canal.getCreadorId(), canal.getProductoraId(), canal.getId()),
                        null, canal.getCreadorId(), canal.getProductoraId(), canal.getId());
            }
            case Aviso.VIDEO_RETIRADO -> {
                retirada = publicaciones.findByVideoId(exigirVideo(p.videoId()))
                        .orElseThrow(() -> noExiste("Ese video"));
                yield deVideo(retirada);
            }
            case Aviso.CUENTA_BLOQUEADA -> {
                if (p.para() == null || "todos".equals(p.para())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Elige de quién es la cuenta bloqueada: un creador, un medio o un canal.");
                }
                yield deAudiencia(p.para(), p.id(), "bloqueado");
            }
            case Aviso.MENSAJE -> deAudiencia(p.para(), p.id(), "mensaje");
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tipo de aviso desconocido: " + tipo);
        };

        // Con enlace, tocar el aviso lo abre. "platform" y "url" son los
        // mismos campos que ya leen las apps anteriores para abrir un enlace,
        // así que también a ellas les funciona.
        Map<String, String> datos = new LinkedHashMap<>(plan.datos());
        if (enlace != null) {
            datos.put("platform", "web");
            datos.put("url", enlace);
        }

        Aviso aviso = new Aviso();
        aviso.setTipo(tipo);
        aviso.setTitulo(titulo);
        aviso.setCuerpo(cuerpo);
        aviso.setEnlace(enlace);
        aviso.setDestino(plan.destino());
        aviso.setCreadorId(plan.creadorId());
        aviso.setProductoraId(plan.productoraId());
        aviso.setCanalId(plan.canalId());
        aviso.setVideoId(retirada != null ? retirada.getVideoId() : null);
        aviso.setAutorId(autor);
        avisos.saveAndFlush(aviso);

        String fallo = push.enviarAviso(plan.destinos(), new AvisoDelEquipo(
                titulo, cuerpo, plan.imagen(), datos, "equipo_" + aviso.getId()));

        aviso.setEnviado(fallo == null);
        aviso.setError(fallo);

        if (fallo == null) {
            if (anunciado != null) {
                anunciado.setAnunciadoEn(Instant.now());
                creadores.save(anunciado);
            }
            log.info("Aviso del equipo {} ({}) enviado a {}", aviso.getId(), tipo, plan.destino());
        } else {
            log.warn("Aviso del equipo {} ({}) no salió: {}", aviso.getId(), tipo, fallo);
        }

        // El video se quita de la app aunque el aviso falle: son dos cosas, y
        // la persona pidió las dos.
        if (retirada != null && Boolean.TRUE.equals(p.retirarVideo())) {
            retirada.setEstado(Publicacion.RETIRADO);
            publicaciones.save(retirada);
        }

        avisos.save(aviso);
        return dto(aviso, null);
    }

    // -------------------------------------------------------------------------
    // A quién
    // -------------------------------------------------------------------------

    private Creador creadorParaAnunciar(UUID id) {
        Creador creador = creadores.findById(exigir(id, "creador"))
                .orElseThrow(() -> noExiste("Ese creador"));
        if (!creador.isActivo()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ese creador está oculto. Hazlo visible antes de avisar de él.");
        }
        if (creador.getAnunciadoEn() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya se avisó a todos de este creador. No se manda dos veces.");
        }
        return creador;
    }

    private Plan aTodos(Map<String, String> datos, String imagen,
                        UUID creadorId, UUID productoraId, UUID canalId) {
        return new Plan(PushService.destinosPara(List.of(PushService.TOPIC_TODOS)),
                "Todos", datos, imagen, creadorId, productoraId, canalId);
    }

    /**
     * Un video retirado: a quienes lo recibieron, por los mismos topics que
     * su aviso de video nuevo.
     */
    private Plan deVideo(Publicacion p) {
        // Un corto con los cortos apagados no le llegó a nadie.
        if (p.esCorto() && !ajustes.cortos()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Es un video corto y los cortos están apagados: no lo vio nadie a quien avisar.");
        }

        PushService.Emisor emisor = emisores.paraAvisoManual(p)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "El creador de ese video ya no está en el directorio."));

        return new Plan(emisor.destinos(p.esCorto()), "Quienes siguen a " + emisor.nombre(),
                datos("retirado", emisor.creadorId(), emisor.productoraId(), null),
                null, emisor.creadorId(), emisor.productoraId(), p.getCanalId());
    }

    /**
     * Para una cuenta bloqueada o un mensaje: todos, o quienes siguen a un
     * creador, a un medio o a un canal. Quien sigue solo algunos canales de
     * un creador también lo recibe.
     */
    private Plan deAudiencia(String para, UUID id, String tipoApp) {
        String a = para == null ? "todos" : para;
        switch (a) {
            case "todos" -> {
                return aTodos(datos(tipoApp, null, null, null), null, null, null, null);
            }
            case "creador" -> {
                Creador c = creadores.findById(exigir(id, "creador")).orElseThrow(() -> noExiste("Ese creador"));
                List<String> topics = new ArrayList<>();
                topics.add(PushService.topicDe(c.getId()));
                canales.deCreador(c.getId()).forEach(k -> topics.add(PushService.topicDeCanal(k.getId())));
                return new Plan(PushService.destinosPara(topics), "Quienes siguen a " + c.getNombre(),
                        datos(tipoApp, c.getId(), null, null), null, c.getId(), null, null);
            }
            case "productora" -> {
                Productora m = productoras.findById(exigir(id, "medio")).orElseThrow(() -> noExiste("Ese medio"));
                List<String> topics = new ArrayList<>();
                topics.add(PushService.topicDeProductora(m.getId()));
                // El que usan las apps que no conocen los medios: ver Emisor.topics().
                topics.add(PushService.topicDe(m.getId()));
                canales.deProductora(m.getId()).forEach(k -> topics.add(PushService.topicDeCanal(k.getId())));
                return new Plan(PushService.destinosPara(topics), "Quienes siguen a " + m.getNombre(),
                        datos(tipoApp, null, m.getId(), null), null, null, m.getId(), null);
            }
            case "canal" -> {
                Canal k = canales.findById(exigir(id, "canal")).orElseThrow(() -> noExiste("Ese canal"));
                List<String> topics = emisores.de(k).map(PushService.Emisor::topics)
                        .orElse(List.of(PushService.topicDeCanal(k.getId())));
                String nombre = k.getTitulo() != null ? k.getTitulo()
                        : k.getHandle() != null ? k.getHandle() : k.getNombre();
                return new Plan(PushService.destinosPara(topics),
                        "Quienes siguen el canal " + (nombre != null ? nombre : k.getId()),
                        datos(tipoApp, k.getCreadorId(), k.getProductoraId(), k.getId()),
                        null, k.getCreadorId(), k.getProductoraId(), k.getId());
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Destino desconocido: " + para);
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Lo que la app lee al tocar el aviso. "tipo" dice qué abrir y los ids,
     * de quién. FCM solo admite cadenas; lo que no hay no se manda.
     */
    private static Map<String, String> datos(String tipo, UUID creador, UUID productora, UUID canal) {
        Map<String, String> datos = new LinkedHashMap<>();
        datos.put("tipo", tipo);
        if (creador != null) datos.put("creatorId", creador.toString());
        if (productora != null) datos.put("productoraId", productora.toString());
        if (canal != null) datos.put("canalId", canal.toString());
        return datos;
    }

    private static UUID exigir(UUID id, String que) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elige el " + que + " del aviso.");
        }
        return id;
    }

    private static String exigirVideo(String videoId) {
        if (videoId == null || videoId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elige el video del aviso.");
        }
        return videoId.trim();
    }

    private static ResponseStatusException noExiste(String que) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, que + " ya no existe.");
    }

    private static String texto(String valor, String que, int maximo) {
        String limpio = valor == null ? "" : valor.trim();
        if (limpio.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El aviso necesita " + que + ".");
        }
        if (limpio.length() > maximo) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El " + que + " no puede pasar de " + maximo + " caracteres.");
        }
        return limpio;
    }

    private static String enlace(String valor) {
        if (valor == null || valor.isBlank()) return null;
        String limpio = valor.trim();
        if (!limpio.regionMatches(true, 0, "https://", 0, 8) || limpio.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El enlace debe empezar por https:// y no pasar de 500 caracteres.");
        }
        return limpio;
    }

    /** FCM solo pinta imágenes https. Cualquier otra se deja fuera. */
    private static String https(String url) {
        return url != null && url.regionMatches(true, 0, "https://", 0, 8) ? url : null;
    }

    private static AvisoDto dto(Aviso a, String autor) {
        return new AvisoDto(a.getId(), a.getTipo(), a.getTitulo(), a.getCuerpo(), a.getEnlace(),
                a.getDestino(), a.getCreadorId(), a.getProductoraId(), a.getCanalId(), a.getVideoId(),
                a.isEnviado(), a.getError(), autor, a.getCreadoEn());
    }
}
