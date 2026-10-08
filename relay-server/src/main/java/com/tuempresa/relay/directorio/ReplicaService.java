package com.tuempresa.relay.directorio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuempresa.relay.config.RelayProperties;
import com.tuempresa.relay.directorio.CanalesService.Cambio;
import com.tuempresa.relay.modelo.Canal;
import com.tuempresa.relay.modelo.Creador;
import com.tuempresa.relay.modelo.Dtos;
import com.tuempresa.relay.modelo.Productora;
import com.tuempresa.relay.modelo.Repositorios;
import jakarta.annotation.PreDestroy;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Copia de creadores y productoras de producción a testing.
 *
 * Producción (REPLICA_URL puesto) manda cada creador que se guarda en su panel
 * a POST /internal/replica/creadores de testing, y cada productora a
 * /internal/replica/productoras. Testing los guarda con un id propio, anota de
 * qué fila de producción son copia y se suscribe por su cuenta al hub de
 * YouTube, con su propia URL de callback.
 *
 * Los ids de producción no valen en testing, así que las referencias se
 * traducen al llegar: la productora de un canal y las productoras de un
 * creador se buscan por su id de origen. Por eso una productora tiene que
 * estar copiada antes que los creadores que la nombran; la copia completa las
 * manda primero. Si no está, el creador se guarda igual, sin esa liga.
 *
 * Los borrados no se copian: retirar algo en producción lo deja como está en
 * testing.
 */
@Service
public class ReplicaService {

    private static final Logger log = LoggerFactory.getLogger(ReplicaService.class);

    public static final String CABECERA = "X-Token-Replica";
    public static final String RUTA = "/internal/replica/creadores";
    public static final String RUTA_PRODUCTORAS = "/internal/replica/productoras";

    private final Repositorios.Creadores creadores;
    private final Repositorios.Canales canales;
    private final Repositorios.Productoras productoras;
    private final CreadoresService servicio;
    private final ProductorasService servicioDeProductoras;
    private final CanalesService canalesService;
    private final RestClient http;
    private final RelayProperties config;
    private final Validator validador;
    private final ObjectMapper json = new ObjectMapper();

    // El hub de YouTube tarda entre medio segundo y veinte en contestar. Las
    // suscripciones de lo recibido van aquí, de una en una y fuera de la
    // petición: así producción no espera al hub de testing y una migración de
    // muchos creadores no lo bombardea.
    private final ExecutorService cola = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("replica-websub-", 0).factory());

    public ReplicaService(Repositorios.Creadores creadores, Repositorios.Canales canales,
                          Repositorios.Productoras productoras,
                          CreadoresService servicio, ProductorasService servicioDeProductoras,
                          CanalesService canalesService,
                          RestClient http, RelayProperties config, Validator validador) {
        this.creadores = creadores;
        this.canales = canales;
        this.productoras = productoras;
        this.servicio = servicio;
        this.servicioDeProductoras = servicioDeProductoras;
        this.canalesService = canalesService;
        this.http = http;
        this.config = config;
        this.validador = validador;
    }

    @PreDestroy
    void cerrar() {
        cola.shutdownNow();
    }

    // -------------------------------------------------------------------------
    // Lado que envía (producción)
    // -------------------------------------------------------------------------

    /**
     * Manda un creador al servidor de testing, con todos sus canales.
     *
     * @return null si se copió o si este servidor no replica; si falló, el
     *         motivo en una frase que el panel puede mostrar. Nunca lanza: un
     *         fallo de testing no debe estropear un guardado de producción.
     */
    public String enviar(Creador creador) {
        if (!config.replica().envia()) return null;
        return mandar(RUTA, cuerpoDe(creador, canales.deCreador(creador.getId())),
                "Creador " + creador.getId());
    }

    /** Lo mismo para una productora, con sus canales propios. */
    public String enviar(Productora productora) {
        if (!config.replica().envia()) return null;
        return mandar(RUTA_PRODUCTORAS,
                cuerpoDe(productora, canales.propiosDeProductora(productora.getId())),
                "Productora " + productora.getId());
    }

    private String mandar(String ruta, Object cuerpo, String que) {
        RelayProperties.Replica replica = config.replica();
        if (replica.token().isBlank()) {
            return "Falta REPLICA_TOKEN en este servidor.";
        }

        String motivo;
        try {
            http.post()
                    .uri(destino(replica.url(), ruta))
                    .header(CABECERA, replica.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(cuerpo)
                    .retrieve()
                    .toBodilessEntity();

            log.info("{} copiado a testing", que);
            return null;

        } catch (RestClientResponseException e) {
            int codigo = e.getStatusCode().value();
            motivo = codigo == 404
                    ? "testing no reconoce la petición (404). Comprueba que REPLICA_TOKEN sea "
                            + "el mismo en los dos servidores y que testing ya tenga esta versión."
                    : "testing respondió " + codigo + mensajeDe(e.getResponseBodyAsString());
        } catch (Exception e) {
            motivo = "no se pudo conectar con testing: " + e.getMessage();
        }

        log.warn("{} no copiado a testing: {}", que, motivo);
        return motivo;
    }

    /**
     * Manda todas las productoras y todos los creadores. Lo usa
     * scripts/vps/replicar-creadores.sh.
     *
     * Las productoras van primero: los creadores las nombran, y testing solo
     * puede ligarlos a las que ya conoce.
     */
    public Dtos.ResultadoReplica enviarTodos() {
        if (!config.replica().envia()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este servidor no tiene REPLICA_URL: no hay a dónde copiar.");
        }

        List<Productora> casas = productoras.findAll().stream()
                .sorted(Comparator.comparing(Productora::getNombre))
                .toList();
        List<Creador> todos = creadores.findAll().stream()
                .sorted(Comparator.comparing(Creador::getNombre))
                .toList();

        int replicados = 0;
        List<String> errores = new ArrayList<>();

        for (Productora productora : casas) {
            String fallo = enviar(productora);
            if (fallo == null) replicados++;
            else errores.add("Medio " + productora.getNombre() + ": " + fallo);
        }
        for (Creador creador : todos) {
            String fallo = enviar(creador);
            if (fallo == null) replicados++;
            else errores.add(creador.getNombre() + ": " + fallo);
        }

        // Segunda vuelta para quien tiene canales que aparecen con otros
        // creadores: en la primera, testing todavía no conocía a todos y dejó
        // caer esas ligas. Ahora ya están copiados.
        for (Productora productora : casas) {
            if (!conCompartidos(canales.propiosDeProductora(productora.getId()))) continue;
            String fallo = enviar(productora);
            if (fallo != null) errores.add("Medio " + productora.getNombre() + " (canales compartidos): " + fallo);
        }
        for (Creador creador : todos) {
            if (!conCompartidos(canales.deCreador(creador.getId()))) continue;
            String fallo = enviar(creador);
            if (fallo != null) errores.add(creador.getNombre() + " (canales compartidos): " + fallo);
        }

        int total = casas.size() + todos.size();
        log.info("Copia completa a testing: {} de {} ({} productoras, {} creadores)",
                replicados, total, casas.size(), todos.size());
        return new Dtos.ResultadoReplica(total, replicados, errores.size(), errores);
    }

    private static boolean conCompartidos(List<Canal> lista) {
        return lista.stream().anyMatch(k -> !k.getVinculados().isEmpty());
    }

    /**
     * El id viaja como origen: testing no lo usa como id propio.
     *
     * Lleva los canales en los dos formatos. `canales` es el completo;
     * `conexiones` (uno por plataforma) es el que entiende un testing que
     * todavía no tenga esta versión.
     */
    static Dtos.GuardarCreador cuerpoDe(Creador creador, List<Canal> suyos) {
        Map<String, Dtos.ConexionDto> conexiones = new LinkedHashMap<>();
        Dtos.ConexionDto.principales(suyos).forEach(cx -> conexiones.put(cx.plataforma(), cx));

        return new Dtos.GuardarCreador(creador.getId(), creador.getNombre(), creador.getCategoria(),
                creador.getBio(), creador.getFotoUrl(), conexiones, creador.isActivo(),
                suyos.stream().map(CanalesService::comoPedido).toList(),
                List.copyOf(creador.getProductoras()));
    }

    /**
     * Sin la lista de creadores: quién figura en una productora viaja con cada
     * creador, que es quien sabe en cuáles está.
     */
    static Dtos.GuardarProductora cuerpoDe(Productora productora, List<Canal> propios) {
        return new Dtos.GuardarProductora(productora.getId(), productora.getNombre(),
                productora.getDescripcion(), productora.getLogoUrl(), productora.isActivo(),
                propios.stream().map(CanalesService::comoPedido).toList(), null,
                productora.isEnDirectorio(), productora.getCategoria());
    }

    static String destino(String url) {
        return destino(url, RUTA);
    }

    static String destino(String url, String ruta) {
        String base = url.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + ruta;
    }

    /** Saca el campo "message" del error de testing, si viene. */
    private String mensajeDe(String cuerpo) {
        try {
            String mensaje = json.readTree(cuerpo).path("message").asText("");
            return mensaje.isBlank() ? "" : ": " + mensaje;
        } catch (Exception e) {
            return "";
        }
    }

    // -------------------------------------------------------------------------
    // Lado que recibe (testing)
    // -------------------------------------------------------------------------

    /** Lo que hace falta para sincronizar el hub después de guardar. */
    public record Recibido(UUID id, boolean nuevo, Cambio cambio) {}

    /**
     * El token compartido. Comparación en tiempo constante, y nunca coincide
     * si este servidor no recibe copias.
     */
    public boolean autoriza(String token) {
        return config.replica().recibe() && coincide(config.replica().token(), token);
    }

    public static boolean coincide(String esperado, String recibido) {
        if (esperado == null || esperado.isBlank() || recibido == null) return false;
        return MessageDigest.isEqual(
                esperado.getBytes(StandardCharsets.UTF_8),
                recibido.getBytes(StandardCharsets.UTF_8));
    }

    @Transactional
    public Recibido recibir(Dtos.GuardarCreador peticion) {
        // Se valida aquí y no con @Valid en el controlador para que el token
        // se compruebe antes que el cuerpo.
        validar(peticion);

        UUID origen = peticion.id();
        if (origen == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el id que el creador tiene en el servidor de origen.");
        }

        Creador porOrigen = creadores.findByOrigenId(origen).orElse(null);
        Creador porCanal = duenoDeAqui(canalesDe(peticion), porOrigen);

        Creador creador = elegirDestino(porOrigen, porCanal);
        boolean nuevo = creador.getId() == null;
        creador.setOrigenId(origen);

        Cambio cambio = servicio.aplicar(creador, traducir(peticion));

        log.info("Creador {} {} por copia de {}", creador.getId(),
                nuevo ? "creado" : "actualizado", origen);
        return new Recibido(creador.getId(), nuevo, cambio);
    }

    @Transactional
    public Recibido recibir(Dtos.GuardarProductora peticion) {
        validar(peticion);

        UUID origen = peticion.id();
        if (origen == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el id que la productora tiene en el servidor de origen.");
        }

        Productora productora = productoras.findByOrigenId(origen).orElseGet(Productora::new);
        boolean nueva = productora.getId() == null;
        productora.setOrigenId(origen);

        // Los ids de los canales son de producción: aquí se emparejan por
        // channel_id o por enlace. Quién figura en ella no se toca (ver
        // cuerpoDe); con qué creadores aparece cada canal, sí.
        Dtos.GuardarProductora local = new Dtos.GuardarProductora(null, peticion.nombre(),
                peticion.descripcion(), peticion.logoUrl(), peticion.activo(),
                peticion.canales() == null ? null
                        : peticion.canales().stream()
                                .filter(Objects::nonNull)
                                .map(k -> sinIds(k, null))
                                .toList(),
                null, peticion.enDirectorio(), peticion.categoria());

        Cambio cambio = servicioDeProductoras.aplicar(productora, local);

        log.info("Productora {} {} por copia de {}", productora.getId(),
                nueva ? "creada" : "actualizada", origen);
        return new Recibido(productora.getId(), nueva, cambio);
    }

    private <T> void validar(T peticion) {
        Set<ConstraintViolation<T>> fallos = validador.validate(peticion);
        if (!fallos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    fallos.iterator().next().getMessage());
        }
    }

    /**
     * Cambia las referencias de producción por las de aquí.
     *
     * Una productora que testing todavía no conoce se deja caer: el creador
     * se guarda sin esa liga y la recupera la próxima vez que se copie.
     */
    private Dtos.GuardarCreador traducir(Dtos.GuardarCreador peticion) {
        Map<UUID, UUID> aLocal = new LinkedHashMap<>();
        Function<UUID, UUID> local = origen -> {
            if (origen == null) return null;
            if (!aLocal.containsKey(origen)) {
                UUID id = productoras.findByOrigenId(origen).map(Productora::getId).orElse(null);
                if (id == null) log.warn("La productora {} de producción no está copiada aquí", origen);
                aLocal.put(origen, id);
            }
            return aLocal.get(origen);
        };

        List<Dtos.GuardarCanal> suyos = peticion.canales() == null ? null
                : peticion.canales().stream()
                        .filter(Objects::nonNull)
                        .map(k -> sinIds(k, local.apply(k.productoraId())))
                        .toList();

        List<UUID> casas = peticion.productoras() == null ? null
                : peticion.productoras().stream().map(local).filter(Objects::nonNull).toList();

        return new Dtos.GuardarCreador(peticion.id(), peticion.nombre(), peticion.categoria(),
                peticion.bio(), peticion.fotoUrl(), peticion.conexiones(), peticion.activo(),
                suyos, casas);
    }

    /**
     * El canal con las referencias de aquí. Un creador con el que aparece y
     * que testing todavía no conoce se deja caer, igual que una productora:
     * la liga vuelve la próxima vez que se copie.
     */
    private Dtos.GuardarCanal sinIds(Dtos.GuardarCanal k, UUID productoraLocal) {
        List<UUID> conQuien = k.creadores() == null ? null
                : k.creadores().stream()
                        .filter(Objects::nonNull)
                        .map(origen -> creadores.findByOrigenId(origen).map(Creador::getId).orElse(null))
                        .filter(Objects::nonNull)
                        .toList();

        return new Dtos.GuardarCanal(null, k.plataforma(), k.nombre(), k.url(),
                k.handle(), k.channelId(), productoraLocal, conQuien);
    }

    /**
     * El creador de aquí que ya tiene alguno de esos canales de YouTube, sin
     * contar a la propia copia. Si son dos creadores distintos, no hay forma
     * de guardar la copia sin dejar un canal con dos dueños.
     */
    private Creador duenoDeAqui(List<String> deYouTube, Creador porOrigen) {
        Creador dueno = null;
        boolean esLaCopia = false;

        for (String canal : deYouTube) {
            UUID creadorId = canales.porCanalDeYouTube(canal).map(Canal::getCreadorId).orElse(null);
            if (creadorId == null) continue;

            if (porOrigen != null && creadorId.equals(porOrigen.getId())) {
                esLaCopia = true;
                continue;
            }
            if (dueno != null && !dueno.getId().equals(creadorId)) {
                throw conflicto(dueno);
            }
            if (dueno == null) dueno = creadores.findById(creadorId).orElse(null);
        }

        return dueno != null ? dueno : (esLaCopia ? porOrigen : null);
    }

    /**
     * Sobre qué fila se guarda la copia.
     *
     * @param porOrigen el creador que ya es copia de este mismo origen, o null
     * @param porCanal  el creador de aquí que ya tiene ese canal de YouTube, o null
     */
    static Creador elegirDestino(Creador porOrigen, Creador porCanal) {
        if (porOrigen != null) {
            // Si el canal lo tuviera otro creador, quedarían dos con el mismo
            // canal y el webhook no sabría de quién es cada video.
            if (porCanal != null && !porCanal.getId().equals(porOrigen.getId())) {
                throw conflicto(porCanal);
            }
            return porOrigen;
        }

        if (porCanal == null) return new Creador();

        // Dado de alta a mano en testing antes de que existiera la copia: se
        // adopta en lugar de duplicarlo. Si ya es copia de otro creador, no.
        if (porCanal.getOrigenId() != null) throw conflicto(porCanal);
        return porCanal;
    }

    private static ResponseStatusException conflicto(Creador dueno) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "En testing ese canal de YouTube ya es de otro creador: " + dueno.getNombre() + ".");
    }

    /**
     * Los canales de YouTube que trae la petición, en cualquiera de los dos
     * formatos: la lista de canales o, si no viene, el enlace por plataforma.
     */
    static List<String> canalesDe(Dtos.GuardarCreador peticion) {
        Set<String> ids = new LinkedHashSet<>();

        if (peticion.canales() != null) {
            for (Dtos.GuardarCanal k : peticion.canales()) {
                if (k != null && Canal.YOUTUBE.equals(k.plataforma())
                        && k.channelId() != null && !k.channelId().isBlank()) {
                    ids.add(k.channelId().trim());
                }
            }
        } else {
            Dtos.ConexionDto youtube = peticion.conexionesSeguras().get(Canal.YOUTUBE);
            if (youtube != null && youtube.channelId() != null && !youtube.channelId().isBlank()) {
                ids.add(youtube.channelId().trim());
            }
        }
        return List.copyOf(ids);
    }

    /** Altas y bajas en el hub, en segundo plano y de una en una. */
    public void sincronizarDespues(Recibido recibido) {
        if (recibido.cambio().vacio()) return;

        cola.execute(() -> {
            try {
                canalesService.sincronizarWebSub(recibido.cambio());
            } catch (Exception e) {
                // Queda en ERROR en la tabla de suscripciones y la repesca de
                // cada 15 minutos lo vuelve a intentar.
                log.warn("Suscripción pendiente para la copia {}: {}",
                        recibido.id(), e.getMessage());
            }
        });
    }
}
