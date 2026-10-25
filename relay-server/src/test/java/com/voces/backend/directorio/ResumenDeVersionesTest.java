package com.voces.backend.directorio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** La tarjeta «Versiones y llaves» de Usuarios, armada desde la consulta agrupada. */
class ResumenDeVersionesTest {

    private static final Instant T = Instant.parse("2026-10-24T12:00:00Z");
    private static final String PROD = "1:1025901070678:android:bdaed1e83362e0074b6ef7";

    private static Object[] fila(String plat, String ver, Integer llave, String app, long n, long activas, Integer orden) {
        return new Object[]{plat, ver, llave == null ? null : llave.shortValue(), app, n, activas, T, orden};
    }

    @Test
    @DisplayName("Cada versión con sus cuentas, de la más nueva a la más vieja, y el reparto por llave")
    void porVersion() {
        ResumenDeVersiones.Resumen r = ResumenDeVersiones.armar(List.of(
                fila("android", "1.1.8", 1, PROD, 40, 5, 1001008),
                fila("android", "1.1.8", null, null, 10, 0, 1001008),
                fila("android", "1.2.2", 2, PROD, 25, 25, 1002002),
                fila("android", "1.2.2", 1, PROD, 3, 3, 1002002),
                fila("android", "1.1.10", 1, PROD, 7, 1, 1001010),
                fila(null, null, null, null, 100, 2, null)));

        List<String> orden = r.versiones().stream().map(v -> v.version()).toList();
        assertEquals(java.util.Arrays.asList("1.2.2", "1.1.10", "1.1.8", null), orden, "por número: 1.1.10 después de 1.2.2 y antes de 1.1.8");

        ResumenDeVersiones.Version nueva = r.versiones().get(0);
        assertEquals(28, nueva.cuentas());
        assertEquals(28, nueva.activas());
        assertEquals(Map.of("1", 3L, "2", 25L), nueva.porLlave());
        assertEquals(List.of("1", "2"), List.copyOf(nueva.porLlave().keySet()), "las llaves por número");

        ResumenDeVersiones.Version vieja = r.versiones().get(2);
        assertEquals(50, vieja.cuentas());
        assertEquals(List.of("1", "sin"), List.copyOf(vieja.porLlave().keySet()), "«sin» al final");
        assertEquals(Map.of("Producción", 40L, "sin", 10L), vieja.porAppCheck());
    }

    @Test
    @DisplayName("Cada llave con cuántas cuentas y de qué versión a qué versión")
    void porLlave() {
        ResumenDeVersiones.Resumen r = ResumenDeVersiones.armar(List.of(
                fila("android", "1.1.8", 1, PROD, 40, 5, 1001008),
                fila("android", "1.2.2", 2, PROD, 25, 25, 1002002),
                fila("android", "1.2.2", 1, PROD, 3, 3, 1002002),
                fila("android", "1.0.9", null, null, 10, 0, 1000009)));

        assertEquals(List.of("1", "2", "sin"), r.llaves().stream().map(ResumenDeVersiones.Grupo::nombre).toList());
        ResumenDeVersiones.Grupo uno = r.llaves().get(0);
        assertEquals(43, uno.cuentas());
        assertEquals("1.1.8", uno.versionMasVieja());
        assertEquals("1.2.2", uno.versionMasNueva());
        assertEquals(List.of("Producción", "sin"), r.appsDeAppCheck().stream().map(ResumenDeVersiones.Grupo::nombre).toList());
    }

    @Test
    @DisplayName("Sin cuentas, todo vacío")
    void vacio() {
        ResumenDeVersiones.Resumen r = ResumenDeVersiones.armar(List.of());
        assertTrue(r.versiones().isEmpty());
        assertTrue(r.llaves().isEmpty());
    }

    @Test
    @DisplayName("El orden numérico de una versión")
    void orden() {
        assertEquals(1002002, com.voces.backend.version.VersionDeApp.orden("1.2.2"));
        assertEquals(1001010, com.voces.backend.version.VersionDeApp.orden("1.1.10"));
        assertTrue(com.voces.backend.version.VersionDeApp.orden("1.1.10") > com.voces.backend.version.VersionDeApp.orden("1.1.9"));
        assertEquals(1002000, com.voces.backend.version.VersionDeApp.orden("1.2"));
        assertEquals(1002002, com.voces.backend.version.VersionDeApp.orden("v1.2.2-pruebas"));
        assertNull(com.voces.backend.version.VersionDeApp.orden(null));
        assertNull(com.voces.backend.version.VersionDeApp.orden("nueva"));
    }
}
