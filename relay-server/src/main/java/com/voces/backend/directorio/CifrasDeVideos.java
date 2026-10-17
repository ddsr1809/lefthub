package com.voces.backend.directorio;

import com.voces.backend.config.RelayProperties;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Publicacion;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.youtube.YouTubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pone al día las cifras de los videos: vistas, "me gusta", comentarios.
 *
 * Las pide a YouTube con videos.list, que cuesta 1 unidad de cuota por cada
 * 50 videos. Cada media hora una pasada con los que tocan (ver
 * Repositorios.Publicaciones.porActualizarCifras): los recientes a menudo,
 * los viejos cada tres días. Con mil videos en el directorio son unas 30
 * unidades al día, de las 10.000 que da YouTube.
 *
 * Además se piden al momento desde el panel (botón «Actualizar cifras» y al
 * abrir la ficha de un video), y vienen gratis en las llamadas que el
 * servidor ya hacía: al llegar un video, al completar uno sin datos y al
 * vigilar un directo.
 *
 * Solo lee. No cambia título, tipo ni nada de lo que ve la app.
 */
@Service
public class CifrasDeVideos {

    private static final Logger log = LoggerFactory.getLogger(CifrasDeVideos.class);

    /** Cuántos por pasada programada: 10 unidades como mucho. */
    static final int POR_PASADA = 500;

    /** Lo más que se pide de una vez desde el panel: 4 unidades. */
    static final int MAXIMO_DEL_PANEL = 200;

    /** Al abrir la ficha de un video, se piden de nuevo si son más viejas que esto. */
    static final Duration FRESCAS = Duration.ofMinutes(10);

    private final Repositorios.Publicaciones publicaciones;
    private final YouTubeClient youtube;
    private final RelayProperties config;

    // Una a la vez: la pasada programada y el botón del panel podrían coincidir.
    private final ReentrantLock enMarcha = new ReentrantLock();

    public CifrasDeVideos(Repositorios.Publicaciones publicaciones, YouTubeClient youtube,
                          RelayProperties config) {
        this.publicaciones = publicaciones;
        this.youtube = youtube;
        this.config = config;
    }

    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.MINUTES, initialDelay = 5)
    public void programada() {
        try {
            Dtos.CifrasActualizadas r = actualizar(publicaciones.porActualizarCifras(POR_PASADA));
            if (r.pedidos() > 0) {
                log.info("Cifras de videos: {} pedidas, {} al día, {} ya no están en YouTube",
                        r.pedidos(), r.actualizados(), r.noExisten());
            }
            if (r.sinApi() && r.pedidos() > 0) {
                log.warn("Cifras de videos: la Data API no contestó; revisa YOUTUBE_API_KEY y la cuota");
            }
        } catch (RuntimeException e) {
            log.warn("No se pudieron actualizar las cifras de los videos: {}", e.getMessage());
        }
    }

    /** Las de esos videos, ahora. Lo pide el panel para lo que tiene en pantalla. */
    public Dtos.CifrasActualizadas deVideos(Collection<String> videoIds) {
        List<String> ids = videoIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .limit(MAXIMO_DEL_PANEL)
                .toList();
        if (ids.isEmpty()) return new Dtos.CifrasActualizadas(0, 0, 0, false, false);
        return actualizar(publicaciones.findByVideoIdIn(ids));
    }

    /**
     * Las de un video si están viejas. Para la ficha del panel: quien la abre
     * quiere ver el número de ahora. Devuelve si cambió algo.
     */
    public boolean refrescarSiHaceFalta(Publicacion p) {
        if (!esYouTube(p)) return false;
        if (p.getCifrasEn() != null && p.getCifrasEn().isAfter(Instant.now().minus(FRESCAS))) return false;
        return actualizar(List.of(p)).actualizados() > 0;
    }

    /**
     * Sin @Transactional a propósito, igual que RevisionDeVideos: los cambios
     * se guardan de forma explícita al final.
     */
    Dtos.CifrasActualizadas actualizar(List<Publicacion> lista) {
        List<Publicacion> pedidos = lista.stream().filter(CifrasDeVideos::esYouTube).toList();
        if (pedidos.isEmpty()) return new Dtos.CifrasActualizadas(0, 0, 0, false, false);
        if (config.youtube().apiKey().isBlank()) {
            return new Dtos.CifrasActualizadas(pedidos.size(), 0, 0, true, false);
        }
        if (!enMarcha.tryLock()) {
            return new Dtos.CifrasActualizadas(pedidos.size(), 0, 0, false, true);
        }
        try {
            Map<String, YouTubeClient.DetalleDeVideo> detalles = youtube.detallesDeVideos(
                    pedidos.stream().map(Publicacion::getVideoId).distinct().toList());
            if (detalles == null) {
                // La API no contestó: no se toca nada y se reintenta en la siguiente pasada.
                return new Dtos.CifrasActualizadas(pedidos.size(), 0, 0, true, false);
            }

            Resultado r = aplicar(pedidos, detalles, Instant.now());
            publicaciones.saveAll(pedidos);
            return new Dtos.CifrasActualizadas(pedidos.size(), r.actualizados(), r.noExisten(), false, false);
        } finally {
            enMarcha.unlock();
        }
    }

    record Resultado(int actualizados, int noExisten) {}

    /**
     * Pone a cada video lo que contestó YouTube.
     *
     * Uno que no viene en la respuesta se borró o se hizo privado: conserva
     * las últimas cifras que tuvo, y se apunta la hora para no volver a
     * preguntar por él hasta su siguiente turno.
     */
    static Resultado aplicar(List<Publicacion> pedidos, Map<String, YouTubeClient.DetalleDeVideo> detalles,
                             Instant ahora) {
        int actualizados = 0;
        int noExisten = 0;
        for (Publicacion p : pedidos) {
            YouTubeClient.DetalleDeVideo d = detalles.get(p.getVideoId());
            if (d == null) {
                p.setEspectadores(null);
                p.setCifrasEn(ahora);
                noExisten++;
            } else {
                d.copiarCifras(p, ahora);
                actualizados++;
            }
        }
        return new Resultado(actualizados, noExisten);
    }

    private static boolean esYouTube(Publicacion p) {
        return "youtube".equals(p.getPlataforma()) && p.getVideoId() != null && !p.getVideoId().isBlank();
    }
}
