package com.voces.backend.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Cuánto dura una sesión: la del panel, lo que se eligió al entrar; las demás, la de siempre. */
class DuracionDeSesionTest {

    private static final long DE_SIEMPRE = 30;

    @Test
    @DisplayName("Un administrador desde el panel: 7 días o un año")
    void administrador() {
        assertEquals(7, ServicioJwt.diasDeSesion(true, 7, DE_SIEMPRE));
        assertEquals(365, ServicioJwt.diasDeSesion(true, 365, DE_SIEMPRE));
    }

    @Test
    @DisplayName("Sin rol de administrador no se puede pedir otra duración")
    void sinRol() {
        assertEquals(DE_SIEMPRE, ServicioJwt.diasDeSesion(false, 365, DE_SIEMPRE));
        assertEquals(DE_SIEMPRE, ServicioJwt.diasDeSesion(false, 7, DE_SIEMPRE));
    }

    @Test
    @DisplayName("Sin pedir nada, o pidiendo algo fuera de 1..365, la de siempre")
    void fueraDeRango() {
        assertEquals(DE_SIEMPRE, ServicioJwt.diasDeSesion(true, null, DE_SIEMPRE));
        assertEquals(DE_SIEMPRE, ServicioJwt.diasDeSesion(true, 0, DE_SIEMPRE));
        assertEquals(DE_SIEMPRE, ServicioJwt.diasDeSesion(true, -3, DE_SIEMPRE));
        assertEquals(DE_SIEMPRE, ServicioJwt.diasDeSesion(true, 366, DE_SIEMPRE));
        assertEquals(DE_SIEMPRE, ServicioJwt.diasDeSesion(true, Integer.MAX_VALUE, DE_SIEMPRE));
    }
}
