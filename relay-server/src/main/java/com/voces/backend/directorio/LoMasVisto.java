package com.voces.backend.directorio;

import java.time.Duration;
import java.time.Instant;

/**
 * Las reglas del apartado «Lo más visto» de Novedades.
 *
 * Es lo más visto de todo el directorio, no solo de quienes sigue la
 * persona: sirve para descubrir. Pero todo sale de los creadores, canales y
 * medios que el equipo eligió a mano; no es un ranking de YouTube.
 *
 * Se ordena por las vistas que el servidor ya guarda (CifrasDeVideos), sin
 * gastar cuota extra. Las de los videos de los últimos dos días se piden
 * cada media hora, así que el orden de «Hoy» va como mucho media hora
 * detrás de YouTube.
 *
 * Lógica pura, sin Spring, para poder probarla sola.
 */
final class LoMasVisto {

    private LoMasVisto() {}

    /** Cuántos videos trae como mucho, pida lo que pida la app. */
    static final int MAXIMO = 30;

    /** Cuántos trae si la app no dice. */
    static final int POR_DEFECTO = 20;

    /**
     * El periodo en el que se busca. Cuenta hacia atrás desde ahora, no
     * desde la medianoche: a las siete de la mañana «Hoy» todavía tiene lo
     * de anoche, que es lo que la persona no ha visto.
     */
    enum Periodo {
        HOY(Duration.ofHours(24)),
        SEMANA(Duration.ofDays(7));

        private final Duration largo;

        Periodo(Duration largo) {
            this.largo = largo;
        }

        Instant desde(Instant ahora) {
            return ahora.minus(largo);
        }

        /** "hoy" o "semana"; cualquier otra cosa, o nada, es «Hoy». */
        static Periodo de(String texto) {
            return "semana".equalsIgnoreCase(texto == null ? "" : texto.trim()) ? SEMANA : HOY;
        }
    }

    /** Lo que pidió la app, dentro de lo permitido. */
    static int cuantos(int pedidos) {
        if (pedidos <= 0) return POR_DEFECTO;
        return Math.min(pedidos, MAXIMO);
    }
}
