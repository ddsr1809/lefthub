package com.voces.backend.youtube;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Video normal o video corto. Equivocarse hacia un lado esconde un video que
 * la gente espera; hacia el otro, cuela un Short donde no se quieren.
 */
class TipoDeVideoTest {

    @Test
    @DisplayName("youtube.com/shorts/ID responde 200: es un Short")
    void esShort() {
        assertEquals(Boolean.TRUE, YouTubeClient.veredicto(200, null));
    }

    @Test
    @DisplayName("Redirige a la página normal del video: no es un Short")
    void noEsShort() {
        assertEquals(Boolean.FALSE, YouTubeClient.veredicto(303, "https://www.youtube.com/watch?v=abc"));
        assertEquals(Boolean.FALSE, YouTubeClient.veredicto(302, "/watch?v=abc"));
    }

    @Test
    @DisplayName("Cualquier otra respuesta no aclara nada")
    void sinAclarar() {
        assertNull(YouTubeClient.veredicto(404, null));
        assertNull(YouTubeClient.veredicto(429, null));
        assertNull(YouTubeClient.veredicto(500, null));
        assertNull(YouTubeClient.veredicto(302, null));
        // La pantalla de consentimiento de cookies: redirige, pero no al video.
        assertNull(YouTubeClient.veredicto(302,
                "https://consent.youtube.com/m?continue=https%3A%2F%2Fwww.youtube.com%2Fshorts%2Fabc"));
    }

    @Test
    @DisplayName("Un video de más de tres minutos es normal, sin preguntar a nadie")
    void largo() {
        assertEquals("video", YouTubeClient.tipoDe("video", "no", null));
        assertEquals("video", YouTubeClient.tipoDe("video", "no", true));
    }

    @Test
    @DisplayName("Un video breve que YouTube dice que no es Short es un video normal")
    void breveNormal() {
        assertEquals("video", YouTubeClient.tipoDe("short", "no", false));
    }

    @Test
    @DisplayName("Un video breve que YouTube confirma como Short, o del que no contesta, es corto")
    void breveCorto() {
        assertEquals("short", YouTubeClient.tipoDe("short", "no", true));
        assertEquals("short", YouTubeClient.tipoDe("short", "no", null));
    }

    @Test
    @DisplayName("Un directo o un estreno nunca es un corto, dure lo que dure")
    void directo() {
        assertEquals("video", YouTubeClient.tipoDe("short", "en_vivo", true));
        assertEquals("video", YouTubeClient.tipoDe("short", "programado", null));
        assertEquals("video", YouTubeClient.tipoDe("short", "terminado", true));
    }
}
