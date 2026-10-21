package com.voces.backend.modelo;

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

        /**
         * Canales de YouTube a los que hay que preguntarles el nombre: los que
         * nunca se preguntaron y los que se preguntaron antes de {@code antes}.
         */
        @Query("""
                select k from Canal k
                where k.plataforma = 'youtube' and k.channelId is not null
                  and (k.tituloEn is null or k.tituloEn < :antes)
                order by k.tituloEn asc nulls first, k.creadoEn asc
                """)
        List<Canal> sinTituloReciente(@Param("antes") java.time.Instant antes,
                                      org.springframework.data.domain.Pageable pagina);

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

        /** Los ID de todos los canales de YouTube del directorio, visibles o no. */
        @Query("""
                select k.channelId from Canal k
                where k.plataforma = 'youtube' and k.channelId is not null
                """)
        List<String> idsDeYouTube();
    }

    public interface Productoras extends JpaRepository<Productora, UUID> {

        List<Productora> findByActivoTrueOrderByNombreAsc();

        /** La copia en testing de una productora de produccion. */
        Optional<Productora> findByOrigenId(UUID origenId);
    }

    public interface Publicaciones extends JpaRepository<Publicacion, UUID> {

        Optional<Publicacion> findByVideoId(String videoId);

        boolean existsByVideoId(String videoId);

        /** Para el panel: solo los cortos, o todo menos los cortos. */
        List<Publicacion> findByTipoOrderByPublicadoEnDesc(String tipo, Pageable pagina);

        /** Por canal, cuándo salió su último video normal: [canalId, fecha]. */
        @Query("""
                select p.canalId, max(p.publicadoEn) from Publicacion p
                where p.canalId is not null and p.tipo = 'video' and p.publicadoEn is not null
                group by p.canalId
                """)
        List<Object[]> ultimoVideoPorCanal();

        /**
         * Videos que llegaron sin sus datos de YouTube (sin duración) y a los
         * que todavía no se ha vuelto a preguntar. Los más nuevos primero.
         * Los directos tienen su propia vigilancia y no entran.
         */
        @Query("""
                select p from Publicacion p
                where p.duracion is null and p.datosRevisadosEn is null
                  and p.directo = 'no' and p.plataforma = 'youtube'
                order by p.publicadoEn desc
                """)
        List<Publicacion> sinDatos(Pageable pagina);

        @Query("""
                select count(p) from Publicacion p
                where p.duracion is null and p.datosRevisadosEn is null
                  and p.directo = 'no' and p.plataforma = 'youtube'
                """)
        long cuantosSinDatos();

        List<Publicacion> findByTipoNotOrderByPublicadoEnDesc(String tipo, Pageable pagina);

        List<Publicacion> findByVideoIdIn(Collection<String> videoIds);

        /**
         * Los videos cuyas cifras toca pedir otra vez. Cuanto más nuevo el
         * video, más rápido cambian: los de dos días, cada media hora; los del
         * último mes, cada seis horas; los demás, cada tres días. Primero los
         * que nunca se han pedido. Los retirados no: YouTube ya no los tiene.
         */
        @Query(value = """
                select * from publicaciones
                where plataforma = 'youtube'
                  and estado <> 'removed'
                  and (cifras_en is null
                       or cifras_en < now() - case
                            when publicado_en > now() - interval '2 days' then interval '30 minutes'
                            when publicado_en > now() - interval '30 days' then interval '6 hours'
                            else interval '3 days' end)
                order by cifras_en asc nulls first, publicado_en desc nulls last
                limit :limite
                """, nativeQuery = true)
        List<Publicacion> porActualizarCifras(@Param("limite") int limite);

        /** Directos pendientes de arrancar o de terminar. */
        List<Publicacion> findByDirectoIn(Collection<String> estados);

        /**
         * Lo publicado por los creadores que se siguen o en los canales de las
         * productoras que se siguen. Un video que cumple las dos cosas sale
         * una sola vez: es una fila.
         *
         * Ninguna de las dos colecciones puede llegar vacia; quien llama pone
         * un id que no existe en la que no tenga nada.
         *
         * Los videos cortos nunca van mezclados con los demas: `cortos` elige
         * cual de las dos listas se pide.
         */
        @Query("""
                select p from Publicacion p
                where (p.creadorId in :creadores or p.canalId in :canales)
                  and p.estado <> 'removed'
                  and ((:cortos = true and p.tipo = 'short') or (:cortos = false and p.tipo <> 'short'))
                order by p.publicadoEn desc nulls last
                """)
        List<Publicacion> delFeed(@Param("creadores") Collection<UUID> creadores,
                                  @Param("canales") Collection<UUID> canales,
                                  @Param("cortos") boolean cortos,
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

        /** Los últimos videos de un canal, para su ficha en la app. Sin los cortos. */
        @Query("""
                select p from Publicacion p
                where p.canalId = :canal and p.estado <> 'removed' and p.tipo <> 'short'
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

        /** Cuentas por versión de la app, con la última vez que entró alguna. */
        @Query("""
                select u.appPlataforma, u.appVersion, count(u), max(u.vistoEn) from Usuario u
                group by u.appPlataforma, u.appVersion
                """)
        List<Object[]> porVersionDeApp();

        /** Lo mismo, contando solo a quienes abrieron la app desde esa fecha. */
        @Query("""
                select u.appPlataforma, u.appVersion, count(u) from Usuario u
                where u.vistoEn >= :desde
                group by u.appPlataforma, u.appVersion
                """)
        List<Object[]> activosPorVersionDeApp(@Param("desde") Instant desde);

        /** Cuántas cuentas ya no ven anuncios, por motivo: compra, folio o panel. */
        @Query("""
                select u.sinAnunciosOrigen, count(u) from Usuario u
                where u.sinAnuncios = true
                group by u.sinAnunciosOrigen
                """)
        List<Object[]> sinAnunciosPorOrigen();

        /** Las cuentas a las que el equipo les activó el chat interno. */
        List<Usuario> findByChatHabilitadoTrueOrderByVistoEnDesc();

        /** Las cuentas que el equipo eligió para recomendar canales. */
        List<Usuario> findByRecomendarHabilitadoTrueOrderByVistoEnDesc();

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

    public interface Ajustes extends JpaRepository<Ajuste, String> {
    }

    public interface Etiquetas extends JpaRepository<Etiqueta, UUID> {

        List<Etiqueta> findAllByOrderByOrdenAscNombreAsc();

        /** Las que llegan a la app. */
        List<Etiqueta> findByActivaTrueOrderByOrdenAscNombreAsc();

        List<Etiqueta> findByNombreIgnoreCase(String nombre);
    }

    public interface Folios extends JpaRepository<Folio, String> {

        List<Folio> findAllByOrderByCreadoEnDesc(Pageable pagina);

        /**
         * Gasta un folio: lo borra y dice si estaba. Es una sola sentencia a
         * propósito. Si dos personas mandan el mismo folio a la vez, la base
         * deja pasar a una sola: la otra recibe 0 y se queda sin él.
         */
        @Modifying
        @Query("delete from Folio f where f.codigo = :codigo")
        int gastar(@Param("codigo") String codigo);
    }

    public interface Compras extends JpaRepository<Compra, UUID> {

        Optional<Compra> findByToken(String token);

        /**
         * Las compras de una cuenta pasan a otra. Hay que llamarlo antes de
         * borrar la primera cuando se funden dos: si no, se irían con ella.
         */
        @Modifying
        @Query("update Compra c set c.usuarioId = :a where c.usuarioId = :de")
        void pasar(@Param("de") UUID de, @Param("a") UUID a);
    }

    public interface Fotos extends JpaRepository<Foto, UUID> {

        /** La copia que ya hay de la foto de esa cuenta ("x/usuario"), si hay. */
        Optional<Foto> findByOrigen(String origen);
    }

    public interface Suscripciones extends JpaRepository<Suscripcion, String> {

        List<Suscripcion> findByEstado(String estado);
    }

    public interface Avisos extends JpaRepository<Aviso, Long> {

        List<Aviso> findAllByOrderByCreadoEnDesc(Pageable pagina);
    }

    public interface Reportes extends JpaRepository<Reporte, Long> {

        List<Reporte> findByResueltoFalseOrderByCreadoEnDesc(Pageable pagina);

        @Modifying
        @Query("delete from Reporte r where r.usuarioId = :usuario")
        void borrarDeUsuario(@Param("usuario") UUID usuario);

        long countByUsuarioId(UUID usuarioId);
    }

    public interface MensajesChat extends JpaRepository<MensajeChat, Long> {

        /** Los últimos de una conversación, del más nuevo al más viejo. */
        List<MensajeChat> findByUsuarioIdOrderByIdDesc(UUID usuarioId, Pageable pagina);

        /** Los que llegaron después de ese, en orden. */
        List<MensajeChat> findByUsuarioIdAndIdGreaterThanOrderByIdAsc(UUID usuarioId, long despues, Pageable pagina);

        /**
         * Los que todavía no ha leído un lado. {@code deAdmin} es quién los
         * escribió: los del equipo los lee la persona, y al revés.
         */
        long countByUsuarioIdAndDeAdminAndLeidoEnIsNull(UUID usuarioId, boolean deAdmin);

        /** Todos los mensajes de las personas que ningún administrador ha leído. */
        long countByDeAdminFalseAndLeidoEnIsNull();

        /** Cuántas conversaciones tienen algo de la persona sin leer. */
        @Query("select count(distinct m.usuarioId) from MensajeChat m where m.deAdmin = false and m.leidoEn is null")
        long conversacionesSinLeer();

        /** Marca como leídos los que escribió el otro lado, hasta ese mensaje. */
        @Modifying
        @Query("""
                update MensajeChat m set m.leidoEn = :ahora
                where m.usuarioId = :usuario and m.deAdmin = :deAdmin
                  and m.leidoEn is null and m.id <= :hasta
                """)
        int marcarLeidos(@Param("usuario") UUID usuario, @Param("deAdmin") boolean deAdmin,
                         @Param("hasta") long hasta, @Param("ahora") Instant ahora);

        /**
         * Una fila por conversación que tiene mensajes: la cuenta, el id y la
         * fecha del último, y cuántos de la persona no ha leído el equipo.
         */
        @Query(value = """
                select usuario_id, max(id) as ultimo_id, max(creado_en) as ultimo_en,
                       count(*) filter (where not de_admin and leido_en is null) as sin_leer
                from mensajes_chat
                group by usuario_id
                """, nativeQuery = true)
        List<Object[]> resumenPorConversacion();

        @Modifying
        @Query("delete from MensajeChat m where m.usuarioId = :usuario")
        int borrarConversacion(@Param("usuario") UUID usuario);

        /** Toda la conversación, para pasarla a otra cuenta al fundir dos. */
        List<MensajeChat> findByUsuarioIdOrderByIdAsc(UUID usuarioId);
    }

    public interface Recomendaciones extends JpaRepository<Recomendacion, Long> {

        /** Las de una cuenta, la más nueva primero. Lo que ve la persona en la app. */
        List<Recomendacion> findByUsuarioIdOrderByCreadoEnDesc(UUID usuarioId, Pageable pagina);

        /** Las más recientes de todas, para el panel. */
        List<Recomendacion> findAllByOrderByCreadoEnDesc(Pageable pagina);

        long countByUsuarioIdAndCreadoEnAfter(UUID usuarioId, Instant desde);

        long countByUsuarioIdAndEstado(UUID usuarioId, String estado);

        long countByEstado(String estado);

        /**
         * Las recomendaciones de una cuenta pasan a otra. Hay que llamarlo
         * antes de borrar la primera cuando se funden dos: si no, se irían
         * con ella.
         */
        @Modifying
        @Query("update Recomendacion r set r.usuarioId = :a where r.usuarioId = :de")
        void pasar(@Param("de") UUID de, @Param("a") UUID a);
    }
}
