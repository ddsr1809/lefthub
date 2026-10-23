package com.voces.backend.directorio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Qué ids de video se aceptan al apuntar los vistos de una cuenta. */
class VideosVistosTest {

    @Test
    @DisplayName("Un id de YouTube se acepta; lo que no tiene su forma, no")
    void ids() {
        assertTrue(VideosVistos.idValido("dQw4w9WgXcQ"));
        assertTrue(VideosVistos.idValido("a-b_C1"));
        assertFalse(VideosVistos.idValido(null));
        assertFalse(VideosVistos.idValido(""));
        assertFalse(VideosVistos.idValido("../etc"));
        assertFalse(VideosVistos.idValido("uno dos"));
        assertFalse(VideosVistos.idValido("x".repeat(65)));
    }

    @Test
    @DisplayName("Una lista se limpia: sin repetidos, sin ids raros y en su orden")
    void limpiar() {
        assertEquals(List.of("a1", "b2"), VideosVistos.limpiar(Arrays.asList("a1", "a1", "mal id", null, "b2")));
        assertEquals(List.of(), VideosVistos.limpiar(null));
    }

    @Test
    @DisplayName("Nunca más del tope de una vez")
    void tope() {
        List<String> muchos = new ArrayList<>();
        for (int i = 0; i < VideosVistos.TOPE + 50; i++) muchos.add("v" + i);
        List<String> limpios = VideosVistos.limpiar(muchos);
        assertEquals(VideosVistos.TOPE, limpios.size());
        assertEquals("v0", limpios.get(0));
    }
}
