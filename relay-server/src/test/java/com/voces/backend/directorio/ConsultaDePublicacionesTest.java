package com.voces.backend.directorio;

import com.voces.backend.directorio.ConsultaDePublicaciones.Consulta;
import com.voces.backend.directorio.ConsultaDePublicaciones.Filtro;
import com.voces.backend.modelo.Publicacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Las consultas de la sección Publicaciones del panel: qué filtra cada
 * opción, en qué orden salen, y que lo que manda el panel nunca se pegue al
 * texto de la consulta. Sin base de datos: se revisa el texto.
 */
class ConsultaDePublicacionesTest {

    private static final Filtro TODO = new Filtro(null, null, null, null, null, null);

    @Test
    @DisplayName("Sin filtros: todos los videos, el más reciente primero")
    void sinFiltros() {
        Consulta c = ConsultaDePublicaciones.videosJpql(TODO, "publicado", false);
        assertFalse(c.jpql().contains(" where "));
        assertTrue(c.jpql().contains("order by case when p.publicadoEn is null then 1 else 0 end, p.publicadoEn desc"));
        assertTrue(c.parametros().isEmpty());
    }

    @Test
    @DisplayName("Cada columna ordena por su dato, y lo que no lo tiene va al final")
    void ordenes() {
        assertTrue(ConsultaDePublicaciones.videosJpql(TODO, "vistas", false).jpql()
                .contains("order by case when p.vistas is null then 1 else 0 end, p.vistas desc, p.publicadoEn desc"));
        assertTrue(ConsultaDePublicaciones.videosJpql(TODO, "vistas", true).jpql()
                .contains("case when p.vistas is null then 1 else 0 end, p.vistas asc"),
                "de menos a más, los que no tienen cifras siguen al final");
        assertTrue(ConsultaDePublicaciones.videosJpql(TODO, "duracion", false).jpql().contains("p.duracionSegundos desc"),
                "la duración se ordena en segundos, no como texto");
        assertTrue(ConsultaDePublicaciones.videosJpql(TODO, "creador", true).jpql().contains("lower(c.nombre) asc"));
        assertTrue(ConsultaDePublicaciones.videosJpql(TODO, "meGusta", false).jpql().contains("p.meGusta desc"));
        assertTrue(ConsultaDePublicaciones.videosJpql(TODO, "comentarios", false).jpql().contains("p.comentarios desc"));
    }

    @Test
    @DisplayName("Un orden que no existe no llega a la consulta: se ordena por fecha")
    void ordenDesconocido() {
        String jpql = ConsultaDePublicaciones.videosJpql(TODO, "p.id; delete from Publicacion p", false).jpql();
        assertFalse(jpql.contains("delete"));
        assertTrue(jpql.contains("p.publicadoEn desc"));
    }

    @Test
    @DisplayName("Fechas, tipo y dueño van como parámetros")
    void filtros() {
        UUID creador = UUID.randomUUID(), canal = UUID.randomUUID(), medio = UUID.randomUUID();
        Instant desde = Instant.parse("2026-10-01T07:00:00Z"), hasta = Instant.parse("2026-10-21T07:00:00Z");
        Consulta c = ConsultaDePublicaciones.videosJpql(
                new Filtro("cortos", desde, hasta, creador, canal, medio), "vistas", false);

        assertTrue(c.jpql().contains(" where p.tipo = :corto and p.publicadoEn >= :desde and p.publicadoEn < :hasta"
                + " and p.creadorId = :creador and p.canalId = :canal and k.productoraId = :medio order by"), c.jpql());
        assertEquals(Publicacion.TIPO_CORTO, c.parametros().get("corto"));
        assertEquals(desde, c.parametros().get("desde"));
        assertEquals(hasta, c.parametros().get("hasta"));
        assertEquals(creador, c.parametros().get("creador"));
        assertEquals(canal, c.parametros().get("canal"));
        assertEquals(medio, c.parametros().get("medio"));

        assertTrue(ConsultaDePublicaciones.videosJpql(new Filtro("videos", null, null, null, null, null), "publicado", false)
                .jpql().contains(" where p.tipo <> :corto order by"));
        assertFalse(ConsultaDePublicaciones.videosJpql(new Filtro("otra cosa", null, null, null, null, null), "publicado", false)
                .jpql().contains(" where "), "un tipo desconocido es «todos»");
    }

    @Test
    @DisplayName("Los totales se agrupan por creador, canal o medio, del más visto al menos")
    void totales() {
        Consulta porCreador = ConsultaDePublicaciones.totalesJpql(TODO, "creador");
        assertTrue(porCreador.jpql().startsWith("select p.creadorId, count(p),"));
        assertTrue(porCreador.jpql().contains(" where p.creadorId is not null group by p.creadorId"
                + " order by sum(coalesce(p.vistas, 0)) desc"), porCreador.jpql());

        Consulta porMedio = ConsultaDePublicaciones.totalesJpql(
                new Filtro("videos", Instant.EPOCH, null, null, null, null), "medio");
        assertTrue(porMedio.jpql().contains("left join Canal k on k.id = p.canalId"
                + " where p.tipo <> :corto and p.publicadoEn >= :desde and k.productoraId is not null"
                + " group by k.productoraId"), porMedio.jpql());

        assertTrue(ConsultaDePublicaciones.totalesJpql(TODO, "canal").jpql().contains("group by p.canalId"));
        assertThrows(IllegalArgumentException.class, () -> ConsultaDePublicaciones.totalesJpql(TODO, "pais"));
    }

    @Test
    @DisplayName("La duración en segundos se calcula al guardarla")
    void duracionEnSegundos() {
        Publicacion p = new Publicacion();
        p.setDuracion("PT1H2M3S");
        assertEquals(3723, p.getDuracionSegundos());
        p.setDuracion("PT9M");
        assertEquals(540, p.getDuracionSegundos());
        p.setDuracion("P0D");
        assertNull(p.getDuracionSegundos(), "un directo sin terminar");
        p.setDuracion(null);
        assertNull(p.getDuracionSegundos());
        assertEquals(93600, Publicacion.segundosDe("P1DT2H"));
        assertNull(Publicacion.segundosDe("doce minutos"));
    }
}
