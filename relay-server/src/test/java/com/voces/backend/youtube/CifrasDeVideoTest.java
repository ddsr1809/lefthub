package com.voces.backend.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voces.backend.modelo.Publicacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lo que el panel enseña de cada video: duración, vistas, "me gusta",
 * comentarios y, en un directo, cuántos lo ven. Se lee de la respuesta de
 * videos.list tal como la manda YouTube, sin red.
 */
class CifrasDeVideoTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String texto) throws Exception {
        return JSON.readTree(texto.replace('\'', '"'));
    }

    @Test
    @DisplayName("Las cifras llegan como texto y se leen como números")
    void cifrasDeUnVideo() throws Exception {
        JsonNode item = json("""
                {'id':'abc','snippet':{'title':'La reforma, explicada','channelId':'UC1'},
                 'contentDetails':{'duration':'PT12M34S'},
                 'statistics':{'viewCount':'15321','likeCount':'842','favoriteCount':'0','commentCount':'97'}}""");

        YouTubeClient.DetalleDeVideo d = YouTubeClient.aDetalle("abc", item);

        assertEquals(15321L, d.vistas());
        assertEquals(842L, d.meGusta());
        assertEquals(97L, d.comentarios());
        assertNull(d.espectadores(), "no es un directo");
        assertEquals("PT12M34S", d.duracion());
    }

    @Test
    @DisplayName("Lo que el canal oculta queda vacío, no en cero")
    void cifrasOcultas() throws Exception {
        JsonNode item = json("""
                {'id':'abc','snippet':{'title':'x'},'contentDetails':{'duration':'PT1M'},
                 'statistics':{'viewCount':'40'}}""");

        YouTubeClient.DetalleDeVideo d = YouTubeClient.aDetalle("abc", item);

        assertEquals(40L, d.vistas());
        assertNull(d.meGusta());
        assertNull(d.comentarios());
    }

    @Test
    @DisplayName("Una respuesta sin cifras (una versión vieja de la llamada) no rompe nada")
    void sinCifras() throws Exception {
        YouTubeClient.DetalleDeVideo d = YouTubeClient.aDetalle("abc",
                json("{'id':'abc','snippet':{'title':'x'},'contentDetails':{'duration':'PT1M'}}"));
        assertNull(d.vistas());
        assertNull(d.meGusta());
        assertEquals("x", d.titulo());
    }

    @Test
    @DisplayName("En un directo al aire se sabe cuántos lo están viendo")
    void directo() throws Exception {
        JsonNode item = json("""
                {'id':'abc','snippet':{'title':'EN VIVO'},'contentDetails':{'duration':'P0D'},
                 'statistics':{'viewCount':'5200'},
                 'liveStreamingDetails':{'actualStartTime':'2026-10-16T18:00:00Z','concurrentViewers':'1834'}}""");

        YouTubeClient.DetalleDeVideo d = YouTubeClient.aDetalle("abc", item);

        assertEquals("en_vivo", d.directo());
        assertEquals(1834L, d.espectadores());
    }

    @Test
    @DisplayName("Al copiarlas a la publicación se apunta de cuándo son")
    void copiar() throws Exception {
        Publicacion p = new Publicacion();
        p.setVistas(10L);
        p.setMeGusta(3L);
        Instant ahora = Instant.parse("2026-10-16T20:00:00Z");

        YouTubeClient.aDetalle("abc", json("""
                {'id':'abc','snippet':{'title':'x'},'statistics':{'viewCount':'99','commentCount':'4'}}"""))
                .copiarCifras(p, ahora);

        assertEquals(99L, p.getVistas());
        assertNull(p.getMeGusta(), "el canal los ocultó desde la última vez: no se deja el número viejo");
        assertEquals(4L, p.getComentarios());
        assertEquals(ahora, p.getCifrasEn());
    }

    @Test
    @DisplayName("Al terminar el directo deja de haber espectadores")
    void directoTerminado() throws Exception {
        Publicacion p = new Publicacion();
        p.setEspectadores(1834L);

        YouTubeClient.aDetalle("abc", json("""
                {'id':'abc','snippet':{'title':'x'},'statistics':{'viewCount':'9000'},
                 'liveStreamingDetails':{'actualStartTime':'2026-10-16T18:00:00Z','actualEndTime':'2026-10-16T19:30:00Z','concurrentViewers':'12'}}"""))
                .copiarCifras(p, Instant.now());

        assertNull(p.getEspectadores());
        assertEquals(9000L, p.getVistas());
    }

    @Test
    @DisplayName("Números raros de la API no revientan")
    void numerosRaros() throws Exception {
        JsonNode cifras = json("{'a':'12','b':12,'c':'','d':'mucho','e':null,'f':'99999999999'}");
        assertEquals(12L, YouTubeClient.numero(cifras, "a"));
        assertEquals(12L, YouTubeClient.numero(cifras, "b"));
        assertNull(YouTubeClient.numero(cifras, "c"));
        assertNull(YouTubeClient.numero(cifras, "d"));
        assertNull(YouTubeClient.numero(cifras, "e"));
        assertNull(YouTubeClient.numero(cifras, "no-esta"));
        assertEquals(99999999999L, YouTubeClient.numero(cifras, "f"), "más vistas de las que caben en un int");
        assertNull(YouTubeClient.numero(null, "a"));
    }

    @Test
    @DisplayName("La duración en segundos, de la forma en que la escribe YouTube")
    void duracion() {
        assertEquals(754L, YouTubeClient.segundos("PT12M34S"));
        assertEquals(3723L, YouTubeClient.segundos("PT1H2M3S"));
        assertEquals(45L, YouTubeClient.segundos("PT45S"));
        assertEquals(93600L, YouTubeClient.segundos("P1DT2H"), "un directo de más de un día");
        assertNull(YouTubeClient.segundos("P0D"), "un directo que no ha terminado no tiene duración");
        assertNull(YouTubeClient.segundos(null));
        assertNull(YouTubeClient.segundos(""));
        assertNull(YouTubeClient.segundos("doce minutos"));
    }
}
