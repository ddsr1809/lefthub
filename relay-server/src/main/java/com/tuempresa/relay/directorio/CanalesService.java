package com.tuempresa.relay.directorio;

import com.tuempresa.relay.modelo.Canal;
import com.tuempresa.relay.modelo.Creador;
import com.tuempresa.relay.modelo.Dtos;
import com.tuempresa.relay.modelo.Productora;
import com.tuempresa.relay.modelo.Repositorios;
import com.tuempresa.relay.websub.WebSubService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Los canales de un dueño: guardarlos y dejar el hub de YouTube de acuerdo.
 *
 * El dueño es un creador o, en los canales que no tienen creador, una
 * productora. Los dos guardan su lista por aquí, así que las reglas —qué
 * plataformas valen, que un canal de YouTube no puede tener dos dueños, qué
 * fila se reutiliza— están escritas una vez.
 *
 * Un canal de YouTube tiene además su ficha propia (guardarFicha): ahí se
 * dice con qué otros creadores aparece y se le puede cambiar de dueño.
 */
@Service
public class CanalesService {

    private static final Logger log = LoggerFactory.getLogger(CanalesService.class);

    static final int MAXIMO_NOMBRE = 60;

    private final Repositorios.Canales canales;
    private final Repositorios.Creadores creadores;
    private final Repositorios.Productoras productoras;
    private final Repositorios.Publicaciones publicaciones;
    private final WebSubService websub;

    public CanalesService(Repositorios.Canales canales, Repositorios.Creadores creadores,
                          Repositorios.Productoras productoras,
                          Repositorios.Publicaciones publicaciones, WebSubService websub) {
        this.canales = canales;
        this.creadores = creadores;
        this.productoras = productoras;
        this.publicaciones = publicaciones;
        this.websub = websub;
    }

    // -------------------------------------------------------------------------
    // Qué hacer en el hub después de guardar
    // -------------------------------------------------------------------------

    /**
     * Canales de YouTube que hay que dar de baja y de alta en el hub.
     *
     * Las altas incluyen los que ya estaban: reenviar el handshake es
     * idempotente y es lo que hace el botón de "reintentar" del panel.
     */
    public record Cambio(Set<String> bajas, Set<String> altas) {

        public static final Cambio NINGUNO = new Cambio(Set.of(), Set.of());

        public boolean vacio() { return bajas.isEmpty() && altas.isEmpty(); }

        /**
         * @param antes   canales de YouTube que tenía el dueño
         * @param despues los que tiene ahora
         * @param visible si el dueño está visible; oculto, no se vigila nada suyo
         */
        static Cambio de(Collection<String> antes, Collection<String> despues, boolean visible) {
            Set<String> altas = visible ? new LinkedHashSet<>(despues) : new LinkedHashSet<>();

            Set<String> bajas = new LinkedHashSet<>(antes);
            bajas.addAll(despues);
            bajas.removeAll(altas);

            return new Cambio(bajas, altas);
        }
    }

    /**
     * Deja el hub de acuerdo con lo guardado.
     *
     * Intenta todos los canales aunque alguno falle, y al final lanza con el
     * primer motivo. Lo guardado ya quedó guardado; lo que falle queda en
     * ERROR en la tabla de suscripciones y la repesca de cada 15 minutos lo
     * vuelve a intentar.
     */
    public void sincronizarWebSub(Cambio cambio) {
        String primerFallo = null;

        for (String canal : cambio.bajas()) {
            try {
                websub.desuscribir(canal);
            } catch (Exception e) {
                log.warn("Baja del hub fallida para {}: {}", canal, e.getMessage());
                if (primerFallo == null) primerFallo = e.getMessage();
            }
        }
        for (String canal : cambio.altas()) {
            try {
                websub.suscribir(canal);
            } catch (Exception e) {
                log.warn("Alta en el hub fallida para {}: {}", canal, e.getMessage());
                if (primerFallo == null) primerFallo = e.getMessage();
            }
        }

        if (primerFallo != null) throw new IllegalStateException(primerFallo);
    }

    // -------------------------------------------------------------------------
    // Guardar la lista de un dueño
    // -------------------------------------------------------------------------

    /** Sustituye los canales de un creador por los pedidos. */
    @Transactional
    public Cambio guardarDeCreador(UUID creadorId, List<Dtos.GuardarCanal> pedidos, boolean visible) {
        return guardar(canales.deCreador(creadorId), pedidos, creadorId, null, visible);
    }

    /** Sustituye los canales propios de una productora, los que no tienen creador. */
    @Transactional
    public Cambio guardarDeProductora(UUID productoraId, List<Dtos.GuardarCanal> pedidos, boolean visible) {
        return guardar(canales.propiosDeProductora(productoraId), pedidos, null, productoraId, visible);
    }

    /**
     * @param productoraFija la productora dueña cuando no hay creador. Con
     *                       creador va en null y la productora de cada canal
     *                       es la que traiga el pedido.
     */
    private Cambio guardar(List<Canal> actuales, List<Dtos.GuardarCanal> pedidos,
                           UUID creadorId, UUID productoraFija, boolean visible) {

        List<Dtos.GuardarCanal> limpios = normalizar(pedidos);

        Set<UUID> conocidos = new HashSet<>();
        actuales.forEach(k -> conocidos.add(k.getId()));

        if (creadorId != null) comprobarProductoras(limpios);
        comprobarCreadores(limpios);
        comprobarQueNoSonDeOtro(limpios, conocidos);

        Plan plan = planear(actuales, limpios);
        List<String> antes = deYouTube(actuales);

        // Primero se va lo que sobra, y se manda ya a la base. Hibernate
        // ordena los INSERT antes que los DELETE: sin este flush, cambiar un
        // canal por otro con el mismo channel_id chocaría con el índice único.
        if (!plan.sobrantes().isEmpty()) {
            publicaciones.borrarSinCreadorDe(plan.sobrantes().stream().map(Canal::getId).toList());
            canales.deleteAll(plan.sobrantes());
            canales.flush();
        }

        List<Canal> guardados = new ArrayList<>();
        for (int i = 0; i < plan.asignaciones().size(); i++) {
            Asignacion a = plan.asignaciones().get(i);
            Dtos.GuardarCanal pedido = a.pedido();

            Canal canal = a.existente() != null ? a.existente() : new Canal();
            canal.setCreadorId(creadorId);
            canal.setProductoraId(creadorId != null ? pedido.productoraId() : productoraFija);
            canal.setPlataforma(pedido.plataforma());
            canal.setNombre(pedido.nombre());
            canal.setUrl(pedido.url());
            canal.setHandle(pedido.handle());
            canal.setChannelId(pedido.channelId());
            canal.setOrden(i);
            // Sin lista, el canal sigue apareciendo con los creadores que ya tenía.
            if (pedido.creadores() != null) ponerVinculados(canal, pedido.creadores());
            guardados.add(canal);
        }

        // Los que ya existían antes que los nuevos, por la misma razón: si un
        // canal cambia de channel_id y otro nuevo hereda el que tenía, el
        // UPDATE tiene que llegar a la base antes que el INSERT.
        canales.saveAll(guardados.stream().filter(k -> k.getId() != null).toList());
        canales.flush();
        canales.saveAll(guardados.stream().filter(k -> k.getId() == null).toList());
        canales.flush();

        return Cambio.de(antes, deYouTube(guardados), visible);
    }

    /** Las productoras a las que se asignan canales tienen que existir. */
    private void comprobarProductoras(List<Dtos.GuardarCanal> pedidos) {
        Set<UUID> pedidas = new HashSet<>();
        pedidos.forEach(p -> { if (p.productoraId() != null) pedidas.add(p.productoraId()); });
        if (pedidas.isEmpty()) return;

        if (productoras.findAllById(pedidas).size() != pedidas.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Uno de los canales apunta a un medio que ya no existe.");
        }
    }

    /** Los creadores con los que se dice que aparece un canal tienen que existir. */
    private void comprobarCreadores(List<Dtos.GuardarCanal> pedidos) {
        Set<UUID> pedidos2 = new HashSet<>();
        pedidos.forEach(p -> { if (p.creadores() != null) pedidos2.addAll(p.creadores()); });
        comprobarQueExisten(pedidos2);
    }

    private void comprobarQueExisten(Collection<UUID> ids) {
        if (ids.isEmpty()) return;
        if (creadores.findAllById(ids).size() != new HashSet<>(ids).size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Uno de los creadores con los que aparece el canal ya no existe.");
        }
    }

    /** Deja al canal apareciendo exactamente con esos creadores, sin contar a su dueño. */
    private static void ponerVinculados(Canal canal, Collection<UUID> pedidos) {
        Set<UUID> quedan = new LinkedHashSet<>(pedidos);
        quedan.remove(null);
        quedan.remove(canal.getCreadorId());

        // Sobre el mismo conjunto: Hibernate vigila esa instancia.
        canal.getVinculados().retainAll(quedan);
        canal.getVinculados().addAll(quedan);
    }

    // -------------------------------------------------------------------------
    // La ficha de un canal de YouTube, por sí sola
    // -------------------------------------------------------------------------

    /**
     * @param creadores   creadores dueños que hay que volver a copiar a testing
     * @param productoras productoras dueñas (de canales sin creador) que hay
     *                    que volver a copiar
     */
    public record Ficha(Canal canal, Cambio cambio, Set<UUID> creadores, Set<UUID> productoras) {}

    /**
     * Guarda la ficha de un canal de YouTube: de quién es, de qué productora
     * y con qué otros creadores aparece. Es el único sitio donde un canal
     * puede cambiar de dueño.
     */
    @Transactional
    public Ficha guardarFicha(Dtos.GuardarFichaDeCanal peticion) {
        Canal canal;
        if (peticion.id() != null) {
            canal = canales.findById(peticion.id())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "Ese canal ya no existe."));
            if (!Canal.YOUTUBE.equals(canal.getPlataforma())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Solo los canales de YouTube tienen ficha propia.");
            }
        } else {
            canal = new Canal();
            canal.setPlataforma(Canal.YOUTUBE);
        }

        UUID creadorId = peticion.creadorId();
        UUID productoraId = peticion.productoraId();

        if (creadorId == null && productoraId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El canal tiene que ser de un creador o de un medio.");
        }
        Creador dueno = creadorId == null ? null : creadores.findById(creadorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ese creador ya no existe."));
        Productora casa = productoraId == null ? null : productoras.findById(productoraId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Ese medio ya no existe."));

        List<UUID> conQuien = peticion.creadores() != null ? peticion.creadores() : List.of();
        comprobarQueExisten(conQuien.stream().filter(Objects::nonNull).toList());

        String channelId = limpiar(peticion.channelId());
        String url = limpiar(peticion.url());
        if (channelId == null || url == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Faltan el enlace o el ID del canal de YouTube.");
        }
        Optional<Canal> ocupado = canales.porCanalDeYouTube(channelId);
        if (ocupado.isPresent() && !ocupado.get().getId().equals(canal.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ese canal de YouTube ya está en el directorio: es de "
                            + nombreDelDueno(ocupado.get()) + ".");
        }

        // Cómo estaba, para saber qué dar de baja en el hub y a quién más
        // hay que volver a copiar a testing.
        List<String> antes = deYouTube(List.of(canal));
        UUID creadorAnterior = canal.getCreadorId();
        UUID productoraAnterior = canal.getProductoraId();
        boolean nuevo = canal.getId() == null;
        boolean cambiaDeDueno = nuevo || !Objects.equals(creadorAnterior, creadorId)
                || (creadorId == null && !Objects.equals(productoraAnterior, productoraId));

        String nombre = limpiar(peticion.nombre());
        if (nombre != null && nombre.length() > MAXIMO_NOMBRE) nombre = nombre.substring(0, MAXIMO_NOMBRE);

        canal.setNombre(nombre);
        canal.setUrl(url);
        canal.setHandle(limpiar(peticion.handle()));
        canal.setChannelId(channelId);
        canal.setCreadorId(creadorId);
        canal.setProductoraId(productoraId);
        if (cambiaDeDueno) {
            // Al final de la lista de su nuevo dueño: no le quita el sitio a
            // su canal principal.
            List<Canal> hermanos = creadorId != null
                    ? canales.deCreador(creadorId)
                    : canales.propiosDeProductora(productoraId);
            canal.setOrden(hermanos.stream()
                    .filter(k -> !k.getId().equals(canal.getId()))
                    .mapToInt(Canal::getOrden).max().orElse(-1) + 1);
        }
        ponerVinculados(canal, conQuien);

        canales.saveAndFlush(canal);

        // Lo que publicó cuando no tenía creador pasa a ser de su creador;
        // si no, esos videos se quedarían sin nadie que los firme.
        if (creadorId != null && creadorAnterior == null && !nuevo) {
            publicaciones.adoptar(canal.getId(), creadorId);
        }

        boolean visible = dueno != null ? dueno.isActivo() : casa.isActivo();

        Set<UUID> creadoresAfectados = new LinkedHashSet<>();
        Set<UUID> productorasAfectadas = new LinkedHashSet<>();
        anotarDueno(creadorId, productoraId, creadoresAfectados, productorasAfectadas);
        if (!nuevo) anotarDueno(creadorAnterior, productoraAnterior, creadoresAfectados, productorasAfectadas);

        return new Ficha(canal, Cambio.de(antes, deYouTube(List.of(canal)), visible),
                creadoresAfectados, productorasAfectadas);
    }

    /**
     * Quita un canal de YouTube del directorio. Lo que publicó sigue siendo
     * de su creador; si no tenía, se va con él.
     */
    @Transactional
    public Ficha eliminarFicha(UUID canalId) {
        Canal canal = canales.findById(canalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Ese canal ya no existe."));

        List<String> suyo = deYouTube(List.of(canal));
        Set<UUID> creadoresAfectados = new LinkedHashSet<>();
        Set<UUID> productorasAfectadas = new LinkedHashSet<>();
        anotarDueno(canal.getCreadorId(), canal.getProductoraId(), creadoresAfectados, productorasAfectadas);

        publicaciones.borrarSinCreadorDe(List.of(canal.getId()));
        canales.delete(canal);
        canales.flush();

        return new Ficha(canal, Cambio.de(suyo, List.of(), false), creadoresAfectados, productorasAfectadas);
    }

    /** El dueño es el creador; sin creador, la productora. */
    private static void anotarDueno(UUID creadorId, UUID productoraId,
                                    Set<UUID> creadores, Set<UUID> productoras) {
        if (creadorId != null) creadores.add(creadorId);
        else if (productoraId != null) productoras.add(productoraId);
    }

    /**
     * Un canal de YouTube es de un solo dueño. El índice único de la base lo
     * garantiza de todos modos; esto es para decirlo con nombre y apellido en
     * vez de con un error 500.
     */
    private void comprobarQueNoSonDeOtro(List<Dtos.GuardarCanal> pedidos, Set<UUID> propios) {
        for (Dtos.GuardarCanal pedido : pedidos) {
            if (!Canal.YOUTUBE.equals(pedido.plataforma()) || pedido.channelId() == null) continue;

            Optional<Canal> ocupado = canales.porCanalDeYouTube(pedido.channelId());
            if (ocupado.isPresent() && !propios.contains(ocupado.get().getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Ese canal de YouTube ya está en el directorio: es de "
                                + nombreDelDueno(ocupado.get()) + ".");
            }
        }
    }

    private String nombreDelDueno(Canal canal) {
        if (canal.getCreadorId() != null) {
            return creadores.findById(canal.getCreadorId())
                    .map(c -> c.getNombre()).orElse("otro creador");
        }
        return productoras.findById(canal.getProductoraId())
                .map(p -> "el medio " + p.getNombre()).orElse("un medio");
    }

    // -------------------------------------------------------------------------
    // Decisiones puras: sin base de datos, para poder probarlas
    // -------------------------------------------------------------------------

    /** Un pedido y, si lo hay, el canal ya guardado sobre el que se escribe. */
    record Asignacion(Canal existente, Dtos.GuardarCanal pedido) {}

    record Plan(List<Asignacion> asignaciones, List<Canal> sobrantes) {}

    /**
     * Limpia y valida lo que manda el panel. Lanza 400 con un mensaje que se
     * puede mostrar tal cual.
     */
    static List<Dtos.GuardarCanal> normalizar(List<Dtos.GuardarCanal> pedidos) {
        List<Dtos.GuardarCanal> limpios = new ArrayList<>();
        Set<String> vistos = new HashSet<>();

        for (Dtos.GuardarCanal pedido : pedidos != null ? pedidos : List.<Dtos.GuardarCanal>of()) {
            if (pedido == null) continue;

            String plataforma = limpiar(pedido.plataforma());
            if (plataforma == null || !Dtos.PLATAFORMAS.contains(plataforma)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Plataforma no válida: " + pedido.plataforma());
            }

            String url = limpiar(pedido.url());
            if (url == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "A uno de los canales le falta el enlace.");
            }

            String channelId = limpiar(pedido.channelId());
            if (Canal.YOUTUBE.equals(plataforma) && channelId != null && !vistos.add(channelId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El mismo canal de YouTube aparece dos veces.");
            }

            String nombre = limpiar(pedido.nombre());
            if (nombre != null && nombre.length() > MAXIMO_NOMBRE) {
                nombre = nombre.substring(0, MAXIMO_NOMBRE);
            }

            limpios.add(new Dtos.GuardarCanal(pedido.id(), plataforma, nombre, url,
                    limpiar(pedido.handle()), channelId, pedido.productoraId(),
                    pedido.creadores() == null ? null
                            : pedido.creadores().stream().filter(Objects::nonNull).distinct().toList()));
        }
        return limpios;
    }

    /**
     * Empareja cada pedido con el canal que ya existe, si lo hay.
     *
     * Reutilizar la fila importa: conserva el id del canal, y con él de qué
     * canal salió cada publicación y a cuáles está suscrita cada persona en
     * YouTube. Se busca por id; si no, por channel_id; si no, por enlace. Las
     * dos últimas hacen falta para la copia a testing, donde los ids de los
     * canales no coinciden con los de producción.
     *
     * Lo que no se empareja con nada es nuevo, y lo que queda sin pedir sobra.
     */
    static Plan planear(List<Canal> actuales, List<Dtos.GuardarCanal> pedidos) {
        List<Canal> libres = new ArrayList<>(actuales);
        Canal[] elegido = new Canal[pedidos.size()];

        // Tres pasadas, de la coincidencia más segura a la menos: así un
        // pedido sin id no le quita la fila a otro que sí la nombra.
        for (int pasada = 0; pasada < 3; pasada++) {
            for (int i = 0; i < pedidos.size(); i++) {
                if (elegido[i] != null) continue;

                Dtos.GuardarCanal pedido = pedidos.get(i);
                for (Canal canal : libres) {
                    if (coincide(pasada, canal, pedido)) {
                        elegido[i] = canal;
                        libres.remove(canal);
                        break;
                    }
                }
            }
        }

        List<Asignacion> asignaciones = new ArrayList<>();
        for (int i = 0; i < pedidos.size(); i++) {
            asignaciones.add(new Asignacion(elegido[i], pedidos.get(i)));
        }
        return new Plan(asignaciones, libres);
    }

    private static boolean coincide(int pasada, Canal canal, Dtos.GuardarCanal pedido) {
        return switch (pasada) {
            case 0 -> pedido.id() != null && pedido.id().equals(canal.getId());
            case 1 -> pedido.channelId() != null
                    && Objects.equals(pedido.plataforma(), canal.getPlataforma())
                    && pedido.channelId().equals(canal.getChannelId());
            default -> Objects.equals(pedido.plataforma(), canal.getPlataforma())
                    && pedido.url().equals(canal.getUrl());
        };
    }

    /**
     * Traduce el formato anterior (un enlace por plataforma) a una lista de
     * canales, para el panel y la producción que todavía mandan `conexiones`.
     *
     * Solo toca el canal principal de cada plataforma: lo actualiza, lo crea o
     * lo quita. Los demás canales del creador, que ese formato no sabe
     * nombrar, se quedan como están en vez de borrarse.
     */
    static List<Dtos.GuardarCanal> desdeConexiones(List<Canal> actuales,
                                                   Map<String, Dtos.ConexionDto> conexiones) {
        List<Dtos.GuardarCanal> pedidos = new ArrayList<>();

        for (String plataforma : Dtos.PLATAFORMAS) {
            List<Canal> dePlataforma = actuales.stream()
                    .filter(k -> plataforma.equals(k.getPlataforma()))
                    .toList();
            Canal principal = dePlataforma.isEmpty() ? null : dePlataforma.get(0);

            Dtos.ConexionDto conexion = conexiones.get(plataforma);
            if (conexion != null && limpiar(conexion.url()) != null) {
                pedidos.add(new Dtos.GuardarCanal(
                        principal != null ? principal.getId() : null,
                        plataforma,
                        principal != null ? principal.getNombre() : null,
                        conexion.url(), conexion.handle(), conexion.channelId(),
                        principal != null ? principal.getProductoraId() : null, null));
            }

            dePlataforma.stream().skip(1).forEach(k -> pedidos.add(comoPedido(k)));
        }
        return pedidos;
    }

    /** El canal tal como está, para guardar "lo mismo que había". */
    static Dtos.GuardarCanal comoPedido(Canal k) {
        return new Dtos.GuardarCanal(k.getId(), k.getPlataforma(), k.getNombre(), k.getUrl(),
                k.getHandle(), k.getChannelId(), k.getProductoraId(),
                List.copyOf(k.getVinculados()));
    }

    static List<String> deYouTube(Collection<Canal> lista) {
        return lista.stream().map(Canal::getCanalDeYouTube).filter(Objects::nonNull).toList();
    }

    private static String limpiar(String texto) {
        if (texto == null) return null;
        String limpio = texto.trim();
        return limpio.isEmpty() ? null : limpio;
    }
}
