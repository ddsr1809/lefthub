package com.voces.backend.modelo;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Un aviso que mandó el equipo desde el panel: un creador, medio o canal
 * nuevo, un video retirado, una cuenta bloqueada o un mensaje libre.
 *
 * Se guarda aunque FCM lo rechace, con el motivo en {@code error}: el
 * historial del panel tiene que decir también lo que no salió.
 */
@Entity
@Table(name = "avisos")
public class Aviso {

    public static final String NUEVO_CREADOR = "nuevo_creador";
    public static final String NUEVO_MEDIO = "nuevo_medio";
    public static final String NUEVO_CANAL = "nuevo_canal";
    public static final String VIDEO_RETIRADO = "video_retirado";
    public static final String CUENTA_BLOQUEADA = "cuenta_bloqueada";
    public static final String MENSAJE = "mensaje";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String tipo;

    @Column(nullable = false, columnDefinition = "text")
    private String titulo;

    @Column(nullable = false, columnDefinition = "text")
    private String cuerpo;

    @Column(columnDefinition = "text")
    private String enlace;

    @Column(nullable = false, columnDefinition = "text")
    private String destino;

    @Column(name = "creador_id")
    private UUID creadorId;

    @Column(name = "productora_id")
    private UUID productoraId;

    @Column(name = "canal_id")
    private UUID canalId;

    @Column(name = "video_id")
    private String videoId;

    @Column(nullable = false)
    private boolean enviado = false;

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "autor_id")
    private UUID autorId;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }

    public String getTitulo() { return titulo; }
    public void setTitulo(String titulo) { this.titulo = titulo; }

    public String getCuerpo() { return cuerpo; }
    public void setCuerpo(String cuerpo) { this.cuerpo = cuerpo; }

    public String getEnlace() { return enlace; }
    public void setEnlace(String enlace) { this.enlace = enlace; }

    public String getDestino() { return destino; }
    public void setDestino(String destino) { this.destino = destino; }

    public UUID getCreadorId() { return creadorId; }
    public void setCreadorId(UUID creadorId) { this.creadorId = creadorId; }

    public UUID getProductoraId() { return productoraId; }
    public void setProductoraId(UUID productoraId) { this.productoraId = productoraId; }

    public UUID getCanalId() { return canalId; }
    public void setCanalId(UUID canalId) { this.canalId = canalId; }

    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }

    public boolean isEnviado() { return enviado; }
    public void setEnviado(boolean enviado) { this.enviado = enviado; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public UUID getAutorId() { return autorId; }
    public void setAutorId(UUID autorId) { this.autorId = autorId; }

    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }
}
