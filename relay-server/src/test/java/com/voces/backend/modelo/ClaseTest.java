package com.voces.backend.modelo;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/** El tipo de cuenta: general, televisión pública o política y gobierno. */
class ClaseTest {

    @Test
    void soloLasTresValen() {
        assertEquals("politica", Clase.validar(" politica "));
        assertNull(Clase.validar(null), "sin clase, no se toca");
        assertThrows(ResponseStatusException.class, () -> Clase.validar("religion"));
        assertThrows(ResponseStatusException.class, () -> Clase.validar(""));
    }

    @Test
    void unCanalSinClaseTieneLaDeSuDueno() {
        Creador ana = new Creador();
        ana.setClase(Clase.POLITICA);
        Productora medio = new Productora();
        medio.setClase(Clase.TELEVISION_PUBLICA);
        Canal k = new Canal();

        assertEquals(Clase.POLITICA, Clase.deCanal(k, ana, medio), "con creador, la del creador");
        assertEquals(Clase.TELEVISION_PUBLICA, Clase.deCanal(k, null, medio), "sin creador, la del medio");
        assertEquals(Clase.GENERAL, Clase.deCanal(k, null, null));

        k.setClase(Clase.GENERAL);
        assertEquals(Clase.GENERAL, Clase.deCanal(k, ana, medio), "la suya manda");
    }

    @Test
    void enUnCanalVacioEsLaDeSuDueno() {
        Canal k = new Canal();
        Clase.aplicar(k, "politica");
        assertEquals("politica", k.getClase());
        Clase.aplicar(k, null);
        assertEquals("politica", k.getClase(), "null no toca");
        Clase.aplicar(k, "");
        assertNull(k.getClase(), "vacía: la de su dueño");
        assertThrows(ResponseStatusException.class, () -> Clase.aplicar(k, "x"));
    }
}
