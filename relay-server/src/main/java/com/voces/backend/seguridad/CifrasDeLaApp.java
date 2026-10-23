package com.voces.backend.seguridad;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Cuántas peticiones de la app pasan la firma y App Check, hora por hora.
 *
 * Es lo que el panel enseña en Apps → Llave de la app para decidir cuándo
 * exigirlas: si casi todas pasan, exigir no deja fuera a nadie. Vive en
 * memoria (las últimas 24 horas); al reiniciar el servidor empieza de cero.
 * Cada hora se escribe además un resumen en el registro.
 */
@Component
public class CifrasDeLaApp {

    private static final Logger log = LoggerFactory.getLogger(CifrasDeLaApp.class);
    private static final int HORAS = 24;

    // Posiciones en el contador de cada hora.
    static final int TOTAL = 0, RECHAZADAS = 1,
            FIRMA_OK = 2, FIRMA_FALTA = 3, FIRMA_MALA = 4, FIRMA_HORA = 5, FIRMA_REPETIDA = 6,
            CHECK_OK = 7, CHECK_FALTA = 8, CHECK_MALO = 9, CHECK_SIN_VERIFICAR = 10;
    private static final int CAMPOS = 11;

    /** Una hora: desde cuándo y sus cuentas. */
    public record Hora(Instant desde, long total, long rechazadas,
                       long firmaOk, long firmaFalta, long firmaMala, long firmaHora, long firmaRepetida,
                       long appCheckOk, long appCheckFalta, long appCheckMalo, long appCheckSinVerificar) {}

    private final Clock reloj;
    private final Deque<Hora> pasadas = new ArrayDeque<>();
    private volatile AtomicLongArray actual = new AtomicLongArray(CAMPOS);
    private volatile Instant desde;

    public CifrasDeLaApp() {
        this(Clock.systemUTC());
    }

    CifrasDeLaApp(Clock reloj) {
        this.reloj = reloj;
        this.desde = reloj.instant().truncatedTo(ChronoUnit.HOURS);
    }

    /** Una petición comprobada. null = esa comprobación estaba apagada. */
    public void anotar(FirmaDeApp.Resultado firma, VerificadorAppCheck.Resultado appCheck, boolean rechazada) {
        AtomicLongArray c = actual;
        c.incrementAndGet(TOTAL);
        if (rechazada) c.incrementAndGet(RECHAZADAS);
        if (firma != null) {
            c.incrementAndGet(switch (firma) {
                case OK -> FIRMA_OK;
                case FALTA -> FIRMA_FALTA;
                case MALA -> FIRMA_MALA;
                case HORA -> FIRMA_HORA;
                case REPETIDA -> FIRMA_REPETIDA;
            });
        }
        if (appCheck != null) {
            c.incrementAndGet(switch (appCheck) {
                case OK -> CHECK_OK;
                case FALTA -> CHECK_FALTA;
                case MALO -> CHECK_MALO;
                case SIN_VERIFICAR -> CHECK_SIN_VERIFICAR;
            });
        }
    }

    /** Cierra la hora en curso: la guarda, la resume en el registro y empieza otra. */
    @Scheduled(cron = "0 0 * * * *")
    public synchronized void cerrarHora() {
        AtomicLongArray c = actual;
        actual = new AtomicLongArray(CAMPOS);
        Hora h = hora(desde, c);
        desde = reloj.instant().truncatedTo(ChronoUnit.HOURS);
        pasadas.addLast(h);
        while (pasadas.size() > HORAS) pasadas.removeFirst();
        if (h.total() > 0) {
            log.info("Llave de la app, última hora: {} petición(es), {} rechazada(s). Firma: {} bien, {} sin firma, "
                            + "{} mala, {} fuera de hora, {} repetida. App Check: {} bien, {} sin token, {} malo, {} sin verificar",
                    h.total(), h.rechazadas(), h.firmaOk(), h.firmaFalta(), h.firmaMala(), h.firmaHora(),
                    h.firmaRepetida(), h.appCheckOk(), h.appCheckFalta(), h.appCheckMalo(), h.appCheckSinVerificar());
        }
    }

    /** Las últimas horas cerradas y la que va en curso, de la más vieja a la más nueva. */
    public synchronized List<Hora> ultimas() {
        List<Hora> lista = new ArrayList<>(pasadas);
        lista.add(hora(desde, actual));
        return lista;
    }

    private static Hora hora(Instant desde, AtomicLongArray c) {
        return new Hora(desde, c.get(TOTAL), c.get(RECHAZADAS),
                c.get(FIRMA_OK), c.get(FIRMA_FALTA), c.get(FIRMA_MALA), c.get(FIRMA_HORA), c.get(FIRMA_REPETIDA),
                c.get(CHECK_OK), c.get(CHECK_FALTA), c.get(CHECK_MALO), c.get(CHECK_SIN_VERIFICAR));
    }
}
