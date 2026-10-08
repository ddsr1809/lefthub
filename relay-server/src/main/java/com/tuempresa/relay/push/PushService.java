package com.tuempresa.relay.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;
import com.tuempresa.relay.config.RelayProperties;
import com.tuempresa.relay.modelo.Creador;
import com.tuempresa.relay.modelo.Productora;
import com.tuempresa.relay.youtube.YouTubeClient;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Envío de notificaciones por FCM HTTP v1.
 *
 * Hablamos directamente con la API REST en lugar de usar firebase-admin, que
 * arrastra Firestore, gRPC y unas cincuenta dependencias más para algo que son
 * dos llamadas HTTP. Lo único que necesitamos de la biblioteca de Google es el
 * token OAuth de la cuenta de servicio.
 *
 * FCM es lo único que sigue siendo de Google en esta arquitectura, y es así
 * porque no hay alternativa: Android e iOS solo aceptan push a través de sus
 * propios canales. Es gratis e ilimitado, sin tarjeta.
 *
 * Usamos topics: un envío alcanza a toda la audiencia de un creador, sin
 * fan-out ni almacenar tokens de dispositivo. Eso último simplifica además el
 * borrado de cuenta.
 *
 * Las productoras tienen su propio topic. Cuando un video tiene varias
 * audiencias (su creador, su productora, los demás creadores con los que
 * aparece el canal) no se manda un mensaje a cada una sino uno solo con una
 * condición ("sigue a este O a aquel"): FCM lo entrega una sola vez a cada
 * teléfono, aunque siga a varios.
 */
@Service
public class PushService {

    private static final Logger log = LoggerFactory.getLogger(PushService.class);

    // Deben coincidir letra por letra con los canales que crea la app de
    // Android. Si no coinciden, el aviso llega pero cae en "Otros".
    public static final String CANAL_PUBLICACIONES = "publicaciones";
    public static final String CANAL_AVISOS = "avisos";

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final RestClient http;
    private final RelayProperties config;
    private final ObjectMapper json = new ObjectMapper();

    private GoogleCredentials credenciales;
    private String urlEnvio;

    public PushService(RestClient http, RelayProperties config) {
        this.http = http;
        this.config = config;
    }

    @PostConstruct
    void preparar() {
        if (!config.fcm().estaConfigurado()) {
            log.warn("FCM sin configurar: los avisos se registrarán pero no se enviarán. "
                    + "Rellena FCM_PROYECTO_ID y FCM_CREDENCIALES.");
            return;
        }

        // El contenedor corre con un usuario sin privilegios. Si el JSON se
        // monta desde el host con permisos 600, ese usuario no puede leerlo y
        // el servidor arranca "sano" pero mudo. Lo decimos con todas las letras.
        Path ruta = Path.of(config.fcm().credenciales());
        if (!Files.isRegularFile(ruta) || !Files.isReadable(ruta)) {
            log.error("FCM DESACTIVADO: {} no existe, es un directorio o no es legible por el "
                    + "usuario del proceso. En el host: chmod 644 sobre el archivo de "
                    + "FCM_CREDENCIALES_HOST y recrea el contenedor.", ruta);
            return;
        }

        try (FileInputStream flujo = new FileInputStream(config.fcm().credenciales())) {
            credenciales = GoogleCredentials.fromStream(flujo).createScoped(List.of(SCOPE));
            urlEnvio = "https://fcm.googleapis.com/v1/projects/"
                    + config.fcm().proyectoId() + "/messages:send";
            log.info("FCM listo para el proyecto {}", config.fcm().proyectoId());

        } catch (Exception e) {
            log.error("No se pudieron leer las credenciales de FCM desde {}",
                    config.fcm().credenciales(), e);
        }
    }

    public static String topicDe(Object creadorId) {
        return "creator_" + creadorId;
    }

    public static String topicDeProductora(Object productoraId) {
        return "productora_" + productoraId;
    }

    /**
     * En nombre de quién sale un aviso y a quién le llega.
     *
     * Siempre hay creador, productora o los dos: es el dueño del canal. El
     * nombre que se lee en la notificación es el del creador; el de la
     * productora solo cuando el canal es suyo y de nadie más. {@code tambien}
     * son los demás creadores con los que aparece el canal: no firman, pero a
     * quienes los siguen también les llega.
     */
    public record Emisor(String nombre, UUID creadorId, UUID productoraId, List<UUID> tambien) {

        /** FCM no admite más de cinco topics en una condición. */
        static final int TOPICS_POR_MENSAJE = 5;

        public Emisor {
            tambien = tambien != null ? List.copyOf(tambien) : List.of();
        }

        public static Emisor de(Creador creador, Productora productora, Collection<UUID> tambien) {
            if (creador == null && productora == null) {
                throw new IllegalArgumentException("Un aviso necesita creador o productora.");
            }
            return new Emisor(
                    creador != null ? creador.getNombre() : productora.getNombre(),
                    creador != null ? creador.getId() : null,
                    productora != null ? productora.getId() : null,
                    tambien != null ? List.copyOf(tambien) : List.of());
        }

        public static Emisor de(Creador creador, Productora productora) {
            return de(creador, productora, List.of());
        }

        public static Emisor de(Creador creador) {
            return de(creador, null, List.of());
        }

        /** El topic de siempre; también agrupa los avisos en el teléfono. */
        public String topic() {
            return creadorId != null ? topicDe(creadorId) : topicDeProductora(productoraId);
        }

        /**
         * Todos los topics a los que va el aviso, sin repetir.
         *
         * La productora lleva dos: el suyo y el que le tocaría si fuera un
         * creador. Una versión de la app que no conoce las productoras la ve
         * en el directorio como un creador más y se suscribe a ese.
         */
        public List<String> topics() {
            Set<String> todos = new LinkedHashSet<>();
            if (creadorId != null) todos.add(topicDe(creadorId));
            if (productoraId != null) {
                todos.add(topicDeProductora(productoraId));
                todos.add(topicDe(productoraId));
            }
            tambien.forEach(id -> todos.add(topicDe(id)));
            return List.copyOf(todos);
        }

        /**
         * A quién se dirige cada mensaje: un topic suelto, o una condición de
         * FCM ("sigue a este O a aquel") cuando hay varias audiencias. Con
         * condición, quien sigue a dos de ellas recibe un solo aviso.
         *
         * Casi siempre es un solo mensaje. Solo con más de cinco topics hay
         * que partirlo, y entonces quien cae en dos trozos lo recibe dos
         * veces; por eso esos mensajes llevan la misma etiqueta, para que el
         * segundo sustituya al primero en la pantalla.
         */
        public List<Destino> destinos() {
            List<String> todos = topics();
            if (todos.size() == 1) return List.of(new Destino(todos.get(0), null));

            List<Destino> destinos = new ArrayList<>();
            for (int i = 0; i < todos.size(); i += TOPICS_POR_MENSAJE) {
                List<String> trozo = todos.subList(i, Math.min(i + TOPICS_POR_MENSAJE, todos.size()));
                destinos.add(trozo.size() == 1
                        ? new Destino(trozo.get(0), null)
                        : new Destino(null, String.join(" || ",
                                trozo.stream().map(t -> "'" + t + "' in topics").toList())));
            }
            return destinos;
        }

        /**
         * Quién publicó, para que la app abra el perfil correcto. FCM exige
         * que todos los valores sean cadenas, así que lo que no hay no se manda.
         */
        void identificar(ObjectNode datos) {
            if (creadorId != null) datos.put("creatorId", String.valueOf(creadorId));
            if (productoraId != null) datos.put("productoraId", String.valueOf(productoraId));
        }
    }

    /** El destinatario de un mensaje: `topic` o `condition`, nunca los dos. */
    public record Destino(String topic, String condicion) {

        void dirigir(ObjectNode mensaje) {
            if (condicion != null) mensaje.put("condition", condicion);
            else mensaje.put("topic", topic);
        }
    }

    public static String nombreDePlataforma(String plataforma) {
        if (plataforma == null) return "la plataforma del creador";
        return switch (plataforma) {
            case "youtube" -> "YouTube";
            case "tiktok" -> "TikTok";
            case "twitch" -> "Twitch";
            case "instagram" -> "Instagram";
            case "x" -> "X";
            case "facebook" -> "Facebook";
            case "threads" -> "Threads";
            case "telegram" -> "Telegram";
            case "spotify" -> "Spotify";
            case "patreon" -> "Patreon";
            case "web" -> "su página";
            default -> "la plataforma del creador";
        };
    }

    // -------------------------------------------------------------------------

    /**
     * Avisa de una publicación nueva.
     *
     * El texto está escrito para entenderse de un vistazo: quién publicó, qué
     * publicó, y nada más. Sin emojis decorativos ni jerga de plataforma.
     */
    public boolean avisarPublicacion(Emisor emisor, String videoId, String titulo,
                                     String miniatura, YouTubeClient.DetalleDeVideo detalle) {

        if (detalle != null && detalle.enVivo()) {
            return avisarDirecto(emisor, videoId, titulo, miniatura);
        }

        String encabezado = (detalle != null && "short".equals(detalle.tipo()))
                ? emisor.nombre() + " publicó un video corto"
                : emisor.nombre() + " subió un video nuevo";

        // Un solo aviso por creador: si llegan tres videos seguidos, el último
        // reemplaza al anterior en vez de apilar tres tarjetas.
        return enviarPublicacion(emisor, videoId, titulo, miniatura, encabezado, emisor.topic());
    }

    /**
     * Avisa de que un directo acaba de arrancar.
     *
     * Lleva etiqueta propia. Con la etiqueta del creador, el aviso del
     * siguiente clip que subiera el canal lo borraba de la pantalla, y el
     * directo es justo el aviso que caduca si no se ve a tiempo.
     */
    public boolean avisarDirecto(Emisor emisor, String videoId, String titulo, String miniatura) {
        return enviarPublicacion(emisor, videoId, titulo, miniatura,
                emisor.nombre() + " está en vivo ahora", "directo_" + videoId);
    }

    private boolean enviarPublicacion(Emisor emisor, String videoId, String titulo,
                                      String miniatura, String encabezado, String etiqueta) {

        List<Destino> destinos = emisor.destinos();
        boolean todos = true;

        for (Destino destino : destinos) {
            ObjectNode mensaje = json.createObjectNode();
            destino.dirigir(mensaje);

            ObjectNode notificacion = mensaje.putObject("notification");
            notificacion.put("title", encabezado);
            notificacion.put("body", (titulo == null || titulo.isBlank())
                    ? "Toca para verlo en YouTube." : titulo);
            if (miniatura != null) notificacion.put("image", miniatura);

            // Los datos viajan aparte para que la app resuelva el enlace profundo
            // al abrir la notificación, incluso si el destino cambió después.
            ObjectNode datos = mensaje.putObject("data");
            datos.put("tipo", "publicacion");
            emisor.identificar(datos);
            datos.put("videoId", videoId);
            datos.put("platform", "youtube");
            datos.put("url", "https://www.youtube.com/watch?v=" + videoId);

            ObjectNode android = mensaje.putObject("android");
            android.put("priority", "HIGH");
            ObjectNode androidNotif = android.putObject("notification");
            androidNotif.put("channel_id", CANAL_PUBLICACIONES);
            androidNotif.put("tag", etiqueta);
            if (miniatura != null) androidNotif.put("image", miniatura);

            ObjectNode apns = mensaje.putObject("apns");
            ObjectNode cabeceras = apns.putObject("headers");
            cabeceras.put("apns-priority", "10");
            // Solo si el aviso salió partido: que la copia que llegue por el
            // segundo trozo sustituya a la primera en vez de sumarse.
            if (destinos.size() > 1) cabeceras.put("apns-collapse-id", "video_" + videoId);
            ObjectNode aps = apns.putObject("payload").putObject("aps");
            aps.put("sound", "default");
            aps.put("thread-id", emisor.topic());
            aps.put("mutable-content", 1);
            if (miniatura != null) apns.putObject("fcm_options").put("image", miniatura);

            if (enviar(mensaje, encabezado) != null) todos = false;
        }
        return todos;
    }

    /**
     * Aviso de contenido movido.
     *
     * Es la pieza que hace resiliente al directorio: si una plataforma tumba
     * un video, el destino se reemplaza y la audiencia recibe el enlace nuevo
     * sin tener que buscar nada. Es la diferencia entre que un creador pierda
     * su audiencia y que solo pierda un video.
     */
    public boolean avisarContenidoMovido(Emisor emisor, String videoId, String tituloVideo,
                                         String destinoUrl, String destinoPlataforma) {

        String donde = nombreDePlataforma(destinoPlataforma);
        String cuerpo = (tituloVideo != null && !tituloVideo.isBlank())
                ? "\"" + tituloVideo + "\" ahora está en " + donde + ". Toca para verlo."
                : "Ahora está en " + donde + ". Toca para verlo.";

        boolean todos = true;

        for (Destino destino : emisor.destinos()) {
            ObjectNode mensaje = json.createObjectNode();
            destino.dirigir(mensaje);

            ObjectNode notificacion = mensaje.putObject("notification");
            notificacion.put("title", "El video de " + emisor.nombre() + " cambió de lugar");
            notificacion.put("body", cuerpo);

            ObjectNode datos = mensaje.putObject("data");
            datos.put("tipo", "movido");
            emisor.identificar(datos);
            datos.put("videoId", videoId);
            // FCM exige que todos los valores de data sean cadenas: un null
            // devuelve 400 y el aviso se pierde entero.
            datos.put("platform", destinoPlataforma != null ? destinoPlataforma : "web");
            datos.put("url", destinoUrl != null ? destinoUrl : "");

            ObjectNode android = mensaje.putObject("android");
            android.put("priority", "HIGH");
            ObjectNode androidNotif = android.putObject("notification");
            androidNotif.put("channel_id", CANAL_AVISOS);
            // Misma etiqueta en todos los trozos: ver Emisor.destinos().
            androidNotif.put("tag", "movido_" + videoId);

            ObjectNode apns = mensaje.putObject("apns");
            apns.putObject("headers").put("apns-priority", "10");
            apns.putObject("payload").putObject("aps").put("sound", "default");

            if (enviar(mensaje, "contenido movido de " + emisor.nombre()) != null) todos = false;
        }
        return todos;
    }

    /**
     * Aviso de prueba al topic de un creador. Sirve para comprobar el tramo
     * servidor → FCM → teléfono sin esperar a que alguien publique un video.
     *
     * Devuelve null si FCM aceptó el mensaje, o el motivo del fallo.
     */
    public String avisarPrueba(Creador creador) {
        ObjectNode mensaje = json.createObjectNode();
        mensaje.put("topic", topicDe(creador.getId()));

        ObjectNode notificacion = mensaje.putObject("notification");
        notificacion.put("title", "Aviso de prueba");
        notificacion.put("body", "Si lees esto, los avisos de " + creador.getNombre() + " llegan bien.");

        ObjectNode android = mensaje.putObject("android");
        android.put("priority", "HIGH");
        android.putObject("notification").put("channel_id", CANAL_AVISOS);

        ObjectNode apns = mensaje.putObject("apns");
        apns.putObject("headers").put("apns-priority", "10");
        apns.putObject("payload").putObject("aps").put("sound", "default");

        return enviar(mensaje, "prueba de " + creador.getNombre());
    }

    // -------------------------------------------------------------------------

    private static String destinoDe(ObjectNode mensaje) {
        return mensaje.has("condition")
                ? mensaje.path("condition").asText()
                : "topic " + mensaje.path("topic").asText();
    }

    /** Devuelve null si FCM aceptó el mensaje, o el motivo del fallo. */
    private String enviar(ObjectNode mensaje, String descripcion) {
        if (credenciales == null) {
            log.warn("FCM sin configurar; no se envió el aviso de {} a {}", descripcion, destinoDe(mensaje));
            return "FCM sin configurar en el servidor (revisa FCM_PROYECTO_ID y el JSON de credenciales).";
        }

        try {
            // refreshIfExpired cachea: solo pide un token nuevo cuando el
            // anterior está a punto de caducar, no en cada envío.
            credenciales.refreshIfExpired();
            String token = credenciales.getAccessToken().getTokenValue();

            ObjectNode sobre = json.createObjectNode();
            sobre.set("message", mensaje);

            JsonNode respuesta = http.post()
                    .uri(urlEnvio)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(sobre.toString())
                    .retrieve()
                    .body(JsonNode.class);

            log.info("Aviso enviado ({}) a {}: {}", descripcion, destinoDe(mensaje),
                    respuesta != null ? respuesta.path("name").asText() : "sin id");
            return null;

        } catch (RestClientResponseException e) {
            // El cuerpo trae el motivo real (PERMISSION_DENIED, SENDER_ID_MISMATCH,
            // API desactivada...). Sin él solo se ve "403" y no se sabe por qué.
            String motivo = "FCM respondió HTTP " + e.getStatusCode().value()
                    + ": " + e.getResponseBodyAsString();
            log.error("No se pudo enviar el aviso de {}: {}", descripcion, motivo);
            return motivo;

        } catch (Exception e) {
            // Un fallo de push no debe tumbar el procesado del aviso: el video
            // ya está guardado y aparecerá en la app la próxima vez que abra.
            log.error("No se pudo enviar el aviso de {}", descripcion, e);
            return String.valueOf(e.getMessage());
        }
    }
}
