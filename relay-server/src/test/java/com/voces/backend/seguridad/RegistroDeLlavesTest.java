package com.voces.backend.seguridad;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Qué cuentas traen la llave de la app: se escribe en la base solo lo que
 * cambió, o lo que lleva rato sin escribirse.
 */
class RegistroDeLlavesTest {

    private static final Instant AHORA = Instant.parse("2026-10-23T20:00:00Z");

    /** Un reloj que se puede adelantar. */
    private static final class Reloj extends Clock {
        Instant ahora = AHORA;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant() { return ahora; }
    }

    @Test
    @DisplayName("Se anota la última petición de cada cuenta, con nombres en minúsculas")
    void anota() {
        Reloj reloj = new Reloj();
        RegistroDeLlaves r = new RegistroDeLlaves(null, reloj);
        UUID ana = UUID.randomUUID(), luis = UUID.randomUUID();

        r.anotar(ana, FirmaDeApp.Resultado.MALA, VerificadorAppCheck.Resultado.FALTA);
        r.anotar(ana, FirmaDeApp.Resultado.OK, VerificadorAppCheck.Resultado.FALTA);
        r.anotar(luis, FirmaDeApp.Resultado.FALTA, null);
        r.anotar(null, FirmaDeApp.Resultado.OK, null);
        r.anotar(UUID.randomUUID(), null, null);

        var pendientes = r.pendientes();
        assertEquals(2, pendientes.size());
        var deAna = pendientes.stream().filter(p -> p.cuenta().equals(ana)).findFirst().orElseThrow();
        assertEquals(new RegistroDeLlaves.Estado("ok", "falta"), deAna.estado(), "vale la última");
        var deLuis = pendientes.stream().filter(p -> p.cuenta().equals(luis)).findFirst().orElseThrow();
        assertEquals(new RegistroDeLlaves.Estado("falta", null), deLuis.estado(), "App Check apagado: null");
    }

    @Test
    @DisplayName("Lo ya guardado no se reescribe hasta que cambia o pasa un rato")
    void soloLoQueCambia() {
        Reloj reloj = new Reloj();
        RegistroDeLlaves r = new RegistroDeLlaves(null, reloj);
        UUID ana = UUID.randomUUID();

        r.anotar(ana, FirmaDeApp.Resultado.OK, VerificadorAppCheck.Resultado.OK);
        r.pendientes().forEach(r::marcarGuardado);
        assertTrue(r.pendientes().isEmpty());

        reloj.ahora = AHORA.plusSeconds(60);
        r.anotar(ana, FirmaDeApp.Resultado.OK, VerificadorAppCheck.Resultado.OK);
        assertTrue(r.pendientes().isEmpty(), "igual que lo guardado, y hace poco");

        reloj.ahora = AHORA.plus(RegistroDeLlaves.REFRESCO).plusSeconds(1);
        r.anotar(ana, FirmaDeApp.Resultado.OK, VerificadorAppCheck.Resultado.OK);
        assertEquals(1, r.pendientes().size(), "pasado el rato, se refresca la fecha");
        r.pendientes().forEach(r::marcarGuardado);

        r.anotar(ana, FirmaDeApp.Resultado.MALA, VerificadorAppCheck.Resultado.OK);
        assertEquals(new RegistroDeLlaves.Estado("mala", "ok"), r.pendientes().get(0).estado(), "cambió: al momento");
    }
}
