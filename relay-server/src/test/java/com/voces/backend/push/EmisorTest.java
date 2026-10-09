package com.voces.backend.push;

import com.voces.backend.modelo.Creador;
import com.voces.backend.modelo.Productora;
import com.voces.backend.push.PushService.Destino;
import com.voces.backend.push.PushService.Emisor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A quién se dirige cada aviso. Lo que importa: las versiones de la app que
 * solo conocen creadores siguen recibiendo lo mismo, y nadie recibe dos veces
 * el mismo video por seguir a varios de los que lo publican.
 */
class EmisorTest {

    private static Creador creador(String nombre) {
        Creador c = new Creador();
        c.setId(UUID.randomUUID());
        c.setNombre(nombre);
        return c;
    }

    private static Productora productora(String nombre) {
        Productora p = new Productora();
        p.setId(UUID.randomUUID());
        p.setNombre(nombre);
        return p;
    }

    private static String o(String... topics) {
        return String.join(" || ", List.of(topics).stream().map(t -> "'" + t + "' in topics").toList());
    }

    @Test
    @DisplayName("Un canal solo de su creador avisa a su topic de siempre, sin condición")
    void soloCreador() {
        Creador juan = creador("Juan Pérez");
        Emisor emisor = Emisor.de(juan);

        assertEquals("Juan Pérez", emisor.nombre());
        assertEquals("creator_" + juan.getId(), emisor.topic());
        assertEquals(List.of(new Destino("creator_" + juan.getId(), null)), emisor.destinos());
        assertNull(emisor.productoraId());
    }

    @Test
    @DisplayName("El canal propio de una productora firma con su nombre y llega también a quien la sigue como creador")
    void soloProductora() {
        Productora estudio = productora("Estudio X");
        Emisor emisor = Emisor.de(null, estudio);

        assertEquals("Estudio X", emisor.nombre());
        assertEquals("productora_" + estudio.getId(), emisor.topic());
        assertNull(emisor.creadorId());

        // El segundo topic es el de las apps que no conocen las productoras:
        // la ven en el directorio como un creador más y se suscriben a ese.
        assertEquals(List.of(new Destino(null,
                o("productora_" + estudio.getId(), "creator_" + estudio.getId()))), emisor.destinos());
    }

    @Test
    @DisplayName("Un canal de creador y productora manda un solo aviso con las dos audiencias")
    void losDos() {
        Creador juan = creador("Juan Pérez");
        Productora estudio = productora("Estudio X");
        Emisor emisor = Emisor.de(juan, estudio);

        assertEquals("Juan Pérez", emisor.nombre(), "firma el creador");
        assertEquals("creator_" + juan.getId(), emisor.topic());
        assertEquals(List.of(new Destino(null, o("creator_" + juan.getId(),
                "productora_" + estudio.getId(), "creator_" + estudio.getId()))), emisor.destinos());
    }

    @Test
    @DisplayName("El canal de una productora que aparece con dos creadores: firma ella y les llega a los seguidores de los tres")
    void compartido() {
        Productora gobierno = productora("Gobierno de México");
        UUID claudia = UUID.randomUUID();
        UUID andres = UUID.randomUUID();

        Emisor emisor = Emisor.de(null, gobierno, List.of(claudia, andres));

        assertEquals("Gobierno de México", emisor.nombre());
        assertEquals(1, emisor.destinos().size(), "caben en un solo mensaje");
        assertEquals(o("productora_" + gobierno.getId(), "creator_" + gobierno.getId(),
                "creator_" + claudia, "creator_" + andres), emisor.destinos().get(0).condicion());
    }

    @Test
    @DisplayName("El dueño no se repite aunque también venga entre los demás creadores")
    void sinRepetir() {
        Creador juan = creador("Juan Pérez");
        Emisor emisor = Emisor.de(juan, null, List.of(juan.getId()));

        assertEquals(List.of("creator_" + juan.getId()), emisor.topics());
        assertNull(emisor.destinos().get(0).condicion());
    }

    @Test
    @DisplayName("Con más de cinco topics el aviso se parte, porque FCM no admite más en una condición")
    void partido() {
        Creador juan = creador("Juan Pérez");
        List<UUID> otros = new ArrayList<>();
        for (int i = 0; i < 6; i++) otros.add(UUID.randomUUID());

        Emisor emisor = Emisor.de(juan, productora("Estudio X"), otros);

        // 1 del creador + 2 de la productora + 6 de los demás = 9 topics.
        assertEquals(9, emisor.topics().size());
        List<Destino> destinos = emisor.destinos();
        assertEquals(2, destinos.size());
        assertEquals(5, destinos.get(0).condicion().split("\\|\\|").length);
        assertEquals(4, destinos.get(1).condicion().split("\\|\\|").length);
    }

    @Test
    @DisplayName("Si al partir queda un topic suelto, va como topic y no como condición")
    void restoDeUno() {
        List<UUID> otros = new ArrayList<>();
        for (int i = 0; i < 5; i++) otros.add(UUID.randomUUID());

        List<Destino> destinos = Emisor.de(creador("Juan Pérez"), null, otros).destinos();

        assertEquals(2, destinos.size());
        assertEquals("creator_" + otros.get(4), destinos.get(1).topic());
        assertNull(destinos.get(1).condicion());
    }

    @Test
    @DisplayName("Un video corto va por topics aparte: los de siempre no lo reciben")
    void cortos() {
        Creador juan = creador("Juan Pérez");
        Emisor emisor = Emisor.de(juan);

        assertEquals(List.of(new Destino("creator_" + juan.getId() + "_cortos", null)),
                emisor.destinos(true));
        assertEquals(emisor.destinos(), emisor.destinos(false), "lo demás no cambia");
        assertTrue(emisor.topics().stream().noneMatch(t -> t.endsWith("_cortos")));
    }

    @Test
    @DisplayName("El corto de un canal compartido llega a las mismas audiencias, menos a las apps que no conocen las productoras")
    void cortosCompartidos() {
        Productora gobierno = productora("Gobierno de México");
        UUID claudia = UUID.randomUUID();

        List<Destino> destinos = Emisor.de(null, gobierno, List.of(claudia)).destinos(true);

        assertEquals(List.of(new Destino(null, o("productora_" + gobierno.getId() + "_cortos",
                "creator_" + claudia + "_cortos"))), destinos);
    }

    @Test
    @DisplayName("Sin creador ni productora no hay aviso que mandar")
    void nadie() {
        assertThrows(IllegalArgumentException.class, () -> Emisor.de(null, null));
    }
}
