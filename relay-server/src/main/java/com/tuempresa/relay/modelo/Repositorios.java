package com.tuempresa.relay.modelo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repositorios.
 *
 * Van agrupados como interfaces anidadas porque cada uno tiene tres o cuatro
 * metodos: repartirlos en seis archivos de diez lineas hace mas dificil ver de
 * un vistazo como se consulta la base de datos. Spring Data las detecta igual.
 */
public final class Repositorios {

    private Repositorios() {}

    public interface Creadores extends JpaRepository<Creador, UUID> {

        List<Creador> findByActivoTrueOrderByNombreAsc();

        List<Creador> findByActivoTrueAndCategoriaOrderByNombreAsc(String categoria);

        /**
         * Busca al creador dueno de un canal de YouTube.
         *
         * Es la consulta mas frecuente del sistema: se ejecuta en cada aviso
         * que llega del hub. El indice parcial sobre conexiones.channel_id la
         * resuelve sin recorrer la tabla.
         */
        @Query("""
                select c from Creador c
                join c.conexiones cx
                where key(cx) = 'youtube' and cx.channelId = :canal
                """)
        Optional<Creador> porCanalDeYouTube(@Param("canal") String canal);

        @Query("select c from Creador c join c.conexiones cx where key(cx) = 'youtube' and c.activo = true")
        List<Creador> activosConYouTube();

        /** La copia en testing de un creador de produccion. */
        Optional<Creador> findByOrigenId(UUID origenId);
    }

    public interface Publicaciones extends JpaRepository<Publicacion, UUID> {

        Optional<Publicacion> findByVideoId(String videoId);

        boolean existsByVideoId(String videoId);

        /** Directos pendientes de arrancar o de terminar. */
        List<Publicacion> findByDirectoIn(Collection<String> estados);

        @Query("""
                select p from Publicacion p
                where p.creadorId in :creadores and p.estado <> 'removed'
                order by p.publicadoEn desc nulls last
                """)
        List<Publicacion> delFeed(@Param("creadores") Collection<UUID> creadores, Pageable pagina);

        List<Publicacion> findAllByOrderByPublicadoEnDesc(Pageable pagina);

        @Modifying
        @Query("update Publicacion p set p.reportes = p.reportes + 1 where p.videoId = :videoId")
        void sumarReporte(@Param("videoId") String videoId);
    }

    public interface Usuarios extends JpaRepository<Usuario, UUID> {

        Optional<Usuario> findByDeviceId(String deviceId);

        Optional<Usuario> findByProveedorAndProveedorSub(String proveedor, String sub);

        Optional<Usuario> findByEmail(String email);

        /** Quienes siguen a un creador. Sirve para contar audiencia. */
        @Query("select count(u) from Usuario u join u.favoritos f where f = :creador")
        long cuantosSiguen(@Param("creador") UUID creador);

        // --- Panel: sección Usuarios -----------------------------------------

        /** Abrió la app después de esa fecha: visto_en se toca en cada arranque. */
        long countByVistoEnAfter(Instant desde);

        long countByCreadoEnAfter(Instant desde);

        /** Cuentas que siguen al menos a un creador. */
        @Query("select count(distinct u) from Usuario u join u.favoritos f")
        long conFavoritos();

        /** Una fila por cada "seguir": el total de seguimientos. */
        @Query("select count(u) from Usuario u join u.favoritos f")
        long seguimientos();

        @Query("select u.proveedor, count(u) from Usuario u group by u.proveedor")
        List<Object[]> porProveedor();

        @Query("select u.escalaTexto, count(u) from Usuario u group by u.escalaTexto")
        List<Object[]> porEscalaTexto();

        @Query("select u.tema, count(u) from Usuario u group by u.tema")
        List<Object[]> porTema();

        /** Cuentas por país de su última conexión, de más a menos. */
        @Query("""
                select u.pais, count(u) from Usuario u
                where u.pais is not null
                group by u.pais
                order by count(u) desc
                """)
        List<Object[]> porPais();

        long countByPosibleBotTrue();

        /** Fechas de alta recientes; el controlador las agrupa por día. */
        @Query("select u.creadoEn from Usuario u where u.creadoEn >= :desde")
        List<Instant> altasDesde(@Param("desde") Instant desde);

        /**
         * Listado paginado con filtros.
         *
         * Los filtros vacíos llegan como cadena vacía y no como null: un
         * parámetro null dentro de "(:x is null or ...)" obliga a PostgreSQL a
         * adivinar su tipo y es una fuente clásica de errores en tiempo de
         * ejecución.
         */
        @Query(value = """
                select u from Usuario u
                where (:proveedor = '' or u.proveedor = :proveedor)
                  and (:texto = '' or lower(u.email) like :texto or u.ip like :texto)
                  and (:pais = '' or u.pais = :pais)
                  and (:soloAdmins = false or u.esAdmin = true)
                  and (:soloBots = false or u.posibleBot = true)
                """,
               countQuery = """
                select count(u) from Usuario u
                where (:proveedor = '' or u.proveedor = :proveedor)
                  and (:texto = '' or lower(u.email) like :texto or u.ip like :texto)
                  and (:pais = '' or u.pais = :pais)
                  and (:soloAdmins = false or u.esAdmin = true)
                  and (:soloBots = false or u.posibleBot = true)
                """)
        Page<Usuario> buscar(@Param("proveedor") String proveedor,
                             @Param("texto") String texto,
                             @Param("pais") String pais,
                             @Param("soloAdmins") boolean soloAdmins,
                             @Param("soloBots") boolean soloBots,
                             Pageable pagina);
    }

    public interface Suscripciones extends JpaRepository<Suscripcion, String> {

        List<Suscripcion> findByEstado(String estado);
    }

    public interface Reportes extends JpaRepository<Reporte, Long> {

        List<Reporte> findByResueltoFalseOrderByCreadoEnDesc(Pageable pagina);

        @Modifying
        @Query("delete from Reporte r where r.usuarioId = :usuario")
        void borrarDeUsuario(@Param("usuario") UUID usuario);

        long countByUsuarioId(UUID usuarioId);
    }
}
