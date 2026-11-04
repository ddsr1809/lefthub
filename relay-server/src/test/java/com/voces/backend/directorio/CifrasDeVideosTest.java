package com.voces.backend.directorio;

import com.voces.backend.modelo.Publicacion;
import com.voces.backend.youtube.YouTubeClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Qué le pasa a cada video cuando se piden sus cifras. */
class CifrasDeVideosTest {

    private static Publicacion video(String id, Long vistas) {
        Publicacion p = new Publicacion();
        p.setVideoId(id);
        p.setVistas(vistas);
        p.setEspectadores(50L);
        return p;
    }

    private static YouTubeClient.DetalleDeVideo detalle(String id, long vistas) {
        return new YouTubeClient.DetalleDeVideo(id, "t", "", "UC1", "Canal", null, null, "PT5M",
                false, "video", "no", vistas, 10L, 2L, null);
    }

    @Test
    @DisplayName("Los que YouTube devuelve quedan al día; los que no, conservan sus últimas cifras")
    void aplicar() {
        Publicacion uno = video("uno", 5L);
        Publicacion borrado = video("borrado", 700L);
        Instant ahora = Instant.parse("2026-10-16T20:00:00Z");

        CifrasDeVideos.Resultado r = CifrasDeVideos.aplicar(List.of(uno, borrado),
                Map.of("uno", detalle("uno", 1200)), ahora);

        assertEquals(1, r.actualizados());
        assertEquals(1, r.noExisten());

        assertEquals(1200L, uno.getVistas());
        assertEquals(ahora, uno.getCifrasEn());

        assertEquals(700L, borrado.getVistas(), "lo último que se supo de él");
        assertNull(borrado.getEspectadores(), "ya nadie lo está viendo en directo");
        assertEquals(ahora, borrado.getCifrasEn(), "para no preguntar por él hasta su siguiente turno");
    }

    private static Publicacion reciente(String id, Instant publicado, Instant cifras) {
        Publicacion p = video(id, 1L);
        p.setPlataforma("youtube");
        p.setEstado("ok");
        p.setPublicadoEn(publicado);
        p.setCifrasEn(cifras);
        return p;
    }

    @Test
    @DisplayName("Al abrir la app se piden solo las de videos recientes con cifras de hace más de 10 minutos")
    void alAbrir() {
        Instant ahora = Instant.parse("2026-11-04T20:00:00Z");
        Instant ayer = ahora.minus(Duration.ofDays(1));
        List<Publicacion> lista = List.of(
                reciente("viejas", ayer, ahora.minus(Duration.ofMinutes(12))),
                reciente("frescas", ayer, ahora.minus(Duration.ofMinutes(8))),
                reciente("nunca", ayer, null),
                reciente("de-hace-un-mes", ahora.minus(Duration.ofDays(30)), ahora.minus(Duration.ofDays(9))),
                reciente("de-hace-6-dias", ahora.minus(Duration.ofDays(6)), ahora.minus(Duration.ofHours(1))));
        Publicacion retirado = reciente("retirado", ayer, null);
        retirado.setEstado("removed");
        Publicacion otraPlataforma = reciente("tiktok", ayer, null);
        otraPlataforma.setPlataforma("tiktok");

        List<String> ids = CifrasDeVideos.porPedirAlAbrir(
                java.util.stream.Stream.concat(lista.stream(), java.util.stream.Stream.of(retirado, otraPlataforma)).toList(),
                ahora);
        assertEquals(List.of("viejas", "nunca", "de-hace-6-dias"), ids);
    }

    @Test
    @DisplayName("Al abrir la app, nunca más de una llamada a YouTube")
    void alAbrirTope() {
        Instant ahora = Instant.parse("2026-11-04T20:00:00Z");
        List<Publicacion> muchos = new java.util.ArrayList<>();
        for (int i = 0; i < 80; i++) muchos.add(reciente("v" + i, ahora.minus(Duration.ofHours(3)), null));
        assertEquals(CifrasDeVideos.MAXIMO_AL_ABRIR, CifrasDeVideos.porPedirAlAbrir(muchos, ahora).size());
    }
}
