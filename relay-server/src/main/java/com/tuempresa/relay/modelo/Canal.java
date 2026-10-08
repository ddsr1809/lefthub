package com.tuempresa.relay.modelo;

import jakarta.persistence.*;

import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Un canal en una plataforma: el de YouTube de alguien, su TikTok, su página.
 *
 * Antes era una "conexión" incrustada en el creador y solo cabía una por
 * plataforma. Ahora tiene identidad propia, porque hacen falta dos cosas que
 * así no se podían decir:
 *
 *   · un creador con varios canales en la misma plataforma (el principal, el
 *     de clips, el de directos);
 *   · un canal que pertenece a una productora, con creador o sin él.
 *
 * El dueño es el creador. Si no hay creador, es la productora: el canal
 * oficial de la casa. La base no admite un canal sin ninguno de los dos.
 *
 * Además del dueño, un canal puede aparecer con otros creadores (ver
 * {@link #getVinculados()}): el canal de una productora en el que salen tres
 * personas, por ejemplo. Lo que publica les llega también a quienes los
 * siguen, pero el dueño sigue siendo quien firma los avisos y quien decide,
 * con estar visible u oculto, si el canal se vigila.
 *
 * Los dos vínculos van como ids sueltos y no como relaciones JPA a propósito:
 * el canal se consulta casi siempre por su channel_id (el webhook) o en bloque
 * (el directorio), y así ninguna de esas consultas arrastra cargas perezosas.
 */
@Entity
@Table(name = "canales")
public class Canal {

    public static final String YOUTUBE = "youtube";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "creador_id")
    private UUID creadorId;

    @Column(name = "productora_id")
    private UUID productoraId;

    @Column(nullable = false)
    private String plataforma = YOUTUBE;

    /** Cómo se distingue de los otros canales del mismo dueño: "Clips", "Directos". */
    private String nombre;

    @Column(nullable = false)
    private String url = "";

    private String handle;

    @Column(name = "channel_id")
    private String channelId;

    /**
     * Posición dentro del dueño. El primero de cada plataforma es el
     * principal: el que reciben las versiones de la app anteriores a los
     * canales múltiples.
     */
    @Column(nullable = false)
    private int orden = 0;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    /**
     * Los otros creadores con los que aparece este canal, sin contar al dueño.
     *
     * BatchSize: el directorio lee todos los canales de una vez, y sin él
     * Hibernate haría una consulta por canal para traer esta lista.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "canales_creadores", joinColumns = @JoinColumn(name = "canal_id"))
    @Column(name = "creador_id")
    @BatchSize(size = 200)
    private Set<UUID> vinculados = new LinkedHashSet<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getCreadorId() { return creadorId; }
    public void setCreadorId(UUID creadorId) { this.creadorId = creadorId; }

    public UUID getProductoraId() { return productoraId; }
    public void setProductoraId(UUID productoraId) { this.productoraId = productoraId; }

    public String getPlataforma() { return plataforma; }
    public void setPlataforma(String plataforma) { this.plataforma = plataforma; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getHandle() { return handle; }
    public void setHandle(String handle) { this.handle = handle; }

    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }

    public int getOrden() { return orden; }
    public void setOrden(int orden) { this.orden = orden; }

    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }

    public Set<UUID> getVinculados() { return vinculados; }
    public void setVinculados(Set<UUID> vinculados) {
        this.vinculados = vinculados != null ? vinculados : new LinkedHashSet<>();
    }

    /** ID canónico si es un canal de YouTube que se puede vigilar; si no, null. */
    @Transient
    public String getCanalDeYouTube() {
        if (!YOUTUBE.equals(plataforma) || channelId == null || channelId.isBlank()) return null;
        return channelId;
    }
}
