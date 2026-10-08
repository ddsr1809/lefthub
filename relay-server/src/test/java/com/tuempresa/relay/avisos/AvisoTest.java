package com.tuempresa.relay.avisos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lo que lee quien administra en la notificación, y cuándo un canal parado
 * merece una. Lógica pura, sin red ni base de datos.
 */
class AvisoTest {

    // -------------------------------------------------------------------------
    // Reportes
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Un enlace roto dice qué video y de quién")
    void reporteCompleto() {
        Aviso aviso = Aviso.deReporte("enlace_roto", null, "dQw4w9WgXcQ", "La reforma, explicada", "Ana Ruiz", 7);
        assertEquals("Reporte nuevo: enlace roto", aviso.titulo());
        assertEquals("«La reforma, explicada», de Ana Ruiz.", aviso.cuerpo());
        assertEquals("reportes", aviso.vista());
        assertEquals("reporte-dQw4w9WgXcQ", aviso.etiqueta());
    }

    @Test
    @DisplayName("Con lo que haya: un video que el servidor no conoce, o solo el creador")
    void reporteConDatosSueltos() {
        assertEquals("El video dQw4w9WgXcQ.",
                Aviso.deReporte(null, null, "dQw4w9WgXcQ", null, null, 7).cuerpo());
        assertEquals("El video dQw4w9WgXcQ, de Ana Ruiz.",
                Aviso.deReporte("enlace_roto", null, "dQw4w9WgXcQ", " ", "Ana Ruiz", 7).cuerpo());

        Aviso soloCreador = Aviso.deReporte("enlace_roto", null, null, null, "Ana Ruiz", 7);
        assertEquals("Sobre Ana Ruiz.", soloCreador.cuerpo());
        assertEquals("reporte-7", soloCreador.etiqueta());

        assertEquals("Ábrelo para ver de qué se trata.",
                Aviso.deReporte("enlace_roto", null, null, null, null, 7).cuerpo());
    }

    @Test
    @DisplayName("Un motivo escrito por la persona se lee en el aviso")
    void reporteConOtroMotivo() {
        Aviso aviso = Aviso.deReporte("El video es de otro canal", null, "dQw4w9WgXcQ", "Título", "Ana", 7);
        assertEquals("Reporte nuevo", aviso.titulo());
        assertEquals("«Título», de Ana. Motivo: El video es de otro canal", aviso.cuerpo());
    }

    @Test
    @DisplayName("La incidencia de Apple no se confunde con un enlace roto")
    void incidenciaDeApple() {
        Aviso aviso = Aviso.deReporte("apple_revoke_failed", "invalid_grant", null, null, null, 12);
        assertEquals("Falló la revocación con Apple", aviso.titulo());
        assertTrue(aviso.cuerpo().endsWith("invalid_grant"));
        assertEquals("reporte-12", aviso.etiqueta());
    }

    // -------------------------------------------------------------------------
    // Canales
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Varios canales parados caben en un solo aviso")
    void canales() {
        Aviso uno = Aviso.deCanales(List.of("Ana Ruiz"));
        assertEquals("Un canal dejó de recibir publicaciones", uno.titulo());
        assertTrue(uno.cuerpo().startsWith("Ana Ruiz: "));
        assertEquals("canales", uno.vista());

        Aviso varios = Aviso.deCanales(List.of("Ana", "Beto", "Caro", "Dani", "Eli"));
        assertEquals("5 canales dejaron de recibir publicaciones", varios.titulo());
        assertTrue(varios.cuerpo().startsWith("Ana, Beto y 3 más: "));

        assertEquals("Ana y Beto", Aviso.enumerar(List.of("Ana", "Beto")));
        assertEquals("Ana, Beto y Caro", Aviso.enumerar(List.of("Ana", "Beto", "Caro")));
    }

    @Test
    @DisplayName("Un canal no avisa mientras se da de alta o se renueva, solo si sigue parado")
    void esperaDeUnCanal() {
        VigiaDeCanales vigia = new VigiaDeCanales(Duration.ofMinutes(30));
        Instant t = Instant.parse("2026-10-08T10:00:00Z");

        assertEquals(List.of(), vigia.porAvisar(List.of("UC1"), t), "primera vez que se ve");
        assertEquals(List.of(), vigia.porAvisar(List.of("UC1"), t.plusSeconds(15 * 60)), "todavía en la espera");
        assertEquals(List.of("UC1"), vigia.porAvisar(List.of("UC1"), t.plusSeconds(30 * 60)));
    }

    @Test
    @DisplayName("De un canal parado se avisa una vez, y otra si se arregla y vuelve a fallar")
    void unAvisoPorCaida() {
        VigiaDeCanales vigia = new VigiaDeCanales(Duration.ofMinutes(30));
        Instant t = Instant.parse("2026-10-08T10:00:00Z");

        vigia.porAvisar(List.of("UC1"), t);
        assertEquals(List.of("UC1"), vigia.porAvisar(List.of("UC1"), t.plusSeconds(1800)));
        vigia.avisado("UC1");
        assertEquals(List.of(), vigia.porAvisar(List.of("UC1"), t.plusSeconds(2700)), "ya se avisó");
        assertEquals(List.of(), vigia.porAvisar(List.of("UC1"), t.plusSeconds(86400)), "y no se repite");

        // Se arregla (deja de venir en la lista) y vuelve a fallar: empieza de cero.
        assertEquals(List.of(), vigia.porAvisar(List.of(), t.plusSeconds(90000)));
        assertEquals(List.of(), vigia.porAvisar(List.of("UC1"), t.plusSeconds(90900)));
        assertEquals(List.of("UC1"), vigia.porAvisar(List.of("UC1"), t.plusSeconds(90900 + 1800)));
    }

    @Test
    @DisplayName("Un canal oculto que no se avisó sigue pendiente por si lo hacen visible")
    void canalQueNoSeAviso() {
        VigiaDeCanales vigia = new VigiaDeCanales(Duration.ofMinutes(30));
        Instant t = Instant.parse("2026-10-08T10:00:00Z");

        vigia.porAvisar(List.of("UC1", "UC2"), t);
        assertEquals(List.of("UC1", "UC2"), vigia.porAvisar(List.of("UC1", "UC2"), t.plusSeconds(1800)));
        vigia.avisado("UC1");   // UC2 era de un creador oculto: no se marcó

        assertEquals(List.of("UC2"), vigia.porAvisar(List.of("UC1", "UC2", "UC2"), t.plusSeconds(2700)));
    }

    // -------------------------------------------------------------------------
    // Cómo viaja
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("El aviso viaja como JSON válido aunque el título del video traiga comillas")
    void carga() {
        Aviso aviso = Aviso.deReporte("enlace_roto", null, "abc", "El \"plan\" \\ de\nmañana 🎬", "Ñandú", 1);
        String json = new String(aviso.carga(), StandardCharsets.UTF_8);
        assertEquals("{\"titulo\":\"Reporte nuevo: enlace roto\","
                + "\"cuerpo\":\"«El \\\"plan\\\" \\\\ de\\nmañana 🎬», de Ñandú.\","
                + "\"vista\":\"reportes\",\"etiqueta\":\"reporte-abc\"}", json);
    }

    @Test
    @DisplayName("Un texto larguísimo se recorta, sin partir un emoji, y sigue cabiendo")
    void recorte() {
        Aviso aviso = Aviso.deReporte("enlace_roto", null, "abc", "🎬".repeat(400), "Ana", 1);
        assertTrue(aviso.cuerpo().length() <= Aviso.MAX_CUERPO);
        assertTrue(aviso.cuerpo().endsWith("…"));
        assertFalse(Character.isHighSurrogate(aviso.cuerpo().charAt(aviso.cuerpo().length() - 2)));
        assertTrue(aviso.carga().length < WebPush.MAXIMO);

        assertEquals("", Aviso.recortar(null, 10));
        assertEquals("hola", Aviso.recortar("  hola  ", 10));
        assertEquals("abcdefghi…", Aviso.recortar("abcdefghijklmnop", 10));
    }
}
