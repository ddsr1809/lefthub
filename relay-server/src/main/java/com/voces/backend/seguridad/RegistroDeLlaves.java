package com.voces.backend.seguridad;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Qué cuenta trae la llave de la app y cuál no: cómo llegó la última petición
 * de cada una (firma y App Check), para que el panel lo enseñe en Usuarios y
 * en Apps → Llave de la app.
 *
 * FiltroDeApp lo anota en cada petición con sesión. Escribir en la base en
 * cada petición sería caro, así que se junta en memoria y se vuelca una vez
 * por minuto, y solo lo que cambió (o lo que no se tocaba hace un rato, para
 * que "cuándo" siga al día). El panel lo ve, como mucho, un minuto tarde.
 *
 * Si el servidor se reinicia se pierde ese último minuto: no importa, la
 * siguiente petición de cada cuenta lo vuelve a anotar.
 */
@Component
public class RegistroDeLlaves {

    private static final Logger log = LoggerFactory.getLogger(RegistroDeLlaves.class);

    /** Pasado este tiempo se reescribe aunque no haya cambiado, para que llave_vista_en siga al día. */
    static final Duration REFRESCO = Duration.ofMinutes(15);
    private static final int TOPE = 100_000;

    /** Cómo llegó la última petición de una cuenta. null = esa comprobación estaba apagada. */
    record Estado(String firma, String appCheck) {}

    private static final class Entrada {
        Estado ultimo;
        Instant cuando;
        Estado guardado;
        Instant guardadoEn;
    }

    private final JdbcTemplate jdbc;
    private final Clock reloj;
    private final Map<UUID, Entrada> cuentas = new ConcurrentHashMap<>();

    @Autowired
    public RegistroDeLlaves(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    RegistroDeLlaves(JdbcTemplate jdbc, Clock reloj) {
        this.jdbc = jdbc;
        this.reloj = reloj;
    }

    /** Una petición comprobada de esa cuenta. */
    public void anotar(UUID cuenta, FirmaDeApp.Resultado firma, VerificadorAppCheck.Resultado appCheck) {
        if (cuenta == null || (firma == null && appCheck == null)) return;
        if (cuentas.size() >= TOPE) {
            log.warn("Demasiadas cuentas en el registro de llaves sin volcar; se vuelca ya");
            volcar();
            if (cuentas.size() >= TOPE) cuentas.clear();
        }
        Estado estado = new Estado(nombre(firma), nombre(appCheck));
        Instant ahora = reloj.instant();
        Entrada e = cuentas.computeIfAbsent(cuenta, k -> new Entrada());
        synchronized (e) {
            e.ultimo = estado;
            e.cuando = ahora;
        }
    }

    /** Lo que hay que escribir: lo que cambió desde la última vez, o lleva rato sin escribirse. */
    record Pendiente(UUID cuenta, Estado estado, Instant cuando) {}

    List<Pendiente> pendientes() {
        Instant ahora = reloj.instant();
        List<Pendiente> lista = new ArrayList<>();
        cuentas.forEach((cuenta, e) -> {
            synchronized (e) {
                if (e.ultimo == null) return;
                boolean cambio = !Objects.equals(e.ultimo, e.guardado);
                boolean refrescar = e.guardadoEn != null
                        && Duration.between(e.guardadoEn, ahora).compareTo(REFRESCO) >= 0
                        && e.cuando.isAfter(e.guardadoEn);
                if (cambio || refrescar) lista.add(new Pendiente(cuenta, e.ultimo, e.cuando));
            }
        });
        return lista;
    }

    void marcarGuardado(Pendiente p) {
        Entrada e = cuentas.get(p.cuenta());
        if (e == null) return;
        synchronized (e) {
            e.guardado = p.estado();
            e.guardadoEn = reloj.instant();
        }
    }

    /** Escribe en la base lo pendiente. Cada minuto. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public synchronized void volcar() {
        List<Pendiente> lista = pendientes();
        if (lista.isEmpty()) return;
        try {
            jdbc.batchUpdate(
                    "update usuarios set llave_firma = ?, llave_app_check = ?, llave_vista_en = ? where id = ?",
                    lista.stream().map(p -> new Object[]{
                            p.estado().firma(), p.estado().appCheck(), Timestamp.from(p.cuando()), p.cuenta()
                    }).toList());
            lista.forEach(this::marcarGuardado);
        } catch (RuntimeException ex) {
            // Se reintenta al minuto siguiente con lo que haya entonces.
            log.warn("No se pudo guardar el estado de la llave de {} cuenta(s): {}", lista.size(), ex.toString());
        }
        // Las cuentas ya guardadas y quietas no hace falta tenerlas en memoria.
        Instant viejo = reloj.instant().minus(REFRESCO.multipliedBy(2));
        cuentas.entrySet().removeIf(en -> {
            Entrada e = en.getValue();
            synchronized (e) {
                return Objects.equals(e.ultimo, e.guardado) && e.cuando != null && e.cuando.isBefore(viejo);
            }
        });
    }

    // -------------------------------------------------------------------------

    /** Cuántas cuentas hay en cada estado, entre las que abrieron la app en los últimos días. */
    public record Resumen(int dias, Map<String, Long> porFirma, Map<String, Long> porAppCheck,
                          long sinDatos, long activas) {}

    public Resumen resumen(int dias) {
        Timestamp desde = Timestamp.from(reloj.instant().minus(Duration.ofDays(dias)));
        Map<String, Long> porFirma = contar("llave_firma", desde);
        Map<String, Long> porAppCheck = contar("llave_app_check", desde);
        Long sinDatos = jdbc.queryForObject(
                "select count(*) from usuarios where visto_en >= ? and llave_vista_en is null", Long.class, desde);
        Long activas = jdbc.queryForObject(
                "select count(*) from usuarios where visto_en >= ?", Long.class, desde);
        return new Resumen(dias, porFirma, porAppCheck, sinDatos == null ? 0 : sinDatos, activas == null ? 0 : activas);
    }

    /** columna viene de este archivo, nunca de fuera. */
    private Map<String, Long> contar(String columna, Timestamp desde) {
        Map<String, Long> mapa = new LinkedHashMap<>();
        jdbc.query("select " + columna + " as estado, count(*) as n from usuarios"
                        + " where llave_vista_en >= ? and " + columna + " is not null group by 1 order by 2 desc",
                rs -> { mapa.put(rs.getString("estado"), rs.getLong("n")); }, desde);
        return mapa;
    }

    static String nombre(Enum<?> resultado) {
        return resultado == null ? null : resultado.name().toLowerCase(Locale.ROOT);
    }
}
