package com.voces.backend.directorio;

import com.fasterxml.jackson.databind.JsonNode;
import com.voces.backend.config.SeguridadConfig;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Las rutas de las versiones del directorio y de la migración de pruebas a
 * producción. Ver {@link VersionesService}.
 *
 * Las de /api/admin las usa el panel, con sesión de administrador. Las de
 * /internal las llama el servidor de producción al de pruebas, con el token
 * compartido de la copia de creadores; con el token equivocado o sin
 * configurar, la ruta no existe.
 *
 * Casi todas devuelven el JSON tal como sale de la base, sin pasar por una
 * clase: el formato de una versión se escribe en un solo sitio, la migración
 * V10, y quien lo lee es el panel.
 */
@RestController
public class VersionesController {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private final VersionesService versiones;

    public VersionesController(VersionesService versiones) {
        this.versiones = versiones;
    }

    // -------------------------------------------------------------------------
    // Panel: versiones de este ambiente
    // -------------------------------------------------------------------------

    /** Qué papel tiene este servidor, sus versiones, lo pendiente de migrar y la última migración. */
    @GetMapping(value = "/api/admin/versiones", produces = JSON)
    public String lista() {
        return versiones.lista();
    }

    /** Corta una versión: la foto del directorio tal como está ahora. */
    @PostMapping("/api/admin/versiones")
    public Map<String, Object> cortar(@RequestBody(required = false) JsonNode cuerpo) {
        String nota = cuerpo == null ? null : cuerpo.path("nota").asText(null);
        int numero = versiones.cortar(nota, SeguridadConfig.Sesion.uidActual(), false);
        return Map.of("numero", numero);
    }

    /** El directorio de ahora, en el formato de una versión, sin guardarlo. */
    @GetMapping(value = "/api/admin/versiones/actual", produces = JSON)
    public String actual() {
        return versiones.actual();
    }

    @GetMapping(value = "/api/admin/versiones/{numero}", produces = JSON)
    public String una(@PathVariable int numero) {
        return versiones.version(numero);
    }

    /**
     * Pruebas: deja de proteger los cambios sin migrar de un creador o una
     * productora. La siguiente copia que mande producción lo pisa.
     */
    @PostMapping("/api/admin/versiones/descartar")
    public Map<String, Object> descartar(@RequestBody JsonNode cuerpo) {
        versiones.marcarSincronizado(tipoDe(cuerpo), idDe(cuerpo, "id"));
        return Map.of("ok", true);
    }

    // -------------------------------------------------------------------------
    // Panel de producción: migrar desde pruebas
    // -------------------------------------------------------------------------

    /** Las versiones que hay en pruebas. */
    @GetMapping(value = "/api/admin/versiones/remotas", produces = JSON)
    public String remotas() {
        return versiones.remotas();
    }

    @GetMapping(value = "/api/admin/versiones/remotas/{numero}", produces = JSON)
    public String remota(@PathVariable int numero) {
        return versiones.remota(numero);
    }

    /** La última migración aplicada aquí, con su base. El JSON {@code null} si no hay ninguna. */
    @GetMapping(value = "/api/admin/migraciones/ultima", produces = JSON)
    public String ultimaMigracion() {
        return versiones.ultimaMigracion();
    }

    /**
     * Antes de aplicar una versión de pruebas: se corta una versión de aquí,
     * para poder consultar cómo estaba todo, y se avisa a pruebas de que deje
     * entrar la copia de vuelta de lo que se va a guardar.
     */
    @PostMapping("/api/admin/migraciones/preparar")
    public Map<String, Object> preparar(@RequestBody JsonNode cuerpo) {
        int numero = versionDe(cuerpo);

        // Primero se comprueba que pruebas responde: si no, no se corta nada.
        versiones.prepararEnPruebas(numero);
        int respaldo = versiones.cortar("Antes de migrar la versión " + numero + " de pruebas",
                SeguridadConfig.Sesion.uidActual(), true);

        return Map.of("respaldo", respaldo);
    }

    /** Recién creado aquí uno que nació en pruebas: se le dice a pruebas cuál es. */
    @PostMapping(value = "/api/admin/migraciones/enlazar", produces = JSON)
    public String enlazar(@RequestBody JsonNode cuerpo) {
        return versiones.enlazarEnPruebas(tipoDe(cuerpo), idDe(cuerpo, "pruebas"), idDe(cuerpo, "produccion"));
    }

    /** Deja constancia de la migración aplicada: es la base de la siguiente. */
    @PostMapping("/api/admin/migraciones")
    public Map<String, Object> registrar(@RequestBody JsonNode cuerpo) {
        int id = versiones.registrar(cuerpo, SeguridadConfig.Sesion.uidActual());
        return Map.of("id", id);
    }

    // -------------------------------------------------------------------------
    // De servidor a servidor: producción le pide a pruebas
    // -------------------------------------------------------------------------

    @GetMapping(value = "/internal/versiones", produces = JSON)
    public ResponseEntity<String> listaParaProduccion(
            @RequestHeader(name = VersionesService.CABECERA, required = false) String token
    ) {
        if (!versiones.autoriza(token)) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        return ResponseEntity.ok(versiones.lista());
    }

    @GetMapping(value = "/internal/versiones/{numero}", produces = JSON)
    public ResponseEntity<String> unaParaProduccion(
            @RequestHeader(name = VersionesService.CABECERA, required = false) String token,
            @PathVariable int numero
    ) {
        if (!versiones.autoriza(token)) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        return ResponseEntity.ok(versiones.version(numero));
    }

    @PostMapping("/internal/versiones/preparar")
    public ResponseEntity<Map<String, Object>> prepararParaProduccion(
            @RequestHeader(name = VersionesService.CABECERA, required = false) String token,
            @RequestBody JsonNode cuerpo
    ) {
        if (!versiones.autoriza(token)) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("sincronizados", versiones.preparar(versionDe(cuerpo)));
        return ResponseEntity.ok(respuesta);
    }

    @PostMapping("/internal/versiones/enlazar")
    public ResponseEntity<Map<String, Object>> enlazarParaProduccion(
            @RequestHeader(name = VersionesService.CABECERA, required = false) String token,
            @RequestBody JsonNode cuerpo
    ) {
        if (!versiones.autoriza(token)) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("enlazado",
                versiones.enlazar(tipoDe(cuerpo), idDe(cuerpo, "pruebas"), idDe(cuerpo, "produccion")));
        return ResponseEntity.ok(respuesta);
    }

    // -------------------------------------------------------------------------

    private static int versionDe(JsonNode cuerpo) {
        if (cuerpo == null || !cuerpo.path("version").isInt()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta el número de versión.");
        }
        return cuerpo.path("version").asInt();
    }

    private static String tipoDe(JsonNode cuerpo) {
        String tipo = cuerpo == null ? "" : cuerpo.path("tipo").asText("");
        if (!VersionesService.CREADOR.equals(tipo) && !VersionesService.PRODUCTORA.equals(tipo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El tipo tiene que ser creador o productora.");
        }
        return tipo;
    }

    private static UUID idDe(JsonNode cuerpo, String campo) {
        try {
            return UUID.fromString(cuerpo.path(campo).asText(""));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta el id (" + campo + ").");
        }
    }
}
