package com.tuempresa.relay.youtube;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuempresa.relay.config.RelayProperties;
import com.tuempresa.relay.modelo.Dtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente de la Data API v3.
 *
 * Regla de oro de cuota: nunca usamos search.list, que cuesta 100 unidades por
 * llamada y agotaría el presupuesto diario de 10.000 en cien peticiones.
 * videos.list y channels.list cuestan 1 unidad cada una, y siempre las
 * invocamos con un ID que ya conocemos porque nos lo dio WebSub gratis.
 *
 * Ese desacoplamiento —detección gratis por WebSub, enriquecimiento de 1
 * unidad por ID conocido— es lo que convierte 10.000 unidades diarias en una
 * barrera prácticamente inalcanzable.
 */
@Service
public class YouTubeClient {

    private static final Logger log = LoggerFactory.getLogger(YouTubeClient.class);
    private static final String BASE = "https://www.googleapis.com/youtube/v3";
    private static final List<String> TAMANOS_MINIATURA =
            List.of("maxres", "standard", "high", "medium", "default");

    private final RestClient http;
    private final RelayProperties config;

    public YouTubeClient(RestClient http, RelayProperties config) {
        this.http = http;
        this.config = config;
    }

    public record DetalleDeVideo(
            String videoId,
            String titulo,
            String descripcion,
            String channelId,
            String channelTitle,
            String miniatura,
            String publicado,
            String duracion,
            boolean enVivo,
            String tipo,
            /** no | programado | en_vivo | terminado. Ver Publicacion.DIRECTO_*. */
            String directo
    ) {}

    /**
     * Metadatos completos de un video. Coste: 1 unidad.
     *
     * Este paso es obligatorio: con lo que trae el XML de WebSub no se puede
     * armar una notificación presentable.
     */
    public DetalleDeVideo detallesDeVideo(String videoId) {
        JsonNode respuesta = pedir(BASE + "/videos"
                + "?part=snippet,liveStreamingDetails,contentDetails"
                + "&id=" + codificar(videoId)
                + "&key=" + config.youtube().apiKey());

        JsonNode item = primerItem(respuesta);
        if (item == null) {
            // Pasa cuando el video es privado, se borró en segundos o es un
            // Short todavía en proceso. No es un error.
            log.warn("videos.list no devolvió resultados para {}", videoId);
            return null;
        }

        return aDetalle(videoId, item);
    }

    /**
     * Estado actual de varios videos en una sola llamada. Coste: 1 unidad por
     * cada 50 IDs, que es lo que hace barato vigilar los directos.
     *
     * Devuelve null si la llamada falló, y un mapa (quizá vacío) si funcionó.
     * La diferencia importa: un video ausente en una respuesta correcta es un
     * video borrado o privado; un fallo de red no dice nada de ninguno.
     */
    public Map<String, DetalleDeVideo> detallesDeVideos(List<String> videoIds) {
        Map<String, DetalleDeVideo> detalles = new LinkedHashMap<>();

        for (int desde = 0; desde < videoIds.size(); desde += 50) {
            List<String> lote = videoIds.subList(desde, Math.min(desde + 50, videoIds.size()));

            JsonNode respuesta = pedir(BASE + "/videos"
                    + "?part=snippet,liveStreamingDetails,contentDetails"
                    + "&maxResults=50"
                    + "&id=" + codificar(String.join(",", lote))
                    + "&key=" + config.youtube().apiKey());

            if (respuesta == null) return null;

            JsonNode items = respuesta.get("items");
            if (items == null || !items.isArray()) continue;

            for (JsonNode item : items) {
                String id = texto(item, "id");
                if (id != null) detalles.put(id, aDetalle(id, item));
            }
        }
        return detalles;
    }

    private DetalleDeVideo aDetalle(String videoId, JsonNode item) {
        JsonNode snippet = item.get("snippet");
        JsonNode enVivo = item.get("liveStreamingDetails");
        String duracion = texto(item.path("contentDetails"), "duration");

        // liveStreamingDetails solo existe en directos y estrenos. Las marcas
        // de tiempo dicen en qué punto está: sin inicio real, aún no arranca;
        // con inicio y sin final, está al aire; con final, ya terminó.
        String directo;
        if (enVivo == null) directo = "no";
        else if (enVivo.has("actualEndTime")) directo = "terminado";
        else if (enVivo.has("actualStartTime")) directo = "en_vivo";
        else directo = "programado";

        return new DetalleDeVideo(
                videoId,
                textoODefecto(snippet, "title", "Video nuevo"),
                recortar(texto(snippet, "description"), 1500),
                texto(snippet, "channelId"),
                texto(snippet, "channelTitle"),
                mejorMiniatura(snippet),
                texto(snippet, "publishedAt"),
                duracion,
                "en_vivo".equals(directo),
                esCorto(duracion) ? "short" : "video",
                directo
        );
    }

    /** Datos públicos de un canal, para prellenar el formulario del panel. */
    public Dtos.DatosDeCanal detallesDeCanal(String channelId) {
        JsonNode respuesta = pedir(BASE + "/channels"
                + "?part=snippet,statistics"
                + "&id=" + codificar(channelId)
                + "&key=" + config.youtube().apiKey());

        JsonNode item = primerItem(respuesta);
        if (item == null) return null;

        JsonNode snippet = item.get("snippet");
        return new Dtos.DatosDeCanal(
                channelId,
                textoODefecto(snippet, "title", ""),
                recortar(texto(snippet, "description"), 800),
                mejorMiniatura(snippet),
                texto(snippet, "customUrl"),
                texto(item.path("statistics"), "subscriberCount")
        );
    }

    /**
     * Resuelve un @handle a su channelId (UC...). Coste: 1 unidad.
     * WebSub exige el ID canónico; los handles no valen como hub.topic.
     */
    public String resolverHandle(String handle) {
        String limpio = handle.startsWith("@") ? handle.substring(1) : handle;

        JsonNode respuesta = pedir(BASE + "/channels"
                + "?part=id&forHandle=@" + codificar(limpio)
                + "&key=" + config.youtube().apiKey());

        JsonNode item = primerItem(respuesta);
        return item != null ? texto(item, "id") : null;
    }

    // -------------------------------------------------------------------------

    private JsonNode pedir(String url) {
        try {
            return http.get().uri(url).retrieve().body(JsonNode.class);
        } catch (Exception e) {
            log.error("Llamada a la Data API fallida: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode primerItem(JsonNode respuesta) {
        if (respuesta == null) return null;
        JsonNode items = respuesta.get("items");
        return items != null && items.isArray() && !items.isEmpty() ? items.get(0) : null;
    }

    private String mejorMiniatura(JsonNode snippet) {
        if (snippet == null) return null;
        JsonNode miniaturas = snippet.get("thumbnails");
        if (miniaturas == null) return null;

        for (String tamano : TAMANOS_MINIATURA) {
            String url = texto(miniaturas.path(tamano), "url");
            if (url != null) return url;
        }
        return null;
    }

    /** Heurística barata: un Short dura tres minutos o menos. */
    private boolean esCorto(String duracionIso) {
        if (duracionIso == null || duracionIso.isBlank()) return false;
        try {
            long segundos = Duration.parse(duracionIso).getSeconds();
            return segundos > 0 && segundos <= 180;
        } catch (Exception e) {
            return false;
        }
    }

    private String texto(JsonNode nodo, String campo) {
        if (nodo == null) return null;
        JsonNode valor = nodo.get(campo);
        return valor != null && !valor.isNull() ? valor.asText() : null;
    }

    private String textoODefecto(JsonNode nodo, String campo, String porDefecto) {
        String valor = texto(nodo, campo);
        return valor != null ? valor : porDefecto;
    }

    private String recortar(String texto, int maximo) {
        if (texto == null) return "";
        return texto.length() <= maximo ? texto : texto.substring(0, maximo);
    }

    private String codificar(String valor) {
        return UriUtils.encodeQueryParam(valor, StandardCharsets.UTF_8);
    }
}
