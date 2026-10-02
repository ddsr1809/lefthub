package com.tuempresa.relay.youtube;

import com.tuempresa.relay.youtube.SuscripcionesYouTubeService.Cubeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Las tres decisiones que protegen la comprobación de suscripciones: de quién
 * aceptamos un token, qué permiso tiene que traer y cuántas veces puede
 * preguntar una misma cuenta. Lógica pura, sin red ni base de datos.
 */
class SuscripcionesYouTubeTest {

    private static final String WEB = "389825726990-b6ubrv9f9fv2n2rn2r9dcbho9dnmdv8c.apps.googleusercontent.com";

    // -------------------------------------------------------------------------
    // De qué proyecto es el token
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("El cliente de Android del mismo proyecto se acepta aunque no sea el de tipo web")
    void clienteAndroidDelMismoProyecto() {
        assertTrue(SuscripcionesYouTubeService.mismoProyecto(
                WEB, "389825726990-androidandroidandroid.apps.googleusercontent.com"));
        assertTrue(SuscripcionesYouTubeService.mismoProyecto(WEB, WEB));
    }

    @Test
    @DisplayName("Un token sacado por otra app no pasa, ni aunque su número empiece igual")
    void clienteDeOtroProyecto() {
        assertFalse(SuscripcionesYouTubeService.mismoProyecto(
                WEB, "111111111111-otraapp.apps.googleusercontent.com"));
        assertFalse(SuscripcionesYouTubeService.mismoProyecto(
                WEB, "3898257269901-otraapp.apps.googleusercontent.com"));
    }

    @Test
    @DisplayName("Sin GOOGLE_CLIENT_ID configurado no se acepta ningún token")
    void sinConfigurar() {
        assertFalse(SuscripcionesYouTubeService.mismoProyecto("", WEB));
        assertFalse(SuscripcionesYouTubeService.mismoProyecto(null, WEB));
        assertFalse(SuscripcionesYouTubeService.mismoProyecto(WEB, null));
        assertFalse(SuscripcionesYouTubeService.mismoProyecto("-raro", "-raro"));
    }

    // -------------------------------------------------------------------------
    // Qué permiso trae
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("El permiso de solo lectura basta, venga solo o entre otros")
    void permisoDeLectura() {
        assertTrue(SuscripcionesYouTubeService.permiteLeerSuscripciones(
                "https://www.googleapis.com/auth/youtube.readonly"));
        assertTrue(SuscripcionesYouTubeService.permiteLeerSuscripciones(
                "openid https://www.googleapis.com/auth/userinfo.email "
                        + "https://www.googleapis.com/auth/youtube.readonly"));
    }

    @Test
    @DisplayName("Un token que solo sirve para iniciar sesión no deja leer YouTube")
    void sinPermisoDeYouTube() {
        assertFalse(SuscripcionesYouTubeService.permiteLeerSuscripciones(
                "openid https://www.googleapis.com/auth/userinfo.email"));
        // Parecido no es igual: hay que comparar el permiso entero.
        assertFalse(SuscripcionesYouTubeService.permiteLeerSuscripciones(
                "https://www.googleapis.com/auth/youtube.readonly.falso"));
        assertFalse(SuscripcionesYouTubeService.permiteLeerSuscripciones(""));
        assertFalse(SuscripcionesYouTubeService.permiteLeerSuscripciones(null));
    }

    // -------------------------------------------------------------------------
    // Cuántas veces puede preguntar una cuenta
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Seis comprobaciones seguidas pasan; la séptima espera")
    void rafaga() {
        Instant ahora = Instant.parse("2026-10-01T12:00:00Z");
        Cubeta cubeta = new Cubeta(ahora);

        for (int i = 0; i < Cubeta.CAPACIDAD; i++) {
            assertTrue(cubeta.gastar(ahora), "la comprobación " + (i + 1) + " debería pasar");
        }
        assertFalse(cubeta.gastar(ahora));
    }

    @Test
    @DisplayName("A los dos minutos vuelve a haber saldo para una más, y solo una")
    void recarga() {
        Instant ahora = Instant.parse("2026-10-01T12:00:00Z");
        Cubeta cubeta = new Cubeta(ahora);
        for (int i = 0; i < Cubeta.CAPACIDAD; i++) cubeta.gastar(ahora);

        assertFalse(cubeta.gastar(ahora.plus(Duration.ofSeconds(90))));

        Instant despues = ahora.plus(Cubeta.RECARGA);
        assertTrue(cubeta.gastar(despues));
        assertFalse(cubeta.gastar(despues));
    }

    @Test
    @DisplayName("El saldo no se acumula por encima del tope aunque pasen días")
    void tope() {
        Instant ahora = Instant.parse("2026-10-01T12:00:00Z");
        Cubeta cubeta = new Cubeta(ahora);

        Instant semanaDespues = ahora.plus(Duration.ofDays(7));
        assertTrue(cubeta.llena(semanaDespues));

        for (int i = 0; i < Cubeta.CAPACIDAD; i++) assertTrue(cubeta.gastar(semanaDespues));
        assertFalse(cubeta.gastar(semanaDespues));
        assertFalse(cubeta.llena(semanaDespues));
    }
}
