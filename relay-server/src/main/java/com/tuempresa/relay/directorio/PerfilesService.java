package com.tuempresa.relay.directorio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tuempresa.relay.config.RelayProperties;
import com.tuempresa.relay.modelo.Dtos;
import com.tuempresa.relay.push.PushService;
import com.tuempresa.relay.youtube.YouTubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Los datos de una cuenta para rellenar la ficha de un creador: cómo se
 * llama, cómo se describe y su foto.
 *
 * De YouTube lo da todo la Data API. De las demás redes (X, Instagram,
 * TikTok, Facebook...) no hay nada oficial sin permisos, así que el nombre y
 * la descripción se leen de la tarjeta de presentación de su perfil (lo que
 * la propia red publica para cuando alguien comparte el enlace), a través de
 * un servicio que sabe abrir esas páginas (microlink.io), y la foto la trae
 * FotosService.
 *
 * Es una ayuda para no teclear, no una fuente segura: cada red redacta esa
 * tarjeta a su manera y la cambia cuando quiere. Por eso devuelve lo que
 * haya podido sacar y dice lo que no; el panel rellena con eso y lo demás se
 * escribe a mano.
 *
 * Solo se llama cuando alguien del equipo lo pide en el panel. Sin clave, el
 * servicio da 25 consultas al día.
 */
@Service
public class PerfilesService {

    private static final Logger log = LoggerFactory.getLogger(PerfilesService.class);

    private static final String GRATIS = "https://api.microlink.io";
    private static final String DE_PAGO = "https://pro.microlink.io";

    static final int LARGO_NOMBRE = 60;
    static final int LARGO_DESCRIPCION = 600;

    private final FotosService fotos;
    private final YouTubeClient youtube;
    private final RelayProperties config;
    private final ObjectMapper json = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public PerfilesService(FotosService fotos, YouTubeClient youtube, RelayProperties config) {
        this.fotos = fotos;
        this.youtube = youtube;
        this.config = config;
    }

    /**
     * Lo que se pueda saber de la cuenta. Falla solo si no se sacó nada.
     *
     * @param plataforma youtube, x, instagram..., o web
     * @param url        el enlace de la cuenta, como está en el formulario
     * @param channelId  solo para YouTube
     */
    public Dtos.CuentaDto leer(String plataforma, String url, String channelId) {
        if ("youtube".equals(plataforma)) return deYouTube(channelId);

        String red = PushService.nombreDePlataforma(plataforma);
        boolean esWeb = "web".equals(plataforma);

        // Solo perfiles de las redes del directorio (o una página, si es
        // "web"): esto no es un lector de direcciones cualesquiera.
        String cuenta = FotosService.cuentaDe(plataforma, url);
        if (!esWeb && cuenta == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se reconoce la cuenta en ese enlace de " + red + ". Escribe los datos a mano.");
        }
        if (esWeb && (url == null || !url.trim().toLowerCase(Locale.ROOT).startsWith("https://"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El enlace de la página tiene que empezar por https://");
        }

        List<String> avisos = new ArrayList<>();
        String nombre = null;
        String descripcion = null;
        String foto = null;
        ResponseStatusException primerFallo = null;

        try {
            Tarjeta tarjeta = tarjetaDe(url.trim(), red);
            String usuario = cuenta != null ? cuenta.substring(cuenta.indexOf('/') + 1) : null;
            nombre = nombreDe(tarjeta.titulo(), usuario);
            if (nombre == null) nombre = nombreDe(tarjeta.autor(), usuario);
            descripcion = descripcionDe(tarjeta.descripcion());

            if (nombre == null) avisos.add(red + " no dejó leer el nombre: escríbelo a mano.");
            if (descripcion == null) avisos.add("No se encontró una descripción en " + red + ".");
        } catch (ResponseStatusException e) {
            primerFallo = e;
            avisos.add("Nombre y descripción: " + e.getReason());
        }

        if (!esWeb) {
            try {
                foto = fotos.traer(plataforma, url, null).url();
            } catch (ResponseStatusException e) {
                if (primerFallo == null) primerFallo = e;
                avisos.add("Foto: " + e.getReason());
            }
        }

        if (nombre == null && descripcion == null && foto == null) {
            // Nada de nada: mejor el motivo real que una ficha vacía.
            if (primerFallo != null) throw primerFallo;
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No se pudo leer nada de esa cuenta de " + red + ". Escribe los datos a mano.");
        }
        return new Dtos.CuentaDto(plataforma, nombre, descripcion, foto, avisos);
    }

    private Dtos.CuentaDto deYouTube(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta el ID del canal de YouTube.");
        }
        Dtos.DatosDeCanal datos = youtube.detallesDeCanal(channelId.trim());
        if (datos == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "YouTube no devolvió datos de ese canal.");
        }
        return new Dtos.CuentaDto("youtube",
                recortar(limpio(datos.titulo()), LARGO_NOMBRE),
                recortar(limpio(datos.descripcion()), LARGO_DESCRIPCION),
                limpio(datos.fotoUrl()), List.of());
    }

    // -------------------------------------------------------------------------
    // La tarjeta de presentación del perfil
    // -------------------------------------------------------------------------

    private record Tarjeta(String titulo, String descripcion, String autor) {}

    private Tarjeta tarjetaDe(String url, String red) {
        boolean conClave = !config.perfiles().apiKey().isBlank();
        String base = sinBarraFinal(config.perfiles().base());
        // Con clave, el servicio atiende en otra dirección.
        if (conClave && GRATIS.equals(base)) base = DE_PAGO;

        HttpRequest.Builder peticion = HttpRequest.newBuilder(
                        URI.create(base + "/?url=" + URLEncoder.encode(url, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(40))
                .header("Accept", "application/json")
                .GET();
        if (conClave) peticion.header("x-api-key", config.perfiles().apiKey());

        HttpResponse<String> respuesta;
        try {
            respuesta = http.send(peticion.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "se interrumpió la consulta.");
        } catch (Exception e) {
            log.warn("No se pudo leer el perfil {}: {}", url, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "no se pudo consultar " + red + " ahora. Inténtalo de nuevo o escríbelos a mano.");
        }

        int estado = respuesta.statusCode();
        if (estado == 429) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "por hoy ya no quedan consultas a las redes. Inténtalo mañana o escríbelos a mano.");
        }

        JsonNode datos;
        try {
            JsonNode cuerpo = json.readTree(respuesta.body());
            datos = "success".equals(cuerpo.path("status").asText()) ? cuerpo.path("data") : null;
        } catch (Exception e) {
            datos = null;
        }
        if (estado != 200 || datos == null || !datos.isObject()) {
            log.warn("El lector de perfiles respondió HTTP {} para {}", estado, url);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    red + " no dejó leer el perfil (HTTP " + estado + "). Escríbelos a mano.");
        }

        return new Tarjeta(texto(datos, "title"), texto(datos, "description"), texto(datos, "author"));
    }

    private static String texto(JsonNode nodo, String campo) {
        JsonNode valor = nodo.get(campo);
        return valor != null && valor.isTextual() ? valor.asText() : null;
    }

    // -------------------------------------------------------------------------
    // Del título de la tarjeta al nombre de la persona
    // -------------------------------------------------------------------------

    /** "(@usuario)", que casi todas las redes pegan al nombre. */
    private static final Pattern ARROBA = Pattern.compile("\\s*\\(@[^)]*\\)");

    /** Lo que cada red añade después del nombre: " / X", " | TikTok", " • Instagram photos and videos"... */
    private static final Pattern COLA = Pattern.compile(
            "\\s*(?:[/|•·\\-–—]|\\bon\\b)\\s*(?:X|Twitter|Instagram|TikTok|Facebook|Threads|Twitch|Telegram|Patreon|Spotify)\\b.*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Títulos que no son de nadie: la portada de la red, su pantalla de entrada, un error. */
    private static final Pattern DE_NADIE = Pattern.compile(
            "^(?:X|Twitter|Instagram|TikTok|Facebook|Threads|Twitch|Telegram|Patreon|Spotify|Profile|Perfil)?\\s*$"
                    + "|\\blog ?in\\b|\\bsign ?up\\b|\\biniciar? sesi[oó]n\\b|\\breg[ií]strate\\b|\\bnot found\\b|\\bno encontrad"
                    + "|\\bjust a moment\\b|\\battention required\\b|\\baccess denied\\b|\\berror\\b|\\bmake your day\\b"
                    + "|\\bit'?s what'?s happening\\b|\\bcontact @",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * El nombre de la persona, o null si el título no lo trae.
     *
     * Ejemplos de lo que llega: "Rosa Luna (@rosaluna) / X", "Rosa Luna
     * (@rosa.luna) • Instagram photos and videos", "Rosa Luna | Facebook",
     * "Rosa Luna (@rosaluna) | TikTok". Y de lo que no sirve: "X", "Log in •
     * Instagram", "Telegram: Contact @rosaluna".
     */
    static String nombreDe(String titulo, String usuario) {
        String nombre = limpio(titulo);
        if (nombre == null) return null;

        nombre = COLA.matcher(nombre).replaceFirst("");
        nombre = ARROBA.matcher(nombre).replaceAll("");
        nombre = nombre.replaceAll("^[\\s\"'“”«»]+|[\\s\"'“”«»|/•·\\-–—]+$", "").trim();

        if (nombre.length() < 2 || DE_NADIE.matcher(nombre).find()) return null;
        // Si lo único que quedó es el usuario, no es su nombre: ya está en el enlace.
        if (usuario != null && nombre.replaceFirst("^@", "").equalsIgnoreCase(usuario)) return null;

        return recortar(nombre, LARGO_NOMBRE);
    }

    /** Cifras de seguidores y frases hechas de la red: no son una descripción de nadie. */
    private static final Pattern RELLENO = Pattern.compile(
            "\\d[\\d.,]*\\s*[KkMm]?\\s+(?:followers|following|seguidores|seguidos|likes|me gusta|posts|publicaciones)"
                    + "|\\bsee instagram photos\\b|\\bwatch the latest video\\b|\\blog ?in\\b|\\bsign ?up\\b|\\biniciar? sesi[oó]n\\b"
                    + "|from breaking news and entertainment|join facebook|únete a facebook",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** La descripción que escribió la persona, o null si lo que hay es relleno de la red. */
    static String descripcionDe(String texto) {
        String descripcion = limpio(texto);
        if (descripcion == null || descripcion.length() < 3) return null;
        if (RELLENO.matcher(descripcion).find()) return null;
        return recortar(descripcion, LARGO_DESCRIPCION);
    }

    private static String limpio(String texto) {
        if (texto == null) return null;
        String limpio = texto.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replaceAll("[ \\t]+", " ").trim();
        return limpio.isEmpty() ? null : limpio;
    }

    private static String recortar(String texto, int maximo) {
        if (texto == null) return null;
        return texto.length() <= maximo ? texto : texto.substring(0, maximo).trim();
    }

    private static String sinBarraFinal(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
