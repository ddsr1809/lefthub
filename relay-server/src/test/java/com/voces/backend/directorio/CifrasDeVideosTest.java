package com.voces.backend.directorio;

import com.voces.backend.modelo.Publicacion;
import com.voces.backend.youtube.YouTubeClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
}
