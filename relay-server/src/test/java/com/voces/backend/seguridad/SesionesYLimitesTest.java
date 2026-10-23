package com.voces.backend.seguridad;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El identificador del teléfono guardado como huella, el cierre de sesiones
 * y el límite de inicios de sesión por IP.
 */
class SesionesYLimitesTest {

    private static final Instant AHORA = Instant.parse("2026-10-23T08:00:00Z");

    // --- Huella del dispositivo ---------------------------------------------

    @Test
    @DisplayName("La huella del teléfono es la misma que calcula el SQL del arranque")
    void huellaComoEnSql() {
        // select 'h1:' || encode(sha256(convert_to('abc', 'UTF8')), 'hex');
        assertEquals("h1:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                HuellaDeDispositivo.de("abc"));
        assertNull(HuellaDeDispositivo.de(" "));
    }

    @Test
    @DisplayName("Una huella vista en la base no sirve para entrar: se vuelve a calcular")
    void huellaNoEsLlave() {
        String guardada = HuellaDeDispositivo.de("telefono-de-ana");
        assertNotEquals(guardada, HuellaDeDispositivo.de(guardada));
    }

    @Test
    @DisplayName("La columna nunca guarda el identificador legible, y no rehace una huella")
    void columnaDelDispositivo() {
        HuellaDeDispositivo.Columna columna = new HuellaDeDispositivo.Columna();
        String huella = HuellaDeDispositivo.de("telefono-de-ana");

        assertEquals(huella, columna.convertToDatabaseColumn("telefono-de-ana"));
        assertEquals(huella, columna.convertToDatabaseColumn(huella));
        assertNull(columna.convertToDatabaseColumn(null));
    }

    // --- Sesiones -------------------------------------------------------------

    @Test
    @DisplayName("Cerrar sesiones invalida los tokens anteriores, no los de después")
    void revocar() {
        EstadoDeSesiones.Estado sinCerrar = new EstadoDeSesiones.Estado(false, null);
        EstadoDeSesiones.Estado cerradas = new EstadoDeSesiones.Estado(false, AHORA.plusMillis(700));

        assertFalse(sinCerrar.revoca(AHORA.minusSeconds(86_400)));
        assertTrue(cerradas.revoca(AHORA.minusSeconds(1)));
        assertFalse(cerradas.revoca(AHORA), "emitido en el mismo segundo: el token guarda segundos");
        assertFalse(cerradas.revoca(AHORA.plusSeconds(5)));
        assertTrue(cerradas.revoca(null));
    }

    @Test
    @DisplayName("El estado de la cuenta se recuerda unos segundos, y se olvida si cambia")
    void memoria() {
        UUID id = UUID.randomUUID();
        AtomicInteger lecturas = new AtomicInteger();
        Instant[] ahora = {AHORA};
        Clock reloj = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zona) { return this; }
            @Override public Instant instant() { return ahora[0]; }
        };
        EstadoDeSesiones estados = new EstadoDeSesiones(u -> {
            lecturas.incrementAndGet();
            return Optional.of(new EstadoDeSesiones.Estado(true, null));
        }, reloj);

        estados.de(id);
        estados.de(id);
        assertEquals(1, lecturas.get());

        ahora[0] = AHORA.plus(EstadoDeSesiones.MEMORIA);
        estados.de(id);
        assertEquals(2, lecturas.get(), "pasado el tiempo, vuelve a la base");

        estados.olvidar(id);
        estados.de(id);
        assertEquals(3, lecturas.get(), "tras un cambio, vuelve a la base al momento");
    }

    @Test
    @DisplayName("Una cuenta que ya no existe no tiene estado")
    void cuentaBorrada() {
        EstadoDeSesiones estados = new EstadoDeSesiones(u -> Optional.empty(), Clock.fixed(AHORA, ZoneOffset.UTC));
        assertTrue(estados.de(UUID.randomUUID()).isEmpty());
    }

    // --- Límite por IP --------------------------------------------------------

    @Test
    @DisplayName("Una IP tiene margen para entrar muchas veces; después, una cada dos segundos")
    void limite() {
        LimiteDePeticiones limite = new LimiteDePeticiones(Clock.fixed(AHORA, ZoneOffset.UTC));
        for (int i = 0; i < LimiteDePeticiones.MARGEN; i++) {
            assertTrue(limite.permitir("200.1.1.1", AHORA), "intento " + (i + 1));
        }
        assertFalse(limite.permitir("200.1.1.1", AHORA));
        assertTrue(limite.permitir("200.2.2.2", AHORA), "otra IP no se ve afectada");

        Instant despues = AHORA.plus(LimiteDePeticiones.RECARGA);
        assertTrue(limite.permitir("200.1.1.1", despues));
        assertFalse(limite.permitir("200.1.1.1", despues));
    }
}
