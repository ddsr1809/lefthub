package com.tuempresa.relay.acceso;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Señales de que una cuenta quizá no es una persona con la app en la mano.
 *
 * Son indicios, no pruebas, y por eso en todas partes se llama "posible bot".
 * Las dos señales tienen falsos positivos conocidos:
 *
 *  · Una persona real con una VPN sale por la IP de un centro de datos.
 *  · Los teléfonos de prueba de Google Play (el informe previo al lanzamiento)
 *    abren la app de verdad, desde redes de Google. Esos sí son robots, pero
 *    inofensivos.
 *
 * El dato sirve para que las cifras del panel no se inflen con tráfico
 * automático. No bloquea a nadie: una decisión así no se toma con indicios.
 */
public final class DetectorDeBots {

    private DetectorDeBots() {}

    /**
     * Herramientas y bibliotecas que no son nuestras apps. Android manda
     * "okhttp/x.y.z", iOS "Relay/1 CFNetwork/... Darwin/..." y el panel, el
     * User-Agent de un navegador: ninguno coincide con esta lista.
     */
    private static final Pattern HERRAMIENTA = Pattern.compile(
            "curl|wget|python|scrapy|libwww|go-http-client|httpclient|java/|postman|insomnia"
                    + "|axios|node-fetch|undici|bot|spider|crawl|headless|phantom|selenium"
                    + "|playwright|puppeteer");

    /**
     * Compañías que alquilan servidores. El nombre viene del registro de la
     * red (el campo `red`), así que se compara por fragmentos en minúsculas.
     */
    private static final List<String> CENTROS_DE_DATOS = List.of(
            "amazon", "google", "microsoft", "digitalocean", "ovh", "hetzner", "linode",
            "akamai", "vultr", "choopa", "constant company", "contabo", "oracle", "alibaba",
            "tencent", "leaseweb", "m247", "datacamp", "scaleway", "hostinger", "ionos",
            "godaddy", "cloudflare", "fastly", "hosting", "datacenter", "data center",
            "colocation", "dedicated", "vps");

    /** Mismo fragmento, pero es una compañía de internet para casas. */
    private static final List<String> EXCEPCIONES = List.of("google fiber");

    /**
     * @return el motivo, escrito para mostrarse tal cual en el panel, o null
     *         si no hay nada raro.
     */
    public static String evaluar(String agente, String red) {
        if (agente == null || agente.isBlank()) {
            return "La petición llegó sin identificar la aplicación (sin User-Agent).";
        }
        if (HERRAMIENTA.matcher(agente.toLowerCase(Locale.ROOT)).find()) {
            return "La petición no vino de la app, sino de una herramienta automática.";
        }
        if (esCentroDeDatos(red)) {
            return "Se conecta desde un centro de datos (" + red + "): un servidor o una VPN, no una red de casa o de celular.";
        }
        return null;
    }

    static boolean esCentroDeDatos(String red) {
        if (red == null || red.isBlank()) return false;
        String nombre = red.toLowerCase(Locale.ROOT);
        for (String excepcion : EXCEPCIONES) {
            if (nombre.contains(excepcion)) return false;
        }
        for (String fragmento : CENTROS_DE_DATOS) {
            if (nombre.contains(fragmento)) return true;
        }
        return false;
    }
}
