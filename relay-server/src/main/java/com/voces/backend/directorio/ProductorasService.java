package com.voces.backend.directorio;

import com.voces.backend.directorio.CanalesService.Cambio;
import com.voces.backend.modelo.Canal;
import com.voces.backend.modelo.Creador;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Productora;
import com.voces.backend.modelo.Repositorios;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Guardado y retirada de una productora. Como con los creadores, lo comparten
 * el panel y la copia que manda producción a testing.
 */
@Service
public class ProductorasService {

    private final Repositorios.Productoras productoras;
    private final Repositorios.Creadores creadores;
    private final Repositorios.Canales canales;
    private final Repositorios.Publicaciones publicaciones;
    private final CanalesService canalesService;

    public ProductorasService(Repositorios.Productoras productoras, Repositorios.Creadores creadores,
                              Repositorios.Canales canales, Repositorios.Publicaciones publicaciones,
                              CanalesService canalesService) {
        this.productoras = productoras;
        this.creadores = creadores;
        this.canales = canales;
        this.publicaciones = publicaciones;
        this.canalesService = canalesService;
    }

    /**
     * Vuelca la petición sobre la productora y la guarda.
     *
     * Los canales que se guardan aquí son los propios, los que no tienen
     * creador. Que un canal de un creador sea de la productora se dice en el
     * creador, canal por canal.
     *
     * @return lo que hay que hacer en el hub de YouTube.
     */
    @Transactional
    public Cambio aplicar(Productora productora, Dtos.GuardarProductora peticion) {
        productora.setNombre(peticion.nombre().trim());
        productora.setDescripcion(recortar(peticion.descripcion(), 600));
        productora.setLogoUrl(peticion.logoUrl());
        productora.setActivo(peticion.estaActivo());
        productora.setActualizadoEn(Instant.now());

        // Quien no manda estos dos campos (el botón de reintentar, un panel
        // anterior) deja la productora como estaba en el directorio.
        if (peticion.enDirectorio() != null) productora.setEnDirectorio(peticion.enDirectorio());
        if (peticion.categoria() != null && !peticion.categoria().isBlank()) {
            if (!Dtos.CATEGORIAS.contains(peticion.categoria())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Categoría no válida: " + peticion.categoria());
            }
            productora.setCategoria(peticion.categoria());
        }

        productoras.saveAndFlush(productora);

        if (peticion.creadores() != null) {
            ponerCreadores(productora.getId(), peticion.creadores());
        }

        // Sin lista de canales se guardan los mismos que había: aun así hay
        // que pasar por aquí, porque ocultar o mostrar la productora cambia
        // qué se vigila en el hub.
        List<Dtos.GuardarCanal> pedidos = peticion.canales() != null
                ? peticion.canales()
                : canales.propiosDeProductora(productora.getId()).stream()
                        .map(CanalesService::comoPedido).toList();

        return canalesService.guardarDeProductora(productora.getId(), pedidos, productora.isActivo());
    }

    /** Deja como creadores de la productora exactamente a los pedidos. */
    private void ponerCreadores(UUID productoraId, List<UUID> pedidos) {
        Set<UUID> quedan = new HashSet<>(pedidos);
        quedan.remove(null);

        List<Creador> nuevos = creadores.findAllById(quedan);
        if (nuevos.size() != quedan.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Uno de los creadores ya no existe.");
        }

        for (Creador actual : creadores.deProductora(productoraId)) {
            if (!quedan.contains(actual.getId())) actual.getProductoras().remove(productoraId);
        }
        for (Creador nuevo : nuevos) {
            nuevo.getProductoras().add(productoraId);
        }
        creadores.flush();
    }

    /**
     * Retira la productora del directorio.
     *
     * Sus canales propios se van con ella, y con ellos lo que publicaron. Los
     * canales de sus creadores no: siguen siendo de cada creador, solo dejan
     * de estar ligados a la productora.
     *
     * @return los canales que hay que dar de baja en el hub.
     */
    @Transactional
    public Cambio retirar(Productora productora) {
        UUID id = productora.getId();

        List<Canal> propios = canales.propiosDeProductora(id);
        List<String> suyos = CanalesService.deYouTube(propios);

        if (!propios.isEmpty()) {
            publicaciones.borrarSinCreadorDe(propios.stream().map(Canal::getId).toList());
            canales.deleteAll(propios);
        }
        for (Canal ajeno : canales.deProductora(id)) {
            if (ajeno.getCreadorId() != null) ajeno.setProductoraId(null);
        }
        // Antes de borrar la productora: la base no deja un canal sin dueño.
        canales.flush();

        // Quién figura en ella y quién la sigue se va en cascada por las
        // claves foráneas.
        productoras.delete(productora);
        productoras.flush();

        return Cambio.de(suyos, List.of(), false);
    }

    private static String recortar(String texto, int maximo) {
        if (texto == null) return null;
        return texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }
}
