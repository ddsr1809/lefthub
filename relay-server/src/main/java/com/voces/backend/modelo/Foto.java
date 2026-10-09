package com.voces.backend.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * La copia de una foto de perfil tomada de una red social. Ver V9.
 */
@Entity
@Table(name = "fotos")
public class Foto {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** De qué cuenta salió: "x/usuario", "instagram/usuario". */
    @Column(nullable = false)
    private String origen;

    /** El tipo de imagen, tal como se sirve: image/jpeg, image/png... */
    @Column(nullable = false)
    private String tipo;

    @Column(nullable = false)
    private byte[] datos;

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getOrigen() { return origen; }
    public void setOrigen(String origen) { this.origen = origen; }

    public String getTipo() { return tipo; }
    public void setTipo(String tipo) { this.tipo = tipo; }

    public byte[] getDatos() { return datos; }
    public void setDatos(byte[] datos) { this.datos = datos; }

    public Instant getActualizadoEn() { return actualizadoEn; }
    public void setActualizadoEn(Instant actualizadoEn) { this.actualizadoEn = actualizadoEn; }
}
