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

        /** La copia en testing de un creador de produccion. */
        Optional<Creador> findByOrigenId(UUID origenId);

        /** Los creadores que figuran en una productora, visibles o no. */
        @Query("select c from Creador c join c.productoras p where p = :productora")
        List<Creador> deProductora(@Param("productora") UUID productora);
    }

    public interface Canales extends JpaRepository<Canal, UUID> {

        /**
         * Busca el canal de YouTube con ese ID canonico.
         *
         * Es la consulta mas frecuente del sistema: se ejecuta en cada aviso
         * que llega del hub. El indice unico sobre canales.channel_id la
         * resuelve sin recorrer la tabla, y garantiza que hay uno solo.
         */
        @Query("select k from Canal k where k.plataforma = 'youtube' and k.channelId = :canal")
        Optional<Canal> porCanalDeYouTube(@Param("canal") String canal);

        /** Todo el directorio de una vez, ya en el orden en que se muestra. */
        @Query("select k from Canal k order by k.orden asc, k.creadoEn asc, k.id asc")
        List<Canal> todosEnOrden();

        @Query("""
                select k from Canal k
                where k.creadorId = :creador
                order by k.orden asc, k.creadoEn asc, k.id asc
                """)
        List<Canal> deCreador(@Param("creador") UUID creador);

        /** Los canales que pertenecen a una productora, con creador o sin el. */
        @Query("""
                select k from Canal k
                where k.productoraId = :productora
                order by k.orden asc, k.creadoEn asc, k.id asc
                """)
        List<Canal> deProductora(@Param("productora") UUID productora);

        @Query("select k from Canal k where k.productoraId in :productoras")
        List<Canal> deProductoras(@Param("productoras") Collection<UUID> productoras);

        /** Los canales de YouTube, que son los que tienen ficha propia en el panel. */
        @Query("""
                select k from Canal k
                where k.plataforma = 'youtube'
                order by k.creadoEn asc, k.id asc
                """)
        List<Canal> deYouTube();

        /** Canales de otros en los que aparece un creador. */
        @Query("""
                select k from Canal k join k.vinculados v
                where v = :creador
                order by k.creadoEn asc, k.id asc
                """)
        List<Canal> dondeAparece(@Param("creador") UUID creador);

        /** Los canales propios de una productora: los que no tienen creador. */
        @Query("""
                select k from Canal k
                where k.productoraId = :productora and k.creadorId is null
                order by k.orden asc, k.creadoEn asc, k.id asc
                """)
        List<Canal> propiosDeProductora(@Param("productora") UUID productora);

        /**
         * Los canales de YouTube que hay que vigilar: los de creadores
         * visibles y, donde no hay creador, los de productoras visibles.
         */
        @Query("""
                select k from Canal k
                where k.plataforma = 'youtube'
                  and k.channelId is not null and k.channelId <> ''
                  and (
                        (k.creadorId is not null and exists (
                            select 1 from Creador c where c.id = k.creadorId and c.activo = true))
                     or (k.creadorId is null and exists (
                            select 1 from Productora p where p.id = k.productoraId and p.activo = true))
                  )
                order by k.creadoEn asc, k.id asc
                """)
        List<Canal> vivosDeYouTube();
    }

    public interface Productoras extends JpaRepository<Productora, UUID> {

        List<Productora> findByActivoTrueOrderByNombreAsc();

        /** La copia en testing de una productora de produccion. */
        Optional<Productora> findByOrigenId(UUID origenId);
    }

    public interface Publicaciones extends JpaRepository<Publicacion, UUID> {

        Optional<Publicacion> findByVideoId(String videoId);

        boolean existsByVideoId(String videoId);

        /** Directos pendientes de arrancar o de terminar. */
        List<Publicacion> findByDirectoIn(Collection<String> estados);

        /**
         * Lo publicado por los creadores que se siguen o en los canales de las
         * productoras que se siguen. Un video que cumple las dos cosas sale
         * una sola vez: es una fila.
         *
         * Ninguna de las dos colecciones puede llegar vacia; quien llama pone
         * un id que no existe en la que no tenga nada.
         */
        @Query("""
                select p from Publicacion p
                where (p.creadorId in :creadores or p.canalId in :canales)
                  and p.estado <> 'removed'
                order by p.publicadoEn desc nulls last
                """)
        List<Publicacion> delFeed(@Param("creadores") Collection<UUID> creadores,
                                  @Param("canales") Collection<UUID> canales,
                                  Pageable pagina);

        /**
         * Lo publicado en canales sin creador. Hay que llamarlo antes de
         * borrar esos canales: despues ya no habria por donde encontrarlo.
         */
        @Modifying
        @Query("delete from Publicacion p where p.creadorId is null and p.canalId in :canales")
        void borrarSinCreadorDe(@Param("canales") Collection<UUID> canales);

        /** Lo que un canal publicó cuando no tenía creador pasa a ser del que ahora tiene. */
        @Modifying
        @Query("update Publicacion p set p.creadorId = :creador where p.canalId = :canal and p.creadorId is null")
        void adoptar(@Param("canal") UUID canal, @Param("creador") UUID creador);

        List<Publicacion> findAllByOrderByPublicadoEnDesc(Pageable pagina);

        /** Lo último que salió en un canal, para su ficha en la app. */
        @Query("""
                select p from Publicacion p
                where p.canalId = :canal and p.estado <> 'removed'
                order by p.publicadoEn desc nulls last
                """)
        List<Publicacion> delCanal(@Param("canal") UUID canal, Pageable pagina);

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

        @Query("select count(u) from Usuario u join u.productorasSeguidas f where f = :productora")
        long cuantosSiguenProductora(@Param("productora") UUID productora);

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
