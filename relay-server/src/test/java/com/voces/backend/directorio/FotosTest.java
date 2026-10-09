package com.voces.backend.directorio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Del enlace de una cuenta, tal como está en el directorio, a la cuenta que
 * se le pide al servicio de fotos. Si esto falla, la foto que se trae es la
 * de otra persona o no se trae ninguna.
 */
class FotosTest {

    @Test
    @DisplayName("X, Instagram, TikTok, Threads y Twitch: el usuario es lo primero del enlace")
    void usuarioDirecto() {
        assertEquals("x/rosaluna", FotosService.cuentaDe("x", "https://x.com/rosaluna"));
        assertEquals("x/rosaluna", FotosService.cuentaDe("x", "https://twitter.com/rosaluna/"));
        assertEquals("instagram/rosa.luna_", FotosService.cuentaDe("instagram", "https://www.instagram.com/rosa.luna_/?hl=es"));
        assertEquals("tiktok/rosaluna", FotosService.cuentaDe("tiktok", "https://www.tiktok.com/@rosaluna"));
        assertEquals("threads/rosaluna", FotosService.cuentaDe("threads", "https://www.threads.net/@rosaluna"));
        assertEquals("twitch/rosaluna", FotosService.cuentaDe("twitch", "https://www.twitch.tv/rosaluna"));
    }

    @Test
    @DisplayName("Telegram y Patreon: con o sin el tramo de más que a veces llevan")
    void conTramoDeMas() {
        assertEquals("telegram/rosaluna", FotosService.cuentaDe("telegram", "https://t.me/rosaluna"));
        assertEquals("telegram/rosaluna", FotosService.cuentaDe("telegram", "https://t.me/s/rosaluna"));
        assertEquals("patreon/rosaluna", FotosService.cuentaDe("patreon", "https://www.patreon.com/rosaluna"));
        assertEquals("patreon/rosaluna", FotosService.cuentaDe("patreon", "https://www.patreon.com/c/rosaluna/posts"));
    }

    @Test
    @DisplayName("Facebook: por usuario, por número de perfil o por la dirección larga")
    void facebook() {
        assertEquals("facebook/rosaluna", FotosService.cuentaDe("facebook", "https://www.facebook.com/rosaluna"));
        assertEquals("facebook/100012345", FotosService.cuentaDe("facebook", "https://www.facebook.com/profile.php?id=100012345&sk=about"));
        assertEquals("facebook/100012345", FotosService.cuentaDe("facebook", "https://www.facebook.com/people/Rosa-Luna/100012345/"));
        assertNull(FotosService.cuentaDe("facebook", "https://www.facebook.com/profile.php"));
    }

    @Test
    @DisplayName("Spotify: artista, programa o usuario, con su tipo")
    void spotify() {
        assertEquals("spotify/artist:4Z8W4fKeB5YxbusRsdQVPb", FotosService.cuentaDe("spotify", "https://open.spotify.com/artist/4Z8W4fKeB5YxbusRsdQVPb?si=1"));
        assertEquals("spotify/show:abc123", FotosService.cuentaDe("spotify", "https://open.spotify.com/intl-es/show/abc123"));
        assertNull(FotosService.cuentaDe("spotify", "https://open.spotify.com/"));
    }

    @Test
    @DisplayName("De una página web, de una red desconocida o de un enlace que no es un perfil no se saca nada")
    void sinCuenta() {
        assertNull(FotosService.cuentaDe("web", "https://rosaluna.mx"));
        assertNull(FotosService.cuentaDe("myspace", "https://myspace.com/rosaluna"));
        assertNull(FotosService.cuentaDe("x", "https://x.com/"));
        assertNull(FotosService.cuentaDe("x", "rosaluna"));
        assertNull(FotosService.cuentaDe("x", null));
        assertNull(FotosService.cuentaDe("x", "https://x.com/rosa luna"));
    }

    @Test
    @DisplayName("Un usuario con caracteres raros no llega al servicio de fotos")
    void sinColarNada() {
        assertNull(FotosService.cuentaDe("x", "https://x.com/..%2F..%2Fadmin"));
        assertNull(FotosService.cuentaDe("x", "https://x.com/.."));
        assertNull(FotosService.cuentaDe("tiktok", "https://www.tiktok.com/@"));
        assertEquals("instagram/a", FotosService.cuentaDe("instagram", "https://instagram.com/a?b=/c"));
    }
}
