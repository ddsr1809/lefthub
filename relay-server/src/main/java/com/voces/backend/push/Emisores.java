package com.voces.backend.push;

import com.voces.backend.modelo.Canal;
import com.voces.backend.modelo.Creador;
import com.voces.backend.modelo.Productora;
import com.voces.backend.modelo.Publicacion;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.push.PushService.Emisor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Decide en nombre de quién sale el aviso de un canal o de una publicación.
 *
 * La regla, en un solo sitio para que el webhook, el vigilante de directos y
 * el panel la apliquen igual:
 *
 *   · si el canal tiene creador, el creador manda: oculto él, no se avisa a
 *     nadie, tampoco a quienes siguen a la productora;
 *   · la productora se suma si el canal le pertenece y está visible;
 *   · sin creador, firma la productora;
 *   · los demás creadores con los que aparece el canal se suman si están
 *     visibles, pero no firman ni bastan: sin dueño visible no avisa nadie.
 *
 * Devuelve vacío cuando no queda nadie a quien avisar.
 */
@Component
public class Emisores {

    private final Repositorios.Creadores creadores;
    private final Repositorios.Productoras productoras;
    private final Repositorios.Canales canales;

    public Emisores(Repositorios.Creadores creadores, Repositorios.Productoras productoras,
                    Repositorios.Canales canales) {
        this.creadores = creadores;
        this.productoras = productoras;
        this.canales = canales;
    }

    public Optional<Emisor> de(Canal canal) {
        return resolver(canal.getCreadorId(), canal.getProductoraId(), canal.getVinculados(), true);
    }

    /**
     * El creador es el que quedó anotado en la publicación; la productora y
     * los demás creadores, los que tenga hoy su canal. Si el canal ya no está
     * en el directorio, la publicación sigue siendo de su creador y solo él
     * avisa.
     */
    public Optional<Emisor> de(Publicacion publicacion) {
        Canal canal = canalDe(publicacion);
        return resolver(publicacion.getCreadorId(),
                canal != null ? canal.getProductoraId() : null,
                canal != null ? canal.getVinculados() : Set.of(), true);
    }

    /**
     * Para el aviso de "este video cambió de lugar", que manda una persona
     * desde el panel: llega también a quienes siguen a un creador que ahora
     * está oculto, como siempre ha hecho.
     */
    public Optional<Emisor> paraAvisoManual(Publicacion publicacion) {
        Canal canal = canalDe(publicacion);
        return resolver(publicacion.getCreadorId(),
                canal != null ? canal.getProductoraId() : null,
                canal != null ? canal.getVinculados() : Set.of(), false);
    }

    private Canal canalDe(Publicacion publicacion) {
        if (publicacion.getCanalId() == null) return null;
        return canales.findById(publicacion.getCanalId()).orElse(null);
    }

    private Optional<Emisor> resolver(UUID creadorId, UUID productoraId, Set<UUID> vinculados,
                                      boolean soloVisible) {
        Creador creador = null;
        if (creadorId != null) {
            creador = creadores.findById(creadorId)
                    .filter(c -> c.isActivo() || !soloVisible)
                    .orElse(null);
            if (creador == null) return Optional.empty();
        }

        Productora productora = productoraId == null ? null
                : productoras.findById(productoraId).filter(Productora::isActivo).orElse(null);

        // Sin dueño visible no avisa nadie, tampoco los demás creadores.
        if (creador == null && productora == null) return Optional.empty();

        List<UUID> tambien = vinculados.isEmpty() ? List.of()
                : creadores.findAllById(vinculados).stream()
                        .filter(Creador::isActivo)
                        .map(Creador::getId)
                        .filter(id -> !id.equals(creadorId))
                        .sorted()
                        .toList();

        return Optional.of(Emisor.de(creador, productora, tambien));
    }
}
