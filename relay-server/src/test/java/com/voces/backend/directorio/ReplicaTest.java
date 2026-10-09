package com.voces.backend.directorio;

import com.voces.backend.config.RelayProperties;
import com.voces.backend.modelo.Canal;
import com.voces.backend.modelo.Creador;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Productora;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Las decisiones de la copia de creadores y productoras de producción a
 * testing: quién envía y quién recibe, con qué token, qué viaja y sobre qué
 * fila se guarda cada copia.
 * Lógica pura, sin red ni base de datos.
 */
class ReplicaTest {

    private static final String CANAL = "UCabcdefghijklmnopqrstuv";
    private static final String CLIPS = "UCzyxwvutsrqponmlkjihgfe";

    private static Creador creador(String nombre, UUID origen) {
        Creador c = new Creador();
        c.setId(UUID.randomUUID());
        c.setNombre(nombre);
        c.setOrigenId(origen);
        return c;
    }

    private static Canal canal(String plataforma, String url, String channelId) {
        Canal k = new Canal();
        k.setId(UUID.randomUUID());
        k.setPlataforma(plataforma);
        k.setUrl(url);
        k.setChannelId(channelId);
        return k;
    }

    /** Como lo manda una producción anterior a los canales múltiples. */
    private static Dtos.GuardarCreador peticion(Map<String, Dtos.ConexionDto> conexiones) {
        return new Dtos.GuardarCreador(UUID.randomUUID(), "Canal Once", "noticias",
                null, null, conexiones, true, null, null);
    }

    private static Dtos.GuardarCreador peticionConCanales(Dtos.GuardarCanal... canales) {
        return new Dtos.GuardarCreador(UUID.randomUUID(), "Canal Once", "noticias",
                null, null, null, true, List.of(canales), null);
    }

    // -------------------------------------------------------------------------
    // Quién envía y quién recibe
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Producción envía y no recibe; testing recibe y no envía; local, ninguna de las dos")
    void papeles() {
        var produccion = new RelayProperties.Replica("https://testapp.example", "secreto");
        assertTrue(produccion.envia());
        assertFalse(produccion.recibe(), "el token compartido no debe abrir producción");

        var testing = new RelayProperties.Replica("", "secreto");
        assertFalse(testing.envia());
        assertTrue(testing.recibe());

        var local = new RelayProperties.Replica("", "");
        assertFalse(local.envia());
        assertFalse(local.recibe());
    }

    @Test
    @DisplayName("El token tiene que coincidir entero, y sin token configurado no pasa nadie")
    void token() {
        assertTrue(ReplicaService.coincide("secreto", "secreto"));
        assertFalse(ReplicaService.coincide("secreto", "secret"));
        assertFalse(ReplicaService.coincide("secreto", null));
        assertFalse(ReplicaService.coincide("", ""));
        assertFalse(ReplicaService.coincide(null, null));
    }

    @Test
    @DisplayName("La URL de destino aguanta una barra final en REPLICA_URL")
    void destino() {
        String esperado = "https://testapp.example/internal/replica/creadores";
        assertEquals(esperado, ReplicaService.destino("https://testapp.example"));
        assertEquals(esperado, ReplicaService.destino("https://testapp.example/ "));

        assertEquals("https://testapp.example/internal/replica/productoras",
                ReplicaService.destino("https://testapp.example/", ReplicaService.RUTA_PRODUCTORAS));
    }

    // -------------------------------------------------------------------------
    // Qué viaja
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Lo que se envía lleva el id de producción, todos los canales y sus productoras")
    void cuerpo() {
        UUID productora = UUID.randomUUID();

        Creador c = creador("Canal Once", null);
        c.setCategoria("noticias");
        c.setActivo(false);
        c.getProductoras().add(productora);

        Canal principal = canal("youtube", "https://www.youtube.com/@CanalOnceIPN", CANAL);
        principal.setHandle("CanalOnceIPN");
        Canal clips = canal("youtube", "https://www.youtube.com/@OnceClips", CLIPS);
        clips.setNombre("Clips");
        clips.setProductoraId(productora);
        UUID invitada = UUID.randomUUID();
        clips.getVinculados().add(invitada);

        Dtos.GuardarCreador cuerpo = ReplicaService.cuerpoDe(c, List.of(principal, clips));

        assertEquals(c.getId(), cuerpo.id());
        assertEquals("Canal Once", cuerpo.nombre());
        assertEquals("noticias", cuerpo.categoria());
        assertFalse(cuerpo.estaActivo());
        assertEquals(List.of(productora), cuerpo.productoras());

        assertEquals(2, cuerpo.canales().size());
        assertEquals("Clips", cuerpo.canales().get(1).nombre());
        assertEquals(productora, cuerpo.canales().get(1).productoraId());
        assertEquals(List.of(), cuerpo.canales().get(0).creadores());
        assertEquals(List.of(invitada), cuerpo.canales().get(1).creadores(),
                "con qué otros creadores aparece cada canal viaja con el canal");
        assertEquals(List.of(CANAL, CLIPS), ReplicaService.canalesDe(cuerpo));
    }

    @Test
    @DisplayName("Para un testing con la versión anterior, viaja también el canal principal de cada plataforma")
    void cuerpoCompatible() {
        Creador c = creador("Canal Once", null);

        Dtos.GuardarCreador cuerpo = ReplicaService.cuerpoDe(c, List.of(
                canal("youtube", "https://www.youtube.com/@CanalOnceIPN", CANAL),
                canal("youtube", "https://www.youtube.com/@OnceClips", CLIPS),
                canal("tiktok", "https://tiktok.com/@once", null)));

        assertEquals(2, cuerpo.conexiones().size());
        assertEquals(CANAL, cuerpo.conexiones().get("youtube").channelId());
        assertEquals("https://tiktok.com/@once", cuerpo.conexiones().get("tiktok").url());
    }

    @Test
    @DisplayName("La productora viaja con sus canales propios y sin lista de creadores")
    void cuerpoDeProductora() {
        Productora p = new Productora();
        p.setId(UUID.randomUUID());
        p.setNombre("Estudio X");
        p.setEnDirectorio(true);
        p.setCategoria("noticias");

        Canal oficial = canal("youtube", "https://www.youtube.com/@EstudioX", CANAL);
        oficial.setProductoraId(p.getId());

        Dtos.GuardarProductora cuerpo = ReplicaService.cuerpoDe(p, List.of(oficial));

        assertEquals(p.getId(), cuerpo.id());
        assertEquals("Estudio X", cuerpo.nombre());
        assertEquals(CANAL, cuerpo.canales().get(0).channelId());
        assertNull(cuerpo.creadores(), "quién figura en ella viaja con cada creador");
        assertEquals(Boolean.TRUE, cuerpo.enDirectorio());
        assertEquals("noticias", cuerpo.categoria());
    }

    @Test
    @DisplayName("Un creador sin YouTube, o con el canal en blanco, no tiene canal que buscar")
    void sinCanal() {
        assertTrue(ReplicaService.canalesDe(peticion(null)).isEmpty());
        assertTrue(ReplicaService.canalesDe(peticion(Map.of("tiktok",
                new Dtos.ConexionDto("tiktok", "https://tiktok.com/@x", null, null)))).isEmpty());
        assertTrue(ReplicaService.canalesDe(peticion(Map.of("youtube",
                new Dtos.ConexionDto("youtube", "https://youtube.com/@x", "x", " ")))).isEmpty());

        assertTrue(ReplicaService.canalesDe(peticionConCanales(
                new Dtos.GuardarCanal(null, "tiktok", null, "https://tiktok.com/@x", null, null, null, null),
                new Dtos.GuardarCanal(null, "youtube", null, "https://youtube.com/@x", "x", " ", null, null)
        )).isEmpty());
    }

    @Test
    @DisplayName("Los canales de YouTube se leen de cualquiera de los dos formatos")
    void canalesQueBuscar() {
        assertEquals(List.of(CANAL), ReplicaService.canalesDe(peticion(Map.of("youtube",
                new Dtos.ConexionDto("youtube", "https://youtube.com/@x", "x", CANAL)))));

        assertEquals(List.of(CANAL, CLIPS), ReplicaService.canalesDe(peticionConCanales(
                new Dtos.GuardarCanal(null, "youtube", null, "https://youtube.com/@x", "x", CANAL, null, null),
                new Dtos.GuardarCanal(null, "youtube", "Clips", "https://youtube.com/@y", "y", CLIPS, null, null),
                new Dtos.GuardarCanal(null, "web", null, "https://ejemplo.mx", null, null, null, null))));
    }

    // -------------------------------------------------------------------------
    // Sobre qué fila se guarda la copia
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Un creador que testing no conoce se crea con id propio")
    void nuevo() {
        Creador destino = ReplicaService.elegirDestino(null, null);
        assertNull(destino.getId());
        assertNull(destino.getOrigenId());
    }

    @Test
    @DisplayName("Una copia que ya existe se actualiza, aunque el creador cambie de canal")
    void yaCopiado() {
        Creador copia = creador("Canal Once", UUID.randomUUID());

        assertSame(copia, ReplicaService.elegirDestino(copia, null));
        assertSame(copia, ReplicaService.elegirDestino(copia, copia));
    }

    @Test
    @DisplayName("Un creador dado de alta a mano en testing con ese canal se adopta, no se duplica")
    void adoptado() {
        Creador aMano = creador("Once (prueba)", null);
        assertSame(aMano, ReplicaService.elegirDestino(null, aMano));
    }

    @Test
    @DisplayName("Nunca quedan dos creadores con el mismo canal de YouTube")
    void canalOcupado() {
        Creador copia = creador("Canal Once", UUID.randomUUID());
        Creador otro = creador("Otro", null);
        Creador copiaDeOtro = creador("Copia de otro", UUID.randomUUID());

        var porOtro = assertThrows(ResponseStatusException.class,
                () -> ReplicaService.elegirDestino(copia, otro));
        assertEquals(409, porOtro.getStatusCode().value());
        assertTrue(porOtro.getReason().contains("Otro"));

        var porCopia = assertThrows(ResponseStatusException.class,
                () -> ReplicaService.elegirDestino(null, copiaDeOtro));
        assertEquals(409, porCopia.getStatusCode().value());
    }
}
