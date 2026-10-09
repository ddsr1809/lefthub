package com.voces.backend.directorio;

import com.voces.backend.modelo.Canal;
import com.voces.backend.modelo.Creador;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Productora;
import com.voces.backend.modelo.Repositorios;
import org.springframework.data.domain.Sort;
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
 * El directorio leído de una vez: creadores, canales y productoras en tres
 * consultas, y a partir de ahí todo en memoria.
 *
 * Los tres se cruzan en cada respuesta (los canales de un creador, la
 * productora de cada canal, los creadores de una productora, con quién más
 * aparece un canal). Ir a la base por cada cruce serían decenas de consultas
 * por pantalla; el directorio es un catálogo curado a mano, del orden de
 * decenas de entradas, y cabe entero en memoria sin esfuerzo.
 */
@Service
public class Catalogo {

    private final Repositorios.Canales canales;
    private final Repositorios.Productoras productoras;
    private final Repositorios.Creadores creadores;

    public Catalogo(Repositorios.Canales canales, Repositorios.Productoras productoras,
                    Repositorios.Creadores creadores) {
        this.canales = canales;
        this.productoras = productoras;
        this.creadores = creadores;
    }

    @Transactional(readOnly = true)
    public Vista vista() {
        return new Vista(canales.todosEnOrden(), productoras.findAll(),
                creadores.findAll(Sort.by("nombre")));
    }

    /** Una foto del directorio. No toca la base: se puede usar fuera de la transacción. */
    public static final class Vista {

        private final Map<UUID, Productora> productoras = new LinkedHashMap<>();
        private final Map<UUID, Creador> creadores = new LinkedHashMap<>();
        private final Map<UUID, Canal> canales = new HashMap<>();
        private final Map<UUID, List<Canal>> porCreador = new HashMap<>();
        private final Map<UUID, List<Canal>> porProductora = new HashMap<>();
        private final Map<UUID, List<Canal>> porVinculado = new HashMap<>();

        Vista(List<Canal> enOrden, List<Productora> todas, List<Creador> porNombre) {
            todas.forEach(p -> productoras.put(p.getId(), p));
            porNombre.forEach(c -> creadores.put(c.getId(), c));

            for (Canal k : enOrden) {
                canales.put(k.getId(), k);
                if (k.getCreadorId() != null) {
                    porCreador.computeIfAbsent(k.getCreadorId(), id -> new ArrayList<>()).add(k);
                }
                if (k.getProductoraId() != null) {
                    porProductora.computeIfAbsent(k.getProductoraId(), id -> new ArrayList<>()).add(k);
                }
                for (UUID vinculado : k.getVinculados()) {
                    porVinculado.computeIfAbsent(vinculado, id -> new ArrayList<>()).add(k);
                }
            }
        }

        /** Los canales de los que el creador es dueño. */
        public List<Canal> canalesDe(UUID creadorId) {
            return porCreador.getOrDefault(creadorId, List.of());
        }

        /** Los canales de otros (de una productora, de otro creador) en los que aparece. */
        public List<Canal> compartidosCon(UUID creadorId) {
            return porVinculado.getOrDefault(creadorId, List.of());
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

        public Creador creador(UUID id) {
            return id != null ? creadores.get(id) : null;
        }

        /** El creador, solo si existe y está visible. */
        public Creador creadorVisible(UUID id) {
            Creador c = creador(id);
            return c != null && c.isActivo() ? c : null;
        }

        /** Todos los creadores, por orden alfabético. */
        public Collection<Creador> creadores() {
            return creadores.values();
        }

        /**
         * El canal tiene a su dueño visible: su creador o, si no tiene, su
         * productora. Con el dueño oculto no se vigila ni se enseña, tampoco
         * a través de los demás creadores con los que aparece.
         */
        public boolean vivo(Canal k) {
            return k.getCreadorId() != null
                    ? creadorVisible(k.getCreadorId()) != null
                    : productoraVisible(k.getProductoraId()) != null;
        }

        // ---------------------------------------------------------------------
        // Lo que leen las apps: lo que está oculto no asoma por ningún lado
        // ---------------------------------------------------------------------

        /** La ficha de un creador visible. */
        public Dtos.CreadorDto ficha(Creador c) {
            List<Canal> suyos = new ArrayList<>(canalesDe(c.getId()));
            compartidosCon(c.getId()).stream().filter(this::vivo).forEach(suyos::add);

            return new Dtos.CreadorDto(c.getId(), c.getNombre(), c.getCategoria(),
                    c.getBio(), c.getFotoUrl(),
                    Dtos.ConexionDto.principales(suyos),
                    suyos.stream().map(this::canalPublico).toList(),
                    c.getProductoras().stream().filter(id -> productoraVisible(id) != null).toList(),
                    null);
        }

        /** La ficha de una productora visible. */
        public Dtos.ProductoraDto ficha(Productora p) {
            List<UUID> figuran = creadores.values().stream()
                    .filter(c -> c.isActivo() && c.getProductoras().contains(p.getId()))
                    .map(Creador::getId)
                    .toList();

            return new Dtos.ProductoraDto(p.getId(), p.getNombre(), p.getDescripcion(),
                    p.getLogoUrl(), canalesVisiblesDe(p).stream().map(this::canalPublico).toList(),
                    figuran, p.isEnDirectorio(), p.getCategoria());
        }

        /**
         * La productora como una fila más del listado de creadores, con su
         * mismo id. Es lo que ve una versión de la app que no conoce las
         * productoras: un creador llamado así, con esos canales, al que se
         * puede seguir.
         */
        public Dtos.CreadorDto comoCreador(Productora p) {
            List<Canal> suyos = canalesVisiblesDe(p);

            return new Dtos.CreadorDto(p.getId(), p.getNombre(), p.getCategoria(),
                    p.getDescripcion(), p.getLogoUrl(),
                    Dtos.ConexionDto.principales(suyos),
                    suyos.stream().map(this::canalPublico).toList(),
                    List.of(), true);
        }

        /** Sus canales propios primero, y después los de sus creadores visibles. */
        private List<Canal> canalesVisiblesDe(Productora p) {
            List<Canal> todos = canalesDeProductora(p.getId()).stream().filter(this::vivo).toList();

            List<Canal> enOrden = new ArrayList<>();
            todos.stream().filter(k -> k.getCreadorId() == null).forEach(enOrden::add);
            todos.stream().filter(k -> k.getCreadorId() != null).forEach(enOrden::add);
            return enOrden;
        }

        private Dtos.CanalDto canalPublico(Canal k) {
            Productora p = productoraVisible(k.getProductoraId());
            List<UUID> conQuien = k.getVinculados().stream()
                    .filter(id -> creadorVisible(id) != null)
                    .toList();

            return Dtos.CanalDto.de(k, p != null ? p.getId() : null, conQuien);
        }
    }
}
