package com.tuempresa.relay.push;

import com.tuempresa.relay.modelo.Creador;
import com.tuempresa.relay.modelo.Productora;
import com.tuempresa.relay.push.PushService.Emisor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A quién se dirige cada aviso. Lo que importa: las versiones de la app que
 * solo conocen creadores siguen recibiendo lo mismo, y nadie recibe dos veces
 * el mismo video por seguir a un creador y a su productora.
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

    @Test
    @DisplayName("Un canal solo de su creador avisa a su topic de siempre")
    void soloCreador() {
        Creador juan = creador("Juan Pérez");
        Emisor emisor = Emisor.de(juan);

        assertEquals("Juan Pérez", emisor.nombre());
        assertEquals("creator_" + juan.getId(), emisor.topic());
        assertNull(emisor.condicion());
        assertNull(emisor.productoraId());
    }

    @Test
    @DisplayName("El canal propio de una productora avisa en su nombre y a su topic")
    void soloProductora() {
        Productora estudio = productora("Estudio X");
        Emisor emisor = Emisor.de(null, estudio);

        assertEquals("Estudio X", emisor.nombre());
        assertEquals("productora_" + estudio.getId(), emisor.topic());
        assertNull(emisor.condicion());
        assertNull(emisor.creadorId());
    }

    @Test
    @DisplayName("Un canal de creador y productora manda un solo aviso con las dos audiencias")
    void losDos() {
        Creador juan = creador("Juan Pérez");
        Productora estudio = productora("Estudio X");
        Emisor emisor = Emisor.de(juan, estudio);

        assertEquals("Juan Pérez", emisor.nombre(), "firma el creador");
        assertEquals("creator_" + juan.getId(), emisor.topic());
        assertEquals("'creator_" + juan.getId() + "' in topics || 'productora_"
                + estudio.getId() + "' in topics", emisor.condicion());
    }

    @Test
    @DisplayName("Sin creador ni productora no hay aviso que mandar")
    void nadie() {
        assertThrows(IllegalArgumentException.class, () -> Emisor.de(null, null));
    }
}
