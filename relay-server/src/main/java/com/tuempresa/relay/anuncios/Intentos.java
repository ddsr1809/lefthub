package com.tuempresa.relay.anuncios;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cuántos folios equivocados puede mandar seguidos una misma cuenta.
 *
 * Cinco fallos de margen, y después uno más cada tres minutos. A quien se
 * equivoca al copiar su folio le sobra; a quien prueba folios al azar lo deja
 * en unos 480 intentos al día por cuenta.
 *
 * Solo cuentan los fallos: canjear bien un folio no gasta nada.
 *
 * Vive en memoria: hay un solo servidor, y si se reinicia lo peor que pasa es
 * que todo el mundo recupera su margen.
 */
final class Intentos {

    static final int MARGEN = 5;
    static final Duration RECARGA = Duration.ofMinutes(3);

    /** Pasado este número de cuentas apuntadas, se olvidan las que ya recuperaron su margen. */
    private static final int TOPE = 10_000;

    private final Map<UUID, Cuenta> cuentas = new ConcurrentHashMap<>();

    /** ¿Le queda margen para probar un folio? */
    boolean puede(UUID usuario, Instant ahora) {
        Cuenta cuenta = cuentas.get(usuario);
        return cuenta == null || cuenta.disponibles(ahora) >= 1;
    }

    /** Apunta un folio equivocado. */
    void fallo(UUID usuario, Instant ahora) {
        if (cuentas.size() >= TOPE) {
            cuentas.values().removeIf(c -> c.disponibles(ahora) >= MARGEN);
        }
        cuentas.computeIfAbsent(usuario, u -> new Cuenta(ahora)).gastar(ahora);
    }

    private static final class Cuenta {

        private double fichas = MARGEN;
        private Instant ultima;

        Cuenta(Instant ahora) {
            this.ultima = ahora;
        }

        synchronized double disponibles(Instant ahora) {
            recargar(ahora);
            return fichas;
        }

        synchronized void gastar(Instant ahora) {
            recargar(ahora);
            fichas = Math.max(0, fichas - 1);
        }

        private void recargar(Instant ahora) {
            long transcurrido = Duration.between(ultima, ahora).toMillis();
            if (transcurrido <= 0) return;

            fichas = Math.min(MARGEN, fichas + transcurrido / (double) RECARGA.toMillis());
            ultima = ahora;
        }
    }
}
