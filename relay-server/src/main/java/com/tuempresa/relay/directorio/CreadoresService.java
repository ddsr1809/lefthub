package com.tuempresa.relay.directorio;

import com.tuempresa.relay.directorio.CanalesService.Cambio;
import com.tuempresa.relay.modelo.Canal;
import com.tuempresa.relay.modelo.Creador;
import com.tuempresa.relay.modelo.Dtos;
import com.tuempresa.relay.modelo.Repositorios;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Guardado de un creador, compartido por los dos caminos por los que entra:
 * el panel (AdminController) y la copia que manda producción a testing
 * (ReplicaService). Así un campo nuevo se guarda igual por los dos.
 */
@Service
public class CreadoresService {

    private final Repositorios.Creadores creadores;
    private final Repositorios.Canales canales;
    private final Repositorios.Productoras productoras;
    private final CanalesService canalesService;

    public CreadoresService(Repositorios.Creadores creadores, Repositorios.Canales canales,
                            Repositorios.Productoras productoras, CanalesService canalesService) {
        this.creadores = creadores;
        this.canales = canales;
        this.productoras = productoras;
        this.canalesService = canalesService;
    }

    /**
     * Vuelca la petición sobre el creador y lo guarda, con sus canales y las
     * productoras en las que figura.
     *
     * @return lo que hay que hacer en el hub de YouTube para que quede de
     *         acuerdo con lo guardado.
     */
    @Transactional
    public Cambio aplicar(Creador creador, Dtos.GuardarCreador peticion) {
        if (!Dtos.CATEGORIAS.contains(peticion.categoriaOtros())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Categoría no válida: " + peticion.categoriaOtros());
        }

        creador.setNombre(peticion.nombre().trim());
        creador.setCategoria(peticion.categoriaOtros());
        creador.setBio(recortar(peticion.bio(), 600));
        creador.setFotoUrl(peticion.fotoUrl());
        creador.setActivo(peticion.estaActivo());
        creador.setActualizadoEn(Instant.now());

        if (peticion.productoras() != null) {
            Set<UUID> pedidas = new LinkedHashSet<>(peticion.productoras());
            pedidas.remove(null);

            if (productoras.findAllById(pedidas).size() != pedidas.size()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Uno de los medios ya no existe.");
            }
            // Sobre el mismo conjunto: Hibernate vigila esa instancia.
            creador.getProductoras().retainAll(pedidas);
            creador.getProductoras().addAll(pedidas);
        }

        // Los canales llevan el id del creador: tiene que existir antes.
        creadores.saveAndFlush(creador);

        return canalesService.guardarDeCreador(creador.getId(), canalesPedidos(creador, peticion),
                creador.isActivo());
    }

    /**
     * La lista de canales que pide la petición. Quien todavía manda el formato
     * anterior (`conexiones`, un enlace por plataforma) solo toca el canal
     * principal de cada una.
     */
    private List<Dtos.GuardarCanal> canalesPedidos(Creador creador, Dtos.GuardarCreador peticion) {
        if (peticion.canales() != null) return peticion.canales();

        List<Canal> actuales = canales.deCreador(creador.getId());
        return CanalesService.desdeConexiones(actuales, peticion.conexionesSeguras());
    }

    /** Lo que hay que dar de baja en el hub cuando el creador se va del directorio. */
    @Transactional(readOnly = true)
    public Cambio alRetirar(Creador creador) {
        List<String> suyos = CanalesService.deYouTube(canales.deCreador(creador.getId()));
        return Cambio.de(suyos, suyos, false);
    }

    private static String recortar(String texto, int maximo) {
        if (texto == null) return null;
        return texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }
}
