package com.tuempresa.relay.push;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auth.oauth2.GoogleCredentials;
import com.tuempresa.relay.config.RelayProperties;
import com.tuempresa.relay.modelo.Creador;
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
import java.util.List;

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

    public static String nombreDePlataforma(String plataforma) {
        if (plataforma == null) return "la plataforma del creador";
        return switch (plataforma) {
            case "youtube" -> "YouTube";
            case "tiktok" -> "TikTok";
            case "twitch" -> "Twitch";
            case "instagram" -> "Instagram";
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
    public boolean avisarPublicacion(Creador creador, String videoId, String titulo,
                                     String miniatura, YouTubeClient.DetalleDeVideo detalle) {

        if (detalle != null && detalle.enVivo()) {
            return avisarDirecto(creador, videoId, titulo, miniatura);
        }

        String encabezado = (detalle != null && "short".equals(detalle.tipo()))
                ? creador.getNombre() + " publicó un video corto"
                : creador.getNombre() + " subió un video nuevo";

        // Un solo aviso por creador: si llegan tres videos seguidos, el último
        // reemplaza al anterior en vez de apilar tres tarjetas.
        return enviarPublicacion(creador, videoId, titulo, miniatura, encabezado,
                topicDe(creador.getId()));
    }

    /**
     * Avisa de que un directo acaba de arrancar.
     *
     * Lleva etiqueta propia. Con la etiqueta del creador, el aviso del
     * siguiente clip que subiera el canal lo borraba de la pantalla, y el
     * directo es justo el aviso que caduca si no se ve a tiempo.
     */
    public boolean avisarDirecto(Creador creador, String videoId, String titulo, String miniatura) {
        return enviarPublicacion(creador, videoId, titulo, miniatura,
                creador.getNombre() + " está en vivo ahora", "directo_" + videoId);
    }

    private boolean enviarPublicacion(Creador creador, String videoId, String titulo,
                                      String miniatura, String encabezado, String etiqueta) {

        ObjectNode mensaje = json.createObjectNode();
        mensaje.put("topic", topicDe(creador.getId()));

        ObjectNode notificacion = mensaje.putObject("notification");
        notificacion.put("title", encabezado);
        notificacion.put("body", (titulo == null || titulo.isBlank())
                ? "Toca para verlo en YouTube." : titulo);
        if (miniatura != null) notificacion.put("image", miniatura);

        // Los datos viajan aparte para que la app resuelva el enlace profundo
        // al abrir la notificación, incluso si el destino cambió después.
        ObjectNode datos = mensaje.putObject("data");
        datos.put("tipo", "publicacion");
        datos.put("creatorId", String.valueOf(creador.getId()));
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
        apns.putObject("headers").put("apns-priority", "10");
        ObjectNode aps = apns.putObject("payload").putObject("aps");
        aps.put("sound", "default");
        aps.put("thread-id", topicDe(creador.getId()));
        aps.put("mutable-content", 1);
        if (miniatura != null) apns.putObject("fcm_options").put("image", miniatura);

        return enviar(mensaje, encabezado) == null;
    }

    /**
     * Aviso de contenido movido.
     *
     * Es la pieza que hace resiliente al directorio: si una plataforma tumba
     * un video, el destino se reemplaza y la audiencia recibe el enlace nuevo
     * sin tener que buscar nada. Es la diferencia entre que un creador pierda
     * su audiencia y que solo pierda un video.
     */
    public boolean avisarContenidoMovido(Creador creador, String videoId, String tituloVideo,
                                         String destinoUrl, String destinoPlataforma) {

        String donde = nombreDePlataforma(destinoPlataforma);
        String cuerpo = (tituloVideo != null && !tituloVideo.isBlank())
                ? "\"" + tituloVideo + "\" ahora está en " + donde + ". Toca para verlo."
                : "Ahora está en " + donde + ". Toca para verlo.";

        ObjectNode mensaje = json.createObjectNode();
        mensaje.put("topic", topicDe(creador.getId()));

        ObjectNode notificacion = mensaje.putObject("notification");
        notificacion.put("title", "El video de " + creador.getNombre() + " cambió de lugar");
        notificacion.put("body", cuerpo);

        ObjectNode datos = mensaje.putObject("data");
        datos.put("tipo", "movido");
        datos.put("creatorId", String.valueOf(creador.getId()));
        datos.put("videoId", videoId);
        // FCM exige que todos los valores de data sean cadenas: un null
        // devuelve 400 y el aviso se pierde entero.
        datos.put("platform", destinoPlataforma != null ? destinoPlataforma : "web");
        datos.put("url", destinoUrl != null ? destinoUrl : "");

        ObjectNode android = mensaje.putObject("android");
        android.put("priority", "HIGH");
        android.putObject("notification").put("channel_id", CANAL_AVISOS);

        ObjectNode apns = mensaje.putObject("apns");
        apns.putObject("headers").put("apns-priority", "10");
        apns.putObject("payload").putObject("aps").put("sound", "default");

        return enviar(mensaje, "contenido movido de " + creador.getNombre()) == null;
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

    /** Devuelve null si FCM aceptó el mensaje, o el motivo del fallo. */
    private String enviar(ObjectNode mensaje, String descripcion) {
        if (credenciales == null) {
            log.warn("FCM sin configurar; no se envió el aviso de {}", descripcion);
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

            log.info("Aviso enviado ({}) al topic {}: {}", descripcion,
                    mensaje.path("topic").asText(),
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
