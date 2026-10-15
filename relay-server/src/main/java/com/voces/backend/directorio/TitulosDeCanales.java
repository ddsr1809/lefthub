package com.voces.backend.directorio;

import com.voces.backend.config.RelayProperties;
import com.voces.backend.modelo.Canal;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.youtube.YouTubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Llena el nombre que tiene cada canal en YouTube ("Claudia Sheinbaum Clips").
 *
 * La app lo enseña primero en la lista de canales, arriba del nombre de su
 * creador. Nadie lo escribe a mano: cada diez minutos se buscan los canales
 * que todavía no lo tienen (los recién agregados) y los que no se revisan
 * desde hace una semana (por si el canal cambió de nombre), y se le pregunta
 * a YouTube por todos juntos. Cuesta 1 unidad de cuota por cada 50 canales, y
 * nada cuando no hay ninguno pendiente.
 *
 * Sin clave de la Data API no hace nada: la app usa entonces la etiqueta que
 * se le haya puesto al canal en el panel, o su @usuario.
 */
@Service
public class TitulosDeCanales {

    private static final Logger log = LoggerFactory.getLogger(TitulosDeCanales.class);

    /** Cada cuánto se vuelve a preguntar por un nombre que ya se sabe. */
    static final Duration REVISAR_CADA = Duration.ofDays(7);

    /** Cuántos canales como mucho en una pasada: sobra para el directorio entero. */
    static final int POR_PASADA = 500;

    private final Repositorios.Canales canales;
    private final YouTubeClient youtube;
    private final RelayProperties config;

    public TitulosDeCanales(Repositorios.Canales canales, YouTubeClient youtube,
                            RelayProperties config) {
        this.canales = canales;
        this.youtube = youtube;
        this.config = config;
    }

    @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES, initialDelay = 1)
    public void programada() {
        try {
            completar();
        } catch (RuntimeException e) {
            log.warn("No se pudieron revisar los nombres de los canales: {}", e.getMessage());
        }
    }

    /** @return cuántos canales quedaron con su nombre al día; -1 si YouTube no contestó. */
    @Transactional
    public int completar() {
        if (config.youtube().apiKey().isBlank()) return 0;

        Instant ahora = Instant.now();
        List<Canal> pendientes = canales.sinTituloReciente(ahora.minus(REVISAR_CADA),
                PageRequest.of(0, POR_PASADA));
        if (pendientes.isEmpty()) return 0;

        Map<String, String> titulos = youtube.titulosDeCanales(
                pendientes.stream().map(Canal::getChannelId).distinct().toList());
        if (titulos == null) return -1;

        for (Canal canal : pendientes) {
            String titulo = titulos.get(canal.getChannelId());
            // Si YouTube no lo conoce (borrado, privado) se queda el nombre que
            // tenía, y se vuelve a preguntar dentro de una semana.
            if (titulo != null) canal.setTitulo(recortar(titulo));
            canal.setTituloEn(ahora);
        }
        // Guardado explícito: la revisión programada llama a este método desde
        // la misma clase, así que su @Transactional no aplica y los canales
        // llegan aquí ya desconectados de la base. Sin esto el nombre nunca se
        // guardaba y cada diez minutos se volvía a preguntar por los mismos.
        canales.saveAll(pendientes);
        log.info("Nombres de canales revisados: {} ({} con nombre)", pendientes.size(), titulos.size());
        return pendientes.size();
    }

    private static String recortar(String titulo) {
        return titulo.length() > 200 ? titulo.substring(0, 200) : titulo;
    }
}
