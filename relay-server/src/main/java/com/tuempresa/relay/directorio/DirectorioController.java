package com.tuempresa.relay.directorio;

import com.tuempresa.relay.anuncios.TiendaGoogle;
import com.tuempresa.relay.config.SeguridadConfig.Sesion;
import com.tuempresa.relay.modelo.*;
import com.tuempresa.relay.push.PushService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
    private final AjustesService ajustes;
    private final TiendaGoogle tienda;

    public DirectorioController(Repositorios.Creadores creadores,
                                Repositorios.Productoras productoras,
                                Repositorios.Publicaciones publicaciones,
                                Repositorios.Usuarios usuarios,
                                Catalogo catalogo, AjustesService ajustes,
                                TiendaGoogle tienda) {
        this.creadores = creadores;
        this.productoras = productoras;
        this.publicaciones = publicaciones;
        this.usuarios = usuarios;
        this.catalogo = catalogo;
        this.ajustes = ajustes;
        this.tienda = tienda;
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
        boolean todos = categoria == null || categoria.isBlank() || "todos".equals(categoria);
        Catalogo.Vista vista = catalogo.vista();

        List<Dtos.CreadorDto> lista = new ArrayList<>();
        vista.creadores().stream()
                .filter(c -> c.isActivo() && (todos || categoria.equals(c.getCategoria())))
                .map(vista::ficha)
                .forEach(lista::add);

        // Las productoras que aparecen en el directorio van intercaladas por
        // nombre, como una fila más. Los creadores conservan el orden en que
        // los entrega la base.
        Collator porNombre = Collator.getInstance(Locale.forLanguageTag("es"));
        porNombre.setStrength(Collator.PRIMARY);

        vista.productoras().stream()
                .filter(p -> p.isActivo() && p.isEnDirectorio()
                        && (todos || categoria.equals(p.getCategoria())))
                .map(vista::comoCreador)
                .forEach(fila -> {
                    int i = 0;
                    while (i < lista.size() && porNombre.compare(lista.get(i).nombre(), fila.nombre()) <= 0) i++;
                    lista.add(i, fila);
                });

        return lista;
    }

    @GetMapping("/creadores/{id}")
    @Transactional(readOnly = true)
    public Dtos.CreadorDto uno(@PathVariable UUID id) {
        Catalogo.Vista vista = catalogo.vista();

        Creador creador = vista.creadorVisible(id);
        if (creador != null) return vista.ficha(creador);

        Productora productora = vista.productoraVisible(id);
        if (productora != null && productora.isEnDirectorio()) return vista.comoCreador(productora);

        throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Ese creador ya no está en el directorio.");
    }

    /**
     * Los últimos videos de un creador, para el mini feed de su ficha en la
     * app: lo que salió en sus canales y en los canales de otros en los que
     * aparece. Es lo mismo que vería en Novedades quien solo lo siguiera a
     * él. Sin los cortos, y sin depender de a quién siga la persona.
     */
    @GetMapping("/creadores/{id}/publicaciones")
    @Transactional(readOnly = true)
    public List<Dtos.PublicacionDto> delCreador(@PathVariable UUID id,
                                                @RequestParam(defaultValue = "5") int limite) {
        Catalogo.Vista vista = catalogo.vista();

        if (vista.creadorVisible(id) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Ese creador ya no está en el directorio.");
        }

        // Los canales de otros en los que aparece, si su dueño sigue visible.
        List<UUID> compartidos = vista.compartidosCon(id).stream()
                .filter(vista::vivo)
                .map(Canal::getId)
                .toList();

        List<Publicacion> lista = publicaciones.delFeed(
                List.of(id),
                // La consulta no admite una colección vacía.
                compartidos.isEmpty() ? List.of(NINGUNO) : compartidos,
                false,
                PageRequest.of(0, Math.max(1, Math.min(limite, 30))));

        return aDtos(lista, vista);
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

        return productoras.findByActivoTrueOrderByNombreAsc().stream()
                .map(p -> vista.ficha(vista.productora(p.getId())))
                .toList();
    }

    @GetMapping("/productoras/{id}")
    @Transactional(readOnly = true)
    public Dtos.ProductoraDto productora(@PathVariable UUID id) {
        Catalogo.Vista vista = catalogo.vista();

        Productora productora = vista.productoraVisible(id);
        if (productora == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Ese medio ya no está en el directorio.");
        }
        return vista.ficha(productora);
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
     * persona que trabaja con ella. De un creador entra además lo de los
     * canales de otros en los que aparece: el de su productora, si en la
     * ficha de ese canal se marcó que sale con él.
     */
    @GetMapping("/publicaciones")
    @Transactional(readOnly = true)
    public List<Dtos.PublicacionDto> feed(@RequestParam(defaultValue = "50") int limite,
                                          @RequestParam(required = false) String tipo) {
        Usuario usuario = usuarioActual();

        // Los videos cortos van en su propia lista (?tipo=cortos), nunca
        // mezclados. Y solo si el equipo los permite y la persona los quiere:
        // si no, esa lista viene vacía.
        boolean cortos = "cortos".equals(tipo);
        if (cortos && !(ajustes.cortos() && usuario.isCortos())) {
            return List.of();
        }

        Catalogo.Vista vista = catalogo.vista();

        Collection<UUID> deCreadores = usuario.getFavoritos();
        List<UUID> deCanales = canalesSeguidos(usuario, vista);

        if (deCreadores.isEmpty() && deCanales.isEmpty()) {
            return List.of();
        }

        List<Publicacion> lista = publicaciones.delFeed(
                deCreadores.isEmpty() ? List.of(NINGUNO) : deCreadores,
                deCanales.isEmpty() ? List.of(NINGUNO) : deCanales,
                cortos,
                PageRequest.of(0, Math.min(limite, 100)));

        return aDtos(lista, vista);
    }

    /**
     * Los últimos videos de un canal, para su ficha en la app. No depende de
     * a quién siga la persona: es lo que hay en ese canal. Sin los cortos,
     * que solo salen en su apartado de Novedades.
     *
     * Solo canales con el dueño visible, como en el resto del directorio.
     */
    @GetMapping("/canales/{id}/publicaciones")
    @Transactional(readOnly = true)
    public List<Dtos.PublicacionDto> delCanal(@PathVariable UUID id,
                                              @RequestParam(defaultValue = "20") int limite) {
        Catalogo.Vista vista = catalogo.vista();

        Canal canal = vista.canal(id);
        if (canal == null || !vista.vivo(canal)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Ese canal ya no está en el directorio.");
        }

        return aDtos(publicaciones.delCanal(id, PageRequest.of(0, Math.max(1, Math.min(limite, 50)))), vista);
    }

    /**
     * Los canales que entran en las novedades de la persona sin ser de un
     * creador que sigue: los de las productoras visibles que sigue, y los de
     * otros en los que aparece alguno de sus creadores. Un canal con el dueño
     * oculto no cuenta: es lo mismo que enseñan las fichas.
     */
    static List<UUID> canalesSeguidos(Usuario usuario, Catalogo.Vista vista) {
        Set<UUID> ids = new LinkedHashSet<>();

        for (UUID productoraId : usuario.getProductorasSeguidas()) {
            if (vista.productoraVisible(productoraId) == null) continue;
            vista.canalesDeProductora(productoraId).stream()
                    .filter(vista::vivo)
                    .forEach(k -> ids.add(k.getId()));
        }
        for (UUID creadorId : usuario.getFavoritos()) {
            if (vista.creadorVisible(creadorId) == null) continue;
            vista.compartidosCon(creadorId).stream()
                    .filter(vista::vivo)
                    .forEach(k -> ids.add(k.getId()));
        }
        return List.copyOf(ids);
    }

    /**
     * Pone a cada publicación el nombre de quien la publicó y, si su canal es
     * de una productora visible, cuál. Una sola consulta para los nombres, en
     * vez de una por publicación.
     */
    static List<Dtos.PublicacionDto> aDtos(List<Publicacion> lista, Catalogo.Vista vista) {
        return lista.stream().map(p -> {
            Canal canal = vista.canal(p.getCanalId());
            Productora productora = canal != null
                    ? vista.productoraVisible(canal.getProductoraId()) : null;

            Creador creador = vista.creador(p.getCreadorId());
            String nombre = creador != null
                    ? creador.getNombre()
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
        return perfilDe(usuarioActual());
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
        Usuario usuario = usuarioActual();

        if (creadores.existsById(creadorId)) {
            usuario.getFavoritos().add(creadorId);
        } else if (productoras.existsById(creadorId)) {
            // Una productora que aparece en el directorio como un creador
            // más: la app que no conoce las productoras la sigue por aquí.
            usuario.getProductorasSeguidas().add(creadorId);
        } else {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese creador no existe.");
        }

        return Dtos.RespuestaSimple.de("Ahora recibes sus avisos.");
    }

    @DeleteMapping("/favoritos/{creadorId}")
    @Transactional
    public Dtos.RespuestaSimple dejarDeSeguir(@PathVariable UUID creadorId) {
        Usuario usuario = usuarioActual();
        usuario.getFavoritos().remove(creadorId);
        // Por si era una productora seguida como creador (ver seguir()).
        usuario.getProductorasSeguidas().remove(creadorId);

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
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese medio no existe.");
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
        if (peticion.cortos() != null) usuario.setCortos(peticion.cortos());

        return perfilDe(usuario);
    }

    // -------------------------------------------------------------------------

    /**
     * El perfil lleva también lo que decide el equipo: si hay videos cortos,
     * si hay anuncios y si se pueden comprar. Así la app se entera de todo
     * con la misma lectura, la que ya hace al abrirse.
     */
    private Dtos.PerfilDto perfilDe(Usuario usuario) {
        return Dtos.PerfilDto.de(usuario, ajustes.cortos(), ajustes.anuncios(), tienda.lista());
    }

    private Usuario usuarioActual() {
        return usuarios.findById(Sesion.exigir())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Tu sesión ya no es válida. Vuelve a abrir la app."));
    }
}
