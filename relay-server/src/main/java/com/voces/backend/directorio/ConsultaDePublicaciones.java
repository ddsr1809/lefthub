package com.voces.backend.directorio;

import com.voces.backend.modelo.Publicacion;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * La sección Publicaciones del panel: los videos con filtros y en el orden
 * que se pida, y los totales por creador, por canal y por medio.
 *
 * Las consultas se arman aquí y no como métodos del repositorio porque cada
 * filtro es opcional y el orden lo elige quien mira: serían decenas de
 * combinaciones. Lo que entra de fuera nunca se pega al texto de la consulta:
 * el orden sale de una lista cerrada (ORDENES) y los filtros van como
 * parámetros.
 *
 * Todo es por fecha de publicación en YouTube, y las cifras son las que hay
 * guardadas (ver CifrasDeVideos): aquí no se pregunta a nadie.
 */
@Service
public class ConsultaDePublicaciones {

    /**
     * Qué se pide.
     *
     * @param tipo   "videos", "cortos" o null (todos)
     * @param desde  publicados desde este instante; null = sin límite
     * @param hasta  publicados antes de este instante (sin incluirlo); null = sin límite
     * @param creador solo los de este creador
     * @param canal  solo los de este canal
     * @param medio  solo los de los canales de este medio (Canal.productoraId)
     */
    public record Filtro(String tipo, Instant desde, Instant hasta, UUID creador, UUID canal, UUID medio) {}

    /** Por qué se puede ordenar, y con qué expresión. Nada de fuera llega al texto de la consulta. */
    static final Map<String, String> ORDENES = Map.of(
            "publicado", "p.publicadoEn",
            "vistas", "p.vistas",
            "meGusta", "p.meGusta",
            "comentarios", "p.comentarios",
            "duracion", "p.duracionSegundos",
            "titulo", "lower(p.titulo)",
            "creador", "lower(c.nombre)",
            "estado", "p.estado");

    /** Por qué se agrupan los totales. */
    static final Map<String, String> GRUPOS = Map.of(
            "creador", "p.creadorId",
            "canal", "p.canalId",
            "medio", "k.productoraId");

    /** Una consulta lista para ejecutar: el texto y sus parámetros. */
    record Consulta(String jpql, Map<String, Object> parametros) {}

    /** Una fila de los totales: de quién, y lo que suman sus videos. */
    public record Total(UUID id, long videos, long conCifras, long vistas, long meGusta,
                        long comentarios, Instant ultimo) {}

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public List<Publicacion> videos(Filtro f, String orden, boolean ascendente, int limite) {
        Consulta c = videosJpql(f, orden, ascendente);
        TypedQuery<Publicacion> q = em.createQuery(c.jpql(), Publicacion.class);
        c.parametros().forEach(q::setParameter);
        q.setMaxResults(limite);
        return q.getResultList();
    }

    @Transactional(readOnly = true)
    public List<Total> totales(Filtro f, String por) {
        Consulta c = totalesJpql(f, por);
        TypedQuery<Object[]> q = em.createQuery(c.jpql(), Object[].class);
        c.parametros().forEach(q::setParameter);
        return q.getResultList().stream().map(fila -> new Total(
                (UUID) fila[0],
                numero(fila[1]), numero(fila[2]), numero(fila[3]), numero(fila[4]), numero(fila[5]),
                (Instant) fila[6])).toList();
    }

    private static long numero(Object valor) {
        return valor instanceof Number n ? n.longValue() : 0L;
    }

    // -------------------------------------------------------------------------
    // Las consultas. Sin base de datos: se prueban como texto.
    // -------------------------------------------------------------------------

    /**
     * Los videos en el orden pedido. Lo que no tiene ese dato (un video sin
     * cifras todavía, sin duración) va siempre al final, se ordene hacia
     * donde se ordene; a igualdad, el más reciente primero.
     */
    static Consulta videosJpql(Filtro f, String orden, boolean ascendente) {
        String expresion = ORDENES.getOrDefault(orden, ORDENES.get("publicado"));
        Map<String, Object> parametros = new LinkedHashMap<>();

        String jpql = "select p from Publicacion p"
                + " left join Canal k on k.id = p.canalId"
                + " left join Creador c on c.id = p.creadorId"
                + donde(f, parametros)
                + " order by case when " + expresion + " is null then 1 else 0 end, "
                + expresion + (ascendente ? " asc" : " desc")
                + ", p.publicadoEn desc, p.id";
        return new Consulta(jpql, parametros);
    }

    /** Lo que suman los videos de cada creador, canal o medio. Los sin dueño en ese grupo no cuentan. */
    static Consulta totalesJpql(Filtro f, String por) {
        String grupo = GRUPOS.get(por);
        if (grupo == null) throw new IllegalArgumentException("Agrupar por creador, canal o medio.");
        Map<String, Object> parametros = new LinkedHashMap<>();

        String filtros = donde(f, parametros);
        String jpql = "select " + grupo + ", count(p),"
                + " sum(case when p.vistas is null then 0 else 1 end),"
                + " sum(coalesce(p.vistas, 0)), sum(coalesce(p.meGusta, 0)), sum(coalesce(p.comentarios, 0)),"
                + " max(p.publicadoEn)"
                + " from Publicacion p"
                + " left join Canal k on k.id = p.canalId"
                + (filtros.isEmpty() ? " where " : filtros + " and ") + grupo + " is not null"
                + " group by " + grupo
                + " order by sum(coalesce(p.vistas, 0)) desc";
        return new Consulta(jpql, parametros);
    }

    private static String donde(Filtro f, Map<String, Object> parametros) {
        StringBuilder w = new StringBuilder();
        if ("cortos".equals(f.tipo())) {
            y(w, "p.tipo = :corto");
            parametros.put("corto", Publicacion.TIPO_CORTO);
        } else if ("videos".equals(f.tipo())) {
            y(w, "p.tipo <> :corto");
            parametros.put("corto", Publicacion.TIPO_CORTO);
        }
        if (f.desde() != null) {
            y(w, "p.publicadoEn >= :desde");
            parametros.put("desde", f.desde());
        }
        if (f.hasta() != null) {
            y(w, "p.publicadoEn < :hasta");
            parametros.put("hasta", f.hasta());
        }
        if (f.creador() != null) {
            y(w, "p.creadorId = :creador");
            parametros.put("creador", f.creador());
        }
        if (f.canal() != null) {
            y(w, "p.canalId = :canal");
            parametros.put("canal", f.canal());
        }
        if (f.medio() != null) {
            y(w, "k.productoraId = :medio");
            parametros.put("medio", f.medio());
        }
        return w.toString();
    }

    private static void y(StringBuilder w, String condicion) {
        w.append(w.isEmpty() ? " where " : " and ").append(condicion);
    }
}
