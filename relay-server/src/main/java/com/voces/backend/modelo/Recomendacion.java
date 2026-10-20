package com.voces.backend.modelo;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Un canal de YouTube que alguien propone para el directorio.
 *
 * Guarda lo que la persona escribió tal cual y, al lado, lo que se pudo
 * saber del canal en YouTube al recibirla. Quién puede recomendar lo decide
 * el equipo desde el panel: ver RecomendacionesService y V20.
 */
@Entity
@Table(name = "recomendaciones")
public class Recomendacion {

    public static final String PENDIENTE = "pendiente";
    /** El equipo lo agregó al directorio, o dijo que ya estaba. */
    public static final String AGREGADO = "agregado";
    /** El equipo lo revisó y decidió no agregarlo. */
    public static final String DESCARTADO = "descartado";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    @Column(nullable = false, columnDefinition = "text")
    private String texto;

    @Column(columnDefinition = "text")
    private String motivo;

    @Column(name = "channel_id", columnDefinition = "text")
    private String channelId;

    @Column(columnDefinition = "text")
    private String titulo;

    @Column(columnDefinition = "text")
    private String handle;

    @Column(name = "foto_url", columnDefinition = "text")
    private String fotoUrl;

    @Column(nullable = false, columnDefinition = "text")
    private String estado = PENDIENTE;

    @Column(name = "revisado_en")
    private Instant revisadoEn;

    @Column(name = "revisado_por")
    private UUID revisadoPor;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    public static boolean estadoValido(String estado) {
        return PENDIENTE.equals(estado) || AGREGADO.equals(estado) || DESCARTADO.equals(estado);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public UUID getUsuarioId() { return usuarioId; }
    public void setUsuarioId(UUID usuarioId) { this.usuarioId = usuarioId; }

    public String getTexto() { return texto; }
    public void setTexto(String texto) { this.texto = texto; }

    public String getMotivo() { return motivo; }
    public void setMotivo(String motivo) { this.motivo = motivo; }

    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }

    public String getTitulo() { return titulo; }
    public void setTitulo(String titulo) { this.titulo = titulo; }

    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }

    public String getFotoUrl() { return fotoUrl; }
    public void setFotoUrl(String fotoUrl) { this.fotoUrl = fotoUrl; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public Instant getRevisadoEn() { return revisadoEn; }
    public void setRevisadoEn(Instant revisadoEn) { this.revisadoEn = revisadoEn; }

    public UUID getRevisadoPor() { return revisadoPor; }
    public void setRevisadoPor(UUID revisadoPor) { this.revisadoPor = revisadoPor; }

    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }

    /** Revisada: ya no está pendiente. */
    public void revisar(String nuevoEstado, UUID quien) {
        estado = nuevoEstado;
        if (PENDIENTE.equals(nuevoEstado)) {
            revisadoEn = null;
            revisadoPor = null;
        } else {
            revisadoEn = Instant.now();
            revisadoPor = quien;
        }
    }
}
