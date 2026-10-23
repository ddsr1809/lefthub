package com.voces.backend.seguridad;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * La firma con la llave de la app: que la de la app pase una vez, y que nada
 * más pase. El vector fijo del final es el mismo que comprueba la app
 * (FirmaDeAppTest.kt): si alguno cambia el texto firmado, fallan los dos.
 */
class FirmaDeAppTest {

    private static final Instant AHORA = Instant.parse("2026-10-23T09:00:00Z");
    private static final String HORA = String.valueOf(AHORA.getEpochSecond());
    private static final String NONCE = "q8Zl3mW0n1p2r3s4t5u6v7";

    static String llave(int semilla) {
        byte[] b = new byte[32];
        for (int i = 0; i < b.length; i++) b[i] = (byte) (semilla + i);
        return Base64.getEncoder().encodeToString(b);
    }

    private static final FirmaDeApp FIRMA = new FirmaDeApp("1:" + llave(0));

    private static String firmada(FirmaDeApp f, String metodo, String ruta, String hora, String nonce) {
        return f.firmar((short) 1, metodo, ruta, hora, nonce);
    }

    @Test
    @DisplayName("Una petición firmada con la llave pasa, y solo una vez")
    void buena() {
        FirmaDeApp f = new FirmaDeApp("1:" + llave(0));
        String firma = firmada(f, "GET", "/api/creadores", HORA, NONCE);

        assertEquals(FirmaDeApp.Resultado.OK, f.verificar("1", HORA, NONCE, firma, "GET", "/api/creadores", AHORA));
        assertEquals(FirmaDeApp.Resultado.REPETIDA, f.verificar("1", HORA, NONCE, firma, "GET", "/api/creadores", AHORA),
                "una petición copiada no sirve otra vez");
    }

    @Test
    @DisplayName("Sin ninguna cabecera es FALTA; con algunas, o cambiadas, es MALA")
    void faltaYMala() {
        String firma = firmada(FIRMA, "GET", "/api/perfil", HORA, NONCE);
        assertEquals(FirmaDeApp.Resultado.FALTA, FIRMA.verificar(null, null, null, null, "GET", "/api/perfil", AHORA));
        assertEquals(FirmaDeApp.Resultado.MALA, FIRMA.verificar("1", HORA, null, firma, "GET", "/api/perfil", AHORA));
        assertEquals(FirmaDeApp.Resultado.MALA, FIRMA.verificar("1", HORA, NONCE, firma, "POST", "/api/perfil", AHORA),
                "firmada para otro método");
        assertEquals(FirmaDeApp.Resultado.MALA, FIRMA.verificar("1", HORA, NONCE, firma, "GET", "/api/cuenta", AHORA),
                "firmada para otra ruta");
        assertEquals(FirmaDeApp.Resultado.MALA, FIRMA.verificar("2", HORA, NONCE, firma, "GET", "/api/perfil", AHORA),
                "llave que el servidor no tiene");
        assertEquals(FirmaDeApp.Resultado.MALA, FIRMA.verificar("1", HORA, "corto", firma, "GET", "/api/perfil", AHORA));
        assertEquals(FirmaDeApp.Resultado.MALA, FIRMA.verificar("x", HORA, NONCE, firma, "GET", "/api/perfil", AHORA));
        assertEquals(FirmaDeApp.Resultado.MALA, FIRMA.verificar("1", HORA, NONCE, "%%%", "GET", "/api/perfil", AHORA));
    }

    @Test
    @DisplayName("Otra llave no sirve")
    void otraLlave() {
        FirmaDeApp ajena = new FirmaDeApp("1:" + llave(50));
        String firma = firmada(ajena, "GET", "/api/creadores", HORA, NONCE);
        assertEquals(FirmaDeApp.Resultado.MALA, FIRMA.verificar("1", HORA, NONCE, firma, "GET", "/api/creadores", AHORA));
    }

    @Test
    @DisplayName("Una firma de hace más de cinco minutos (o del futuro) no pasa")
    void hora() {
        FirmaDeApp f = new FirmaDeApp("1:" + llave(0));
        String vieja = String.valueOf(AHORA.minusSeconds(301).getEpochSecond());
        String futura = String.valueOf(AHORA.plusSeconds(301).getEpochSecond());
        String justo = String.valueOf(AHORA.minusSeconds(299).getEpochSecond());

        assertEquals(FirmaDeApp.Resultado.HORA,
                f.verificar("1", vieja, NONCE, firmada(f, "GET", "/api/x", vieja, NONCE), "GET", "/api/x", AHORA));
        assertEquals(FirmaDeApp.Resultado.HORA,
                f.verificar("1", futura, NONCE + "a", firmada(f, "GET", "/api/x", futura, NONCE + "a"), "GET", "/api/x", AHORA));
        assertEquals(FirmaDeApp.Resultado.OK,
                f.verificar("1", justo, NONCE + "b", firmada(f, "GET", "/api/x", justo, NONCE + "b"), "GET", "/api/x", AHORA));
    }

    @Test
    @DisplayName("Con dos llaves, sirven las dos (para cambiar de llave sin dejar a nadie fuera)")
    void dosLlaves() {
        FirmaDeApp f = new FirmaDeApp("1:" + llave(0) + ",2:" + llave(9));
        FirmaDeApp soloDos = new FirmaDeApp("2:" + llave(9));
        String firma = soloDos.firmar((short) 2, "GET", "/api/x", HORA, NONCE);
        assertEquals(FirmaDeApp.Resultado.OK, f.verificar("2", HORA, NONCE, firma, "GET", "/api/x", AHORA));
    }

    @Test
    @DisplayName("APP_LLAVES vacía = sin firma; mal escrita, el servidor no arranca")
    void configuracion() {
        assertFalse(new FirmaDeApp("").configurada());
        assertTrue(FIRMA.configurada());
        assertThrows(IllegalStateException.class, () -> new FirmaDeApp("1:corta"));
        assertThrows(IllegalStateException.class, () -> new FirmaDeApp(llave(0)));
        assertThrows(IllegalStateException.class, () -> new FirmaDeApp("0:" + llave(0)));
    }

    @Test
    @DisplayName("Vector fijo: la misma firma que calcula la app Android")
    void vectorCompartido() {
        // Llave: 32 bytes 0x00..0x1f. Texto: lefthub-app-v1\nGET\n/api/creadores\n1761210000\nAAAAAAAAAAAAAAAAAAAAAA
        assertEquals("lefthub-app-v1\nGET\n/api/creadores\n1761210000\nAAAAAAAAAAAAAAAAAAAAAA",
                FirmaDeApp.texto("get", "/api/creadores", "1761210000", "AAAAAAAAAAAAAAAAAAAAAA"));
        assertEquals(VECTOR_FIRMA,
                FIRMA.firmar((short) 1, "GET", "/api/creadores", "1761210000", "AAAAAAAAAAAAAAAAAAAAAA"));
    }

    /** HMAC-SHA256 del vector, en base64url sin relleno. Lo calcula también FirmaDeAppTest.kt. */
    static final String VECTOR_FIRMA = "lYz8SswJkbe1MBkGiOxCEXGyASiO0yjinVOOEop2gis";

    // --- Rutas exentas y cifras -----------------------------------------------

    @Test
    @DisplayName("El panel, entrar con Google o Apple, el estado y las fotos no piden la llave")
    void exentas() {
        assertTrue(FiltroDeApp.exenta("/api/admin/usuarios"));
        assertTrue(FiltroDeApp.exenta("/api/admin"));
        assertTrue(FiltroDeApp.exenta("/api/auth/google"));
        assertTrue(FiltroDeApp.exenta("/api/auth/apple"));
        assertTrue(FiltroDeApp.exenta("/api/servidor"));
        assertTrue(FiltroDeApp.exenta("/api/fotos/abc"));

        assertFalse(FiltroDeApp.exenta("/api/auth/anonimo"), "crear cuentas sí pide la llave");
        assertFalse(FiltroDeApp.exenta("/api/auth/renovar"));
        assertFalse(FiltroDeApp.exenta("/api/administradores"));
        assertFalse(FiltroDeApp.exenta("/api/servidores"));
        assertFalse(FiltroDeApp.exenta("/api/creadores"));
    }

    @Test
    @DisplayName("Las cifras cuentan cada resultado y cierran la hora")
    void cifras() {
        CifrasDeLaApp c = new CifrasDeLaApp(Clock.fixed(AHORA, ZoneOffset.UTC));
        c.anotar(FirmaDeApp.Resultado.OK, VerificadorAppCheck.Resultado.OK, false);
        c.anotar(FirmaDeApp.Resultado.FALTA, VerificadorAppCheck.Resultado.FALTA, true);
        c.anotar(FirmaDeApp.Resultado.HORA, null, false);

        CifrasDeLaApp.Hora h = c.ultimas().get(c.ultimas().size() - 1);
        assertEquals(3, h.total());
        assertEquals(1, h.rechazadas());
        assertEquals(1, h.firmaOk());
        assertEquals(1, h.firmaFalta());
        assertEquals(1, h.firmaHora());
        assertEquals(1, h.appCheckOk());
        assertEquals(1, h.appCheckFalta());

        c.cerrarHora();
        assertEquals(2, c.ultimas().size());
        assertEquals(0, c.ultimas().get(1).total(), "la hora nueva empieza de cero");
    }

    @Test
    @DisplayName("Un modo desconocido guardado vale como avisar; el panel solo acepta los tres")
    void modos() {
        assertEquals(ModosDeLaApp.Modo.AVISAR, ModosDeLaApp.Modo.de(null));
        assertEquals(ModosDeLaApp.Modo.AVISAR, ModosDeLaApp.Modo.de("raro"));
        assertEquals(ModosDeLaApp.Modo.EXIGIR, ModosDeLaApp.Modo.de("exigir"));
        assertEquals(ModosDeLaApp.Modo.APAGADO, ModosDeLaApp.Modo.pedido(" Apagado "));
        assertNull(ModosDeLaApp.Modo.pedido("siempre"));
    }
}
