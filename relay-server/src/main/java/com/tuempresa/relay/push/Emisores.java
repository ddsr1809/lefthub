package com.tuempresa.relay.push;

import com.tuempresa.relay.modelo.Canal;
import com.tuempresa.relay.modelo.Creador;
import com.tuempresa.relay.modelo.Productora;
import com.tuempresa.relay.modelo.Publicacion;
import com.tuempresa.relay.modelo.Repositorios;
import com.tuempresa.relay.push.PushService.Emisor;
import org.springframework.stereotype.Component;

import java.util.Optional;
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
 *   · sin creador, avisa la productora sola.
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
        return resolver(canal.getCreadorId(), canal.getProductoraId(), true);
    }

    /**
     * El creador es el que quedó anotado en la publicación; la productora, la
     * que tenga hoy su canal. Si el canal ya no está en el directorio, la
     * publicación sigue siendo de su creador y solo él avisa.
     */
    public Optional<Emisor> de(Publicacion publicacion) {
        return resolver(publicacion.getCreadorId(), productoraDe(publicacion), true);
    }

    /**
     * Para el aviso de "este video cambió de lugar", que manda una persona
     * desde el panel: llega también a quienes siguen a un creador que ahora
     * está oculto, como siempre ha hecho.
     */
    public Optional<Emisor> paraAvisoManual(Publicacion publicacion) {
        return resolver(publicacion.getCreadorId(), productoraDe(publicacion), false);
    }

    private UUID productoraDe(Publicacion publicacion) {
        if (publicacion.getCanalId() == null) return null;
        return canales.findById(publicacion.getCanalId()).map(Canal::getProductoraId).orElse(null);
    }

    private Optional<Emisor> resolver(UUID creadorId, UUID productoraId, boolean soloVisible) {
        Creador creador = null;
        if (creadorId != null) {
            creador = creadores.findById(creadorId)
                    .filter(c -> c.isActivo() || !soloVisible)
                    .orElse(null);
            if (creador == null) return Optional.empty();
        }

        Productora productora = productoraId == null ? null
                : productoras.findById(productoraId).filter(Productora::isActivo).orElse(null);

        if (creador == null && productora == null) return Optional.empty();
        return Optional.of(Emisor.de(creador, productora));
    }
}
