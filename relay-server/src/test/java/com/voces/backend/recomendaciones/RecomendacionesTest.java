package com.voces.backend.recomendaciones;

import com.voces.backend.directorio.AjustesService;
import com.voces.backend.recomendaciones.LectorDeCanal.Entrada;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Quién puede recomendar canales y qué se entiende de lo que escribe. */
class RecomendacionesTest {

    private static final String ID = "UCabcdefghijklmnopqrstuv";

    @Test
    @DisplayName("Apagado no puede nadie, ni un administrador")
    void apagado() {
        assertFalse(RecomendacionesService.puede(AjustesService.RECOMENDAR_APAGADO, true, true));
        assertFalse(RecomendacionesService.puede(AjustesService.RECOMENDAR_APAGADO, false, true));
        assertFalse(RecomendacionesService.puede(null, true, true), "sin ajuste guardado es apagado");
        assertFalse(RecomendacionesService.puede("cualquier-cosa", true, true), "un valor desconocido es apagado");
    }

    @Test
    @DisplayName("Solo administradores: una cuenta elegida todavía no puede")
    void admins() {
        assertTrue(RecomendacionesService.puede(AjustesService.RECOMENDAR_ADMINS, true, false));
        assertFalse(RecomendacionesService.puede(AjustesService.RECOMENDAR_ADMINS, false, true));
        assertFalse(RecomendacionesService.puede(AjustesService.RECOMENDAR_ADMINS, false, false));
    }

    @Test
    @DisplayName("Elegidos: los administradores y las cuentas marcadas, nadie más")
    void elegidos() {
        assertTrue(RecomendacionesService.puede(AjustesService.RECOMENDAR_ELEGIDOS, true, false));
        assertTrue(RecomendacionesService.puede(AjustesService.RECOMENDAR_ELEGIDOS, false, true));
        assertFalse(RecomendacionesService.puede(AjustesService.RECOMENDAR_ELEGIDOS, false, false));
    }

    @Test
    @DisplayName("Todos: cualquier cuenta, también una de invitado sin marcar")
    void todos() {
        assertTrue(RecomendacionesService.puede(AjustesService.RECOMENDAR_TODOS, false, false));
        assertTrue(RecomendacionesService.puede(AjustesService.RECOMENDAR_TODOS, true, true));
    }

    @Test
    @DisplayName("Los cuatro alcances son los únicos que se aceptan")
    void alcances() {
        for (String a : new String[]{"apagado", "admins", "elegidos", "todos"}) {
            assertTrue(AjustesService.alcanceValido(a), a);
        }
        assertFalse(AjustesService.alcanceValido("Todos"));
        assertFalse(AjustesService.alcanceValido(""));
        assertFalse(AjustesService.alcanceValido(null));
    }

    @Test
    @DisplayName("El enlace del canal o su ID dan el ID directamente")
    void idDelCanal() {
        assertEquals(new Entrada(ID, null, null), LectorDeCanal.leer("https://www.youtube.com/channel/" + ID));
        assertEquals(new Entrada(ID, null, null), LectorDeCanal.leer("youtube.com/channel/" + ID + "/videos"));
        assertEquals(new Entrada(ID, null, null), LectorDeCanal.leer("  " + ID + "  "));
    }

    @Test
    @DisplayName("Un @handle, suelto o en el enlace, se pregunta a YouTube como handle")
    void handle() {
        assertEquals("rosaluna", LectorDeCanal.leer("@rosaluna").handle());
        assertEquals("rosaluna", LectorDeCanal.leer("https://www.youtube.com/@rosaluna").handle());
        assertEquals("rosaluna", LectorDeCanal.leer("https://m.youtube.com/@rosaluna/videos").handle());
        assertEquals("rosa.luna", LectorDeCanal.leer("mira este: youtube.com/@rosa.luna.").handle(),
                "el punto final de la frase no es del handle");
        assertEquals("CanalCatorceMX", LectorDeCanal.leer("https://www.youtube.com/c/CanalCatorceMX").handle());
        assertEquals("canal14", LectorDeCanal.leer("https://www.youtube.com/user/canal14").handle());
        assertEquals("Andrés_López", LectorDeCanal.leer("@Andrés_López").handle(), "los handles admiten acentos");
    }

    @Test
    @DisplayName("El enlace de un video sirve para saber de qué canal es")
    void video() {
        assertEquals("dQw4w9WgXcQ", LectorDeCanal.leer("https://youtu.be/dQw4w9WgXcQ?si=abc").videoId());
        assertEquals("dQw4w9WgXcQ", LectorDeCanal.leer("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=30s").videoId());
        assertEquals("dQw4w9WgXcQ", LectorDeCanal.leer("https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ").videoId());
        assertEquals("dQw4w9WgXcQ", LectorDeCanal.leer("https://youtube.com/shorts/dQw4w9WgXcQ").videoId());
        assertEquals("dQw4w9WgXcQ", LectorDeCanal.leer("https://www.youtube.com/live/dQw4w9WgXcQ?feature=shared").videoId());
    }

    @Test
    @DisplayName("Un nombre suelto o un correo no se le preguntan a YouTube")
    void soloNombre() {
        assertTrue(LectorDeCanal.leer("El canal de Rosa Luna").vacia());
        assertTrue(LectorDeCanal.leer("rosa@gmail.com").vacia(), "un correo no es un handle");
        assertTrue(LectorDeCanal.leer("https://www.tiktok.com/@rosaluna").vacia(), "solo canales de YouTube");
        assertTrue(LectorDeCanal.leer("").vacia());
        assertTrue(LectorDeCanal.leer(null).vacia());
    }

    @Test
    @DisplayName("El mismo nombre escrito de dos maneras se reconoce como repetido")
    void normalizar() {
        assertEquals(LectorDeCanal.normalizar("Rosa  Luna "), LectorDeCanal.normalizar("rosa luna"));
        assertEquals(LectorDeCanal.normalizar("@RosaLuna"), LectorDeCanal.normalizar("rosaluna"));
        assertEquals("", LectorDeCanal.normalizar(null));
    }

    @Test
    @DisplayName("El texto se recorta al máximo y uno vacío no cuenta")
    void recortar() {
        assertNull(RecomendacionesService.recortar("   ", 10));
        assertNull(RecomendacionesService.recortar(null, 10));
        assertEquals("hola", RecomendacionesService.recortar("  hola  ", 10));
        assertEquals(RecomendacionesService.MAX_TEXTO,
                RecomendacionesService.recortar("a".repeat(1000), RecomendacionesService.MAX_TEXTO).length());
    }
}
