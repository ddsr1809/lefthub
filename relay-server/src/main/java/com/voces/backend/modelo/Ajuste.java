package com.voces.backend.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Un ajuste general de la app, de los que se cambian desde el panel sin
 * desplegar nada. Clave y valor en texto; qué significa cada uno lo sabe
 * AjustesService.
 */
@Entity
@Table(name = "ajustes")
public class Ajuste {

    @Id
    private String clave;

    @Column(nullable = false)
    private String valor = "";

    @Column(name = "actualizado_en", nullable = false)
    private Instant actualizadoEn = Instant.now();

    public Ajuste() {}

    public Ajuste(String clave) { this.clave = clave; }

    public String getClave() { return clave; }
    public void setClave(String clave) { this.clave = clave; }

    public String getValor() { return valor; }
    public void setValor(String valor) { this.valor = valor; }

    public Instant getActualizadoEn() { return actualizadoEn; }
    public void setActualizadoEn(Instant actualizadoEn) { this.actualizadoEn = actualizadoEn; }
}
