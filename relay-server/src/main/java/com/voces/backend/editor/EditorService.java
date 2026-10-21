package com.voces.backend.editor;

import com.voces.backend.directorio.EtiquetasService;
import com.voces.backend.directorio.GuardadoDelDirectorio;
import com.voces.backend.modelo.*;
import com.voces.backend.youtube.YouTubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Agregar al directorio desde la app.
 *
 * Las cuentas que el equipo autoriza desde el panel dan de alta creadores,
 * medios y canales de YouTube sin pasar por el panel ni esperar aprobación:
 * lo que agregan queda visible en el acto. Por dentro va por el mismo camino
 * que el panel ({@link GuardadoDelDirectorio}), así que la suscripción al hub
 * y la copia a testing son las mismas.
 *
 * Lo que pide la app es lo mínimo: el ID del canal y el nombre. El resto (la
 * foto, el enlace, el @handle) se le pregunta aquí a YouTube, sin fiarse de
 * lo que mande el teléfono. Cada alta queda apuntada en altas_desde_app para
 * que el equipo pueda revisar después qué entró y quién lo metió.
 */
@Service
public class EditorService {

    private static final Logger log = LoggerFactory.getLogger(EditorService.class);

    /** Cuántas altas puede hacer una cuenta en 24 horas: limita el daño de un teléfono perdido. */
    static final int MAX_POR_DIA = 20;
    /** Las que la app enseña en "Lo que agregaste". */
    static final int MAX_EN_LA_APP = 30;

    private static final Pattern ID_CANAL = Pattern.compile("^UC[\\w-]{22}$");

    private final GuardadoDelDirectorio guardado;
    private final EtiquetasService etiquetas;
    private final YouTubeClient youtube;
    private final Repositorios.Canales canales;
    private final Repositorios.Creadores creadores;
    private final Repositorios.Productoras productoras;
    private final Repositorios.AltasDesdeApp altas;

    public EditorService(GuardadoDelDirectorio guardado, EtiquetasService etiquetas, YouTubeClient youtube,
                         Repositorios.Canales canales, Repositorios.Creadores creadores,
                         Repositorios.Productoras productoras, Repositorios.AltasDesdeApp altas) {
        this.guardado = guardado;
        this.etiquetas = etiquetas;
        this.youtube = youtube;
        this.canales = canales;
        this.creadores = creadores;
        this.productoras = productoras;
        this.altas = altas;
    }

    // -------------------------------------------------------------------------
    // Lo que manda y recibe la app
    // -------------------------------------------------------------------------

    public record NuevoCreador(String nombre, String descripcion, String channelId, List<UUID> etiquetas) {}

    /** Un medio puede entrar sin canal de YouTube: {@code channelId} es opcional. */
    public record NuevoMedio(String nombre, String descripcion, String channelId, List<UUID> etiquetas) {}

    /** Un canal más de alguien que ya está: o {@code creadorId} o {@code productoraId}. */
    public record NuevoCanal(String channelId, UUID creadorId, UUID productoraId, List<UUID> etiquetas) {}

    /** Un canal de YouTube encontrado, y si ya está en el directorio, de quién es. */
    public record CanalEncontrado(
            String channelId,
            String titulo,
            String descripcion,
            String fotoUrl,
            String handle,
            String suscriptores,
            /** Nombre de quien ya lo tiene en el directorio. Null si no está. */
            String yaEsDe
    ) {}

    /**
     * Lo que se agregó.
     *
     * @param tipo    creador | medio | canal: a qué ficha llevar a la persona
     * @param id      el del creador, el de la productora o el del canal
     * @param aviso   algo que conviene saber aunque haya salido bien; null si nada
     */
    public record Agregado(boolean ok, String tipo, UUID id, String nombre, String mensaje, String aviso) {}

    public record AltaDto(String tipo, UUID id, String nombre, Instant creadoEn) {}

    public record LoQueAgregue(int quedanHoy, List<AltaDto> altas) {}

    // -------------------------------------------------------------------------
    // Permiso
    // -------------------------------------------------------------------------

    /**
     * Solo cuentas guardadas: una de invitado vive en un teléfono y se pierde
     * al reinstalar, y lo que agregue no tendría a nadie detrás.
     */
    public static boolean puede(Usuario u) {
        return u != null && u.isEditorHabilitado() && !u.esAnonimo();
    }

    public void exigir(Usuario u) {
        if (!puede(u)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Tu cuenta no tiene permiso para agregar al directorio. Si crees que es un error, habla con el equipo.");
        }
    }

    // -------------------------------------------------------------------------
    // Buscar
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public CanalEncontrado buscar(Usuario u, String query) {
        exigir(u);
        Dtos.DatosDeCanal d = guardado.buscarCanal(query);
        return new CanalEncontrado(d.channelId(), d.titulo(), d.descripcion(), d.fotoUrl(),
                sinArroba(d.handle()), d.suscriptores(), duenoDe(d.channelId()).orElse(null));
    }

    // -------------------------------------------------------------------------
    // Agregar
    // -------------------------------------------------------------------------

    @Transactional
    public Agregado agregarCreador(Usuario u, NuevoCreador pedido) {
        exigir(u);
        exigirCupo(u);

        String nombre = nombre(pedido.nombre(), "El creador necesita un nombre.");
        Dtos.DatosDeCanal canal = canalLibre(obligatorio(pedido.channelId(),
                "Busca primero el canal de YouTube del creador."));

        Dtos.CreadorGuardado r = guardado.guardarCreador(new Dtos.GuardarCreador(
                null, nombre, "otros", descripcion(pedido.descripcion()), canal.fotoUrl(),
                null, true, List.of(canalPedido(canal)), null));

        ponerTitulo(canal);
        ponerEtiquetas(EtiquetasService.CREADOR, r.id(), pedido.etiquetas());
        apuntar(u, AltaDesdeApp.CREADOR, r.id(), nombre, canal.channelId());

        return new Agregado(true, AltaDesdeApp.CREADOR, r.id(), nombre,
                "Listo. " + nombre + " ya está en el directorio.", avisoDe(r.avisoSuscripcion()));
    }

    @Transactional
    public Agregado agregarMedio(Usuario u, NuevoMedio pedido) {
        exigir(u);
        exigirCupo(u);

        String nombre = nombre(pedido.nombre(), "El medio necesita un nombre.");
        String channelId = vacioANull(pedido.channelId());
        Dtos.DatosDeCanal canal = channelId != null ? canalLibre(channelId) : null;

        Dtos.ProductoraGuardada r = guardado.guardarProductora(new Dtos.GuardarProductora(
                null, nombre, descripcion(pedido.descripcion()), canal != null ? canal.fotoUrl() : null,
                true, canal != null ? List.of(canalPedido(canal)) : List.of(), null,
                // Sale en el directorio como una fila más: si no, solo se
                // llegaría a él desde la ficha de sus creadores, y no tiene.
                true, null));

        if (canal != null) ponerTitulo(canal);
        ponerEtiquetas(EtiquetasService.PRODUCTORA, r.id(), pedido.etiquetas());
        apuntar(u, AltaDesdeApp.MEDIO, r.id(), nombre, channelId);

        return new Agregado(true, AltaDesdeApp.MEDIO, r.id(), nombre,
                "Listo. " + nombre + " ya está en el directorio.", avisoDe(r.avisoSuscripcion()));
    }

    @Transactional
    public Agregado agregarCanal(Usuario u, NuevoCanal pedido) {
        exigir(u);
        exigirCupo(u);

        if ((pedido.creadorId() == null) == (pedido.productoraId() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Elige de quién es el canal: un creador o un medio.");
        }
        String de;
        if (pedido.creadorId() != null) {
            de = creadores.findById(pedido.creadorId()).map(Creador::getNombre)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ese creador ya no está en el directorio."));
        } else {
            de = productoras.findById(pedido.productoraId()).map(Productora::getNombre)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ese medio ya no está en el directorio."));
        }

        Dtos.DatosDeCanal canal = canalLibre(obligatorio(pedido.channelId(), "Busca primero el canal de YouTube."));
        Dtos.GuardarCanal k = canalPedido(canal);

        Dtos.CanalGuardado r = guardado.guardarCanal(new Dtos.GuardarFichaDeCanal(
                null, null, k.url(), k.handle(), k.channelId(),
                pedido.creadorId(), pedido.creadorId() == null ? pedido.productoraId() : null, List.of()));

        String titulo = vacioANull(canal.titulo()) != null ? canal.titulo().strip() : "Canal de " + de;
        ponerTitulo(canal);
        ponerEtiquetas(EtiquetasService.CANAL, r.id(), pedido.etiquetas());
        apuntar(u, AltaDesdeApp.CANAL, r.id(), titulo, canal.channelId());

        return new Agregado(true, AltaDesdeApp.CANAL, r.id(), titulo,
                "Listo. «" + titulo + "» ya es un canal de " + de + ".", avisoDe(r.avisoSuscripcion()));
    }

    @Transactional(readOnly = true)
    public LoQueAgregue loQueAgregue(Usuario u) {
        exigir(u);
        List<AltaDto> lista = altas.findByUsuarioIdOrderByCreadoEnDesc(u.getId(), PageRequest.of(0, MAX_EN_LA_APP))
                .stream().map(a -> new AltaDto(a.getTipo(), a.getObjetoId(), a.getNombre(), a.getCreadoEn()))
                .toList();
        return new LoQueAgregue(quedanHoy(u), lista);
    }

    // -------------------------------------------------------------------------

    int quedanHoy(Usuario u) {
        long hoy = altas.countByUsuarioIdAndCreadoEnAfter(u.getId(), Instant.now().minus(Duration.ofHours(24)));
        return (int) Math.max(0, MAX_POR_DIA - hoy);
    }

    private void exigirCupo(Usuario u) {
        if (quedanHoy(u) <= 0) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Ya agregaste " + MAX_POR_DIA + " cosas hoy. Mañana puedes seguir; si es urgente, usa el panel.");
        }
    }

    /**
     * Pregunta a YouTube por el canal y comprueba que no esté ya en el
     * directorio. Lo que mandó el teléfono solo sirve para saber cuál.
     */
    private Dtos.DatosDeCanal canalLibre(String channelId) {
        String id = channelId.strip();
        if (!ID_CANAL.matcher(id).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Ese no es un canal de YouTube válido. Búscalo otra vez.");
        }
        duenoDe(id).ifPresent(dueno -> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ese canal ya está en el directorio: es de " + dueno + ".");
        });
        Dtos.DatosDeCanal datos = youtube.detallesDeCanal(id);
        if (datos == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "YouTube no contestó o no encontró ese canal. Inténtalo otra vez en un momento.");
        }
        return datos;
    }

    /** El canal tal como lo guarda el panel: con su @handle si lo tiene. */
    static Dtos.GuardarCanal canalPedido(Dtos.DatosDeCanal d) {
        String handle = sinArroba(d.handle());
        String url = handle != null
                ? "https://www.youtube.com/@" + handle
                : "https://www.youtube.com/channel/" + d.channelId();
        return new Dtos.GuardarCanal(null, Canal.YOUTUBE, null, url, handle, d.channelId(), null, null);
    }

    /** Quién tiene ya ese canal en el directorio, por su nombre. */
    private Optional<String> duenoDe(String channelId) {
        return canales.porCanalDeYouTube(channelId).map(k -> {
            if (k.getCreadorId() != null) {
                return creadores.findById(k.getCreadorId()).map(Creador::getNombre).orElse("otro creador");
            }
            if (k.getProductoraId() != null) {
                return productoras.findById(k.getProductoraId()).map(Productora::getNombre).orElse("otro medio");
            }
            return "otro";
        });
    }

    /**
     * El nombre que tiene en YouTube, ya de entrada: si no, el canal sale
     * sin nombre hasta la siguiente revisión de TitulosDeCanales.
     */
    private void ponerTitulo(Dtos.DatosDeCanal d) {
        String titulo = vacioANull(d.titulo());
        if (titulo == null) return;
        canales.porCanalDeYouTube(d.channelId()).ifPresent(k -> {
            k.setTitulo(titulo.length() > 200 ? titulo.substring(0, 200) : titulo);
            k.setTituloEn(Instant.now());
        });
    }

    private void ponerEtiquetas(String tipo, UUID id, List<UUID> pedidas) {
        if (pedidas == null || pedidas.isEmpty()) return;
        etiquetas.asignar(tipo, id, pedidas);
    }

    private void apuntar(Usuario u, String tipo, UUID id, String nombre, String channelId) {
        altas.save(new AltaDesdeApp(u, tipo, id, nombre, channelId));
        log.info("Alta desde la app: {} {} ({}) por {}", tipo, id, nombre, u.getId());
    }

    /** El hub no confirmó: el alta vale y la renovación lo vuelve a intentar sola. */
    private static String avisoDe(String avisoSuscripcion) {
        return avisoSuscripcion == null ? null
                : "YouTube todavía no confirma los avisos de sus videos. Se vuelve a intentar solo; si mañana sigue igual, avisa al equipo.";
    }

    static String nombre(String pedido, String siFalta) {
        String n = vacioANull(pedido);
        if (n == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, siFalta);
        n = n.replaceAll("\\s+", " ");
        if (n.length() < 2) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es demasiado corto.");
        return n.length() > 60 ? n.substring(0, 60).strip() : n;
    }

    static String descripcion(String pedido) {
        String d = vacioANull(pedido);
        return d == null ? null : (d.length() > 600 ? d.substring(0, 600).strip() : d);
    }

    private static String obligatorio(String valor, String siFalta) {
        String v = vacioANull(valor);
        if (v == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, siFalta);
        return v;
    }

    static String sinArroba(String handle) {
        String h = vacioANull(handle);
        if (h == null) return null;
        h = h.startsWith("@") ? h.substring(1) : h;
        return h.isEmpty() ? null : h;
    }

    private static String vacioANull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
