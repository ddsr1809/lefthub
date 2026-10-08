package com.tuempresa.relay.modelo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lo que entra y sale por la API.
 *
 * Aquí sí usamos records: los construye Jackson, no JPA, y son inmutables.
 * Los mensajes de validación están escritos para mostrarse tal cual en la
 * pantalla del usuario.
 */
public final class Dtos {

    private Dtos() {}

    /**
     * En el orden en que se muestran. Solo YouTube genera avisos de videos;
     * las demás son enlaces del perfil. Una app que no conozca alguna de las
     * nuevas (x, facebook, threads, telegram) simplemente no la enseña.
     */
    public static final List<String> PLATAFORMAS =
            List.of("youtube", "tiktok", "twitch", "instagram", "x", "facebook", "threads",
                    "telegram", "spotify", "patreon", "web");

    public static final List<String> CATEGORIAS =
            List.of("cine", "comida", "politica", "musica", "salud", "noticias", "tecnologia", "otros");

    // -------------------------------------------------------------------------
    // Autenticación
    // -------------------------------------------------------------------------

    public record EntrarAnonimo(
            @NotBlank(message = "Falta el identificador del dispositivo.")
            @Size(max = 200)
            String deviceId
    ) {}

    /** El idToken de Google o el identityToken de Apple, según la ruta. */
    public record EntrarConProveedor(
            @NotBlank(message = "Falta el token del proveedor.")
            String token,
            String deviceId,
            String authorizationCode  // solo Apple, para poder revocar después
    ) {}

    public record Sesion(
            String token,
            UUID usuarioId,
            String proveedor,
            String email,
            boolean esAdmin,
            boolean favoritosFusionados
    ) {}

    // -------------------------------------------------------------------------
    // Directorio (lo que leen las apps)
    // -------------------------------------------------------------------------

    /**
     * El formato anterior a los canales múltiples: un enlace por plataforma.
     * Se sigue mandando (y aceptando) para las versiones de la app y del panel
     * que todavía no conocen {@code canales}.
     */
    public record ConexionDto(String plataforma, String url, String handle, String channelId) {

        /**
         * El primer canal de cada plataforma, en el orden de siempre. Es lo
         * que una app antigua entiende por "las conexiones del creador".
         */
        public static List<ConexionDto> principales(List<Canal> canales) {
            return PLATAFORMAS.stream()
                    .map(p -> canales.stream().filter(k -> p.equals(k.getPlataforma())).findFirst())
                    .flatMap(Optional::stream)
                    .map(k -> new ConexionDto(k.getPlataforma(), k.getUrl(), k.getHandle(), k.getChannelId()))
                    .toList();
        }
    }

    /**
     * Un canal del directorio.
     *
     * {@code creadorId} es el dueño y falta en el canal propio de una
     * productora; {@code productoraId} falta en el canal que es solo de su
     * creador. {@code creadores} son los demás creadores con los que aparece.
     */
    public record CanalDto(
            UUID id,
            String plataforma,
            String nombre,
            String url,
            String handle,
            String channelId,
            UUID creadorId,
            UUID productoraId,
            List<UUID> creadores
    ) {
        /**
         * @param productoraId la del canal, o null si no hay que mostrarla.
         * @param creadores    los creadores visibles con los que además aparece.
         */
        public static CanalDto de(Canal k, UUID productoraId, List<UUID> creadores) {
            return new CanalDto(k.getId(), k.getPlataforma(), k.getNombre(), k.getUrl(),
                    k.getHandle(), k.getChannelId(), k.getCreadorId(), productoraId, creadores);
        }
    }

    public record CreadorDto(
            UUID id,
            String nombre,
            String categoria,
            String bio,
            String fotoUrl,
            /** Un enlace por plataforma: lo que leen las apps anteriores. */
            List<ConexionDto> conexiones,
            /** Sus canales, en orden, y después los de otros en los que aparece. */
            List<CanalDto> canales,
            /** Productoras visibles en las que figura. */
            List<UUID> productoras,
            /**
             * {@code true} cuando la fila no es un creador sino una productora
             * que aparece en el directorio: su id es el de la productora.
             * Falta en los creadores de verdad.
             */
            Boolean esProductora
    ) {}

    public record ProductoraDto(
            UUID id,
            String nombre,
            String descripcion,
            String logoUrl,
            /** Los canales que le pertenecen: los propios y los de sus creadores. */
            List<CanalDto> canales,
            /** Creadores visibles que figuran en ella. */
            List<UUID> creadores,
            /** Aparece también en el listado de creadores, en {@code categoria}. */
            boolean enDirectorio,
            String categoria
    ) {}

    public record PublicacionDto(
            String videoId,
            UUID creadorId,
            String creadorNombre,
            String plataforma,
            String titulo,
            String miniaturaUrl,
            String url,
            String tipo,
            boolean enVivo,
            String estado,
            String destinoUrl,
            String destinoPlataforma,
            Instant publicadoEn,
            UUID canalId,
            UUID productoraId,
            String productoraNombre
    ) {
        /**
         * @param nombreCreador el del creador; en el canal propio de una
         *                      productora, el de la productora, para que quien
         *                      solo lee este campo tenga siempre a quién atribuir
         *                      el video.
         */
        public static PublicacionDto de(Publicacion p, String nombreCreador, Productora productora) {
            return new PublicacionDto(
                    p.getVideoId(), p.getCreadorId(), nombreCreador, p.getPlataforma(),
                    p.getTitulo(), p.getMiniaturaUrl(), p.getUrl(), p.getTipo(),
                    p.isEnVivo(), p.getEstado(), p.getDestinoUrl(), p.getDestinoPlataforma(),
                    p.getPublicadoEn(), p.getCanalId(),
                    productora != null ? productora.getId() : null,
                    productora != null ? productora.getNombre() : null);
        }
    }

    public record PerfilDto(
            UUID id,
            String proveedor,
            String email,
            boolean esAdmin,
            List<UUID> favoritos,
            String escalaTexto,
            String tema,
            boolean avisos,
            /** Productoras que sigue. */
            List<UUID> productoras
    ) {
        /**
         * {@code favoritos} lleva a los creadores y también a las productoras
         * que se siguen: una versión de la app que no conoce las productoras
         * las ve en el directorio como un creador más, y con su id aquí pinta
         * "Siguiendo" y suscribe el teléfono a sus avisos. Quien sí las
         * conoce las tiene aparte en {@code productoras} y las descuenta.
         */
        public static PerfilDto de(Usuario u) {
            List<UUID> seguidos = new ArrayList<>(u.getFavoritos());
            u.getProductorasSeguidas().forEach(p -> { if (!seguidos.contains(p)) seguidos.add(p); });

            return new PerfilDto(u.getId(), u.getProveedor(), u.getEmail(), u.isEsAdmin(),
                    seguidos, u.getEscalaTexto(), u.getTema(), u.isAvisos(),
                    List.copyOf(u.getProductorasSeguidas()));
        }
    }

    public record Preferencias(String escalaTexto, String tema, Boolean avisos) {}

    // -------------------------------------------------------------------------
    // Suscripciones de YouTube del usuario
    // -------------------------------------------------------------------------

    /**
     * El token de acceso de Google que la app acaba de obtener en el teléfono,
     * con permiso de solo lectura sobre YouTube. Dura una hora, se usa en el
     * momento y no se guarda.
     */
    public record VerificarYouTube(
            @NotBlank(message = "Falta el permiso de YouTube.")
            @Size(max = 4096, message = "El permiso de YouTube no es válido.")
            String accessToken
    ) {}

    /**
     * A qué canales del directorio está suscrita la persona en YouTube.
     *
     * Van los dos lados porque "no suscrito" y "no lo sabemos" no son lo
     * mismo: lo que no tiene canal de YouTube no aparece en ninguna lista.
     * {@code verificadoEn} falta cuando nunca se ha comprobado.
     *
     * {@code suscritos} y {@code noSuscritos} son ids de CREADORES y hablan de
     * su canal principal de YouTube: es lo que leen las apps anteriores a los
     * canales múltiples. Las dos listas de canales son la respuesta completa.
     */
    public record SuscripcionesYouTube(
            Instant verificadoEn,
            List<UUID> suscritos,
            List<UUID> noSuscritos,
            List<UUID> canalesSuscritos,
            List<UUID> canalesNoSuscritos
    ) {}

    // -------------------------------------------------------------------------
    // Moderación
    // -------------------------------------------------------------------------

    public record GuardarCreador(
            UUID id,

            @NotBlank(message = "El creador necesita un nombre.")
            @Size(min = 2, max = 60, message = "El nombre debe tener entre 2 y 60 caracteres.")
            String nombre,

            String categoria,

            @Size(max = 600, message = "La descripción no puede pasar de 600 caracteres.")
            String bio,

            String fotoUrl,

            /**
             * Formato anterior: un enlace por plataforma. Solo se lee si no
             * viene {@code canales}, y entonces toca nada más el canal
             * principal de cada plataforma.
             */
            Map<String, ConexionDto> conexiones,
            Boolean activo,

            /** La lista completa de canales, en orden. Sustituye a la que había. */
            List<GuardarCanal> canales,

            /** Productoras en las que figura. Si no viene, no se tocan. */
            List<UUID> productoras
    ) {
        public String categoriaOtros() {
            return (categoria == null || categoria.isBlank()) ? "otros" : categoria;
        }

        public boolean estaActivo() { return activo == null || activo; }

        public Map<String, ConexionDto> conexionesSeguras() {
            return conexiones != null ? conexiones : Map.of();
        }
    }

    /**
     * Un canal tal como lo manda el panel.
     *
     * {@code id} es el del canal que se está editando; sin él (o si no es de
     * ese dueño) se busca por channelId o por URL antes de dar uno de alta.
     * {@code productoraId} solo cuenta en los canales de un creador: los de
     * una productora son siempre suyos. {@code creadores} son los demás
     * creadores con los que aparece el canal; si no viene, no se tocan.
     */
    public record GuardarCanal(
            UUID id,
            String plataforma,
            String nombre,
            String url,
            String handle,
            String channelId,
            UUID productoraId,
            List<UUID> creadores
    ) {}

    /**
     * La ficha de un canal de YouTube, guardada por sí sola.
     *
     * {@code creadorId} es el dueño: quien firma los avisos. Si no tiene, el
     * dueño es la productora, y entonces {@code productoraId} es obligatoria.
     * {@code creadores} son los otros creadores con los que aparece: lo que
     * publica el canal les llega también a quienes los siguen.
     */
    public record GuardarFichaDeCanal(
            UUID id,

            @Size(max = 60, message = "La etiqueta no puede pasar de 60 caracteres.")
            String nombre,

            @NotBlank(message = "Falta el enlace del canal.")
            String url,

            String handle,

            @NotBlank(message = "Falta el ID del canal de YouTube. Búscalo con el buscador.")
            String channelId,

            UUID creadorId,
            UUID productoraId,
            List<UUID> creadores
    ) {}

    public record CanalGuardado(UUID id, String avisoSuscripcion, String avisoReplica) {}

    public record GuardarProductora(
            UUID id,

            @NotBlank(message = "La productora necesita un nombre.")
            @Size(min = 2, max = 60, message = "El nombre debe tener entre 2 y 60 caracteres.")
            String nombre,

            @Size(max = 600, message = "La descripción no puede pasar de 600 caracteres.")
            String descripcion,

            String logoUrl,
            Boolean activo,

            /** Sus canales propios, los que no tienen creador. Si no viene, no se tocan. */
            List<GuardarCanal> canales,

            /** Creadores que figuran en ella. Si no viene, no se tocan. */
            List<UUID> creadores,

            /** Aparece en el directorio como un creador más. Si no viene, no se toca. */
            Boolean enDirectorio,

            /** En qué tema del directorio sale. Si no viene, no se toca. */
            String categoria
    ) {
        public boolean estaActivo() { return activo == null || activo; }
    }

    public record MoverContenido(
            @NotBlank(message = "Falta el enlace nuevo.")
            @Pattern(regexp = "^https://.*", message = "El enlace debe empezar por https.")
            String url,

            String plataforma,
            Boolean avisar
    ) {
        public String plataformaDestino() {
            return (plataforma == null || plataforma.isBlank()) ? "web" : plataforma;
        }

        public boolean debeAvisar() { return avisar == null || avisar; }
    }

    /** Un canal con el estado de su suscripción al hub, que es lo que pinta el testigo. */
    public record CanalAdminDto(
            UUID id,
            String plataforma,
            String nombre,
            String url,
            String handle,
            String channelId,
            UUID creadorId,
            String creadorNombre,
            UUID productoraId,
            String estadoSuscripcion,
            Instant expiraEn,
            /** Los otros creadores con los que aparece, visibles o no. */
            List<UUID> creadores
    ) {}

    /**
     * {@code estadoSuscripcion} y {@code expiraEn} resumen todos sus canales
     * de YouTube: si alguno no está activo, se ve ese; si todos lo están, el
     * que vence antes. El detalle por canal va en {@code canales}.
     */
    public record CreadorAdminDto(
            UUID id,
            String nombre,
            String categoria,
            String bio,
            String fotoUrl,
            boolean activo,
            List<ConexionDto> conexiones,
            String estadoSuscripcion,
            Instant expiraEn,
            long seguidores,
            List<CanalAdminDto> canales,
            List<UUID> productoras,
            /** Canales de otros (de una productora, de otro creador) en los que aparece. */
            List<CanalAdminDto> canalesCompartidos
    ) {}

    public record ProductoraAdminDto(
            UUID id,
            String nombre,
            String descripcion,
            String logoUrl,
            boolean activo,
            /** Todos los canales que le pertenecen; los propios vienen sin creadorId. */
            List<CanalAdminDto> canales,
            List<UUID> creadores,
            long seguidores,
            boolean enDirectorio,
            String categoria
    ) {}

    // -------------------------------------------------------------------------
    // Panel: usuarios
    // -------------------------------------------------------------------------

    /**
     * Una cuenta tal como la ve el panel.
     *
     * No lleva deviceId, proveedorSub ni el refresh token de Apple: son
     * identificadores y credenciales que al equipo no le sirven para atender a
     * nadie, y lo que no sale del servidor no se puede filtrar.
     *
     * Los datos de conexión (ip, pais, asn, red, agente) son los del último
     * acceso; vienen en null en las cuentas que no han abierto la app desde
     * que se empezaron a guardar.
     */
    public record UsuarioAdminDto(
            UUID id,
            String proveedor,
            String email,
            boolean esAdmin,
            int favoritos,
            String escalaTexto,
            String tema,
            Instant creadoEn,
            Instant vistoEn,
            String ip,
            String pais,
            Long asn,
            String red,
            String agente,
            boolean posibleBot,
            String motivoBot
    ) {
        public static UsuarioAdminDto de(Usuario u) {
            return new UsuarioAdminDto(u.getId(), u.getProveedor(), u.getEmail(), u.isEsAdmin(),
                    u.getFavoritos().size(), u.getEscalaTexto(), u.getTema(),
                    u.getCreadoEn(), u.getVistoEn(),
                    u.getIp(), u.getPais(), u.getAsn(), u.getRed(), u.getAgente(),
                    u.isPosibleBot(), u.getMotivoBot());
        }
    }

    public record PaginaUsuarios(
            List<UsuarioAdminDto> usuarios,
            long total,
            int pagina,
            int paginas,
            int tamano
    ) {}

    public record CreadorSeguido(UUID id, String nombre, String categoria, boolean activo) {}

    public record UsuarioDetalle(
            UsuarioAdminDto usuario,
            List<CreadorSeguido> sigue,
            long reportes
    ) {}

    /** Día en formato AAAA-MM-DD, ya en la zona horaria que pidió el panel. */
    public record AltasDelDia(String dia, long altas) {}

    public record ResumenUsuarios(
            long total,
            long invitados,
            long conGoogle,
            long conApple,
            long activos24h,
            long activos7d,
            long activos30d,
            long nuevos7d,
            long nuevos30d,
            long conFavoritos,
            long seguimientos,
            long posiblesBots,
            Map<String, Long> porEscalaTexto,
            Map<String, Long> porTema,
            /** Código de país → cuentas, de más a menos. Sin las que no tienen país todavía. */
            Map<String, Long> porPais,
            List<AltasDelDia> altas
    ) {}

    public record Reporte(
            String videoId,
            UUID creadorId,

            @Size(max = 500, message = "El motivo es demasiado largo.")
            String motivo
    ) {
        public String motivoSeguro() {
            return (motivo == null || motivo.isBlank()) ? "enlace_roto" : motivo;
        }
    }

    // -------------------------------------------------------------------------
    // Respuestas
    // -------------------------------------------------------------------------

    public record RespuestaSimple(boolean ok, String mensaje) {
        public static RespuestaSimple de(String mensaje) {
            return new RespuestaSimple(true, mensaje);
        }
    }

    /**
     * Los dos avisos vienen en null cuando todo salió bien. El creador queda
     * guardado aunque traiga alguno: son fallos del hub de YouTube o de la
     * copia a testing, no del guardado.
     */
    public record CreadorGuardado(UUID id, String avisoSuscripcion, String avisoReplica) {}

    /** Igual que {@link CreadorGuardado}, para una productora. */
    public record ProductoraGuardada(UUID id, String avisoSuscripcion, String avisoReplica) {}

    public record ResultadoReplica(int total, int replicados, int fallidos, List<String> errores) {}

    public record DatosDeCanal(
            String channelId,
            String titulo,
            String descripcion,
            String fotoUrl,
            String handle,
            String suscriptores
    ) {}

    public record ResultadoRenovacion(int renovados, int fallidos, List<String> errores) {}
}
