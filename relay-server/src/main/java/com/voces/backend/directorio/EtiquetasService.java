package com.voces.backend.directorio;

import com.voces.backend.modelo.Canal;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Etiqueta;
import com.voces.backend.modelo.Repositorios;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Las etiquetas del directorio: crearlas, encenderlas y decir quién las lleva.
 *
 * Sustituyen a los temas fijos. Dos reglas las gobiernan:
 *
 *   · una etiqueta nace apagada, y apagada no llega a la app;
 *   · la app recibe solo las encendidas y, de cada una, solo lo que está
 *     visible en el directorio. Con cero etiquetas encendidas la app no
 *     enseña ningún filtro.
 */
@Service
public class EtiquetasService {

    static final String CREADOR = "creador";
    static final String PRODUCTORA = "productora";
    static final String CANAL = "canal";

    static final int LARGO_NOMBRE = 30;

    private final Repositorios.Etiquetas etiquetas;
    private final Repositorios.Creadores creadores;
    private final Repositorios.Productoras productoras;
    private final Repositorios.Canales canales;
    private final Catalogo catalogo;

    public EtiquetasService(Repositorios.Etiquetas etiquetas, Repositorios.Creadores creadores,
                            Repositorios.Productoras productoras, Repositorios.Canales canales,
                            Catalogo catalogo) {
        this.etiquetas = etiquetas;
        this.creadores = creadores;
        this.productoras = productoras;
        this.canales = canales;
        this.catalogo = catalogo;
    }

    // -------------------------------------------------------------------------
    // Lo que lee la app
    // -------------------------------------------------------------------------

    /** Las etiquetas encendidas, cada una con lo visible que la lleva. */
    @Transactional(readOnly = true)
    public List<Dtos.EtiquetaDto> paraLaApp() {
        List<Etiqueta> encendidas = etiquetas.findByActivaTrueOrderByOrdenAscNombreAsc();
        if (encendidas.isEmpty()) return List.of();

        Catalogo.Vista vista = catalogo.vista();

        return encendidas.stream()
                .map(e -> new Dtos.EtiquetaDto(e.getId(), e.getNombre(),
                        e.getCreadores().stream().filter(id -> vista.creadorVisible(id) != null).toList(),
                        e.getProductoras().stream().filter(id -> vista.productoraVisible(id) != null).toList(),
                        e.getCanales().stream().filter(id -> {
                            Canal canal = vista.canal(id);
                            return canal != null && vista.vivo(canal);
                        }).toList()))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Lo que maneja el panel
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Dtos.EtiquetaAdminDto> todas() {
        return etiquetas.findAllByOrderByOrdenAscNombreAsc().stream().map(EtiquetasService::aAdmin).toList();
    }

    /**
     * Crea una etiqueta o cambia una que ya existe. Lo que no viene en la
     * petición no se toca; una nueva nace apagada salvo que se diga otra cosa.
     */
    @Transactional
    public Dtos.EtiquetaAdminDto guardar(Dtos.GuardarEtiqueta peticion) {
        String nombre = limpiar(peticion.nombre());

        Etiqueta etiqueta;
        if (peticion.id() != null) {
            etiqueta = etiquetas.findById(peticion.id()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Esa etiqueta ya no existe."));
        } else {
            etiqueta = new Etiqueta();
            // Al final de la lista, hasta que alguien la mueva.
            etiqueta.setOrden(etiquetas.findAll().stream().mapToInt(Etiqueta::getOrden).max().orElse(-1) + 1);
        }

        if (nombre != null || peticion.id() == null) {
            if (nombre == null || nombre.length() < 2) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La etiqueta necesita un nombre de al menos 2 letras.");
            }
            if (nombre.length() > LARGO_NOMBRE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El nombre de la etiqueta no puede pasar de " + LARGO_NOMBRE + " letras.");
            }
            UUID propia = etiqueta.getId();
            boolean repetida = etiquetas.findByNombreIgnoreCase(nombre).stream()
                    .anyMatch(otra -> !otra.getId().equals(propia));
            if (repetida) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ya hay una etiqueta que se llama así.");
            }
            etiqueta.setNombre(nombre);
        }

        if (peticion.activa() != null) etiqueta.setActiva(peticion.activa());
        if (peticion.orden() != null) etiqueta.setOrden(peticion.orden());

        if (peticion.creadores() != null) poner(etiqueta.getCreadores(), peticion.creadores(), creadores);
        if (peticion.productoras() != null) poner(etiqueta.getProductoras(), peticion.productoras(), productoras);
        if (peticion.canales() != null) poner(etiqueta.getCanales(), peticion.canales(), canales);

        return aAdmin(etiquetas.saveAndFlush(etiqueta));
    }

    @Transactional
    public void borrar(UUID id) {
        Etiqueta etiqueta = etiquetas.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Esa etiqueta ya no existe."));
        // Quién la llevaba se va en cascada; ningún creador, medio ni canal se borra.
        etiquetas.delete(etiqueta);
    }

    /**
     * Deja a un creador, a un medio o a un canal con exactamente estas
     * etiquetas: se le ponen las que falten y se le quitan las demás.
     *
     * @return las etiquetas que lleva al terminar
     */
    @Transactional
    public List<UUID> asignar(String tipo, UUID id, Collection<UUID> pedidas) {
        Function<Etiqueta, Set<UUID>> lista = switch (tipo == null ? "" : tipo) {
            case CREADOR -> Etiqueta::getCreadores;
            case PRODUCTORA -> Etiqueta::getProductoras;
            case CANAL -> Etiqueta::getCanales;
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Las etiquetas se ponen a un creador, a una productora o a un canal.");
        };
        JpaRepository<?, UUID> donde = switch (tipo) {
            case CREADOR -> creadores;
            case PRODUCTORA -> productoras;
            default -> canales;
        };
        if (!donde.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Eso ya no está en el directorio.");
        }

        Set<UUID> quiere = new LinkedHashSet<>(pedidas == null ? List.of() : pedidas);
        quiere.remove(null);

        List<UUID> queda = new java.util.ArrayList<>();
        for (Etiqueta etiqueta : etiquetas.findAllByOrderByOrdenAscNombreAsc()) {
            Set<UUID> miembros = lista.apply(etiqueta);
            if (quiere.contains(etiqueta.getId())) {
                miembros.add(id);
                queda.add(etiqueta.getId());
            } else {
                miembros.remove(id);
            }
        }
        etiquetas.flush();
        return queda;
    }

    // -------------------------------------------------------------------------

    /** Sustituye la lista por la pedida, dejando fuera lo que ya no exista. */
    private static void poner(Set<UUID> lista, Collection<UUID> pedidos, JpaRepository<?, UUID> donde) {
        Set<UUID> existentes = new LinkedHashSet<>();
        for (UUID id : pedidos) {
            if (id != null && donde.existsById(id)) existentes.add(id);
        }
        // Sobre el mismo conjunto: Hibernate vigila esa instancia.
        lista.retainAll(existentes);
        lista.addAll(existentes);
    }

    /** Sin espacios de sobra; null si no vino nombre. */
    static String limpiar(String nombre) {
        if (nombre == null) return null;
        return nombre.trim().replaceAll("\\s+", " ");
    }

    private static Dtos.EtiquetaAdminDto aAdmin(Etiqueta e) {
        return new Dtos.EtiquetaAdminDto(e.getId(), e.getNombre(), e.isActiva(), e.getOrden(),
                List.copyOf(e.getCreadores()), List.copyOf(e.getProductoras()), List.copyOf(e.getCanales()));
    }
}
