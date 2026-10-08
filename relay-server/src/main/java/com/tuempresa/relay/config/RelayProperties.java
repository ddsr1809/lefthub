package com.tuempresa.relay.config;

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
        @DefaultValue Compras compras
) {

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
