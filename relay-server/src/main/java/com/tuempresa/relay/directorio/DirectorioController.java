package com.tuempresa.relay.directorio;

import com.tuempresa.relay.config.SeguridadConfig.Sesion;
import com.tuempresa.relay.modelo.*;
import com.tuempresa.relay.push.PushService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lo que consumen las apps de Android e iOS.
 *
 * Antes leían Firestore directamente y recibían actualizaciones en vivo. Ahora
 * preguntan aquí. En la práctica se nota poco: la novedad llega por
 * notificación push, y la app refresca al abrirse o al volver del segundo
 * plano. El feed no es una pantalla que la gente mire fijamente esperando que
 * cambie.
 *
 * Todas las rutas exigen sesión, aunque sea anónima.
 */
@RestController
@RequestMapping("/api")
public class DirectorioController {

    /** Para la consulta del feed cuando una de sus dos listas no tiene nada. */
    private static final UUID NINGUNO = new UUID(0L, 0L);

    private final Repositorios.Creadores creadores;
    private final Repositorios.Productoras productoras;
    private final Repositorios.Publicaciones publicaciones;
    private final Repositorios.Usuarios usuarios;
    private final Catalogo catalogo;

    public DirectorioController(Repositorios.Creadores creadores,
                                Repositorios.Productoras productoras,
                                Repositorios.Publicaciones publicaciones,
                                Repositorios.Usuarios usuarios,
                                Catalogo catalogo) {
        this.creadores = creadores;
        this.productoras = productoras;
        this.publicaciones = publicaciones;
        this.usuarios = usuarios;
        this.catalogo = catalogo;
    }

    // -------------------------------------------------------------------------
    // Directorio
    // -------------------------------------------------------------------------

    /**
     * El directorio completo de creadores activos.
     *
     * Sin paginar a propósito: es un catálogo curado a mano, del orden de
     * decenas de entradas. Paginar aquí añadiría complejidad a las dos apps
     * para resolver un problema que no existe. Si algún día pasa de unos
     * cientos, esto es lo primero que hay que cambiar.
     */
    @GetMapping("/creadores")
    @Transactional(readOnly = true)
    public List<Dtos.CreadorDto> listar(@RequestParam(required = false) String categoria) {
        List<Creador> lista = (categoria == null || categoria.isBlank() || "todos".equals(categoria))
                ? creadores.findByActivoTrueOrderByNombreAsc()
                : creadores.findByActivoTrueAndCategoriaOrderByNombreAsc(categoria);

        Catalogo.Vista vista = catalogo.vista();
        return lista.stream().map(vista::creador).toList();
    }

    @GetMapping("/creadores/{id}")
    @Transactional(readOnly = true)
    public Dtos.CreadorDto uno(@PathVariable UUID id) {
        Creador creador = creadores.findById(id)
                .filter(Creador::isActivo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ese creador ya no está en el directorio."));

        return catalogo.vista().creador(creador);
    }

    // -------------------------------------------------------------------------
    // Productoras
    // -------------------------------------------------------------------------

    /**
     * Las productoras visibles, cada una con los canales que le pertenecen y
     * los ids de los creadores que figuran en ella. Los datos de cada creador
     * no se repiten aquí: la app ya los tiene de /creadores.
     */
    @GetMapping("/productoras")
    @Transactional(readOnly = true)
    public List<Dtos.ProductoraDto> productoras() {
        Catalogo.Vista vista = catalogo.vista();
        Map<UUID, Creador> visibles = creadoresVisibles();

        return productoras.findByActivoTrueOrderByNombreAsc().stream()
                .map(p -> vista.productora(p, visibles))
                .toList();
    }

    @GetMapping("/productoras/{id}")
    @Transactional(readOnly = true)
    public Dtos.ProductoraDto productora(@PathVariable UUID id) {
        Productora productora = productoras.findById(id)
                .filter(Productora::isActivo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Esa productora ya no está en el directorio."));

        return catalogo.vista().productora(productora, creadoresVisibles());
    }

    // -------------------------------------------------------------------------
    // Feed
    // -------------------------------------------------------------------------

    /**
     * Novedades de los creadores y las productoras que sigue el usuario.
     *
     * Nada de recomendaciones ni scroll infinito: solo lo que publicaron las
     * personas que eligió, en orden de tiempo. Esa previsibilidad es la
     * propuesta de valor entera.
     *
     * De una productora entra lo publicado en los canales que le pertenecen,
     * no todo lo de sus creadores: seguir a la casa no es seguir a cada
     * persona que trabaja con ella.
     */
    @GetMapping("/publicaciones")
    @Transactional(readOnly = true)
    public List<Dtos.PublicacionDto> feed(@RequestParam(defaultValue = "50") int limite) {
        Usuario usuario = usuarioActual();
        Catalogo.Vista vista = catalogo.vista();

        Collection<UUID> deCreadores = usuario.getFavoritos();
        List<UUID> deCanales = canalesSeguidos(usuario, vista);

        if (deCreadores.isEmpty() && deCanales.isEmpty()) {
            return List.of();
        }

        List<Publicacion> lista = publicaciones.delFeed(
                deCreadores.isEmpty() ? List.of(NINGUNO) : deCreadores,
                deCanales.isEmpty() ? List.of(NINGUNO) : deCanales,
                PageRequest.of(0, Math.min(limite, 100)));

        return aDtos(lista, vista, creadores);
    }

    /**
     * Los canales de las productoras visibles que sigue la persona. Los de un
     * creador oculto no cuentan: es lo mismo que enseña la ficha de la
     * productora.
     */
    private List<UUID> canalesSeguidos(Usuario usuario, Catalogo.Vista vista) {
        List<Canal> candidatos = new ArrayList<>();
        for (UUID productoraId : usuario.getProductorasSeguidas()) {
            if (vista.productoraVisible(productoraId) == null) continue;
            candidatos.addAll(vista.canalesDeProductora(productoraId));
        }
        if (candidatos.isEmpty()) return List.of();

        Set<UUID> ocultos = new HashSet<>();
        creadores.findAllById(candidatos.stream()
                        .map(Canal::getCreadorId).filter(id -> id != null).distinct().toList())
                .forEach(c -> { if (!c.isActivo()) ocultos.add(c.getId()); });

        return candidatos.stream()
                .filter(k -> k.getCreadorId() == null || !ocultos.contains(k.getCreadorId()))
                .map(Canal::getId)
                .toList();
    }

    /**
     * Pone a cada publicación el nombre de quien la publicó y, si su canal es
     * de una productora visible, cuál. Una sola consulta para los nombres, en
     * vez de una por publicación.
     */
    static List<Dtos.PublicacionDto> aDtos(List<Publicacion> lista, Catalogo.Vista vista,
                                           Repositorios.Creadores creadores) {
        List<UUID> ids = lista.stream()
                .map(Publicacion::getCreadorId)
                .filter(id -> id != null)
                .distinct()
                .toList();

        Map<UUID, String> nombres = new HashMap<>();
        creadores.findAllById(ids).forEach(c -> nombres.put(c.getId(), c.getNombre()));

        return lista.stream().map(p -> {
            Canal canal = vista.canal(p.getCanalId());
            Productora productora = canal != null
                    ? vista.productoraVisible(canal.getProductoraId()) : null;

            String nombre = p.getCreadorId() != null
                    ? nombres.get(p.getCreadorId())
                    : (productora != null ? productora.getNombre() : null);

            return Dtos.PublicacionDto.de(p, nombre, productora);
        }).toList();
    }

    // -------------------------------------------------------------------------
    // Perfil y favoritos
    // -------------------------------------------------------------------------

    @GetMapping("/perfil")
    @Transactional(readOnly = true)
    public Dtos.PerfilDto perfil() {
        return Dtos.PerfilDto.de(usuarioActual());
    }

    /**
     * Seguir a un creador.
     *
     * Esto solo guarda el favorito. La suscripción al topic de FCM la hace la
     * app en el dispositivo: los topics son por aparato, no por cuenta, y el
     * servidor no puede suscribir a nadie en su nombre.
     */
    @PutMapping("/favoritos/{creadorId}")
    @Transactional
    public Dtos.RespuestaSimple seguir(@PathVariable UUID creadorId) {
        if (!creadores.existsById(creadorId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese creador no existe.");
        }

        Usuario usuario = usuarioActual();
        usuario.getFavoritos().add(creadorId);

        return Dtos.RespuestaSimple.de("Ahora recibes sus avisos.");
    }

    @DeleteMapping("/favoritos/{creadorId}")
    @Transactional
    public Dtos.RespuestaSimple dejarDeSeguir(@PathVariable UUID creadorId) {
        Usuario usuario = usuarioActual();
        usuario.getFavoritos().remove(creadorId);

        return Dtos.RespuestaSimple.de("Dejaste de recibir sus avisos.");
    }

    /**
     * Seguir a una productora: avisa de lo que se publica en los canales que
     * le pertenecen. Como con los creadores, aquí solo se guarda; el topic de
     * FCM (productora_<id>) lo suscribe la app en el dispositivo.
     */
    @PutMapping("/favoritos/productoras/{productoraId}")
    @Transactional
    public Dtos.RespuestaSimple seguirProductora(@PathVariable UUID productoraId) {
        if (!productoras.existsById(productoraId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Esa productora no existe.");
        }

        Usuario usuario = usuarioActual();
        usuario.getProductorasSeguidas().add(productoraId);

        return Dtos.RespuestaSimple.de("Ahora recibes sus avisos.");
    }

    @DeleteMapping("/favoritos/productoras/{productoraId}")
    @Transactional
    public Dtos.RespuestaSimple dejarDeSeguirProductora(@PathVariable UUID productoraId) {
        Usuario usuario = usuarioActual();
        usuario.getProductorasSeguidas().remove(productoraId);

        return Dtos.RespuestaSimple.de("Dejaste de recibir sus avisos.");
    }

    /**
     * Preferencias de accesibilidad y apariencia.
     *
     * Viven en el servidor y no en el teléfono para que acompañen al usuario
     * si cambia de aparato. Alguien que necesitó poner la letra en "muy
     * grande" no debería tener que volver a descubrir ese ajuste.
     */
    @PutMapping("/preferencias")
    @Transactional
    public Dtos.PerfilDto preferencias(@Valid @RequestBody Dtos.Preferencias peticion) {
        Usuario usuario = usuarioActual();

        if (peticion.escalaTexto() != null) usuario.setEscalaTexto(peticion.escalaTexto());
        if (peticion.tema() != null) usuario.setTema(peticion.tema());
        if (peticion.avisos() != null) usuario.setAvisos(peticion.avisos());

        return Dtos.PerfilDto.de(usuario);
    }

    // -------------------------------------------------------------------------

    private Usuario usuarioActual() {
        return usuarios.findById(Sesion.exigir())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Tu sesión ya no es válida. Vuelve a abrir la app."));
    }

    /** Los creadores visibles, por id y en orden alfabético. */
    private Map<UUID, Creador> creadoresVisibles() {
        Map<UUID, Creador> visibles = new LinkedHashMap<>();
        creadores.findByActivoTrueOrderByNombreAsc().forEach(c -> visibles.put(c.getId(), c));
        return visibles;
    }
}
