package com.voces.backend.modelo;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Un sticker de un paquete: WebP de 512 x 512. Los bytes van aparte, en
 * {@link DatosDeSticker}, para que listar un paquete no lea sus imágenes.
 * Ver V25.
 */
@Entity
@Table(name = "stickers")
public class Sticker {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "paquete_id", nullable = false)
    private UUID paqueteId;

    @Column(nullable = false)
    private int orden = 0;

    /** De 1 a 3 emojis separados por un espacio. */
    @Column(nullable = false)
    private String emojis;

    @Column(nullable = false)
    private boolean animado = false;

    /** En bytes. */
    @Column(nullable = false)
    private int peso;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getPaqueteId() { return paqueteId; }
    public void setPaqueteId(UUID paqueteId) { this.paqueteId = paqueteId; }

    public int getOrden() { return orden; }
    public void setOrden(int orden) { this.orden = orden; }

    public String getEmojis() { return emojis; }
    public void setEmojis(String emojis) { this.emojis = emojis; }

    public boolean isAnimado() { return animado; }
    public void setAnimado(boolean animado) { this.animado = animado; }

    public int getPeso() { return peso; }
    public void setPeso(int peso) { this.peso = peso; }

    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }
}
