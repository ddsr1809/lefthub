package com.voces.backend.seguridad;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Lo que dice la base sobre la cuenta de un token: si existe, si es
 * administradora y desde cuándo valen sus sesiones.
 *
 * FiltroJwt lo pregunta en cada petición con token. Para no ir a la base cada
 * vez, se recuerda unos segundos: un cambio (cerrar sesiones, quitar el rol)
 * tarda como mucho eso en notarse, o nada si quien lo cambia llama a
 * {@link #olvidar}. Se lee con una consulta mínima, sin cargar la cuenta con
 * sus favoritos.
 */
@Service
public class EstadoDeSesiones {

    static final Duration MEMORIA = Duration.ofSeconds(30);
    private static final int TOPE = 50_000;

    /** Lo que hace falta de la cuenta para aceptar un token. */
    public record Estado(boolean esAdmin, Instant sesionesDesde) {

        /**
         * Si un token emitido en ese momento ya no vale. El token guarda la
         * hora en segundos; la comparación también, para que una sesión
         * abierta justo después de cerrar las demás no caiga con ellas.
         */
        public boolean revoca(Instant emitido) {
            if (sesionesDesde == null) return false;
            if (emitido == null) return true;
            return emitido.isBefore(sesionesDesde.truncatedTo(ChronoUnit.SECONDS));
        }
    }

    private record Recordado(Optional<Estado> estado, Instant leido) {}

    private final Function<UUID, Optional<Estado>> leer;
    private final Clock reloj;
    private final Map<UUID, Recordado> memoria = new ConcurrentHashMap<>();

    @Autowired
    public EstadoDeSesiones(JdbcTemplate jdbc) {
        this(id -> deLaBase(jdbc, id), Clock.systemUTC());
    }

    EstadoDeSesiones(Function<UUID, Optional<Estado>> leer, Clock reloj) {
        this.leer = leer;
        this.reloj = reloj;
    }

    /** Vacío si la cuenta ya no existe. */
    public Optional<Estado> de(UUID usuarioId) {
        Instant ahora = reloj.instant();
        Recordado r = memoria.get(usuarioId);
        if (r != null && Duration.between(r.leido(), ahora).compareTo(MEMORIA) < 0) {
            return r.estado();
        }
        Optional<Estado> estado = leer.apply(usuarioId);
        if (memoria.size() >= TOPE) memoria.clear();
        memoria.put(usuarioId, new Recordado(estado, ahora));
        return estado;
    }

    /** Que la próxima petición de esa cuenta lea la base: acaba de cambiar. */
    public void olvidar(UUID usuarioId) {
        memoria.remove(usuarioId);
    }

    private static Optional<Estado> deLaBase(JdbcTemplate jdbc, UUID id) {
        List<Estado> filas = jdbc.query(
                "select es_admin, sesiones_desde from usuarios where id = ?",
                (rs, n) -> {
                    Timestamp desde = rs.getTimestamp("sesiones_desde");
                    return new Estado(rs.getBoolean("es_admin"), desde == null ? null : desde.toInstant());
                },
                id);
        return filas.stream().findFirst();
    }
}
