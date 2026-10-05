package com.tuempresa.relay.modelo;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * La entidad central del directorio.
 *
 * Es "un creador", no "un canal de YouTube": una persona que publica en varios
 * sitios. Esa decision de modelado permite agrupar YouTube, TikTok y Twitch
 * bajo un solo perfil, y hace que el directorio sobreviva si a alguien le
 * cierran una cuenta.
 *
 * Sus canales viven en su propia tabla (ver Canal) y se buscan por
 * creador_id: puede tener varios, tambien en la misma plataforma.
 */
@Entity
@Table(name = "creadores")
public class Creador {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String nombre = "";

    @Column(nullable = false)
    private String categoria = "otros";

    @Column(columnDefinition = "text")
    private String bio;

    @Column(name = "foto_url")
    private String fotoUrl;

    @Column(nullable = false)
    private boolean activo = true;

    /**
     * Productoras en las que figura. Es independiente de a quien pertenece
     * cada uno de sus canales: ver Productora.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "creadores_productoras", joinColumns = @JoinColumn(name = "creador_id"))
    @Column(name = "productora_id")
    private Set<UUID> productoras = new LinkedHashSet<>();

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn = Instant.now();

    /**
     * Solo en testing: id que tiene este creador en produccion, cuando llego
     * copiado desde alla. Ver V5__origen_de_replica.sql.
     */
    @Column(name = "origen_id")
    private UUID origenId;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getCategoria() { return categoria; }
    public void setCategoria(String categoria) { this.categoria = categoria; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public String getFotoUrl() { return fotoUrl; }
    public void setFotoUrl(String fotoUrl) { this.fotoUrl = fotoUrl; }

    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }

    public Set<UUID> getProductoras() { return productoras; }
    public void setProductoras(Set<UUID> productoras) {
        this.productoras = productoras != null ? productoras : new LinkedHashSet<>();
    }

    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }

    public Instant getActualizadoEn() { return actualizadoEn; }
    public void setActualizadoEn(Instant actualizadoEn) { this.actualizadoEn = actualizadoEn; }

    public UUID getOrigenId() { return origenId; }
    public void setOrigenId(UUID origenId) { this.origenId = origenId; }
}
