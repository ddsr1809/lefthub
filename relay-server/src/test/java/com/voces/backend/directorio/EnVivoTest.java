package com.voces.backend.directorio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Las reglas de la pestaña «En vivo». */
class EnVivoTest {

    private static final UUID ANA = UUID.randomUUID();
    private static final UUID CANAL_DE_ANA = UUID.randomUUID();
    private static final UUID CANAL_DE_UN_MEDIO = UUID.randomUUID();
    private static final UUID CANAL_SUSCRITO = UUID.randomUUID();

    private record Directo(String grupo, String nombre, String videoId) {}

    private static String grupo(UUID creador, UUID canal) {
        return EnVivo.grupoDe(creador, canal, Set.of(ANA), Set.of(CANAL_DE_UN_MEDIO), Set.of(CANAL_SUSCRITO));
    }

    private static List<String> videos(List<Directo> directos) {
        return EnVivo.ordenar(directos, Directo::grupo, Directo::nombre, Directo::videoId)
                .stream().map(Directo::videoId).toList();
    }

    @Test
    @DisplayName("Va primero quien se sigue en la app, por creador o por canal")
    void seguidos() {
        assertEquals(EnVivo.SEGUIDOS, grupo(ANA, CANAL_DE_ANA));
        assertEquals(EnVivo.SEGUIDOS, grupo(UUID.randomUUID(), CANAL_DE_UN_MEDIO));
        // El canal propio de un medio no tiene creador.
        assertEquals(EnVivo.SEGUIDOS, grupo(null, CANAL_DE_UN_MEDIO));
    }

    @Test
    @DisplayName("Quien se sigue en la app y además en YouTube cuenta como seguido")
    void lasDosCosas() {
        assertEquals(EnVivo.SEGUIDOS, grupo(ANA, CANAL_SUSCRITO));
    }

    @Test
    @DisplayName("Después va el canal al que está suscrita en YouTube")
    void suscritos() {
        assertEquals(EnVivo.SUSCRITOS, grupo(UUID.randomUUID(), CANAL_SUSCRITO));
    }

    @Test
    @DisplayName("Sin nada de eso va con los demás, también si perdió su canal")
    void otros() {
        assertEquals(EnVivo.OTROS, grupo(UUID.randomUUID(), UUID.randomUUID()));
        assertEquals(EnVivo.OTROS, grupo(UUID.randomUUID(), null));
    }

    @Test
    @DisplayName("El orden es seguidos, suscritos y los demás, aunque el nombre diga otra cosa")
    void ordenDeLosGrupos() {
        assertEquals(List.of("v3", "v2", "v1"), videos(List.of(
                new Directo(EnVivo.OTROS, "Ana", "v1"),
                new Directo(EnVivo.SUSCRITOS, "Beto", "v2"),
                new Directo(EnVivo.SEGUIDOS, "Zoila", "v3"))));
    }

    @Test
    @DisplayName("Dentro de un grupo van por nombre, sin importar acentos ni mayúsculas")
    void porNombre() {
        assertEquals(List.of("v3", "v2", "v1"), videos(List.of(
                new Directo(EnVivo.OTROS, "beto", "v1"),
                new Directo(EnVivo.OTROS, "Ángel", "v2"),
                new Directo(EnVivo.OTROS, "abel", "v3"))));
    }

    @Test
    @DisplayName("Dos directos del mismo nombre no se turnan el lugar, y sin nombre no truena")
    void desempate() {
        assertEquals(List.of("a", "b"), videos(List.of(
                new Directo(EnVivo.OTROS, "Ana", "b"),
                new Directo(EnVivo.OTROS, "Ana", "a"))));
        assertEquals(List.of("v2", "v1"), videos(List.of(
                new Directo(EnVivo.OTROS, "Ana", "v1"),
                new Directo(EnVivo.OTROS, null, "v2"))));
    }
}
