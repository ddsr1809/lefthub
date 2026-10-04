package com.tuempresa.relay.directorio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuempresa.relay.config.RelayProperties;
import com.tuempresa.relay.modelo.Creador;
import com.tuempresa.relay.modelo.Dtos;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Copia de creadores de producción a testing.
 *
 * Producción (REPLICA_URL puesto) manda cada creador que se guarda en su panel
 * a POST /internal/replica/creadores de testing. Testing lo guarda con un id
 * propio, anota de qué creador de producción es copia y se suscribe por su
 * cuenta al hub de YouTube, con su propia URL de callback.
 *
 * Los borrados no se copian: retirar un creador en producción lo deja como
 * está en testing.
 */
@Service
public class ReplicaService {

    private static final Logger log = LoggerFactory.getLogger(ReplicaService.class);

    public static final String CABECERA = "X-Token-Replica";
    public static final String RUTA = "/internal/replica/creadores";

    private final Repositorios.Creadores creadores;
    private final CreadoresService servicio;
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

    public ReplicaService(Repositorios.Creadores creadores, CreadoresService servicio,
                          RestClient http, RelayProperties config, Validator validador) {
        this.creadores = creadores;
        this.servicio = servicio;
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
     * Manda un creador al servidor de testing.
     *
     * @return null si se copió o si este servidor no replica; si falló, el
     *         motivo en una frase que el panel puede mostrar. Nunca lanza: un
     *         fallo de testing no debe estropear un guardado de producción.
     */
    public String enviar(Creador creador) {
        RelayProperties.Replica replica = config.replica();
        if (!replica.envia()) return null;
        if (replica.token().isBlank()) {
            return "Falta REPLICA_TOKEN en este servidor.";
        }

        String motivo;
        try {
            http.post()
                    .uri(destino(replica.url()))
                    .header(CABECERA, replica.token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(cuerpoDe(creador))
                    .retrieve()
                    .toBodilessEntity();

            log.info("Creador {} copiado a testing", creador.getId());
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

        log.warn("Creador {} no copiado a testing: {}", creador.getId(), motivo);
        return motivo;
    }

    /** Manda todos los creadores. Lo usa scripts/vps/replicar-creadores.sh. */
    public Dtos.ResultadoReplica enviarTodos() {
        if (!config.replica().envia()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este servidor no tiene REPLICA_URL: no hay a dónde copiar.");
        }

        List<Creador> todos = creadores.findAll().stream()
                .sorted(Comparator.comparing(Creador::getNombre))
                .toList();

        int replicados = 0;
        List<String> errores = new ArrayList<>();

        for (Creador creador : todos) {
            String fallo = enviar(creador);
            if (fallo == null) replicados++;
            else errores.add(creador.getNombre() + ": " + fallo);
        }

        log.info("Copia completa a testing: {} de {} creadores", replicados, todos.size());
        return new Dtos.ResultadoReplica(todos.size(), replicados, errores.size(), errores);
    }

    /** El id viaja como origen: testing no lo usa como id propio. */
    static Dtos.GuardarCreador cuerpoDe(Creador creador) {
        Map<String, Dtos.ConexionDto> conexiones = new LinkedHashMap<>();
        creador.getConexiones().forEach((plataforma, cx) -> conexiones.put(plataforma,
                new Dtos.ConexionDto(plataforma, cx.getUrl(), cx.getHandle(), cx.getChannelId())));

        return new Dtos.GuardarCreador(creador.getId(), creador.getNombre(), creador.getCategoria(),
                creador.getBio(), creador.getFotoUrl(), conexiones, creador.isActivo());
    }

    static String destino(String url) {
        String base = url.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + RUTA;
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
    public record Recibido(UUID id, boolean nuevo, String canalPrevio, String canalNuevo, boolean activo) {}

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
        Set<ConstraintViolation<Dtos.GuardarCreador>> fallos = validador.validate(peticion);
        if (!fallos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    fallos.iterator().next().getMessage());
        }

        UUID origen = peticion.id();
        if (origen == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta el id que el creador tiene en el servidor de origen.");
        }

        String canal = canalDe(peticion);
        Creador porOrigen = creadores.findByOrigenId(origen).orElse(null);
        Creador porCanal = canal != null ? creadores.porCanalDeYouTube(canal).orElse(null) : null;

        Creador creador = elegirDestino(porOrigen, porCanal);
        boolean nuevo = creador.getId() == null;
        creador.setOrigenId(origen);

        String canalPrevio = servicio.aplicar(creador, peticion);

        log.info("Creador {} {} por copia de {}", creador.getId(),
                nuevo ? "creado" : "actualizado", origen);
        return new Recibido(creador.getId(), nuevo, canalPrevio,
                creador.getCanalDeYouTube(), creador.isActivo());
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

    static String canalDe(Dtos.GuardarCreador peticion) {
        Dtos.ConexionDto youtube = peticion.conexionesSeguras().get("youtube");
        if (youtube == null || youtube.channelId() == null || youtube.channelId().isBlank()) return null;
        return youtube.channelId().trim();
    }

    /** Alta o baja en el hub, en segundo plano y de una en una. */
    public void sincronizarDespues(Recibido recibido) {
        if (recibido.canalPrevio() == null && recibido.canalNuevo() == null) return;

        cola.execute(() -> {
            try {
                servicio.sincronizarWebSub(recibido.canalPrevio(), recibido.canalNuevo(), recibido.activo());
            } catch (Exception e) {
                // Queda en ERROR en la tabla de suscripciones y la repesca de
                // cada 15 minutos lo vuelve a intentar.
                log.warn("Suscripción pendiente para el creador copiado {}: {}",
                        recibido.id(), e.getMessage());
            }
        });
    }
}
