package com.tuempresa.relay.directorio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Del título que cada red pone en la tarjeta de un perfil al nombre de la
 * persona. Equivocarse aquí es dar de alta a alguien que se llama "Instagram".
 */
class PerfilesTest {

    @Test
    @DisplayName("Cada red pega algo distinto detrás del nombre, y se quita")
    void colas() {
        assertEquals("Rosa Luna", PerfilesService.nombreDe("Rosa Luna (@rosaluna) / X", "rosaluna"));
        assertEquals("Rosa Luna", PerfilesService.nombreDe("Rosa Luna (@rosaluna) on X", "rosaluna"));
        assertEquals("Rosa Luna", PerfilesService.nombreDe("Rosa Luna (@rosaluna) | Twitter", "rosaluna"));
        assertEquals("Rosa Luna", PerfilesService.nombreDe("Rosa Luna (@rosa.luna) • Instagram photos and videos", "rosa.luna"));
        assertEquals("Rosa Luna", PerfilesService.nombreDe("Rosa Luna (@rosaluna) | TikTok", "rosaluna"));
        assertEquals("Rosa Luna", PerfilesService.nombreDe("Rosa Luna | Facebook", "rosaluna"));
        assertEquals("Rosa Luna", PerfilesService.nombreDe("Rosa Luna (@rosaluna) • Threads, Say more", "rosaluna"));
        assertEquals("Rosa Luna", PerfilesService.nombreDe("Rosa Luna - Twitch", "rosaluna"));
        assertEquals("Rosa Luna", PerfilesService.nombreDe("Rosa Luna | Spotify", null));
    }

    @Test
    @DisplayName("Un nombre limpio se queda como está, con sus acentos y sus signos")
    void sinTocar() {
        assertEquals("Andrés Manuel", PerfilesService.nombreDe("Andrés Manuel", "lopezobrador_"));
        assertEquals("Gobierno de México", PerfilesService.nombreDe("  Gobierno de México  ", "GobiernoMX"));
        assertEquals("El Chapucero - Hoy", PerfilesService.nombreDe("El Chapucero - Hoy", "elchapucero"));
        assertEquals("Canal 14", PerfilesService.nombreDe("Canal 14 (@canalcatorcemx) / X", "canalcatorcemx"));
        // Palabras que contienen "error" o "log in" por dentro no son un error ni una pantalla de entrada.
        assertEquals("Cine de Terror", PerfilesService.nombreDe("Cine de Terror (@cineterror) / X", "cineterror"));
        assertEquals("Vlog in México", PerfilesService.nombreDe("Vlog in México | TikTok", "vlogmx"));
        assertEquals("Mujeres en X", PerfilesService.nombreDe("Mujeres en X", "mujeres"));
    }

    @Test
    @DisplayName("La portada de la red, su pantalla de entrada o un error no son el nombre de nadie")
    void deNadie() {
        assertNull(PerfilesService.nombreDe("X", "rosaluna"));
        assertNull(PerfilesService.nombreDe("Instagram", "rosaluna"));
        assertNull(PerfilesService.nombreDe("Profile / X", "rosaluna"));
        assertNull(PerfilesService.nombreDe("Log in • Instagram", "rosaluna"));
        assertNull(PerfilesService.nombreDe("Iniciar sesión • Instagram", "rosaluna"));
        assertNull(PerfilesService.nombreDe("Telegram: Contact @rosaluna", "rosaluna"));
        assertNull(PerfilesService.nombreDe("Page not found • Instagram", "rosaluna"));
        assertNull(PerfilesService.nombreDe("Just a moment...", "rosaluna"));
        assertNull(PerfilesService.nombreDe("TikTok - Make Your Day", "rosaluna"));
        assertNull(PerfilesService.nombreDe("", "rosaluna"));
        assertNull(PerfilesService.nombreDe(null, "rosaluna"));
    }

    @Test
    @DisplayName("Si lo único que queda es el usuario, no se da por nombre")
    void soloUsuario() {
        assertNull(PerfilesService.nombreDe("@rosaluna", "rosaluna"));
        assertNull(PerfilesService.nombreDe("rosaluna (@rosaluna) / X", "RosaLuna"));
    }

    @Test
    @DisplayName("Un nombre larguísimo se recorta a lo que cabe en la ficha")
    void recorte() {
        String largo = "A".repeat(200);
        assertEquals(60, PerfilesService.nombreDe(largo, null).length());
    }

    @Test
    @DisplayName("La descripción vale si la escribió la persona; las cifras y frases de la red, no")
    void descripcion() {
        assertEquals("Periodista. Opiniones propias.", PerfilesService.descripcionDe("  Periodista. Opiniones propias. "));
        assertNull(PerfilesService.descripcionDe("1.2M Followers, 300 Following, 45 Posts - See Instagram photos and videos from Rosa Luna (@rosa.luna)"));
        assertNull(PerfilesService.descripcionDe("@rosaluna 2.5M Followers, 10 Following, 30M Likes - Watch the latest video from Rosa Luna."));
        assertNull(PerfilesService.descripcionDe("125 mil seguidores, 40 seguidos"));
        assertNull(PerfilesService.descripcionDe("Log in to X to see the latest."));
        assertEquals("Mi blog in english y en español", PerfilesService.descripcionDe("Mi blog in english y en español"));
        assertNull(PerfilesService.descripcionDe(""));
        assertNull(PerfilesService.descripcionDe(null));
        assertEquals(600, PerfilesService.descripcionDe("b".repeat(900)).length());
    }
}
