package com.voces.backend.stickers;

import com.voces.backend.config.SeguridadConfig.Sesion;
import com.voces.backend.directorio.AjustesService;
import com.voces.backend.modelo.*;
import com.voces.backend.stickers.StickersDtos.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Los stickers de los creadores.
 *
 * El equipo los sube desde el panel, en paquetes de 3 a 30. La app pide la
 * lista de los publicados, baja las imágenes a su carpeta privada y desde ahí
 * los manda a WhatsApp (como paquete) o a cualquier otra app (uno por uno).
 *
 * Quién los ve lo decide el ajuste 'stickers' del panel, con el mismo
 * esquema que las recomendaciones: apagado, admins, elegidos o todos. Empieza
 * apagado. Mientras alguien no los vea, este servidor no le da ni la lista
 * ni las imágenes: no basta con que la app esconda el apartado.
 */
@Service
public class StickersService {

    private static final Logger log = LoggerFactory.getLogger(StickersService.class);

    private final Repositorios.PaquetesStickers paquetes;
    private final Repositorios.Stickers stickers;
    private final Repositorios.DatosDeStickers datos;
    private final Repositorios.IconosDePaquetes iconos;
    private final Repositorios.Creadores creadores;
    private final Repositorios.Usuarios usuarios;
    private final AjustesService ajustes;

    public StickersService(Repositorios.PaquetesStickers paquetes, Repositorios.Stickers stickers,
                           Repositorios.DatosDeStickers datos, Repositorios.IconosDePaquetes iconos,
                           Repositorios.Creadores creadores, Repositorios.Usuarios usuarios,
                           AjustesService ajustes) {
        this.paquetes = paquetes;
        this.stickers = stickers;
        this.datos = datos;
        this.iconos = iconos;
        this.creadores = creadores;
        this.usuarios = usuarios;
        this.ajustes = ajustes;
    }

    // =========================================================================
    // Quién los ve
    // =========================================================================

    /** Si el alcance de ahora le llega a esta cuenta. */
    @Transactional(readOnly = true)
    public boolean puedeVer(Usuario u) {
        return u != null && AjustesService.permite(ajustes.stickers(), u.isEsAdmin(), u.isStickersHabilitado());
    }

    /**
     * La cuenta de la sesión, si puede ver los stickers. Un administrador
     * puede siempre que pida una imagen: el panel la enseña aunque el
     * apartado esté apagado para todos.
     */
    private Usuario exigirAcceso(boolean bastaConSerAdmin) {
        Usuario u = usuarios.findById(Sesion.exigir())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Tu sesión ya no es válida. Vuelve a abrir la app."));
        if ((bastaConSerAdmin && u.isEsAdmin()) || puedeVer(u)) return u;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Los stickers no están disponibles por ahora.");
    }

    // =========================================================================
    // La app
    // =========================================================================

    /**
     * Los paquetes que la app puede usar: publicados, completos y de un
     * creador visible. Por creador, en orden alfabético, y dentro de cada uno
     * en el orden del panel.
     */
    @Transactional(readOnly = true)
    public List<PaqueteApp> paraLaApp() {
        exigirAcceso(false);

        List<PaqueteStickers> lista = paquetes.findByPublicadoTrueOrderByOrdenAscCreadoEnAsc();
        if (lista.isEmpty()) return List.of();

        Map<UUID, Creador> duenos = porId(creadores.findAllById(
                lista.stream().map(PaqueteStickers::getCreadorId).collect(Collectors.toSet())), Creador::getId);
        Map<UUID, List<Sticker>> porPaquete = stickersDe(lista);

        List<PaqueteApp> resultado = new ArrayList<>();
        for (PaqueteStickers p : lista) {
            Creador c = duenos.get(p.getCreadorId());
            List<Sticker> suyos = porPaquete.getOrDefault(p.getId(), List.of());
            // Publicado y luego incompleto no debería pasar (se despublica
            // solo), pero WhatsApp rechazaría el paquete: mejor no darlo.
            if (c == null || !c.isActivo() || !Reglas.loQueFalta(suyos.size(), p.tieneIcono()).isEmpty()) continue;

            resultado.add(new PaqueteApp(p.getId(), c.getId(), c.getNombre(), p.getNombre(), p.isAnimado(),
                    p.getVersion(), "/api/stickers/paquetes/" + p.getId() + "/icono?v=" + p.getVersion(),
                    suyos.stream().map(s -> new StickerApp(s.getId(), Reglas.listaDeEmojis(s.getEmojis()),
                            "/api/stickers/" + s.getId() + "/imagen?v=" + p.getVersion())).toList()));
        }
        resultado.sort(Comparator.comparing((PaqueteApp p) -> p.creador().toLowerCase(Locale.ROOT)));
        return resultado;
    }

    public record Archivo(String tipo, byte[] datos) {}

    /** La imagen de un sticker. Fuera del panel, solo de un paquete publicado. */
    @Transactional(readOnly = true)
    public Archivo imagen(UUID stickerId) {
        Usuario u = exigirAcceso(true);
        Sticker s = stickers.findById(stickerId).orElseThrow(StickersService::noExiste);
        if (!u.isEsAdmin()) {
            paquetes.findById(s.getPaqueteId()).filter(PaqueteStickers::isPublicado)
                    .orElseThrow(StickersService::noExiste);
        }
        DatosDeSticker d = datos.findById(stickerId).orElseThrow(StickersService::noExiste);
        return new Archivo(Imagen.WEBP, d.getDatos());
    }

    /** El ícono de un paquete. Fuera del panel, solo de uno publicado. */
    @Transactional(readOnly = true)
    public Archivo icono(UUID paqueteId) {
        Usuario u = exigirAcceso(true);
        PaqueteStickers p = paquetes.findById(paqueteId).orElseThrow(StickersService::noExiste);
        if (!u.isEsAdmin() && !p.isPublicado()) throw noExiste();
        IconoDePaquete i = iconos.findById(paqueteId).orElseThrow(StickersService::noExiste);
        return new Archivo(p.getIconoTipo(), i.getDatos());
    }

    // =========================================================================
    // El panel
    // =========================================================================

    @Transactional(readOnly = true)
    public PanelStickers panel() {
        List<CuentaElegida> elegidas = usuarios.findByStickersHabilitadoTrueOrderByVistoEnDesc().stream()
                .map(u -> new CuentaElegida(u.getId(), u.getProveedor(), u.getEmail(), u.isEsAdmin(), u.getVistoEn()))
                .toList();

        List<PaqueteStickers> lista = paquetes.findAllByOrderByOrdenAscCreadoEnAsc();
        Map<UUID, Creador> duenos = porId(creadores.findAllById(
                lista.stream().map(PaqueteStickers::getCreadorId).collect(Collectors.toSet())), Creador::getId);
        Map<UUID, List<Sticker>> porPaquete = stickersDe(lista);

        List<PaqueteAdmin> dtos = lista.stream()
                .map(p -> aDto(p, duenos.get(p.getCreadorId()), porPaquete.getOrDefault(p.getId(), List.of())))
                .sorted(Comparator.comparing((PaqueteAdmin p) -> String.valueOf(p.creador()).toLowerCase(Locale.ROOT)))
                .toList();

        return new PanelStickers(ajustes.stickers(), elegidas, dtos);
    }

    @Transactional
    public Hecho crear(CrearPaquete pedido) {
        Creador c = creadorDe(pedido.creadorId());
        PaqueteStickers p = new PaqueteStickers();
        p.setCreadorId(c.getId());
        p.setNombre(Reglas.nombre(pedido.nombre() == null || pedido.nombre().isBlank()
                ? c.getNombre() : pedido.nombre()));
        p.setOrden(paquetes.ultimoOrden(c.getId()) + 1);
        p = paquetes.save(p);
        log.info("Paquete de stickers {} de {} creado por {}", p.getId(), c.getId(), Sesion.exigir());
        return hecho(p, "Paquete creado. Súbele de 3 a 30 stickers y su ícono, y publícalo.");
    }

    @Transactional
    public Hecho cambiar(UUID id, CambiarPaquete pedido) {
        PaqueteStickers p = paquete(id);
        String mensaje = "Cambios guardados.";

        if (pedido.nombre() != null) {
            String nombre = Reglas.nombre(pedido.nombre());
            if (!nombre.equals(p.getNombre())) {
                p.setNombre(nombre);
                p.cambio();
            }
        }
        if (pedido.creadorId() != null && !pedido.creadorId().equals(p.getCreadorId())) {
            p.setCreadorId(creadorDe(pedido.creadorId()).getId());
            p.setOrden(paquetes.ultimoOrden(p.getCreadorId()) + 1);
            p.cambio();
        }
        if (pedido.publicado() != null && pedido.publicado() != p.isPublicado()) {
            if (pedido.publicado()) {
                List<String> falta = Reglas.loQueFalta((int) stickers.countByPaqueteId(id), p.tieneIcono());
                if (!falta.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Todavía no se puede publicar. " + String.join(" ", falta));
                }
                mensaje = "Publicado. Lo verá en la app quien tenga los stickers, la próxima vez que entre al apartado.";
            } else {
                mensaje = "Despublicado. Ya no sale en la app; quien ya lo había agregado a WhatsApp lo conserva.";
            }
            p.setPublicado(pedido.publicado());
            p.setActualizadoEn(java.time.Instant.now());
            log.info("Paquete de stickers {} {} por {}", id, pedido.publicado() ? "publicado" : "despublicado",
                    Sesion.exigir());
        }
        return hecho(paquetes.save(p), mensaje);
    }

    @Transactional
    public void borrar(UUID id) {
        PaqueteStickers p = paquete(id);
        // La base se lleva sus stickers, sus imágenes y su ícono (on delete cascade).
        paquetes.delete(p);
        log.info("Paquete de stickers {} borrado por {}", id, Sesion.exigir());
    }

    @Transactional
    public Hecho ponerIcono(UUID id, SubirImagen pedido) {
        PaqueteStickers p = paquete(id);
        byte[] bytes = base64(pedido.datos(), Reglas.PESO_ICONO);
        Imagen img = Reglas.icono(bytes);

        iconos.save(new IconoDePaquete(id, bytes));
        p.setIconoTipo(img.tipo());
        p.setIconoPeso(bytes.length);
        p.cambio();
        return hecho(paquetes.save(p), "Ícono guardado.");
    }

    @Transactional
    public Hecho agregar(UUID paqueteId, SubirImagen pedido) {
        PaqueteStickers p = paquete(paqueteId);
        long cuantos = stickers.countByPaqueteId(paqueteId);
        if (cuantos >= Reglas.MAXIMO_POR_PAQUETE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Este paquete ya tiene 30 stickers, el máximo de WhatsApp. Crea otro paquete para el mismo creador.");
        }
        byte[] bytes = base64(pedido.datos(), Reglas.PESO_ANIMADO);
        Imagen img = Reglas.sticker(bytes);

        if (cuantos == 0) {
            p.setAnimado(img.animado());
        } else if (img.animado() != p.isAnimado()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, p.isAnimado()
                    ? "Este paquete es de stickers animados y este sticker es fijo. WhatsApp no deja mezclarlos: súbelo en otro paquete."
                    : "Este paquete es de stickers fijos y este sticker es animado. WhatsApp no deja mezclarlos: súbelo en otro paquete.");
        }

        Sticker s = new Sticker();
        s.setPaqueteId(paqueteId);
        s.setOrden(stickers.ultimoOrden(paqueteId) + 1);
        s.setEmojis(Reglas.emojis(pedido.emojis()));
        s.setAnimado(img.animado());
        s.setPeso(bytes.length);
        s = stickers.save(s);
        datos.save(new DatosDeSticker(s.getId(), bytes));

        p.cambio();
        return hecho(paquetes.save(p), "Sticker agregado (" + Reglas.kb(bytes.length) + ").");
    }

    @Transactional
    public Hecho cambiarSticker(UUID stickerId, CambiarSticker pedido) {
        Sticker s = stickers.findById(stickerId).orElseThrow(StickersService::noExiste);
        s.setEmojis(Reglas.emojis(pedido.emojis()));
        stickers.save(s);
        PaqueteStickers p = paquete(s.getPaqueteId());
        p.cambio();
        return hecho(paquetes.save(p), "Emojis guardados.");
    }

    @Transactional
    public Hecho quitarSticker(UUID stickerId) {
        Sticker s = stickers.findById(stickerId).orElseThrow(StickersService::noExiste);
        PaqueteStickers p = paquete(s.getPaqueteId());
        stickers.delete(s);
        stickers.flush();

        String mensaje = "Sticker quitado.";
        long quedan = stickers.countByPaqueteId(p.getId());
        // Publicado con menos de 3, WhatsApp lo rechazaría: se retira hasta
        // que vuelva a estar completo.
        if (p.isPublicado() && quedan < Reglas.MINIMO_POR_PAQUETE) {
            p.setPublicado(false);
            mensaje = "Sticker quitado. El paquete se quedó con menos de 3 y lo despublicamos: "
                    + "agrégale otro y vuelve a publicarlo.";
        }
        p.cambio();
        return hecho(paquetes.save(p), mensaje);
    }

    @Transactional
    public Hecho ordenar(UUID paqueteId, Orden pedido) {
        PaqueteStickers p = paquete(paqueteId);
        List<Sticker> suyos = stickers.findByPaqueteIdOrderByOrdenAscCreadoEnAsc(paqueteId);
        List<UUID> ids = pedido.ids() == null ? List.of() : pedido.ids();
        if (ids.size() != suyos.size() || !new HashSet<>(ids).equals(
                suyos.stream().map(Sticker::getId).collect(Collectors.toSet()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El paquete cambió mientras lo ordenabas. Recarga la página.");
        }
        Map<UUID, Sticker> porId = porId(suyos, Sticker::getId);
        for (int i = 0; i < ids.size(); i++) porId.get(ids.get(i)).setOrden(i);
        stickers.saveAll(suyos);
        p.cambio();
        return hecho(paquetes.save(p), "Orden guardado.");
    }

    /** Elige o deja de elegir una cuenta para ver los stickers. */
    @Transactional
    public String elegir(UUID usuarioId, boolean valor) {
        Usuario u = usuarios.findById(usuarioId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Esa cuenta ya no existe."));
        u.setStickersHabilitado(valor);
        log.info("Stickers {} para {} por {}", valor ? "activados" : "desactivados", usuarioId, Sesion.exigir());

        String nota = switch (ajustes.stickers()) {
            case AjustesService.RECOMENDAR_ELEGIDOS -> valor
                    ? " Verá el apartado Stickers la próxima vez que abra la app."
                    : " Ya no lo verá en la app.";
            case AjustesService.RECOMENDAR_TODOS -> " Ahora mismo los ven todos, así que no cambia nada hasta que el alcance sea «Administradores y cuentas elegidas».";
            default -> " No los verá mientras el alcance de los stickers no sea «Administradores y cuentas elegidas».";
        };
        return (valor ? "Cuenta elegida para los stickers." : "Cuenta quitada de los stickers.") + nota;
    }

    // =========================================================================

    private Hecho hecho(PaqueteStickers p, String mensaje) {
        Creador c = creadores.findById(p.getCreadorId()).orElse(null);
        return new Hecho(aDto(p, c, stickers.findByPaqueteIdOrderByOrdenAscCreadoEnAsc(p.getId())), mensaje);
    }

    private static PaqueteAdmin aDto(PaqueteStickers p, Creador c, List<Sticker> suyos) {
        return new PaqueteAdmin(p.getId(), p.getCreadorId(), c == null ? null : c.getNombre(),
                c != null && c.isActivo(), p.getNombre(), p.isPublicado(), p.isAnimado(), p.getVersion(),
                p.tieneIcono(), p.getIconoPeso(),
                suyos.stream().map(s -> new StickerAdmin(s.getId(), s.getEmojis(), s.isAnimado(), s.getPeso())).toList(),
                Reglas.loQueFalta(suyos.size(), p.tieneIcono()), p.getActualizadoEn());
    }

    private Map<UUID, List<Sticker>> stickersDe(List<PaqueteStickers> lista) {
        if (lista.isEmpty()) return Map.of();
        return stickers.findByPaqueteIdInOrderByOrdenAscCreadoEnAsc(
                        lista.stream().map(PaqueteStickers::getId).toList()).stream()
                .collect(Collectors.groupingBy(Sticker::getPaqueteId, LinkedHashMap::new, Collectors.toList()));
    }

    private PaqueteStickers paquete(UUID id) {
        return paquetes.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese paquete ya no existe. Recarga la página."));
    }

    private Creador creadorDe(UUID id) {
        if (id == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Elige de qué creador es.");
        return creadores.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ese creador ya no está en el directorio."));
    }

    /**
     * Decodifica la imagen que manda el panel. Se mira el tamaño antes de
     * decodificar: una cadena enorme no llega a ocupar memoria.
     */
    static byte[] base64(String texto, int pesoMaximo) {
        if (texto == null || texto.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta la imagen.");
        }
        String limpio = texto.trim();
        int coma = limpio.indexOf(',');
        if (limpio.startsWith("data:") && coma > 0) limpio = limpio.substring(coma + 1);
        if (limpio.length() > (pesoMaximo / 3 + 1) * 4 + 16) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La imagen pesa más de " + Reglas.kb(pesoMaximo) + ".");
        }
        byte[] bytes;
        try {
            // El estricto: el MIME se salta lo que no entiende y daría por
            // buena una cadena con basura.
            bytes = Base64.getDecoder().decode(limpio.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            bytes = new byte[0];
        }
        if (bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La imagen llegó dañada. Vuelve a subirla.");
        }
        return bytes;
    }

    private static ResponseStatusException noExiste() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Ese sticker ya no existe.");
    }

    private static <T> Map<UUID, T> porId(Collection<T> lista, Function<T, UUID> id) {
        Map<UUID, T> m = new HashMap<>();
        for (T t : lista) m.put(id.apply(t), t);
        return m;
    }
}
