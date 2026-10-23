package com.voces.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Toda la configuracion en un sitio y con tipos. Spring la enlaza al arrancar,
 * asi que un secreto que falta se descubre en el primer segundo.
 */
@ConfigurationProperties(prefix = "relay")
public record RelayProperties(

        @DefaultValue("") String urlPublica,
        @DefaultValue Jwt jwt,
        @DefaultValue WebSub websub,
        @DefaultValue YouTube youtube,
        @DefaultValue Fcm fcm,
        @DefaultValue Google google,
        @DefaultValue Apple apple,
        @DefaultValue Renovacion renovacion,
        @DefaultValue Cors cors,
        @DefaultValue Replica replica,
        @DefaultValue Fotos fotos,
        @DefaultValue Perfiles perfiles,
        @DefaultValue Compras compras,
        @DefaultValue Servidor servidor,
        @DefaultValue Apps apps,
        @DefaultValue Chat chat,
        @DefaultValue Datos datos,
        @DefaultValue LlaveDeApp llaveDeApp
) {

    /**
     * Que solo la app original hable con la API. Ver seguridad/FiltroDeApp.
     * Qué se hace con lo que no pasa (apagado, avisar, exigir) se elige en
     * el panel, no aquí.
     *
     * @param llaves           llaves de la firma, "1:BASE64" (como CHAT_LLAVES).
     *                         Las mismas van en android/firma.properties.
     *                         Vacío = no se comprueba la firma.
     * @param appCheckProyecto número del proyecto de Firebase (project_number
     *                         de google-services.json). Vacío = sin App Check.
     * @param appCheckApps     IDs de app de Firebase permitidos
     *                         (1:123:android:abc), separados por comas.
     *                         Vacío = cualquier app del proyecto.
     */
    public record LlaveDeApp(
            @DefaultValue("") String llaves,
            @DefaultValue("") String appCheckProyecto,
            @DefaultValue("") String appCheckApps
    ) {}

    /**
     * Las llaves de los datos personales cifrados en la base: correo, IP,
     * token de Apple, comprobantes de compra. Ver seguridad/CifradorDeDatos.
     *
     * @param llaves  como las del chat: "1:BASE64", o "1:BASE64,2:BASE64" para
     *                cambiar de llave sin perder lo anterior. Obligatoria.
     * @param indice  32 bytes en base64 para las huellas con las que se busca
     *                un correo o una IP. Obligatoria, y no se cambia nunca.
     * @param diasIp  pasados estos días sin abrir la app, se borran la IP, la
     *                compañía de internet y el agente de esa cuenta. 0 = nunca.
     */
    public record Datos(
            @DefaultValue("") String llaves,
            @DefaultValue("") String indice,
            @DefaultValue("90") int diasIp
    ) {}

    /**
     * Las llaves con las que se cifran los mensajes del chat guardados en la
     * base. Van aquí y no en la base: quien se lleve una copia de la base no
     * puede leer las conversaciones sin ellas.
     *
     * @param llaves "1:BASE64" o, para cambiar de llave sin perder lo anterior,
     *               "1:BASE64,2:BASE64". Cada una son 32 bytes al azar:
     *               {@code echo "1:$(openssl rand -base64 32)"}. Se cifra con
     *               la de número más alto; las demás solo sirven para leer lo
     *               que ya estaba. Vacío = el chat no está disponible.
     */
    public record Chat(@DefaultValue("") String llaves) {}

    public record Jwt(
            @DefaultValue("") String secreto,
            @DefaultValue("relay") String emisor,
            @DefaultValue("30") long diasValidez
    ) {}

    public record WebSub(
            @DefaultValue("https://pubsubhubbub.appspot.com/subscribe") String hub,
            @DefaultValue("") String secreto,
            @DefaultValue("") String tokenCallback,
            @DefaultValue("864000") long leaseSegundos,
            @DefaultValue("6") long antiguedadMaximaHoras
    ) {}

    public record YouTube(
            @DefaultValue("") String apiKey,
            // Las dos direcciones son las de YouTube y no hay por que tocarlas;
            // estan aqui para poder apuntar las pruebas a un servidor de pega.
            @DefaultValue("https://www.googleapis.com/youtube/v3") String apiBase,
            @DefaultValue("https://www.youtube.com/shorts/") String urlShorts
    ) {}

    public record Fcm(
            @DefaultValue("") String proyectoId,
            @DefaultValue("") String credenciales
    ) {
        public boolean estaConfigurado() {
            return !proyectoId.isBlank() && !credenciales.isBlank();
        }
    }

    public record Google(@DefaultValue("") String clientId) {}

    public record Apple(
            @DefaultValue("") String bundleId,
            @DefaultValue("") String teamId,
            @DefaultValue("") String keyId,
            @DefaultValue("") String clavePrivada
    ) {
        public boolean puedeRevocar() {
            return !bundleId.isBlank() && !teamId.isBlank()
                    && !keyId.isBlank() && !clavePrivada.isBlank();
        }
    }

    public record Renovacion(
            @DefaultValue("true") boolean programada,
            @DefaultValue("0 0 4 */4 * *") String cron,
            @DefaultValue("America/Hermosillo") String zona,
            @DefaultValue("") String tokenInterno
    ) {}

    public record Cors(@DefaultValue("") String origenes) {}

    /**
     * De dónde se toman las fotos de perfil de las redes sociales.
     *
     * @param base   el servicio que las encuentra. Sin clave da 25 fotos al día.
     * @param apiKey opcional: la clave del servicio, si se contrata un plan
     */
    public record Fotos(
            @DefaultValue("https://unavatar.io") String base,
            @DefaultValue("") String apiKey
    ) {}

    /**
     * De dónde se leen el nombre y la descripción de una cuenta de una red.
     *
     * @param base   el servicio que abre el perfil. Sin clave da 25 consultas al día.
     * @param apiKey opcional: la clave del servicio, si se contrata un plan
     */
    public record Perfiles(
            @DefaultValue("https://api.microlink.io") String base,
            @DefaultValue("") String apiKey
    ) {}

    /**
     * La compra de "quitar los anuncios" en Google Play.
     *
     * @param paquete      el applicationId de la app en Play Console
     * @param producto     el ID del producto, como se dio de alta allí
     * @param credenciales ruta al JSON de la cuenta de servicio. Si no se
     *                     pone otra, es la misma de FCM: basta con invitarla
     *                     en Play Console
     * @param apiBase      la dirección de Google; existe como ajuste solo para
     *                     poder apuntar las pruebas a un servidor de pega
     */
    public record Compras(
            @DefaultValue("") String paquete,
            @DefaultValue("sin_anuncios") String producto,
            @DefaultValue("") String credenciales,
            @DefaultValue("https://androidpublisher.googleapis.com/androidpublisher/v3") String apiBase
    ) {
        public boolean estaConfigurado() {
            return !paquete.isBlank() && !producto.isBlank() && !credenciales.isBlank();
        }
    }

    /**
     * Copia de creadores de produccion a testing.
     *
     * Produccion lleva la URL de testing y el token; testing lleva solo el
     * token. Un servidor que envia nunca recibe: asi el token compartido no
     * sirve para escribir en produccion.
     */
    public record Replica(
            @DefaultValue("") String url,
            @DefaultValue("") String token
    ) {
        public boolean envia() { return !url.isBlank(); }

        public boolean recibe() { return url.isBlank() && !token.isBlank(); }
    }

    /**
     * De qué compilación salió este servidor.
     *
     * @param commit el commit de la imagen. Lo pone el pipeline al construirla;
     *               vacío si se compiló a mano
     */
    public record Servidor(@DefaultValue("") String commit) {}

    /**
     * La versión más vieja de cada app que todavía se atiende. Vacío = todas.
     * Ver FiltroVersionMinima.
     */
    public record Apps(
            @DefaultValue("") String minimaAndroid,
            @DefaultValue("") String minimaIos
    ) {
        public boolean hayMinima() {
            return !minimaAndroid.isBlank() || !minimaIos.isBlank();
        }
    }

    /** URL exacta que registramos como hub.callback. */
    public String urlWebhook() {
        String base = urlPublica.endsWith("/")
                ? urlPublica.substring(0, urlPublica.length() - 1)
                : urlPublica;
        return base + "/websub?token=" + websub.tokenCallback();
    }

    /** Feed Atom canonico de un canal. WebSub no acepta @handles como tema. */
    public static String feedDe(String channelId) {
        return "https://www.youtube.com/xml/feeds/videos.xml?channel_id=" + channelId;
    }
}
