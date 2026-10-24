package com.voces.backend.seguridad;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lo que se anota de cada cuenta con cada petición, además de si traía la
 * llave: con qué número de llave firmó, de qué app vino su App Check y con qué
 * versión de la app. Sin base de datos: se mira lo que se iría a escribir.
 */
class VersionYLlaveDeCuentaTest {

    private static final Instant AHORA = Instant.parse("2026-10-24T12:00:00Z");
    private static final String PRODUCCION = "1:1025901070678:android:bdaed1e83362e0074b6ef7";

    private static final class Reloj extends Clock {
        Instant ahora = AHORA;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant() { return ahora; }
    }

    private static RegistroDeLlaves.Pendiente unico(RegistroDeLlaves r) {
        List<RegistroDeLlaves.Pendiente> p = r.pendientes();
        assertEquals(1, p.size());
        return p.get(0);
    }

    @Test
    @DisplayName("Se anota el número de llave, la app de App Check y la versión de la app")
    void anotaTodo() {
        RegistroDeLlaves r = new RegistroDeLlaves(null, new Reloj());
        UUID ana = UUID.randomUUID();
        r.anotar(ana, FirmaDeApp.Resultado.OK, (short) 2, VerificadorAppCheck.Resultado.OK, PRODUCCION, "android", "1.2.2");

        RegistroDeLlaves.Pendiente p = unico(r);
        assertEquals(new RegistroDeLlaves.Estado("ok", "ok"), p.estado());
        assertEquals(new RegistroDeLlaves.Detalle((short) 2, PRODUCCION, "android", "1.2.2"), p.detalle());
    }

    @Test
    @DisplayName("Al instalar la versión nueva, la cuenta pasa de la 1.1.8 a la 1.2.2 en la siguiente escritura")
    void cambiaDeVersion() {
        Reloj reloj = new Reloj();
        RegistroDeLlaves r = new RegistroDeLlaves(null, reloj);
        UUID ana = UUID.randomUUID();
        r.anotar(ana, FirmaDeApp.Resultado.OK, (short) 1, null, null, "android", "1.1.8");
        r.pendientes().forEach(r::marcarGuardado);

        reloj.ahora = AHORA.plusSeconds(30);
        r.anotar(ana, FirmaDeApp.Resultado.OK, (short) 1, null, null, "android", "1.1.8");
        assertTrue(r.pendientes().isEmpty(), "misma versión y misma llave: nada que escribir");

        reloj.ahora = AHORA.plusSeconds(60);
        r.anotar(ana, FirmaDeApp.Resultado.OK, (short) 2, null, null, "android", "1.2.2");
        RegistroDeLlaves.Pendiente p = unico(r);
        assertEquals("1.2.2", p.detalle().version());
        assertEquals(Short.valueOf((short) 2), p.detalle().numero(), "la versión nueva firma con la llave nueva");
    }

    @Test
    @DisplayName("Una petición de un administrador, sin comprobaciones, solo anota la versión")
    void soloVersion() {
        RegistroDeLlaves r = new RegistroDeLlaves(null, new Reloj());
        UUID admin = UUID.randomUUID();
        r.anotarVersion(admin, "android", "1.2.2");

        RegistroDeLlaves.Pendiente p = unico(r);
        assertNull(p.estado(), "no se toca lo que había de su llave");
        assertEquals("1.2.2", p.detalle().version());

        r.anotarVersion(UUID.randomUUID(), null, null);
        r.anotarVersion(null, "android", "1.2.2");
        assertEquals(1, r.pendientes().size(), "sin versión o sin cuenta no se anota nada");
    }

    @Test
    @DisplayName("Lo que una petición no trae se conserva de la anterior")
    void conserva() {
        RegistroDeLlaves r = new RegistroDeLlaves(null, new Reloj());
        UUID ana = UUID.randomUUID();
        r.anotar(ana, FirmaDeApp.Resultado.OK, (short) 2, VerificadorAppCheck.Resultado.OK, PRODUCCION, "android", "1.2.2");
        // Una sin cabecera de versión (la app la manda casi siempre, pero no en todas).
        r.anotar(ana, FirmaDeApp.Resultado.OK, (short) 2, VerificadorAppCheck.Resultado.OK, PRODUCCION, null, null);
        assertEquals("1.2.2", unico(r).detalle().version());

        // Con App Check apagado, se queda la app que se sabía.
        r.anotar(ana, FirmaDeApp.Resultado.OK, (short) 2, null, null, "android", "1.2.2");
        assertEquals(PRODUCCION, unico(r).detalle().appCheckApp());
    }

    @Test
    @DisplayName("Un App Check malo o sin token borra la app; sin firma no hay número de llave")
    void borra() {
        RegistroDeLlaves r = new RegistroDeLlaves(null, new Reloj());
        UUID ana = UUID.randomUUID();
        r.anotar(ana, FirmaDeApp.Resultado.OK, (short) 2, VerificadorAppCheck.Resultado.OK, PRODUCCION, "android", "1.2.2");
        r.anotar(ana, FirmaDeApp.Resultado.FALTA, null, VerificadorAppCheck.Resultado.MALO, PRODUCCION, "android", "1.2.2");

        RegistroDeLlaves.Detalle d = unico(r).detalle();
        assertNull(d.numero());
        assertNull(d.appCheckApp(), "de un token malo no se cree lo que dice");
    }

    @Test
    @DisplayName("Las cabeceras de la petición: número de llave y versión de la app")
    void cabeceras() {
        assertEquals(Short.valueOf((short) 2), FiltroDeApp.numeroDeLlave(" 2 "));
        assertNull(FiltroDeApp.numeroDeLlave(null));
        assertNull(FiltroDeApp.numeroDeLlave("dos"));
        assertNull(FiltroDeApp.numeroDeLlave("0"));
        assertNull(FiltroDeApp.numeroDeLlave("99999"));
    }

    @Test
    @DisplayName("Cada app de Firebase con su nombre en el panel")
    void nombresDeApps() {
        assertEquals("Producción", VerificadorAppCheck.nombreDeApp(PRODUCCION));
        assertEquals("Pruebas", VerificadorAppCheck.nombreDeApp("1:1025901070678:android:1b22a606c86d110b4b6ef7"));
        assertEquals("Developer", VerificadorAppCheck.nombreDeApp("1:1025901070678:android:77a8355ef485c9794b6ef7"));
        assertEquals("Otra app (…abc123)", VerificadorAppCheck.nombreDeApp("1:999:android:zzzabc123"));
        assertNull(VerificadorAppCheck.nombreDeApp(null));
        assertNull(VerificadorAppCheck.appDelToken("no es un token"));
        assertNull(VerificadorAppCheck.appDelToken(null));
    }
}
