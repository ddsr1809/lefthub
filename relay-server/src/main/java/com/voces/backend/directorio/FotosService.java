package com.voces.backend.directorio;

import com.voces.backend.config.RelayProperties;
import com.voces.backend.modelo.Dtos;
import com.voces.backend.modelo.Foto;
import com.voces.backend.modelo.Repositorios;
import com.voces.backend.push.PushService;
import com.voces.backend.youtube.YouTubeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * La foto de perfil de un creador, tomada de una de sus cuentas.
 *
 * De YouTube la da la Data API, y su dirección no caduca: se usa tal cual.
 * Las demás redes no ofrecen nada parecido sin permisos, así que se le pide a
 * un servicio que sabe encontrarla (unavatar.io) y se guarda una copia aquí:
 * las direcciones de Instagram o TikTok caducan a los pocos días, y así ni la
 * app ni el panel dependen de que ese servicio siga respondiendo.
 *
 * Solo se llama cuando alguien del equipo pulsa el botón en el panel: una
 * petición por foto. Sin clave, el servicio da 25 al día; con FOTOS_API_KEY,
 * las de su plan.
 */
@Service
public class FotosService {

    private static final Logger log = LoggerFactory.getLogger(FotosService.class);

    /** Una foto de perfil pesa unas decenas de KB. Más que esto no es una. */
    static final int PESO_MAXIMO = 2 * 1024 * 1024;

    /** Lo que las apps saben pintar. Un SVG, por ejemplo, no. */
    private static final Set<String> TIPOS = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private static final Pattern USUARIO = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._-]{0,79}");

    private final Repositorios.Fotos fotos;
    private final YouTubeClient youtube;
    private final RelayProperties config;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public FotosService(Repositorios.Fotos fotos, YouTubeClient youtube, RelayProperties config) {
        this.fotos = fotos;
        this.youtube = youtube;
        this.config = config;
    }

    public Optional<Foto> porId(UUID id) {
        return fotos.findById(id);
    }

    /**
     * Trae la foto de perfil de una cuenta y devuelve la dirección que hay
     * que guardar en el creador.
     *
     * @param plataforma youtube, x, instagram...
     * @param url        el enlace de la cuenta, como está en el directorio
     * @param channelId  solo para YouTube: el ID del canal
     */
    public Dtos.FotoDto traer(String plataforma, String url, String channelId) {
        String red = PushService.nombreDePlataforma(plataforma);

        if ("youtube".equals(plataforma)) {
            return deYouTube(channelId);
        }

        String cuenta = cuentaDe(plataforma, url);
        if (cuenta == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "web".equals(plataforma)
                    ? "De una página web no se puede tomar la foto. Pega la dirección de la imagen."
                    : "No se reconoce la cuenta en ese enlace de " + red + ". Pega la dirección de la imagen.");
        }
        if (config.urlPublica().isBlank()) {
            throw new IllegalStateException(
                    "Falta RELAY_URL_PUBLICA. Sin ella no se sabe en qué dirección servir la foto.");
        }

        Descarga descarga = descargar(cuenta, red);

        Foto foto = fotos.findByOrigen(cuenta).orElseGet(Foto::new);
        foto.setOrigen(cuenta);
        foto.setTipo(descarga.tipo());
        foto.setDatos(descarga.datos());
        foto.setActualizadoEn(Instant.now());
        foto = fotos.save(foto);

        log.info("Foto de {} guardada ({} bytes)", cuenta, descarga.datos().length);

        // La versión va en la dirección: si la foto se vuelve a tomar, es
        // otra dirección y nadie se queda con la antigua en caché.
        return new Dtos.FotoDto(sinBarraFinal(config.urlPublica()) + "/api/fotos/" + foto.getId()
                + "?v=" + foto.getActualizadoEn().getEpochSecond(), plataforma);
    }

    private Dtos.FotoDto deYouTube(String channelId) {
        if (channelId == null || channelId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta el ID del canal de YouTube.");
        }
        Dtos.DatosDeCanal datos = youtube.detallesDeCanal(channelId.trim());
        if (datos == null || datos.fotoUrl() == null || datos.fotoUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "YouTube no devolvió la foto de ese canal.");
        }
        return new Dtos.FotoDto(datos.fotoUrl(), "youtube");
    }

    private record Descarga(String tipo, byte[] datos) {}

    private Descarga descargar(String cuenta, String red) {
        HttpRequest.Builder peticion = HttpRequest.newBuilder(
                        URI.create(sinBarraFinal(config.fotos().base()) + "/" + cuenta + "?fallback=false"))
                .timeout(Duration.ofSeconds(25))
                .header("Accept", "image/*")
                .GET();
        if (!config.fotos().apiKey().isBlank()) {
            peticion.header("x-api-key", config.fotos().apiKey());
        }

        HttpResponse<byte[]> respuesta;
        try {
            respuesta = http.send(peticion.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Se interrumpió la consulta de la foto.");
        } catch (Exception e) {
            log.warn("No se pudo pedir la foto de {}: {}", cuenta, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo consultar la foto de " + red + ". Inténtalo de nuevo o pega la dirección de la imagen.");
        }

        int estado = respuesta.statusCode();
        if (estado == 404) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No se encontró la foto de esa cuenta de " + red
                            + ". Revisa el usuario, prueba con otra red o pega la dirección de la imagen.");
        }
        if (estado == 429) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Por hoy ya no se pueden pedir más fotos a las redes. Inténtalo mañana, usa la de YouTube o pega la dirección de la imagen.");
        }
        if (estado != 200) {
            log.warn("El servicio de fotos respondió HTTP {} para {}", estado, cuenta);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo traer la foto de " + red + " (HTTP " + estado
                            + "). Prueba con otra red o pega la dirección de la imagen.");
        }

        String tipo = respuesta.headers().firstValue("content-type").orElse("")
                .split(";")[0].trim().toLowerCase(Locale.ROOT);
        byte[] datos = respuesta.body();

        if (!TIPOS.contains(tipo) || datos == null || datos.length == 0 || datos.length > PESO_MAXIMO) {
            log.warn("Foto de {} descartada: tipo {} y {} bytes", cuenta, tipo, datos == null ? 0 : datos.length);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Lo que devolvió " + red + " no es una foto que la app pueda mostrar. Prueba con otra red o pega la dirección de la imagen.");
        }
        return new Descarga(tipo, datos);
    }

    // -------------------------------------------------------------------------
    // Del enlace de una cuenta a "red/usuario"
    // -------------------------------------------------------------------------

    /**
     * La cuenta tal como la pide el servicio de fotos ("x/usuario"), o null
     * si de ese enlace no se puede sacar: una página web, un enlace que no
     * es un perfil, una red que el servicio no conoce.
     */
    static String cuentaDe(String plataforma, String url) {
        if (plataforma == null || url == null) return null;

        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (uri.getHost() == null) return null;

        List<String> partes = Arrays.stream(Optional.ofNullable(uri.getPath()).orElse("").split("/"))
                .filter(p -> !p.isBlank())
                .toList();

        String usuario = switch (plataforma) {
            case "x", "instagram", "tiktok", "threads", "twitch" -> parte(partes, 0);
            case "telegram" -> "s".equals(parte(partes, 0)) ? parte(partes, 1) : parte(partes, 0);
            case "patreon" -> Set.of("c", "cw").contains(String.valueOf(parte(partes, 0)))
                    ? parte(partes, 1) : parte(partes, 0);
            case "facebook" -> deFacebook(uri, partes);
            case "spotify" -> deSpotify(partes);
            default -> null;
        };
        if (usuario == null) return null;

        usuario = usuario.startsWith("@") ? usuario.substring(1) : usuario;
        String sinPrefijo = usuario.contains(":") ? usuario.substring(usuario.indexOf(':') + 1) : usuario;
        if (!USUARIO.matcher(sinPrefijo).matches()) return null;

        return plataforma + "/" + usuario;
    }

    private static String parte(List<String> partes, int i) {
        return i < partes.size() ? partes.get(i) : null;
    }

    /** facebook.com/usuario, o los perfiles sin nombre: profile.php?id=123 y people/Nombre/123. */
    private static String deFacebook(URI uri, List<String> partes) {
        String primera = parte(partes, 0);
        if ("profile.php".equals(primera)) {
            String consulta = Optional.ofNullable(uri.getQuery()).orElse("");
            for (String par : consulta.split("&")) {
                if (par.startsWith("id=")) return par.substring(3);
            }
            return null;
        }
        if ("people".equals(primera) || "p".equals(primera)) return parte(partes, partes.size() - 1);
        return primera;
    }

    /** open.spotify.com/artist/ID, /show/ID o /user/ID, con o sin el prefijo de idioma. */
    private static String deSpotify(List<String> partes) {
        for (int i = 0; i + 1 < partes.size(); i++) {
            if (Set.of("artist", "show", "user").contains(partes.get(i))) {
                return partes.get(i) + ":" + partes.get(i + 1);
            }
        }
        return null;
    }

    private static String sinBarraFinal(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
