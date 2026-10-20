package com.voces.backend.recomendaciones;

import com.voces.backend.directorio.AjustesService;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Recomendacion;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.modelo.Usuario;
import com.voces.backend.youtube.YouTubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Recomendaciones de canales: la gente propone canales de YouTube para el
 * directorio y el equipo los revisa en el panel.
 *
 * Empieza apagado para todos. Quién puede recomendar lo decide el ajuste
 * 'recomendaciones' (ver {@link AjustesService#recomendaciones()}) y, cuando
 * está en "elegidos", la marca que el panel pone cuenta por cuenta.
 *
 * Al recibir una se le pregunta a YouTube de qué canal se trata, para que el
 * equipo vea nombre y foto sin buscar nada. Cuesta 1 o 2 unidades de cuota;
 * por eso hay tope diario por cuenta, y si la persona escribió solo un
 * nombre no se pregunta nada (buscar por nombre cuesta 100).
 */
@Service
public class RecomendacionesService {

    private static final Logger log = LoggerFactory.getLogger(RecomendacionesService.class);

    static final int MAX_TEXTO = 300;
    static final int MAX_MOTIVO = 500;
    /** Cuántas puede mandar una cuenta en 24 horas. Los administradores no tienen tope. */
    static final int MAX_POR_DIA = 5;
    /** Cuántas puede tener sin revisar a la vez. */
    static final int MAX_PENDIENTES = 20;
    /** Las que la app enseña en "Tus recomendaciones". */
    static final int MAX_EN_LA_APP = 50;

    private final AjustesService ajustes;
    private final Repositorios.Recomendaciones recomendaciones;
    private final Repositorios.Canales canales;
    private final YouTubeClient youtube;

    public RecomendacionesService(AjustesService ajustes, Repositorios.Recomendaciones recomendaciones,
                                  Repositorios.Canales canales, YouTubeClient youtube) {
        this.ajustes = ajustes;
        this.recomendaciones = recomendaciones;
        this.canales = canales;
        this.youtube = youtube;
    }

    // -------------------------------------------------------------------------
    // Quién puede
    // -------------------------------------------------------------------------

    /**
     * ¿Puede recomendar esta cuenta con este alcance?
     *
     * Estático y sin base de datos para probar cada combinación.
     */
    public static boolean puede(String alcance, boolean esAdmin, boolean elegida) {
        if (alcance == null) return false;
        return switch (alcance) {
            case AjustesService.RECOMENDAR_TODOS -> true;
            case AjustesService.RECOMENDAR_ELEGIDOS -> esAdmin || elegida;
            case AjustesService.RECOMENDAR_ADMINS -> esAdmin;
            default -> false;
        };
    }

    @Transactional(readOnly = true)
    public boolean puede(Usuario usuario) {
        return puede(ajustes.recomendaciones(), usuario.isEsAdmin(), usuario.isRecomendarHabilitado());
    }

    /** Lanza 403 con un mensaje para la pantalla si la cuenta no puede. */
    public void exigir(Usuario usuario) {
        if (!puede(usuario)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Las recomendaciones de canales no están disponibles ahora.");
        }
    }

    // -------------------------------------------------------------------------
    // Desde la app
    // -------------------------------------------------------------------------

    /** Una recomendación como la ve quien la mandó. */
    public record RecomendacionDto(
            Long id,
            String texto,
            String motivo,
            String titulo,
            String handle,
            String fotoUrl,
            /** pendiente | agregado | descartado */
            String estado,
            /** El canal ya está en el directorio, lo haya marcado el equipo o no. */
            boolean enDirectorio,
            Instant creadoEn
    ) {
        static RecomendacionDto de(Recomendacion r, Set<String> delDirectorio) {
            return new RecomendacionDto(r.getId(), r.getTexto(), r.getMotivo(), r.getTitulo(),
                    r.getHandle(), r.getFotoUrl(), r.getEstado(),
                    r.getChannelId() != null && delDirectorio.contains(r.getChannelId()),
                    r.getCreadoEn());
        }
    }

    public record MisRecomendaciones(List<RecomendacionDto> recomendaciones, int quedanHoy) {}

    public record Enviada(boolean ok, String mensaje, RecomendacionDto recomendacion) {}

    @Transactional(readOnly = true)
    public MisRecomendaciones mias(Usuario usuario) {
        exigir(usuario);
        Set<String> delDirectorio = idsDelDirectorio();
        List<RecomendacionDto> lista = recomendaciones
                .findByUsuarioIdOrderByCreadoEnDesc(usuario.getId(), PageRequest.of(0, MAX_EN_LA_APP))
                .stream().map(r -> RecomendacionDto.de(r, delDirectorio)).toList();
        return new MisRecomendaciones(lista, quedanHoy(usuario));
    }

    @Transactional
    public Enviada recomendar(Usuario usuario, String textoPedido, String motivoPedido) {
        exigir(usuario);

        String texto = recortar(textoPedido, MAX_TEXTO);
        if (texto == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Escribe el enlace o el nombre del canal que quieres recomendar.");
        }
        String motivo = recortar(motivoPedido, MAX_MOTIVO);

        // Los topes van antes de preguntarle nada a YouTube: así no se gasta
        // cuota en lo que de todos modos no se va a guardar.
        if (!usuario.isEsAdmin() && quedanHoy(usuario) <= 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Ya mandaste " + MAX_POR_DIA + " recomendaciones hoy. Gracias; mañana puedes mandar más.");
        }
        if (recomendaciones.countByUsuarioIdAndEstado(usuario.getId(), Recomendacion.PENDIENTE) >= MAX_PENDIENTES) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Tienes muchas recomendaciones esperando a que el equipo las revise. "
                            + "Cuando las revisen podrás mandar más.");
        }

        Recomendacion r = new Recomendacion();
        r.setUsuarioId(usuario.getId());
        r.setTexto(texto);
        r.setMotivo(motivo);
        completarConYouTube(r, LectorDeCanal.leer(texto));

        Set<String> delDirectorio = idsDelDirectorio();
        if (r.getChannelId() != null && delDirectorio.contains(r.getChannelId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    (r.getTitulo() != null ? "«" + r.getTitulo() + "»" : "Ese canal")
                            + " ya está en el directorio. Lo encuentras en la pestaña Directorio.");
        }
        if (yaLaMando(usuario.getId(), r)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya nos habías recomendado este canal. El equipo lo está revisando.");
        }

        recomendaciones.save(r);
        log.info("Recomendación {} de {}: {}", r.getId(), usuario.getId(),
                r.getChannelId() != null ? r.getChannelId() : "sin canal identificado");

        String mensaje = r.getTitulo() != null
                ? "Gracias. Le pasamos «" + r.getTitulo() + "» al equipo para que lo revise."
                : "Gracias. El equipo buscará ese canal y lo revisará.";
        return new Enviada(true, mensaje, RecomendacionDto.de(r, delDirectorio));
    }

    /**
     * Cuántas puede mandar todavía en las últimas 24 horas. Para un
     * administrador no hay tope, pero se devuelve el mismo número para que la
     * app no tenga que saberlo.
     */
    int quedanHoy(Usuario usuario) {
        long hoy = recomendaciones.countByUsuarioIdAndCreadoEnAfter(usuario.getId(),
                Instant.now().minus(Duration.ofHours(24)));
        return usuario.isEsAdmin() ? MAX_POR_DIA : (int) Math.max(0, MAX_POR_DIA - hoy);
    }

    /**
     * Le pone a la recomendación el canal de YouTube, si se puede saber.
     * YouTubeClient no lanza: si la API no contesta devuelve null, y la
     * recomendación se guarda igual con lo que la persona escribió.
     */
    private void completarConYouTube(Recomendacion r, LectorDeCanal.Entrada entrada) {
        if (entrada.vacia()) return;
        try {
            String channelId = entrada.channelId();
            if (channelId == null && entrada.handle() != null) {
                channelId = youtube.resolverHandle(entrada.handle());
            }
            if (channelId == null && entrada.videoId() != null) {
                YouTubeClient.DetalleDeVideo video = youtube.detallesDeVideo(entrada.videoId());
                if (video != null) channelId = video.channelId();
            }
            if (channelId == null) return;

            r.setChannelId(channelId);
            Dtos.DatosDeCanal datos = youtube.detallesDeCanal(channelId);
            if (datos != null) {
                r.setTitulo(vacioANull(datos.titulo()));
                r.setHandle(datos.handle() != null ? datos.handle().replaceFirst("^@", "") : null);
                r.setFotoUrl(datos.fotoUrl());
            }
        } catch (RuntimeException e) {
            // Sin datos de YouTube la recomendación sigue valiendo.
            log.warn("No se pudo identificar el canal de una recomendación: {}", e.getMessage());
        }
    }

    /** ¿Ya tiene pendiente una del mismo canal, o con el mismo texto? */
    private boolean yaLaMando(UUID usuarioId, Recomendacion nueva) {
        String texto = LectorDeCanal.normalizar(nueva.getTexto());
        return recomendaciones.findByUsuarioIdOrderByCreadoEnDesc(usuarioId, PageRequest.of(0, 200)).stream()
                .filter(r -> Recomendacion.PENDIENTE.equals(r.getEstado()))
                .anyMatch(r -> nueva.getChannelId() != null
                        ? nueva.getChannelId().equals(r.getChannelId())
                        : texto.equals(LectorDeCanal.normalizar(r.getTexto())));
    }

    // -------------------------------------------------------------------------
    // Para el panel
    // -------------------------------------------------------------------------

    /** Los ID de canal que ya están en el directorio. */
    @Transactional(readOnly = true)
    public Set<String> idsDelDirectorio() {
        return new HashSet<>(canales.idsDeYouTube());
    }

    @Transactional
    public int cambiarEstado(List<Long> ids, String estado, UUID quien) {
        if (!Recomendacion.estadoValido(estado)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado desconocido: " + estado);
        }
        List<Recomendacion> lista = recomendaciones.findAllById(sinNulos(ids));
        lista.forEach(r -> r.revisar(estado, quien));
        return lista.size();
    }

    @Transactional
    public int borrar(List<Long> ids) {
        List<Recomendacion> lista = recomendaciones.findAllById(sinNulos(ids));
        recomendaciones.deleteAll(lista);
        return lista.size();
    }

    // -------------------------------------------------------------------------

    private static List<Long> sinNulos(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No elegiste ninguna recomendación.");
        }
        return ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    }

    static String recortar(String texto, int maximo) {
        if (texto == null) return null;
        String t = texto.strip();
        if (t.isEmpty()) return null;
        return t.length() <= maximo ? t : t.substring(0, maximo).strip();
    }

    private static String vacioANull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
