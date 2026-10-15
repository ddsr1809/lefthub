package com.voces.backend.modelo;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** El nombre de YouTube de un canal va con su channelId: si cambia uno, el otro ya no vale. */
class CanalTest {

    private static Canal conNombre(String channelId) {
        Canal k = new Canal();
        k.setChannelId(channelId);
        k.setTitulo("Claudia Sheinbaum");
        k.setTituloEn(Instant.now());
        return k;
    }

    @Test
    void cambiarDeCanalBorraElNombreDelAnterior() {
        Canal k = conNombre("UCuno");
        k.setChannelId("UCdos");
        assertNull(k.getTitulo());
        assertNull(k.getTituloEn(), "sin fecha, la próxima revisión le pregunta a YouTube");
    }

    @Test
    void guardarElMismoCanalNoLoBorra() {
        Canal k = conNombre("UCuno");
        k.setChannelId("UCuno");
        assertEquals("Claudia Sheinbaum", k.getTitulo());
        assertNotNull(k.getTituloEn());
    }

    @Test
    void quitarleElCanalTambienLoBorra() {
        Canal k = conNombre("UCuno");
        k.setChannelId(null);
        assertNull(k.getTitulo());
    }
}
