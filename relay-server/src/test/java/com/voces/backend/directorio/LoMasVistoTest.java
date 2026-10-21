package com.voces.backend.directorio;

import com.voces.backend.modelo.Canal;
import com.voces.backend.modelo.Creador;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Productora;
import com.voces.backend.modelo.Publicacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Las reglas del apartado «Lo más visto» de Novedades. */
class LoMasVistoTest {

    private static final Instant AHORA = Instant.parse("2026-10-21T14:00:00Z");

    @Test
    @DisplayName("«Hoy» son las últimas 24 horas y «Esta semana» los últimos siete días")
    void periodos() {
        assertEquals(Instant.parse("2026-10-20T14:00:00Z"), LoMasVisto.Periodo.HOY.desde(AHORA));
        assertEquals(Instant.parse("2026-10-14T14:00:00Z"), LoMasVisto.Periodo.SEMANA.desde(AHORA));
    }

    @Test
    @DisplayName("Lo que no se entiende como periodo es «Hoy»")
    void periodoDesconocido() {
        assertEquals(LoMasVisto.Periodo.SEMANA, LoMasVisto.Periodo.de("semana"));
        assertEquals(LoMasVisto.Periodo.SEMANA, LoMasVisto.Periodo.de(" Semana "));
        assertEquals(LoMasVisto.Periodo.HOY, LoMasVisto.Periodo.de("hoy"));
        assertEquals(LoMasVisto.Periodo.HOY, LoMasVisto.Periodo.de("mes"));
        assertEquals(LoMasVisto.Periodo.HOY, LoMasVisto.Periodo.de(null));
    }

    @Test
    @DisplayName("La app no puede pedir más de 30; sin número, 20")
    void cuantos() {
        assertEquals(20, LoMasVisto.cuantos(0));
        assertEquals(20, LoMasVisto.cuantos(-5));
        assertEquals(10, LoMasVisto.cuantos(10));
        assertEquals(30, LoMasVisto.cuantos(500));
    }

    @Test
    @DisplayName("Solo entran los canales y creadores visibles")
    void soloLoVisible() {
        Creador visible = creador(true);
        Creador oculto = creador(false);
        Productora medio = productora(true);
        Productora medioOculto = productora(false);

        Canal deVisible = canal(visible.getId(), null);
        Canal deOculto = canal(oculto.getId(), null);
        Canal delMedio = canal(null, medio.getId());
        Canal delMedioOculto = canal(null, medioOculto.getId());
        // De un creador visible, aunque el medio esté oculto: manda el dueño.
        Canal deVisibleEnMedioOculto = canal(visible.getId(), medioOculto.getId());

        Catalogo.Vista vista = new Catalogo.Vista(
                List.of(deVisible, deOculto, delMedio, delMedioOculto, deVisibleEnMedioOculto),
                List.of(medio, medioOculto),
                List.of(visible, oculto));

        assertEquals(Set.of(deVisible.getId(), delMedio.getId(), deVisibleEnMedioOculto.getId()),
                Set.copyOf(vista.canalesVivos()));
        assertEquals(List.of(visible.getId()), vista.creadoresVisibles());
    }

    @Test
    @DisplayName("Las vistas solo viajan cuando se ponen a propósito")
    void vistasEnElDto() {
        Publicacion p = new Publicacion();
        p.setVideoId("abc");
        p.setVistas(1234L);

        Dtos.PublicacionDto normal = Dtos.PublicacionDto.de(p, "Alguien", null);
        assertNull(normal.vistas(), "Novedades y las fichas no las llevan");

        Dtos.PublicacionDto conVistas = normal.conVistas(p.getVistas());
        assertEquals(1234L, conVistas.vistas());
        assertEquals("abc", conVistas.videoId());
        assertEquals("Alguien", conVistas.creadorNombre());
    }

    private static Creador creador(boolean activo) {
        Creador c = new Creador();
        c.setId(UUID.randomUUID());
        c.setActivo(activo);
        return c;
    }

    private static Productora productora(boolean activo) {
        Productora p = new Productora();
        p.setId(UUID.randomUUID());
        p.setActivo(activo);
        return p;
    }

    private static Canal canal(UUID creador, UUID productora) {
        Canal k = new Canal();
        k.setId(UUID.randomUUID());
        k.setCreadorId(creador);
        k.setProductoraId(productora);
        return k;
    }
}
