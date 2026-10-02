package com.tuempresa.relay.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuempresa.relay.config.RelayProperties;
import com.tuempresa.relay.modelo.Dtos;
import com.tuempresa.relay.modelo.Repositorios;
import com.tuempresa.relay.modelo.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "¿Estoy suscrito en YouTube a este creador?"
 *
 * Seguir a alguien aquí y estar suscrito a su canal son cosas distintas, y la
 * gente no siempre sabe cuál de las dos tiene. Esto lo contesta.
 *
 * El reparto de trabajo, en orden:
 *
 *   1. La app pide permiso a la persona (solo lectura de YouTube) y obtiene de
 *      Google un token de acceso que dura una hora.
 *   2. La app manda ese token aquí cada vez que se abre.
 *   3. Aquí comprobamos que el token es de esta app y de esta cuenta, le
 *      preguntamos a YouTube por los canales del directorio y guardamos la
 *      respuesta.
 *
 * Lo que NO hay a propósito: refresh tokens ni client secret. El servidor no
 * puede consultar YouTube por su cuenta mientras la app está cerrada, y por lo
 * mismo no guarda ninguna credencial de Google que se pueda filtrar. Para lo
 * que hace falta —saberlo al entrar— sobra con el token de una hora.
 *
 * La tabla se maneja con JdbcTemplate y no con una entidad: son tres consultas
 * y una clave compuesta, y una entidad JPA con @IdClass ocuparía más que todo
 * este archivo.
 */
@Service
public class SuscripcionesYouTubeService {

    private static final Logger log = LoggerFactory.getLogger(SuscripcionesYouTubeService.class);

    private static final String TOKENINFO = "https://oauth2.googleapis.com/tokeninfo";

    /**
     * Permisos que dejan leer suscripciones. La app pide el primero, que es el
     * de solo lectura; los otros dos lo incluyen.
     */
    static final Set<String> PERMISOS_VALIDOS = Set.of(
            "https://www.googleapis.com/auth/youtube.readonly",
            "https://www.googleapis.com/auth/youtube",
            "https://www.googleapis.com/auth/youtube.force-ssl");

    /** Lo que YouTube nos presta se refresca o se borra antes de 30 días. */
    private static final int DIAS_DE_RETENCION = 30;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaccion;
    private final RestClient http;
    private final YouTubeClient youtube;
    private final Repositorios.Usuarios usuarios;
    private final RelayProperties config;

    private final Map<UUID, Cubeta> cubetas = new ConcurrentHashMap<>();

    public SuscripcionesYouTubeService(JdbcTemplate jdbc, TransactionTemplate transaccion,
                                       RestClient http, YouTubeClient youtube,
                                       Repositorios.Usuarios usuarios, RelayProperties config) {
        this.jdbc = jdbc;
        this.transaccion = transaccion;
        this.http = http;
        this.youtube = youtube;
        this.usuarios = usuarios;
        this.config = config;
    }

    // -------------------------------------------------------------------------
    // Lo que consume la app
    // -------------------------------------------------------------------------

    /** Lo último que se comprobó, sin llamar a nadie. Para pintar al instante. */
    public Dtos.SuscripcionesYouTube guardadas(UUID usuarioId) {
        record Fila(UUID creador, boolean suscrito, Instant cuando) {}

        // El join descarta a los creadores que salieron del directorio después
        // de la última comprobación.
        List<Fila> filas = jdbc.query("""
                select y.creador_id, y.suscrito, y.verificado_en
                from youtube_suscripciones y
                join creadores c on c.id = y.creador_id
                where y.usuario_id = ? and c.activo
                """,
                (rs, n) -> new Fila(
                        rs.getObject("creador_id", UUID.class),
                        rs.getBoolean("suscrito"),
                        rs.getTimestamp("verificado_en").toInstant()),
                usuarioId);

        List<UUID> suscritos = new ArrayList<>();
        List<UUID> noSuscritos = new ArrayList<>();
        Instant verificadoEn = null;

        for (Fila fila : filas) {
            (fila.suscrito() ? suscritos : noSuscritos).add(fila.creador());
            if (verificadoEn == null || fila.cuando().isAfter(verificadoEn)) {
                verificadoEn = fila.cuando();
            }
        }

        return new Dtos.SuscripcionesYouTube(verificadoEn, suscritos, noSuscritos);
    }

    /**
     * Comprueba contra YouTube y deja guardado el resultado.
     *
     * Los códigos de error están elegidos para que la app sepa qué hacer sin
     * leer el mensaje:
     *   403: esta cuenta no puede usar la función (no entró con Google, o el
     *        permiso es de otra cuenta). Reintentar no arregla nada.
     *   412: el token no sirve. La app lo tira, pide otro y reintenta.
     *   503: Google o YouTube fallaron. Se reintenta más tarde.
     * Nunca 401: la app interpreta un 401 como "mi sesión de Relé murió" y la
     * borra, y aquí el problema sería el token de Google, no el nuestro.
     */
    public Dtos.SuscripcionesYouTube verificar(UUID usuarioId, String tokenDeAcceso) {
        Usuario usuario = usuarios.findById(usuarioId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Tu sesión ya no es válida. Vuelve a abrir la app."));

        if (!Usuario.GOOGLE.equals(usuario.getProveedor())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Para ver tus suscripciones de YouTube, primero guarda tu cuenta con Google.");
        }

        // Cada comprobación gasta cuota del mismo proyecto que usa el webhook
        // para armar las notificaciones. Si una cuenta pide demasiadas
        // seguidas, contestamos con lo que ya sabíamos en vez de gastar más.
        Cubeta cubeta = cubetas.computeIfAbsent(usuarioId, id -> new Cubeta(Instant.now()));
        if (!cubeta.gastar(Instant.now())) {
            log.debug("Cuenta {} sin saldo de comprobaciones; se devuelve lo guardado", usuarioId);
            return guardadas(usuarioId);
        }

        comprobarToken(tokenDeAcceso, usuario);

        record Canal(UUID creador, String channelId) {}

        List<Canal> canales = jdbc.query("""
                select c.id, cx.channel_id
                from creadores c
                join conexiones cx on cx.creador_id = c.id
                where c.activo and cx.plataforma = 'youtube'
                  and cx.channel_id is not null and cx.channel_id <> ''
                """,
                (rs, n) -> new Canal(rs.getObject("id", UUID.class), rs.getString("channel_id")));

        Set<String> suscritos;
        try {
            suscritos = canales.isEmpty()
                    ? Set.of()
                    : youtube.suscripcionesDe(tokenDeAcceso, canales.stream().map(Canal::channelId).toList());
        } catch (YouTubeClient.TokenRechazado e) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "El permiso de YouTube caducó. Vuelve a conectarlo.");
        } catch (YouTubeClient.FalloDeYouTube e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "YouTube no contestó. Inténtalo en un momento.");
        }

        List<Object[]> filas = canales.stream()
                .map(c -> new Object[] { usuarioId, c.creador(), suscritos.contains(c.channelId()) })
                .toList();

        // Las llamadas a Google quedaron fuera de la transacción a propósito:
        // no se retiene una conexión de la base mientras se espera a la red.
        // Borrar y reinsertar también limpia a los creadores que ya no están.
        transaccion.executeWithoutResult(estado -> {
            jdbc.update("delete from youtube_suscripciones where usuario_id = ?", usuarioId);
            if (!filas.isEmpty()) {
                jdbc.batchUpdate("""
                        insert into youtube_suscripciones (usuario_id, creador_id, suscrito)
                        values (?, ?, ?)
                        """, filas);
            }
        });

        log.info("Suscripciones de YouTube comprobadas para {}: {} de {} canales",
                usuarioId, suscritos.size(), canales.size());

        return guardadas(usuarioId);
    }

    /**
     * Borra lo guardado. La app lo llama cuando la persona desconecta YouTube
     * o cuando descubre que retiró el permiso desde su cuenta de Google.
     */
    public void olvidar(UUID usuarioId) {
        int borradas = jdbc.update("delete from youtube_suscripciones where usuario_id = ?", usuarioId);
        if (borradas > 0) {
            log.info("Suscripciones de YouTube de {} olvidadas", usuarioId);
        }
    }

    // -------------------------------------------------------------------------
    // Limpieza
    // -------------------------------------------------------------------------

    /**
     * Las políticas de la API de YouTube no dejan conservar datos del usuario
     * más de 30 días sin refrescarlos. Quien abre la app los refresca solo;
     * esto borra los de quien dejó de abrirla.
     */
    @Scheduled(cron = "0 30 3 * * *", zone = "${relay.renovacion.zona}")
    public void purgarAntiguas() {
        int borradas = jdbc.update(
                "delete from youtube_suscripciones where verificado_en < now() - make_interval(days => ?)",
                DIAS_DE_RETENCION);
        if (borradas > 0) {
            log.info("Purgadas {} suscripciones de YouTube con más de {} días", borradas, DIAS_DE_RETENCION);
        }

        // De paso, las cubetas de quien ya recuperó todo su saldo no hacen falta.
        Instant ahora = Instant.now();
        cubetas.values().removeIf(c -> c.llena(ahora));
    }

    // -------------------------------------------------------------------------
    // Validación del token de Google
    // -------------------------------------------------------------------------

    /**
     * Le pregunta a Google de quién es el token y para qué sirve, antes de
     * usarlo. Tres comprobaciones:
     *
     *   · lo emitió un cliente OAuth de NUESTRO proyecto. Sin esto, alguien
     *     podría mandarnos un token que otra app le sacó a otra persona;
     *   · trae permiso para leer YouTube;
     *   · si dice de qué cuenta es, es la misma con la que se entró aquí.
     *
     * No cuesta cuota: tokeninfo no es parte de la Data API.
     */
    private void comprobarToken(String tokenDeAcceso, Usuario usuario) {
        JsonNode info;
        try {
            info = http.get()
                    .uri(URI.create(TOKENINFO + "?access_token="
                            + UriUtils.encodeQueryParam(tokenDeAcceso, StandardCharsets.UTF_8)))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // Google contesta 400 a un token caducado, revocado o inventado.
            if (e.getStatusCode().is4xxClientError()) {
                throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                        "El permiso de YouTube caducó. Vuelve a conectarlo.");
            }
            log.error("tokeninfo respondió {}", e.getStatusCode().value());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Google no contestó. Inténtalo en un momento.");
        } catch (RestClientException e) {
            log.error("tokeninfo no contestó: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Google no contestó. Inténtalo en un momento.");
        }

        String audiencia = texto(info, "aud");
        if (!mismoProyecto(config.google().clientId(), audiencia)) {
            // Si esto salta con usuarios reales, casi seguro GOOGLE_CLIENT_ID
            // es de un proyecto distinto al del cliente OAuth de Android.
            log.warn("Token de YouTube emitido para otro cliente: {}", audiencia);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Ese permiso de YouTube no es de esta app.");
        }

        if (!permiteLeerSuscripciones(texto(info, "scope"))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "Falta el permiso para ver tus suscripciones de YouTube.");
        }

        // Google solo incluye 'sub' si el token también lleva los permisos del
        // inicio de sesión. Cuando viene, tiene que coincidir.
        String sub = texto(info, "sub");
        if (sub != null && usuario.getProveedorSub() != null && !sub.equals(usuario.getProveedorSub())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "El permiso de YouTube es de otra cuenta de Google. Usa la misma con la que entraste.");
        }
    }

    /**
     * Todos los clientes OAuth de un proyecto de Google empiezan por el mismo
     * número de proyecto: "389825726990-xxxx.apps.googleusercontent.com".
     *
     * El token que saca la app de Android viene a nombre del cliente de tipo
     * Android, no del de tipo web que tenemos en GOOGLE_CLIENT_ID, así que no
     * se pueden comparar enteros. Sí se puede comparar el proyecto.
     */
    static boolean mismoProyecto(String clientIdWeb, String audiencia) {
        if (clientIdWeb == null || audiencia == null) return false;

        int guion = clientIdWeb.indexOf('-');
        if (guion <= 0) return false;

        return audiencia.startsWith(clientIdWeb.substring(0, guion + 1));
    }

    /** {@code scope} llega como una sola cadena con los permisos separados por espacios. */
    static boolean permiteLeerSuscripciones(String alcances) {
        if (alcances == null || alcances.isBlank()) return false;
        return Arrays.stream(alcances.trim().split("\\s+")).anyMatch(PERMISOS_VALIDOS::contains);
    }

    private static String texto(JsonNode nodo, String campo) {
        if (nodo == null) return null;
        JsonNode valor = nodo.get(campo);
        return valor != null && !valor.isNull() && !valor.asText().isBlank() ? valor.asText() : null;
    }

    // -------------------------------------------------------------------------
    // Límite por cuenta
    // -------------------------------------------------------------------------

    /**
     * Cubeta de fichas por cuenta: seis comprobaciones seguidas de margen y una
     * más cada dos minutos. Alguien que abre la app, va a YouTube a suscribirse
     * y vuelve, ni lo nota. Un bucle malintencionado se queda en unas 720
     * unidades al día en vez de vaciar las 10.000 del proyecto.
     *
     * Vive en memoria: hay un solo servidor, y si se reinicia lo peor que pasa
     * es que todo el mundo recupera su margen.
     */
    static final class Cubeta {

        static final int CAPACIDAD = 6;
        static final Duration RECARGA = Duration.ofMinutes(2);

        private double fichas = CAPACIDAD;
        private Instant ultima;

        Cubeta(Instant ahora) {
            this.ultima = ahora;
        }

        synchronized boolean gastar(Instant ahora) {
            recargar(ahora);
            if (fichas < 1) return false;
            fichas -= 1;
            return true;
        }

        synchronized boolean llena(Instant ahora) {
            recargar(ahora);
            return fichas >= CAPACIDAD;
        }

        private void recargar(Instant ahora) {
            long transcurrido = Duration.between(ultima, ahora).toMillis();
            if (transcurrido <= 0) return;

            fichas = Math.min(CAPACIDAD, fichas + transcurrido / (double) RECARGA.toMillis());
            ultima = ahora;
        }
    }
}
