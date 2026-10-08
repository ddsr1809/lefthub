package com.tuempresa.relay.anuncios;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.auth.oauth2.GoogleCredentials;
import com.tuempresa.relay.config.RelayProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Le pregunta a Google Play si una compra es de verdad.
 *
 * La app recibe de Google Play un comprobante (el "purchase token") y nos lo
 * manda. No nos fiamos de él tal cual: cualquiera puede mandar un texto
 * inventado a esta API. Se lo enseñamos a Google con la cuenta de servicio del
 * proyecto, y solo si Google contesta que esa compra existe, es de esta app y
 * está pagada, se quitan los anuncios.
 *
 * Además le decimos a Google que la compra quedó entregada ("acknowledge").
 * Es obligatorio: una compra que nadie reconoce en tres días, Google la
 * devuelve sola y le regresa el dinero a la persona.
 *
 * Como PushService, habla con la API REST directamente: son dos llamadas, y
 * lo único que hace falta de la biblioteca de Google es el token OAuth.
 */
@Service
public class TiendaGoogle {

    private static final Logger log = LoggerFactory.getLogger(TiendaGoogle.class);

    private static final String SCOPE = "https://www.googleapis.com/auth/androidpublisher";

    private static final String RUTA =
            "/applications/{paquete}/purchases/products/{producto}/tokens/{token}";

    private final RestClient http;
    private final RelayProperties.Compras config;

    private GoogleCredentials credenciales;

    public TiendaGoogle(RestClient http, RelayProperties config) {
        this.http = http;
        this.config = config.compras();
    }

    @PostConstruct
    void preparar() {
        if (!config.estaConfigurado()) {
            log.warn("Compras sin configurar: la app no ofrecerá quitar los anuncios pagando "
                    + "(los folios de regalo sí funcionan). Rellena COMPRAS_PAQUETE.");
            return;
        }

        Path ruta = Path.of(config.credenciales());
        if (!Files.isRegularFile(ruta) || !Files.isReadable(ruta)) {
            log.error("COMPRAS DESACTIVADAS: {} no existe, es un directorio o no es legible por "
                    + "el usuario del proceso.", ruta);
            return;
        }

        try (FileInputStream flujo = new FileInputStream(config.credenciales())) {
            credenciales = GoogleCredentials.fromStream(flujo).createScoped(List.of(SCOPE));
            log.info("Compras de Google Play listas para {} (producto {})",
                    config.paquete(), config.producto());

        } catch (Exception e) {
            log.error("No se pudieron leer las credenciales de Google Play desde {}",
                    config.credenciales(), e);
        }
    }

    /**
     * ¿Se pueden verificar compras? Si no, la app no enseña el botón de
     * comprar: cobrarle a alguien algo que luego no se le puede entregar es
     * peor que no ofrecerlo.
     */
    public boolean lista() {
        return credenciales != null;
    }

    /** El producto que se vende, tal como está dado de alta en Play Console. */
    public String producto() {
        return config.producto();
    }

    /** Lo que queda de una compra confirmada. */
    public record Recibo(String orden, Instant compradoEn) {}

    /**
     * Confirma una compra con Google y la da por entregada.
     *
     * Lanza un error con un mensaje para la pantalla si no se puede: la
     * compra no existe, no está pagada todavía, o Google no contesta.
     */
    public Recibo confirmar(String producto, String token) {
        if (credenciales == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Las compras no están disponibles ahora mismo. Inténtalo más tarde.");
        }
        if (producto == null || !config.producto().equals(producto)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Esa compra no es de quitar los anuncios.");
        }

        JsonNode compra;
        try {
            compra = http.get()
                    .uri(config.apiBase() + RUTA, config.paquete(), producto, token)
                    .header("Authorization", "Bearer " + tokenDeAcceso())
                    .retrieve()
                    .body(JsonNode.class);

        } catch (RestClientResponseException e) {
            throw alFallarGoogle(e, "consultar");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("No se pudo consultar la compra en Google Play", e);
            throw sinRespuesta();
        }

        EstadoDeCompra estado = EstadoDeCompra.de(compra);
        switch (estado.pago()) {
            case PENDIENTE -> throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tu pago todavía está en proceso. En cuanto Google Play lo confirme, "
                            + "los anuncios se quitan solos.");
            case CANCELADO -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Google Play dice que esa compra se canceló o se devolvió.");
            case PAGADO -> { /* seguimos */ }
        }

        if (!estado.reconocida()) {
            reconocer(producto, token);
        }

        return new Recibo(estado.orden(), estado.compradoEn());
    }

    private void reconocer(String producto, String token) {
        try {
            http.post()
                    .uri(config.apiBase() + RUTA + ":acknowledge", config.paquete(), producto, token)
                    .header("Authorization", "Bearer " + tokenDeAcceso())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{}")
                    .retrieve()
                    .toBodilessEntity();

        } catch (RestClientResponseException e) {
            throw alFallarGoogle(e, "reconocer");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("No se pudo reconocer la compra en Google Play", e);
            throw sinRespuesta();
        }
    }

    private String tokenDeAcceso() {
        try {
            // refreshIfExpired cachea: solo pide un token nuevo cuando el
            // anterior está a punto de caducar.
            credenciales.refreshIfExpired();
            return credenciales.getAccessToken().getTokenValue();
        } catch (Exception e) {
            log.error("No se pudo obtener el token de la cuenta de servicio para Google Play", e);
            throw sinRespuesta();
        }
    }

    private ResponseStatusException alFallarGoogle(RestClientResponseException e, String paso) {
        int codigo = e.getStatusCode().value();

        // 400, 404 y 410: Google no conoce ese comprobante, o ya caducó. No es
        // un fallo nuestro ni suyo: es una compra que no existe.
        if (codigo == 400 || codigo == 404 || codigo == 410) {
            log.warn("Google Play no reconoce la compra (HTTP {} al {})", codigo, paso);
            return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Google Play no reconoce esa compra.");
        }

        // 401 y 403 son de configuración: la cuenta de servicio no está
        // invitada en Play Console, o la API no está activada. El cuerpo dice
        // cuál de las dos; sin él solo se ve "403".
        log.error("Google Play respondió HTTP {} al {} una compra: {}",
                codigo, paso, e.getResponseBodyAsString());
        return sinRespuesta();
    }

    private static ResponseStatusException sinRespuesta() {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "No pudimos confirmar tu compra con Google Play. No se pierde: "
                        + "se vuelve a intentar sola la próxima vez que abras la app.");
    }
}
