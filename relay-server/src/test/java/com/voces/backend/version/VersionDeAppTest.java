package com.voces.backend.version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cómo se comparan las versiones de la app y a quién deja fuera una versión
 * mínima. Lógica pura, sin red ni base de datos.
 */
class VersionDeAppTest {

    private static boolean rechazada(String minimaAndroid, String minimaIos,
                                     String ruta, String plataforma, String version) {
        return VersionDeApp.rechazada(minimaAndroid, minimaIos, "GET", ruta, plataforma, version);
    }

    @Test
    @DisplayName("Las versiones se comparan por números, no por letras")
    void comparar() {
        assertTrue(VersionDeApp.comparar("1.0.0", "1.0.1") < 0);
        assertTrue(VersionDeApp.comparar("1.0.10", "1.0.9") > 0);
        assertTrue(VersionDeApp.comparar("0.9.9", "1.0.0") < 0);
        assertEquals(0, VersionDeApp.comparar("1.0", "1.0.0"));
        assertEquals(0, VersionDeApp.comparar("v1.2.3", "1.2.3"));
    }

    @Test
    @DisplayName("El sufijo del sabor (-pruebas, -developer) no cuenta")
    void sufijos() {
        assertEquals(0, VersionDeApp.comparar("1.0.1-pruebas", "1.0.1"));
        assertEquals(0, VersionDeApp.comparar(" 1.0.1-developer ", "1.0.1"));
        assertTrue(VersionDeApp.comparar("1.0.0-pruebas", "1.0.1") < 0);
    }

    @Test
    @DisplayName("Lo que no se entiende cuenta como la versión más vieja")
    void ilegibles() {
        assertTrue(VersionDeApp.comparar(null, "0.0.1") < 0);
        assertTrue(VersionDeApp.comparar("", "0.0.1") < 0);
        assertTrue(VersionDeApp.comparar("nueva", "0.0.1") < 0);
        assertEquals(0, VersionDeApp.comparar(null, "0.0.0"));
    }

    @Test
    @DisplayName("Sin mínima no se rechaza a nadie")
    void sinMinima() {
        assertFalse(rechazada("", "", "/api/creadores", null, null));
        assertFalse(rechazada("", "", "/api/creadores", "android", "0.1.0"));
        assertFalse(rechazada(null, null, "/api/creadores", "ios", "0.1.0"));
    }

    @Test
    @DisplayName("Con mínima, pasa la app que llega y no la que se queda corta")
    void conMinima() {
        assertTrue(rechazada("1.0.1", "", "/api/creadores", "android", "1.0.0"));
        assertFalse(rechazada("1.0.1", "", "/api/creadores", "android", "1.0.1"));
        assertFalse(rechazada("1.0.1", "", "/api/creadores", "android", "1.0.1-pruebas"));
        assertFalse(rechazada("1.0.1", "", "/api/creadores", "android", "1.2.0"));
    }

    @Test
    @DisplayName("La app que no dice su versión cuenta como la más vieja, y como Android")
    void sinCabeceras() {
        assertTrue(rechazada("1.0.1", "", "/api/creadores", null, null));
        assertTrue(rechazada("1.0.1", "", "/api/auth/anonimo", null, null));
        // Solo hay mínima de iOS: lo que no dice plataforma es Android y pasa.
        assertFalse(rechazada("", "1.0.0", "/api/creadores", null, null));
    }

    @Test
    @DisplayName("Cada plataforma tiene su mínima")
    void porPlataforma() {
        assertFalse(rechazada("1.0.1", "", "/api/creadores", "ios", "0.1.0"));
        assertTrue(rechazada("1.0.1", "0.2.0", "/api/creadores", "ios", "0.1.0"));
        assertFalse(rechazada("1.0.1", "0.2.0", "/api/creadores", "iOS", "0.2.0"));
        assertTrue(rechazada("1.0.1", "0.2.0", "/api/creadores", "android", "0.2.0"));
    }

    @Test
    @DisplayName("El panel, las fotos, el webhook y lo interno nunca se rechazan")
    void loQueNoEsDeLaApp() {
        assertFalse(rechazada("9.0.0", "9.0.0", "/api/admin/creadores", null, null));
        assertFalse(rechazada("9.0.0", "9.0.0", "/api/fotos/abc", null, null));
        assertFalse(rechazada("9.0.0", "9.0.0", "/api/servidor", null, null));
        assertFalse(rechazada("9.0.0", "9.0.0", "/websub", null, null));
        assertFalse(rechazada("9.0.0", "9.0.0", "/internal/versiones", null, null));
        assertFalse(rechazada("9.0.0", "9.0.0", "/actuator/health", null, null));
        // El panel entra con Google y lee su perfil por rutas de la app.
        assertFalse(rechazada("9.0.0", "9.0.0", "/api/auth/google", "panel", null));
        assertFalse(rechazada("9.0.0", "9.0.0", "/api/perfil", "Panel", null));
    }

    @Test
    @DisplayName("La consulta previa de CORS pasa siempre")
    void preflight() {
        assertFalse(VersionDeApp.rechazada("9.0.0", "", "OPTIONS", "/api/creadores", null, null));
        assertTrue(VersionDeApp.rechazada("9.0.0", "", "POST", "/api/reportes", null, null));
    }
}
