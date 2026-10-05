package com.tuempresa.relay.directorio;

import com.tuempresa.relay.modelo.Canal;
import com.tuempresa.relay.modelo.Creador;
import com.tuempresa.relay.modelo.Dtos;
import com.tuempresa.relay.modelo.Productora;
import com.tuempresa.relay.modelo.Repositorios;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * El directorio leído de una vez: canales y productoras en dos consultas, y a
 * partir de ahí todo en memoria.
 *
 * Creadores, canales y productoras se cruzan en cada respuesta (los canales de
 * un creador, la productora de cada canal, los creadores de una productora).
 * Ir a la base por cada cruce serían decenas de consultas por pantalla; el
 * directorio es un catálogo curado a mano, del orden de decenas de entradas, y
 * cabe entero en memoria sin esfuerzo.
 */
@Service
public class Catalogo {

    private final Repositorios.Canales canales;
    private final Repositorios.Productoras productoras;

    public Catalogo(Repositorios.Canales canales, Repositorios.Productoras productoras) {
        this.canales = canales;
        this.productoras = productoras;
    }

    @Transactional(readOnly = true)
    public Vista vista() {
        return new Vista(canales.todosEnOrden(), productoras.findAll());
    }

    /** Una foto del directorio. No toca la base: se puede usar fuera de la transacción. */
    public static final class Vista {

        private final Map<UUID, Productora> productoras = new LinkedHashMap<>();
        private final Map<UUID, Canal> canales = new HashMap<>();
        private final Map<UUID, List<Canal>> porCreador = new HashMap<>();
        private final Map<UUID, List<Canal>> porProductora = new HashMap<>();

        Vista(List<Canal> enOrden, List<Productora> todas) {
            todas.forEach(p -> productoras.put(p.getId(), p));

            for (Canal k : enOrden) {
                canales.put(k.getId(), k);
                if (k.getCreadorId() != null) {
                    porCreador.computeIfAbsent(k.getCreadorId(), id -> new ArrayList<>()).add(k);
                }
                if (k.getProductoraId() != null) {
                    porProductora.computeIfAbsent(k.getProductoraId(), id -> new ArrayList<>()).add(k);
                }
            }
        }

        public List<Canal> canalesDe(UUID creadorId) {
            return porCreador.getOrDefault(creadorId, List.of());
        }

        /** Todos los canales que pertenecen a la productora, con creador o sin él. */
        public List<Canal> canalesDeProductora(UUID productoraId) {
            return porProductora.getOrDefault(productoraId, List.of());
        }

        public Canal canal(UUID id) {
            return id != null ? canales.get(id) : null;
        }

        public Productora productora(UUID id) {
            return id != null ? productoras.get(id) : null;
        }

        /** La productora, solo si existe y está visible. */
        public Productora productoraVisible(UUID id) {
            Productora p = productora(id);
            return p != null && p.isActivo() ? p : null;
        }

        public Collection<Productora> productoras() {
            return productoras.values();
        }

        // ---------------------------------------------------------------------
        // Lo que leen las apps: una productora oculta no asoma por ningún lado
        // ---------------------------------------------------------------------

        public Dtos.CreadorDto creador(Creador c) {
            List<Canal> suyos = canalesDe(c.getId());

            return new Dtos.CreadorDto(c.getId(), c.getNombre(), c.getCategoria(),
                    c.getBio(), c.getFotoUrl(),
                    Dtos.ConexionDto.principales(suyos),
                    suyos.stream().map(this::canalPublico).toList(),
                    c.getProductoras().stream().filter(id -> productoraVisible(id) != null).toList());
        }

        /**
         * @param visibles los creadores visibles, por id. De una productora
         *                 solo se enseñan los canales y creadores que están en
         *                 este mapa, además de sus canales propios.
         */
        public Dtos.ProductoraDto productora(Productora p, Map<UUID, Creador> visibles) {
            List<Dtos.CanalDto> suyos = canalesDeProductora(p.getId()).stream()
                    .filter(k -> k.getCreadorId() == null || visibles.containsKey(k.getCreadorId()))
                    .map(this::canalPublico)
                    .toList();

            List<UUID> figuran = visibles.values().stream()
                    .filter(c -> c.getProductoras().contains(p.getId()))
                    .map(Creador::getId)
                    .toList();

            return new Dtos.ProductoraDto(p.getId(), p.getNombre(), p.getDescripcion(),
                    p.getLogoUrl(), suyos, figuran);
        }

        private Dtos.CanalDto canalPublico(Canal k) {
            Productora p = productoraVisible(k.getProductoraId());
            return Dtos.CanalDto.de(k, p != null ? p.getId() : null);
        }
    }
}
