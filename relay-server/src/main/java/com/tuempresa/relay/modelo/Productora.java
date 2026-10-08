package com.tuempresa.relay.modelo;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Una productora: la casa que hay detrás de varios creadores y canales.
 *
 * Se liga con el directorio de dos maneras, independientes entre sí:
 *
 *   · con creadores, de muchos a muchos (ver Creador.productoras): una persona
 *     puede figurar en varias casas, o en ninguna;
 *   · con canales, uno por uno (ver Canal.productoraId): de los tres canales
 *     de un creador, uno puede ser de la productora y los otros dos suyos.
 *
 * No coinciden siempre. Alguien puede ser talento de una productora sin que
 * ningún canal propio le pertenezca a ella.
 */
@Entity
@Table(name = "productoras")
public class Productora {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String nombre = "";

    @Column(columnDefinition = "text")
    private String descripcion;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(nullable = false)
    private boolean activo = true;

    /**
     * Aparece en el directorio como un creador más. Así se la puede seguir
     * también desde las versiones de la app que no conocen las productoras:
     * para ellas es una fila más del listado.
     */
    @Column(name = "en_directorio", nullable = false)
    private boolean enDirectorio = false;

    /** En qué tema del directorio sale. Solo cuenta si aparece en él. */
    @Column(nullable = false)
    private String categoria = "otros";

    /**
     * Solo en testing: id que tiene esta productora en producción, cuando
     * llegó copiada desde allá. Ver V5__origen_de_replica.sql.
     */
    @Column(name = "origen_id")
    private UUID origenId;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public String getDescripcion() { return descripcion; }
    public void setDescripcion(String descripcion) { this.descripcion = descripcion; }

    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }

    public boolean isActivo() { return activo; }
    public void setActivo(boolean activo) { this.activo = activo; }

    public boolean isEnDirectorio() { return enDirectorio; }
    public void setEnDirectorio(boolean enDirectorio) { this.enDirectorio = enDirectorio; }

    public String getCategoria() { return categoria; }
    public void setCategoria(String categoria) { this.categoria = categoria; }

    public UUID getOrigenId() { return origenId; }
    public void setOrigenId(UUID origenId) { this.origenId = origenId; }

    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }

    public Instant getActualizadoEn() { return actualizadoEn; }
    public void setActualizadoEn(Instant actualizadoEn) { this.actualizadoEn = actualizadoEn; }
}
