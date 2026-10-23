package com.voces.backend.seguridad;

import com.voces.backend.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Borra los datos de conexión de quien lleva meses sin abrir la app.
 *
 * La IP, la compañía de internet y el agente describen el último acceso, y
 * sirven para ver de dónde se usa la app y detectar bots. Pasado un tiempo ya
 * no describen nada útil y siguen siendo datos personales. Se conservan el
 * país (no identifica a nadie) y el indicio de bot.
 *
 * Una vez al día, a las 4:30 de Hermosillo. Los días salen de
 * relay.datos.dias-ip (DATOS_DIAS_IP, 90 por omisión); 0 lo apaga.
 */
@Component
public class LimpiezaDeAccesos {

    private static final Logger log = LoggerFactory.getLogger(LimpiezaDeAccesos.class);

    private final JdbcTemplate jdbc;
    private final RelayProperties config;

    public LimpiezaDeAccesos(JdbcTemplate jdbc, RelayProperties config) {
        this.jdbc = jdbc;
        this.config = config;
    }

    @Scheduled(cron = "0 30 4 * * *", zone = "America/Hermosillo")
    public void limpiar() {
        int dias = config.datos().diasIp();
        if (dias <= 0) return;

        Instant limite = Instant.now().minus(Duration.ofDays(dias));
        int borradas = jdbc.update("""
                update usuarios
                   set ip = null, ip_huella = null, red = null, asn = null, agente = null
                 where visto_en < ?
                   and (ip is not null or red is not null or agente is not null)
                """, Timestamp.from(limite));
        if (borradas > 0) {
            log.info("Datos de conexión borrados de {} cuenta(s) sin actividad en {} días", borradas, dias);
        }
    }
}
