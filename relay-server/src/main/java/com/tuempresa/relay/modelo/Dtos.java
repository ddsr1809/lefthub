package com.tuempresa.relay.modelo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    public static final List<String> PLATAFORMAS =
            List.of("youtube", "tiktok", "twitch", "instagram", "spotify", "patreon", "web");

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

    public record ConexionDto(String plataforma, String url, String handle, String channelId) {}

    public record CreadorDto(
            UUID id,
            String nombre,
            String categoria,
            String bio,
            String fotoUrl,
            List<ConexionDto> conexiones
    ) {
        public static CreadorDto de(Creador c) {
            List<ConexionDto> lista = PLATAFORMAS.stream()
                    .filter(p -> c.getConexiones().containsKey(p))
                    .map(p -> {
                        Conexion cx = c.getConexiones().get(p);
                        return new ConexionDto(p, cx.getUrl(), cx.getHandle(), cx.getChannelId());
                    })
                    .toList();

            return new CreadorDto(c.getId(), c.getNombre(), c.getCategoria(),
                    c.getBio(), c.getFotoUrl(), lista);
        }
    }

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
            Instant publicadoEn
    ) {
        public static PublicacionDto de(Publicacion p, String nombreCreador) {
            return new PublicacionDto(
                    p.getVideoId(), p.getCreadorId(), nombreCreador, p.getPlataforma(),
                    p.getTitulo(), p.getMiniaturaUrl(), p.getUrl(), p.getTipo(),
                    p.isEnVivo(), p.getEstado(), p.getDestinoUrl(), p.getDestinoPlataforma(),
                    p.getPublicadoEn());
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
            boolean avisos
    ) {
        public static PerfilDto de(Usuario u) {
            return new PerfilDto(u.getId(), u.getProveedor(), u.getEmail(), u.isEsAdmin(),
                    List.copyOf(u.getFavoritos()), u.getEscalaTexto(), u.getTema(), u.isAvisos());
        }
    }

    public record Preferencias(String escalaTexto, String tema, Boolean avisos) {}

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
            Map<String, ConexionDto> conexiones,
            Boolean activo
    ) {
        public String categoriaOtros() {
            return (categoria == null || categoria.isBlank()) ? "otros" : categoria;
        }

        public boolean estaActivo() { return activo == null || activo; }

        public Map<String, ConexionDto> conexionesSeguras() {
            return conexiones != null ? conexiones : Map.of();
        }
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
            long seguidores
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

    public record CreadorGuardado(UUID id, String avisoSuscripcion) {}

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
