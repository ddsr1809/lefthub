package com.voces.backend.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Un folio de regalo que todavía no se ha usado. Quita los anuncios a la
 * cuenta que lo canjea, y al canjearlo la fila se borra: vale una sola vez.
 * Ver V11.
 */
@Entity
@Table(name = "folios")
public class Folio {

    /** Sin guion y en mayúsculas, que es como se compara. */
    @Id
    private String codigo;

    /** Para quién es o de qué campaña. Solo lo lee el equipo. */
    private String nota;

    @Column(name = "creado_por")
    private UUID creadoPor;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    public Folio() {}

    public Folio(String codigo, String nota, UUID creadoPor) {
        this.codigo = codigo;
        this.nota = nota;
        this.creadoPor = creadoPor;
    }

    public String getCodigo() { return codigo; }
    public void setCodigo(String codigo) { this.codigo = codigo; }

    public String getNota() { return nota; }
    public void setNota(String nota) { this.nota = nota; }

    public UUID getCreadoPor() { return creadoPor; }
    public void setCreadoPor(UUID creadoPor) { this.creadoPor = creadoPor; }

    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }
}
