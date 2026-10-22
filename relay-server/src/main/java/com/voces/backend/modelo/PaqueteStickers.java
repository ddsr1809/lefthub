package com.voces.backend.modelo;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Un paquete de stickers de un creador: lo que WhatsApp llama "sticker pack".
 * Los stickers van en {@link Sticker}; los bytes del ícono, en
 * {@link IconoDePaquete}, para que listar paquetes no los lea. Ver V25.
 */
@Entity
@Table(name = "paquetes_stickers")
public class PaqueteStickers {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "creador_id", nullable = false)
    private UUID creadorId;

    @Column(nullable = false)
    private String nombre;

    /** Mientras no se publica, solo lo ve el panel. */
    @Column(nullable = false)
    private boolean publicado = false;

    /** Lo decide el primer sticker: WhatsApp no deja mezclar fijos y animados. */
    @Column(nullable = false)
    private boolean animado = false;

    /**
     * Sube con cada cambio de stickers o de ícono. WhatsApp la usa como
     * image_data_version y la app, para saber si su copia está vieja.
     */
    @Column(nullable = false)
    private int version = 1;

    @Column(nullable = false)
    private int orden = 0;

    /** image/png o image/webp. Null mientras no tiene ícono. */
    @Column(name = "icono_tipo")
    private String iconoTipo;

    @Column(name = "icono_peso")
    private Integer iconoPeso;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn = Instant.now();

    /** Algo de lo que ven WhatsApp y la app cambió: que lo vuelvan a pedir. */
    public void cambio() {
        version++;
        actualizadoEn = Instant.now();
    }

    public boolean tieneIcono() { return iconoTipo != null; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCreadorId() { return creadorId; }
    public void setCreadorId(UUID creadorId) { this.creadorId = creadorId; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public boolean isPublicado() { return publicado; }
    public void setPublicado(boolean publicado) { this.publicado = publicado; }

    public boolean isAnimado() { return animado; }
    public void setAnimado(boolean animado) { this.animado = animado; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public int getOrden() { return orden; }
    public void setOrden(int orden) { this.orden = orden; }

    public String getIconoTipo() { return iconoTipo; }
    public void setIconoTipo(String iconoTipo) { this.iconoTipo = iconoTipo; }

    public Integer getIconoPeso() { return iconoPeso; }
    public void setIconoPeso(Integer iconoPeso) { this.iconoPeso = iconoPeso; }

    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }

    public Instant getActualizadoEn() { return actualizadoEn; }
    public void setActualizadoEn(Instant actualizadoEn) { this.actualizadoEn = actualizadoEn; }
}
