package com.voces.backend.directorio;

import com.voces.backend.modelo.Canal;
import com.voces.backend.modelo.Creador;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Productora;
import com.voces.backend.modelo.Usuario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Qué dice el mapa de selección de una cuenta y cuándo cambia su huella. */
class MapaDeSeleccionTest {

    private static final Dtos.SuscripcionesYouTube SIN_YOUTUBE =
            new Dtos.SuscripcionesYouTube(null, List.of(), List.of(), List.of(), List.of());

    private final UUID creadorId = UUID.randomUUID();
    private final UUID medioId = UUID.randomUUID();

    private Creador creador;
    private Productora medio;
    private final List<Canal> canales = new ArrayList<>();
    private Usuario usuario;

    @BeforeEach
    void antes() {
        creador = new Creador();
        creador.setId(creadorId);
        creador.setActivo(true);

        medio = new Productora();
        medio.setId(medioId);
        medio.setActivo(true);

        usuario = new Usuario();
        usuario.setId(UUID.randomUUID());
    }

    private Canal canalDe(UUID dueno, String plataforma) {
        Canal k = new Canal();
        k.setId(UUID.randomUUID());
        k.setCreadorId(dueno);
        k.setPlataforma(plataforma);
        k.setChannelId("UC" + UUID.randomUUID());
        canales.add(k);
        return k;
    }

    private Canal delMedio() {
        Canal k = canalDe(null, Canal.YOUTUBE);
        k.setProductoraId(medioId);
        return k;
    }

    private Catalogo.Vista vista() {
        return new Catalogo.Vista(canales, List.of(medio), List.of(creador));
    }

    private Dtos.Mapa mapa(Dtos.SuscripcionesYouTube youtube) {
        return MapaDeSeleccion.de(usuario, vista(), youtube);
    }

    private static Dtos.SuscripcionesYouTube youtube(List<UUID> suscritos, List<UUID> noSuscritos) {
        return new Dtos.SuscripcionesYouTube(Instant.now(), List.of(), List.of(), suscritos, noSuscritos);
    }

    private static Dtos.CanalDelMapa enElMapa(Dtos.Mapa mapa, Canal canal) {
        return mapa.canales().stream().filter(c -> c.id().equals(canal.getId())).findFirst().orElse(null);
    }

    @Test
    @DisplayName("Cinco canales seguidos aquí y tres en YouTube: cada mapa dice lo suyo")
    void vocesYYouTubeSonDistintos() {
        List<Canal> cinco = Stream.generate(() -> canalDe(creadorId, Canal.YOUTUBE)).limit(5).toList();
        usuario.getFavoritos().add(creadorId);

        Dtos.Mapa mapa = mapa(youtube(
                cinco.subList(0, 3).stream().map(Canal::getId).toList(),
                cinco.subList(3, 5).stream().map(Canal::getId).toList()));

        assertEquals(5, mapa.canales().size());
        assertTrue(mapa.canales().stream().allMatch(Dtos.CanalDelMapa::voces));
        assertEquals(3, mapa.canales().stream().filter(c -> Boolean.TRUE.equals(c.youtube())).count());
        assertEquals(2, mapa.canales().stream().filter(c -> Boolean.FALSE.equals(c.youtube())).count());

        Dtos.EntradaDelMapa entrada = mapa.creadores().get(0);
        assertEquals(creadorId, entrada.id());
        assertTrue(entrada.voces());
    }

    @Test
    @DisplayName("Suscrita en YouTube sin seguirlo aquí: sale, y no como seguido")
    void soloEnYouTube() {
        Canal canal = canalDe(creadorId, Canal.YOUTUBE);

        Dtos.Mapa mapa = mapa(youtube(List.of(canal.getId()), List.of()));

        assertFalse(enElMapa(mapa, canal).voces());
        assertEquals(Boolean.TRUE, enElMapa(mapa, canal).youtube());
        assertEquals(new Dtos.EntradaDelMapa(creadorId, false, true), mapa.creadores().get(0));
    }

    @Test
    @DisplayName("Lo que no se sigue y de lo que YouTube no contestó no sale en el mapa")
    void soloLoQueDiceAlgo() {
        canalDe(creadorId, Canal.YOUTUBE);

        Dtos.Mapa mapa = mapa(SIN_YOUTUBE);

        assertTrue(mapa.creadores().isEmpty());
        assertTrue(mapa.medios().isEmpty());
        assertTrue(mapa.canales().isEmpty());
    }

    @Test
    @DisplayName("Sin respuesta de YouTube, youtube va en null: no es lo mismo que no estar suscrita")
    void sinRespuestaDeYouTube() {
        Canal canal = canalDe(creadorId, Canal.YOUTUBE);
        usuario.getFavoritos().add(creadorId);

        Dtos.Mapa mapa = mapa(SIN_YOUTUBE);

        assertNull(enElMapa(mapa, canal).youtube());
        assertNull(mapa.youtubeVerificadoEn());
    }

    @Test
    @DisplayName("Un canal suelto va marcado; el que llega por su creador, no")
    void sueltoOPorSuCreador() {
        Canal porCreador = canalDe(creadorId, Canal.YOUTUBE);
        Canal suelto = delMedio();
        usuario.getFavoritos().add(creadorId);
        usuario.getCanalesSeguidos().add(suelto.getId());

        Dtos.Mapa mapa = mapa(SIN_YOUTUBE);

        assertFalse(enElMapa(mapa, porCreador).suelto());
        assertTrue(enElMapa(mapa, porCreador).voces());
        assertTrue(enElMapa(mapa, suelto).suelto());
        assertTrue(enElMapa(mapa, suelto).voces());
    }

    @Test
    @DisplayName("Seguir un medio pone en el mapa al medio y a sus canales")
    void medio() {
        Canal canal = delMedio();
        usuario.getProductorasSeguidas().add(medioId);

        Dtos.Mapa mapa = mapa(SIN_YOUTUBE);

        assertEquals(new Dtos.EntradaDelMapa(medioId, true, null), mapa.medios().get(0));
        assertTrue(enElMapa(mapa, canal).voces());
    }

    @Test
    @DisplayName("Las cuentas de otras redes no son canales del mapa")
    void soloYouTube() {
        Canal instagram = canalDe(creadorId, "instagram");
        usuario.getFavoritos().add(creadorId);

        assertNull(enElMapa(mapa(SIN_YOUTUBE), instagram));
    }

    @Test
    @DisplayName("El canal de un creador oculto no cuenta como recibido, pero el seguimiento se conserva")
    void creadorOculto() {
        Canal canal = canalDe(creadorId, Canal.YOUTUBE);
        usuario.getFavoritos().add(creadorId);
        creador.setActivo(false);

        Dtos.Mapa mapa = mapa(SIN_YOUTUBE);

        assertTrue(mapa.creadores().get(0).voces());
        assertNull(enElMapa(mapa, canal));
    }

    @Test
    @DisplayName("El mismo mapa da la misma huella, aunque YouTube se haya vuelto a comprobar")
    void huellaEstable() {
        Canal canal = canalDe(creadorId, Canal.YOUTUBE);
        usuario.getFavoritos().add(creadorId);

        Dtos.Mapa antes = mapa(new Dtos.SuscripcionesYouTube(
                Instant.parse("2026-11-01T10:00:00Z"), List.of(), List.of(), List.of(canal.getId()), List.of()));
        Dtos.Mapa despues = mapa(new Dtos.SuscripcionesYouTube(
                Instant.parse("2026-11-04T10:00:00Z"), List.of(), List.of(), List.of(canal.getId()), List.of()));

        assertEquals(antes.huella(), despues.huella());
        assertEquals(32, antes.huella().length());
    }

    @Test
    @DisplayName("Seguir algo más cambia la huella")
    void huellaAlSeguir() {
        Canal canal = delMedio();
        String antes = mapa(SIN_YOUTUBE).huella();

        usuario.getCanalesSeguidos().add(canal.getId());

        assertNotEquals(antes, mapa(SIN_YOUTUBE).huella());
    }

    @Test
    @DisplayName("Suscribirse o darse de baja en YouTube cambia la huella")
    void huellaConYouTube() {
        Canal canal = canalDe(creadorId, Canal.YOUTUBE);
        usuario.getFavoritos().add(creadorId);

        String sinSaber = mapa(SIN_YOUTUBE).huella();
        String suscrita = mapa(youtube(List.of(canal.getId()), List.of())).huella();
        String deBaja = mapa(youtube(List.of(), List.of(canal.getId()))).huella();

        assertNotEquals(sinSaber, suscrita);
        assertNotEquals(suscrita, deBaja);
    }

    @Test
    @DisplayName("Dos cuentas con la misma selección tienen huellas distintas")
    void huellaPorCuenta() {
        String primera = mapa(SIN_YOUTUBE).huella();

        usuario.setId(UUID.randomUUID());

        assertNotEquals(primera, mapa(SIN_YOUTUBE).huella());
    }
}
