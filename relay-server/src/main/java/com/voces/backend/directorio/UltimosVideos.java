package com.voces.backend.directorio;

import com.voces.backend.modelo.Canal;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Repositorios;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Cuándo publicó por última vez cada canal visible, para ordenar las listas
 * de la app: arriba quien tiene el video más nuevo.
 *
 * Cada app lo pide al refrescar el directorio. La consulta agrupa todas las
 * publicaciones, así que la respuesta se guarda un minuto y la comparten
 * todas: con muchos teléfonos pidiéndola a la vez, la base la calcula una
 * vez por minuto y no una por teléfono.
 */
@Service
public class UltimosVideos {

    static final Duration VIGENCIA = Duration.ofMinutes(1);

    private final Repositorios.Publicaciones publicaciones;
    private final Catalogo catalogo;

    private volatile List<Dtos.UltimoVideo> guardada;
    private volatile Instant guardadaEn = Instant.EPOCH;

    public UltimosVideos(Repositorios.Publicaciones publicaciones, Catalogo catalogo) {
        this.publicaciones = publicaciones;
        this.catalogo = catalogo;
    }

    @Transactional(readOnly = true)
    public List<Dtos.UltimoVideo> lista() {
        List<Dtos.UltimoVideo> actual = guardada;
        if (actual != null && Instant.now().isBefore(guardadaEn.plus(VIGENCIA))) return actual;

        Catalogo.Vista vista = catalogo.vista();
        List<Dtos.UltimoVideo> nueva = publicaciones.ultimoVideoPorCanal().stream()
                .map(f -> new Dtos.UltimoVideo((UUID) f[0], (Instant) f[1]))
                .filter(u -> {
                    // Solo canales que la app enseña: con su dueño visible.
                    Canal canal = vista.canal(u.canalId());
                    return canal != null && vista.vivo(canal);
                })
                .sorted(Comparator.comparing(Dtos.UltimoVideo::publicadoEn).reversed())
                .toList();

        guardada = nueva;
        guardadaEn = Instant.now();
        return nueva;
    }
}
