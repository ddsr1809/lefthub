package com.tuempresa.relay.directorio;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuempresa.relay.config.RelayProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Versiones del directorio y migración de pruebas a producción.
 *
 * Una versión es una foto numerada de creadores, productoras y canales. La
 * arma la base de datos (ver V10__versiones.sql) y aquí solo se guarda y se
 * entrega: comparar dos fotos, encontrar los conflictos y decidir qué se
 * guarda lo hace el panel, que después aplica el resultado por las mismas
 * rutas de siempre (POST /api/admin/creadores y /productoras).
 *
 * Por eso esta clase no conoce el formato: mueve JSON entre la base, el panel
 * y el otro servidor. Lo que sí decide es de servidor a servidor:
 *
 *   · producción lee las versiones de pruebas con el mismo token compartido de
 *     la copia de creadores (REPLICA_URL y REPLICA_TOKEN);
 *   · pruebas no deja que la copia que manda producción pise un creador que
 *     tiene cambios sin migrar (ver {@link #exigirSinPendientes});
 *   · un creador que nació en pruebas queda enlazado con el que producción
 *     crea al migrarlo (ver {@link #enlazar}).
 */
@Service
public class VersionesService {

    /** La misma cabecera que la copia de creadores. */
    static final String CABECERA = "X-Token-Replica";

    static final String CREADOR = "creador";
    static final String PRODUCTORA = "productora";

    // -------------------------------------------------------------------------
    // SQL. Cada consulta devuelve una sola fila con un JSON ya armado.
    // -------------------------------------------------------------------------

    /** Parámetros: papel, papel. */
    static final String SQL_LISTA = """
            select jsonb_build_object(
                'papel', cast(? as text),
                'versiones', (
                    select coalesce(jsonb_agg(jsonb_build_object(
                               'numero', v.numero,
                               'nota', v.nota,
                               'creadoPor', v.creado_por,
                               'creadoEn', v.creado_en,
                               'automatica', v.automatica,
                               'creadores', jsonb_array_length(v.contenido -> 'creadores'),
                               'productoras', jsonb_array_length(v.contenido -> 'productoras'))
                           order by v.numero desc), '[]'::jsonb)
                      from versiones v),
                'pendientes', case when cast(? as text) = 'pruebas' then (
                    select coalesce(jsonb_agg(t.ficha order by t.ficha ->> 'nombre'), '[]'::jsonb)
                      from (
                        select jsonb_build_object('tipo', 'creador', 'id', c.id, 'nombre', c.nombre,
                                                  'nuevo', c.origen_id is null) as ficha
                          from creadores c
                         where c.origen_id is null
                            or (c.sincronizado is not null
                                and c.sincronizado <> directorio_huella(directorio_creador(c)))
                        union all
                        select jsonb_build_object('tipo', 'productora', 'id', p.id, 'nombre', p.nombre,
                                                  'nuevo', p.origen_id is null)
                          from productoras p
                         where p.origen_id is null
                            or (p.sincronizado is not null
                                and p.sincronizado <> directorio_huella(directorio_productora(p)))
                      ) t)
                    else '[]'::jsonb end,
                'migracion', (
                    select jsonb_build_object('version', m.version,
                                              'aplicadaEn', m.aplicada_en,
                                              'aplicadaPor', m.aplicada_por,
                                              'fallidas', coalesce(m.estado -> 'resumen' -> 'fallidas', '0'::jsonb))
                      from migraciones m order by m.id desc limit 1)
            )::text
            """;

    /** Parámetros: numero. Sin esa versión, null. */
    static final String SQL_VERSION = """
            select (select jsonb_build_object(
                               'numero', v.numero,
                               'nota', v.nota,
                               'creadoPor', v.creado_por,
                               'creadoEn', v.creado_en,
                               'automatica', v.automatica,
                               'contenido', v.contenido)::text
                      from versiones v where v.numero = ?)
            """;

    static final String SQL_ACTUAL = """
            select jsonb_build_object('contenido', directorio())::text
            """;

    /** Parámetros: nota, id de quien la corta, automatica. */
    static final String SQL_CORTAR = """
            insert into versiones (nota, creado_por, automatica, contenido)
            values (?, (select u.email from usuarios u where u.id = cast(? as uuid)), ?, directorio())
            returning numero
            """;

    static final String SQL_ULTIMA_MIGRACION = """
            select coalesce((select jsonb_build_object(
                                        'version', m.version,
                                        'aplicadaEn', m.aplicada_en,
                                        'aplicadaPor', m.aplicada_por,
                                        'estado', m.estado)::text
                               from migraciones m order by m.id desc limit 1), 'null')
            """;

    /** Parámetros: id de quien migra, el cuerpo {version, estado}. */
    static final String SQL_REGISTRAR = """
            insert into migraciones (version, aplicada_por, estado)
            select cast(j ->> 'version' as integer),
                   (select u.email from usuarios u where u.id = cast(? as uuid)),
                   j -> 'estado'
              from (select cast(? as jsonb) as j) x
            returning id
            """;

    /**
     * Da por sincronizado lo que no ha cambiado en pruebas desde que se cortó
     * esa versión: producción va a aplicarla y a mandar de vuelta el
     * resultado, y esa copia tiene que poder entrar. Parámetros: numero.
     */
    static final String SQL_PREPARAR_CREADORES = """
            update creadores c
               set sincronizado = directorio_huella(directorio_creador(c))
              from versiones v, jsonb_array_elements(v.contenido -> 'creadores') e
             where v.numero = ?
               and c.id = cast(e ->> 'id' as uuid)
               and directorio_huella(directorio_creador(c)) = directorio_huella(e)
            """;

    static final String SQL_PREPARAR_PRODUCTORAS = """
            update productoras p
               set sincronizado = directorio_huella(directorio_productora(p))
              from versiones v, jsonb_array_elements(v.contenido -> 'productoras') e
             where v.numero = ?
               and p.id = cast(e ->> 'id' as uuid)
               and directorio_huella(directorio_productora(p)) = directorio_huella(e)
            """;

    /** Parámetros: el id que tiene en producción. Sin cambios pendientes, null. */
    static final String SQL_PENDIENTE_CREADOR = """
            select max(c.nombre)
              from creadores c
             where c.origen_id = cast(? as uuid)
               and c.sincronizado is not null
               and c.sincronizado <> directorio_huella(directorio_creador(c))
            """;

    static final String SQL_PENDIENTE_PRODUCTORA = """
            select max(p.nombre)
              from productoras p
             where p.origen_id = cast(? as uuid)
               and p.sincronizado is not null
               and p.sincronizado <> directorio_huella(directorio_productora(p))
            """;

    /** Parámetros: el id de aquí. */
    static final String SQL_SINCRONIZAR_CREADOR = """
            update creadores c
               set sincronizado = directorio_huella(directorio_creador(c))
             where c.id = cast(? as uuid)
            """;

    static final String SQL_SINCRONIZAR_PRODUCTORA = """
            update productoras p
               set sincronizado = directorio_huella(directorio_productora(p))
             where p.id = cast(? as uuid)
            """;

    // Enlazar: el que nació en pruebas (T) con el que producción creó al
    // migrarlo (P). Al crearlo, producción mandó su copia y pruebas, que no
    // sabía que era el mismo, pudo dar de alta un duplicado con origen P: ese
    // duplicado se va y T se queda con el origen.

    /** Parámetros: P, T, T. */
    static final String SQL_QUITAR_DUPLICADO_CREADOR = """
            delete from creadores d
             where d.origen_id = cast(? as uuid)
               and d.id <> cast(? as uuid)
               and exists (select 1 from creadores t
                            where t.id = cast(? as uuid) and t.origen_id is null)
            """;

    /** Parámetros: P, T. */
    static final String SQL_ENLAZAR_CREADOR = """
            update creadores t
               set origen_id = cast(? as uuid),
                   sincronizado = directorio_huella(directorio_creador(t))
             where t.id = cast(? as uuid)
               and t.origen_id is null
            """;

    /** Los canales propios del duplicado: la base no deja un canal sin dueño. Parámetros: P, T, T. */
    static final String SQL_QUITAR_CANALES_DE_DUPLICADA = """
            delete from canales k
             using productoras d
             where k.creador_id is null
               and k.productora_id = d.id
               and d.origen_id = cast(? as uuid)
               and d.id <> cast(? as uuid)
               and exists (select 1 from productoras t
                            where t.id = cast(? as uuid) and t.origen_id is null)
            """;

    /** Parámetros: P, T, T. */
    static final String SQL_QUITAR_DUPLICADA = """
            delete from productoras d
             where d.origen_id = cast(? as uuid)
               and d.id <> cast(? as uuid)
               and exists (select 1 from productoras t
                            where t.id = cast(? as uuid) and t.origen_id is null)
            """;

    /** Parámetros: P, T. */
    static final String SQL_ENLAZAR_PRODUCTORA = """
            update productoras t
               set origen_id = cast(? as uuid),
                   sincronizado = directorio_huella(directorio_productora(t))
             where t.id = cast(? as uuid)
               and t.origen_id is null
            """;

    // -------------------------------------------------------------------------

    private final JdbcTemplate jdbc;
    private final RestClient http;
    private final RelayProperties config;

    public VersionesService(JdbcTemplate jdbc, RestClient http, RelayProperties config) {
        this.jdbc = jdbc;
        this.http = http;
        this.config = config;
    }

    /**
     * Qué es este servidor en la migración: "produccion" (tiene a dónde
     * copiar), "pruebas" (recibe copias) o "local" (ninguna de las dos).
     */
    public String papel() {
        if (config.replica().envia()) return "produccion";
        if (config.replica().recibe()) return "pruebas";
        return "local";
    }

    // -------------------------------------------------------------------------
    // Versiones de este servidor
    // -------------------------------------------------------------------------

    public String lista() {
        String papel = papel();
        return jdbc.queryForObject(SQL_LISTA, String.class, papel, papel);
    }

    public String version(int numero) {
        String json = jdbc.queryForObject(SQL_VERSION, String.class, numero);
        if (json == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "La versión " + numero + " no existe.");
        }
        return json;
    }

    /** El directorio tal como está ahora, en el mismo formato que una versión. */
    public String actual() {
        return jdbc.queryForObject(SQL_ACTUAL, String.class);
    }

    /** @return el número de la versión nueva. */
    public int cortar(String nota, UUID quien, boolean automatica) {
        String limpia = nota == null || nota.isBlank() ? null : nota.trim();
        if (limpia != null && limpia.length() > 200) limpia = limpia.substring(0, 200);

        Integer numero = jdbc.queryForObject(SQL_CORTAR, Integer.class,
                limpia, texto(quien), automatica);
        return numero == null ? 0 : numero;
    }

    // -------------------------------------------------------------------------
    // Producción: lo que ya migró
    // -------------------------------------------------------------------------

    /** La última migración aplicada, con su base, o el JSON {@code null}. */
    public String ultimaMigracion() {
        return jdbc.queryForObject(SQL_ULTIMA_MIGRACION, String.class);
    }

    public int registrar(JsonNode cuerpo, UUID quien) {
        if (cuerpo == null || !cuerpo.path("version").isInt() || !cuerpo.path("estado").isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Faltan la versión o el estado de la migración.");
        }
        Integer id = jdbc.queryForObject(SQL_REGISTRAR, Integer.class,
                texto(quien), cuerpo.toString());
        return id == null ? 0 : id;
    }

    // -------------------------------------------------------------------------
    // Pruebas: lo que pide producción, de servidor a servidor
    // -------------------------------------------------------------------------

    /** El token compartido. Nunca coincide si este servidor no recibe copias. */
    public boolean autoriza(String token) {
        String esperado = config.replica().token();
        if (!config.replica().recibe() || esperado == null || esperado.isBlank() || token == null) {
            return false;
        }
        return MessageDigest.isEqual(
                esperado.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    /** @return cuántos creadores y productoras quedaron listos para recibir la copia de vuelta. */
    @Transactional
    public int preparar(int numero) {
        version(numero);
        return jdbc.update(SQL_PREPARAR_CREADORES, numero)
                + jdbc.update(SQL_PREPARAR_PRODUCTORAS, numero);
    }

    /**
     * Enlaza al que nació en pruebas con el que producción creó al migrarlo.
     *
     * @return false si ese id no está en pruebas o ya estaba enlazado.
     */
    @Transactional
    public boolean enlazar(String tipo, UUID pruebas, UUID produccion) {
        String t = pruebas.toString();
        String p = produccion.toString();

        if (PRODUCTORA.equals(tipo)) {
            jdbc.update(SQL_QUITAR_CANALES_DE_DUPLICADA, p, t, t);
            jdbc.update(SQL_QUITAR_DUPLICADA, p, t, t);
            return jdbc.update(SQL_ENLAZAR_PRODUCTORA, p, t) > 0;
        }
        jdbc.update(SQL_QUITAR_DUPLICADO_CREADOR, p, t, t);
        return jdbc.update(SQL_ENLAZAR_CREADOR, p, t) > 0;
    }

    /**
     * Antes de recibir la copia de un creador o una productora: si en pruebas
     * tiene cambios que todavía no se migran, no se pisa. Producción guarda
     * igual y su panel enseña este motivo; el choque se resuelve al migrar.
     *
     * @param origen el id que tiene en producción
     */
    public void exigirSinPendientes(String tipo, UUID origen) {
        if (origen == null) return;

        String nombre = jdbc.queryForObject(
                PRODUCTORA.equals(tipo) ? SQL_PENDIENTE_PRODUCTORA : SQL_PENDIENTE_CREADOR,
                String.class, origen.toString());
        if (nombre != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    nombre + " tiene en pruebas cambios que todavía no se migran a producción, "
                            + "y no se pisaron. Se resuelve al migrar, o descártalos en el panel "
                            + "de pruebas (Versiones).");
        }
    }

    /** Lo de pruebas coincide con producción desde ahora: lo que cambie después es un cambio de pruebas. */
    public void marcarSincronizado(String tipo, UUID id) {
        if (id == null) return;
        jdbc.update(PRODUCTORA.equals(tipo) ? SQL_SINCRONIZAR_PRODUCTORA : SQL_SINCRONIZAR_CREADOR,
                id.toString());
    }

    // -------------------------------------------------------------------------
    // Producción: hablar con pruebas
    // -------------------------------------------------------------------------

    public String remotas() {
        return pedir("/internal/versiones", null);
    }

    public String remota(int numero) {
        return pedir("/internal/versiones/" + numero, null);
    }

    public String prepararEnPruebas(int numero) {
        return pedir("/internal/versiones/preparar", "{\"version\":" + numero + "}");
    }

    public String enlazarEnPruebas(String tipo, UUID pruebas, UUID produccion) {
        return pedir("/internal/versiones/enlazar",
                "{\"tipo\":\"" + (PRODUCTORA.equals(tipo) ? PRODUCTORA : CREADOR) + "\","
                        + "\"pruebas\":\"" + pruebas + "\","
                        + "\"produccion\":\"" + produccion + "\"}");
    }

    /** GET si no hay cuerpo, POST si lo hay. Devuelve el JSON que responde pruebas. */
    private String pedir(String ruta, String cuerpo) {
        RelayProperties.Replica replica = config.replica();
        if (!replica.envia()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este servidor no tiene REPLICA_URL: no sabe dónde está el de pruebas.");
        }
        if (replica.token().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Falta REPLICA_TOKEN en este servidor.");
        }

        String destino = ReplicaService.destino(replica.url(), ruta);
        String motivo;
        try {
            String respuesta = cuerpo == null
                    ? http.get()
                            .uri(destino)
                            .header(CABECERA, replica.token())
                            .retrieve()
                            .body(String.class)
                    : http.post()
                            .uri(destino)
                            .header(CABECERA, replica.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(cuerpo)
                            .retrieve()
                            .body(String.class);
            return respuesta == null || respuesta.isBlank() ? "{}" : respuesta;

        } catch (RestClientResponseException e) {
            int codigo = e.getStatusCode().value();
            motivo = codigo == 404
                    ? "El servidor de pruebas no reconoce la petición (404). Comprueba que ya "
                            + "tenga esta versión desplegada y que REPLICA_TOKEN sea el mismo en los dos."
                    : "El servidor de pruebas respondió " + codigo + ".";
        } catch (Exception e) {
            motivo = "No se pudo conectar con el servidor de pruebas: " + e.getMessage();
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, motivo);
    }

    private static String texto(UUID id) {
        return id == null ? null : id.toString();
    }
}
