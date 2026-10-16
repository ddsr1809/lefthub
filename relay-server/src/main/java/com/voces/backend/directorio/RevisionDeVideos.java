package com.voces.backend.directorio;

import com.voces.backend.config.RelayProperties;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Publicacion;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.push.Emisores;
import com.voces.backend.push.PushService;
import com.voces.backend.youtube.YouTubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Completa los videos que llegaron sin sus datos de YouTube.
 *
 * Al llegar un video, el servidor pide a la Data API su duración, su
 * miniatura y su descripción. Si no contesta (la clave no vale, se acabó la
 * cuota, YouTube todavía lo está procesando), el video se guarda como normal y
 * sin duración. Sin duración no se sabe si es un corto, así que un Short que
 * llega así se quedaba para siempre entre los videos normales.
 *
 * Esto vuelve a preguntar por ellos: cada diez minutos una pasada, y desde el
 * panel cuando se quiera. Por cada video que YouTube devuelve se guarda su
 * duración, la miniatura buena y la descripción, y se decide de nuevo si es
 * corto, igual que al llegar.
 *
 * Solo avisa de los videos que llegaron sin datos y de los que tampoco se supo
 * si eran un Short: esos se guardaron sin aviso para no mandar un "video
 * nuevo" que en realidad era un corto. Al completarlos se manda el aviso que
 * toque, si todavía son recientes.
 *
 * Cuesta 1 unidad de cuota por cada 50 videos. A los que duran tres minutos o
 * menos se les pregunta además a youtube.com/shorts, que no cuesta cuota.
 *
 * Lo que el equipo marcó a mano en el panel ("Es corto" / "No es corto") se
 * respeta: se completan sus datos, pero no se le cambia el tipo.
 */
@Service
public class RevisionDeVideos {

    private static final Logger log = LoggerFactory.getLogger(RevisionDeVideos.class);

    /** Cuántos por pasada programada. */
    static final int POR_PASADA = 200;

    /**
     * Mientras YouTube procesa un video, lo devuelve sin duración. Se vuelve a
     * preguntar en la siguiente pasada, salvo que ya lleve este tiempo así:
     * entonces se da por perdido para no preguntar por él para siempre.
     */
    static final Duration ESPERA_PROCESANDO = Duration.ofDays(2);

    private final Repositorios.Publicaciones publicaciones;
    private final YouTubeClient youtube;
    private final RelayProperties config;
    private final PushService push;
    private final Emisores emisores;
    private final AjustesService ajustes;

    // Una pasada a la vez: la programada y la del panel podrían coincidir.
    private final ReentrantLock enMarcha = new ReentrantLock();

    public RevisionDeVideos(Repositorios.Publicaciones publicaciones, YouTubeClient youtube,
                            RelayProperties config, PushService push, Emisores emisores,
                            AjustesService ajustes) {
        this.publicaciones = publicaciones;
        this.youtube = youtube;
        this.config = config;
        this.push = push;
        this.emisores = emisores;
        this.ajustes = ajustes;
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES, initialDelay = 3)
    public void programada() {
        try {
            Dtos.RevisionDeVideos r = completar(POR_PASADA);
            if (r.revisados() > 0) {
                log.info("Videos sin datos: {} revisados, {} completados, {} pasaron a cortos, {} ya no existen, quedan {}",
                        r.revisados(), r.completados(), r.aCortos(), r.noExisten(), r.quedan());
            }
            if (r.sinApi()) log.warn("Videos sin datos: la Data API no contestó; revisa YOUTUBE_API_KEY y la cuota");
        } catch (RuntimeException e) {
            log.warn("No se pudieron revisar los videos sin datos: {}", e.getMessage());
        }
    }

    /**
     * Una pasada.
     *
     * Sin @Transactional a propósito: la tarea programada la llama desde la
     * misma clase y Spring no abriría la transacción. Los cambios se guardan
     * de forma explícita al final.
     */
    public Dtos.RevisionDeVideos completar(int limite) {
        if (config.youtube().apiKey().isBlank()) {
            return new Dtos.RevisionDeVideos(0, 0, 0, 0, publicaciones.cuantosSinDatos(), true, false);
        }
        if (!enMarcha.tryLock()) {
            return new Dtos.RevisionDeVideos(0, 0, 0, 0, publicaciones.cuantosSinDatos(), false, true);
        }
        try {
            List<Publicacion> pendientes = publicaciones.sinDatos(PageRequest.of(0, limite));
            if (pendientes.isEmpty()) {
                return new Dtos.RevisionDeVideos(0, 0, 0, 0, 0, false, false);
            }

            Map<String, YouTubeClient.DetalleDeVideo> detalles = youtube.detallesDeVideos(
                    pendientes.stream().map(Publicacion::getVideoId).distinct().toList());
            if (detalles == null) {
                // La API no contestó: no se marca nada, se reintenta después.
                return new Dtos.RevisionDeVideos(0, 0, 0, 0, publicaciones.cuantosSinDatos(), true, false);
            }

            Instant ahora = Instant.now();
            int completados = 0;
            int aCortos = 0;
            int noExisten = 0;

            for (Publicacion p : pendientes) {
                YouTubeClient.DetalleDeVideo d = detalles.get(p.getVideoId());

                if (d == null) {
                    // YouTube ya no lo tiene: borrado o privado. No se vuelve a preguntar.
                    p.setDatosRevisadosEn(ahora);
                    noExisten++;
                    continue;
                }
                if (d.duracion() == null || "P0D".equals(d.duracion())) {
                    // Todavía procesándose: a la siguiente pasada, salvo que ya lleve mucho así.
                    if (p.getPublicadoEn() != null && p.getPublicadoEn().isBefore(ahora.minus(ESPERA_PROCESANDO))) {
                        p.setDatosRevisadosEn(ahora);
                    }
                    continue;
                }

                // Hay que mirarlo antes de aplicar: después ya puede ser corto.
                boolean faltaAviso = avisoPendiente(p, ahora);

                if (aplicar(p, d)) aCortos++;
                p.setDatosRevisadosEn(ahora);
                completados++;

                if (faltaAviso) avisar(p, d);
            }

            publicaciones.saveAll(pendientes);
            return new Dtos.RevisionDeVideos(pendientes.size(), completados, aCortos, noExisten,
                    publicaciones.cuantosSinDatos(), false, false);
        } finally {
            enMarcha.unlock();
        }
    }

    /**
     * ¿Se guardó sin aviso por no saber si era un corto?
     *
     * Es un video normal (así se guarda cuando no se sabe), sin avisar,
     * reciente y sin directo de por medio. Uno que llegó como corto con los
     * cortos apagados no entra: ese no se avisa nunca, igual que al llegar.
     */
    private boolean avisoPendiente(Publicacion p, Instant ahora) {
        if (p.isNotificado() || p.esCorto()) return false;
        if (!Publicacion.OK.equals(p.getEstado())) return false;
        if (!Publicacion.DIRECTO_NO.equals(p.getDirecto())) return false;
        Instant limite = ahora.minusSeconds(config.websub().antiguedadMaximaHoras() * 3600);
        return p.getPublicadoEn() != null && p.getPublicadoEn().isAfter(limite);
    }

    /** El aviso que se dejó pendiente al llegar, ya con el tipo bien puesto. */
    private void avisar(Publicacion p, YouTubeClient.DetalleDeVideo d) {
        if (p.esCorto() && !ajustes.cortos()) return;

        Optional<PushService.Emisor> emisor = emisores.de(p);
        if (emisor.isEmpty()) return;

        boolean enviado = p.esCorto()
                ? push.avisarCorto(emisor.get(), p.getVideoId(), p.getTitulo(), p.getMiniaturaUrl())
                : push.avisarPublicacion(emisor.get(), p.getVideoId(), p.getTitulo(),
                        p.getMiniaturaUrl(), d);
        p.setNotificado(enviado);
        log.info("Aviso pendiente de {} ({}): {}", p.getVideoId(), p.getTipo(),
                enviado ? "enviado" : "falló");
    }

    /**
     * Pone a un video lo que dice YouTube.
     *
     * @return true si pasó de video normal a corto.
     */
    private boolean aplicar(Publicacion p, YouTubeClient.DetalleDeVideo d) {
        p.setDuracion(d.duracion());
        if (p.getDescripcion() == null && d.descripcion() != null) p.setDescripcion(d.descripcion());
        // La miniatura que se pone cuando no hay datos es la genérica de
        // i.ytimg.com; la de la API es mejor.
        if (d.miniatura() != null && (p.getMiniaturaUrl() == null || p.getMiniaturaUrl().endsWith("/hqdefault.jpg"))) {
            p.setMiniaturaUrl(d.miniatura());
        }

        if (p.isTipoAMano()) return false;

        // Lo mismo que al llegar (WebSubService.tipoDe): a los que duran poco
        // se les pregunta además a YouTube si son un Short.
        boolean hayDuda = Publicacion.TIPO_CORTO.equals(d.tipo())
                && Publicacion.DIRECTO_NO.equals(d.directo());
        String tipo = YouTubeClient.tipoDe(d.tipo(), d.directo(),
                hayDuda ? youtube.esShort(p.getVideoId()) : null);

        boolean aCorto = !p.esCorto() && Publicacion.TIPO_CORTO.equals(tipo);
        p.setTipo(tipo);
        return aCorto;
    }
}
