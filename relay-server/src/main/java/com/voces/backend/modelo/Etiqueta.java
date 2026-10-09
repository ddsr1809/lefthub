package com.voces.backend.modelo;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Una etiqueta del directorio, con todo lo que la lleva. Ver V12.
 *
 * Quién lleva cada etiqueta se guarda del lado de la etiqueta, y no en el
 * creador, la productora o el canal: así ninguna de esas tres fichas cambia
 * de forma, y lo que ya las lee y las guarda sigue funcionando igual.
 */
@Entity
@Table(name = "etiquetas")
public class Etiqueta {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String nombre;

    /** Apagada no sale en la app, tenga a quien tenga dentro. */
    @Column(nullable = false)
    private boolean activa = false;

    @Column(nullable = false)
    private int orden = 0;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    // BatchSize: las etiquetas se leen todas de una vez; sin él Hibernate
    // haría tres consultas por cada una.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "etiquetas_creadores", joinColumns = @JoinColumn(name = "etiqueta_id"))
    @Column(name = "creador_id")
    @BatchSize(size = 200)
    private Set<UUID> creadores = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "etiquetas_productoras", joinColumns = @JoinColumn(name = "etiqueta_id"))
    @Column(name = "productora_id")
    @BatchSize(size = 200)
    private Set<UUID> productoras = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "etiquetas_canales", joinColumns = @JoinColumn(name = "etiqueta_id"))
    @Column(name = "canal_id")
    @BatchSize(size = 200)
    private Set<UUID> canales = new LinkedHashSet<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public boolean isActiva() { return activa; }
    public void setActiva(boolean activa) { this.activa = activa; }

    public int getOrden() { return orden; }
    public void setOrden(int orden) { this.orden = orden; }

    public Instant getCreadoEn() { return creadoEn; }

    public Set<UUID> getCreadores() { return creadores; }
    public Set<UUID> getProductoras() { return productoras; }
    public Set<UUID> getCanales() { return canales; }
}
