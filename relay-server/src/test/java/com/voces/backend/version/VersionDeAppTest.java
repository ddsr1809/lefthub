package com.voces.backend.version;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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

    @Test
    @DisplayName("La versión se guarda solo con sus números")
    void limpia() {
        assertEquals("1.0.2", VersionDeApp.limpia("1.0.2-pruebas"));
        assertEquals("1.0.2", VersionDeApp.limpia(" v1.0.2 "));
        assertEquals("2", VersionDeApp.limpia("2"));
        assertNull(VersionDeApp.limpia("nueva"));
        assertNull(VersionDeApp.limpia(null));
    }

    @Test
    @DisplayName("Lo que no dice plataforma es Android")
    void plataforma() {
        assertEquals("android", VersionDeApp.plataforma(null));
        assertEquals("android", VersionDeApp.plataforma("otra"));
        assertEquals("ios", VersionDeApp.plataforma(" iOS "));
        assertEquals("panel", VersionDeApp.plataforma("Panel"));
    }

    @Test
    @DisplayName("Una versión dada de baja se escribe como plataforma:versión")
    void comoSeEscribeUnaBaja() {
        assertEquals("android:1.0.0", VersionDeApp.baja("android", "1.0.0"));
        assertEquals("ios:0.1.0", VersionDeApp.baja("ios", "v0.1.0"));
        // La app que no dice su versión.
        assertEquals("android:0", VersionDeApp.baja(null, null));
        assertEquals("android:0", VersionDeApp.baja("android", ""));
    }

    @Test
    @DisplayName("Dar de baja una versión la deja fuera a ella y a ninguna otra")
    void bajas() {
        List<String> bajas = List.of("android:1.0.0", "ios:0.1.0");

        assertTrue(VersionDeApp.rechazada("", "", bajas, "GET", "/api/creadores", "android", "1.0.0"));
        assertTrue(VersionDeApp.rechazada("", "", bajas, "GET", "/api/creadores", "android", "1.0.0-pruebas"));
        assertTrue(VersionDeApp.rechazada("", "", bajas, "GET", "/api/creadores", "android", "1.0"));
        assertFalse(VersionDeApp.rechazada("", "", bajas, "GET", "/api/creadores", "android", "1.0.1"));
        assertFalse(VersionDeApp.rechazada("", "", bajas, "GET", "/api/creadores", "android", "0.9.0"));
        // Cada plataforma por su lado.
        assertFalse(VersionDeApp.rechazada("", "", bajas, "GET", "/api/creadores", "ios", "1.0.0"));
        assertTrue(VersionDeApp.rechazada("", "", bajas, "GET", "/api/creadores", "ios", "0.1.0"));
        // La app que no dice su versión no está dada de baja.
        assertFalse(VersionDeApp.rechazada("", "", bajas, "GET", "/api/creadores", null, null));
    }

    @Test
    @DisplayName("Dar de baja a las apps sin identificar deja fuera solo a las que no dicen su versión")
    void bajaDeLasSinIdentificar() {
        List<String> bajas = List.of("android:0");

        assertTrue(VersionDeApp.rechazada("", "", bajas, "GET", "/api/creadores", null, null));
        assertTrue(VersionDeApp.rechazada("", "", bajas, "POST", "/api/auth/anonimo", null, null));
        assertFalse(VersionDeApp.rechazada("", "", bajas, "GET", "/api/creadores", "android", "1.0.2"));
        assertFalse(VersionDeApp.rechazada("", "", bajas, "GET", "/api/creadores", "ios", null));
    }

    @Test
    @DisplayName("Una baja no alcanza al panel, a las fotos ni a lo interno")
    void bajasYLoQueNoEsDeLaApp() {
        List<String> bajas = List.of("android:0", "android:1.0.0");

        assertFalse(VersionDeApp.rechazada("", "", bajas, "GET", "/api/admin/apps", null, null));
        assertFalse(VersionDeApp.rechazada("", "", bajas, "GET", "/api/fotos/abc", null, null));
        assertFalse(VersionDeApp.rechazada("", "", bajas, "POST", "/websub", null, null));
        assertFalse(VersionDeApp.rechazada("", "", bajas, "POST", "/api/auth/google", "panel", null));
        assertFalse(VersionDeApp.rechazada("", "", bajas, "OPTIONS", "/api/creadores", null, null));
    }

    @Test
    @DisplayName("La mínima y las bajas se suman")
    void minimaYBajas() {
        List<String> bajas = List.of("android:1.0.3");

        assertTrue(VersionDeApp.rechazada("1.0.2", "", bajas, "GET", "/api/creadores", "android", "1.0.1"));
        assertFalse(VersionDeApp.rechazada("1.0.2", "", bajas, "GET", "/api/creadores", "android", "1.0.2"));
        assertTrue(VersionDeApp.rechazada("1.0.2", "", bajas, "GET", "/api/creadores", "android", "1.0.3"));
        assertFalse(VersionDeApp.rechazada("1.0.2", "", bajas, "GET", "/api/creadores", "android", "1.0.4"));
    }

    @Test
    @DisplayName("La fila de ajustes con las bajas se lee aunque venga vacía o con espacios de más")
    void filaDeAjustes() {
        assertEquals(Set.of(), AppsService.partir(null));
        assertEquals(Set.of(), AppsService.partir("  "));
        assertEquals(Set.of("android:1.0.0", "ios:0.1.0"), AppsService.partir(" android:1.0.0   ios:0.1.0 "));
        assertEquals(Set.of("android:0"), AppsService.partir("android:0 basura"));
    }

    @Test
    @DisplayName("El mantenimiento alcanza a las apps y a nada más")
    void aQuienDejaFueraElMantenimiento() {
        assertTrue(VersionDeApp.deUnaApp("GET", "/api/creadores", "android"));
        assertTrue(VersionDeApp.deUnaApp("POST", "/api/auth/anonimo", null));
        assertTrue(VersionDeApp.deUnaApp("GET", "/api/perfil", "ios"));
        // El panel sigue, para poder apagarlo.
        assertFalse(VersionDeApp.deUnaApp("GET", "/api/admin/apps", null));
        assertFalse(VersionDeApp.deUnaApp("POST", "/api/auth/google", "panel"));
        assertFalse(VersionDeApp.deUnaApp("GET", "/api/perfil", "Panel"));
        // La app pregunta aquí si hay mantenimiento.
        assertFalse(VersionDeApp.deUnaApp("GET", "/api/servidor", "android"));
        // Y el servidor sigue recibiendo publicaciones y sirviendo fotos.
        assertFalse(VersionDeApp.deUnaApp("POST", "/websub", null));
        assertFalse(VersionDeApp.deUnaApp("GET", "/api/fotos/abc", null));
        assertFalse(VersionDeApp.deUnaApp("POST", "/internal/replica/creadores", null));
        assertFalse(VersionDeApp.deUnaApp("GET", "/actuator/health", null));
        assertFalse(VersionDeApp.deUnaApp("OPTIONS", "/api/creadores", null));
    }

    @Test
    @DisplayName("El mensaje del mantenimiento nunca queda vacío ni desmedido")
    void mensajeDeMantenimiento() {
        assertEquals(MantenimientoService.MENSAJE_NORMAL, MantenimientoService.limpiar(null));
        assertEquals(MantenimientoService.MENSAJE_NORMAL, MantenimientoService.limpiar("   "));
        assertEquals("Volvemos a las 6.", MantenimientoService.limpiar("  Volvemos   a las\n6. "));
        assertEquals(MantenimientoService.LARGO_MENSAJE,
                MantenimientoService.limpiar("a".repeat(1000)).length());
    }
}
