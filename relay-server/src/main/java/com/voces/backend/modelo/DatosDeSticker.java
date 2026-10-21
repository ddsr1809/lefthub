package com.voces.backend.modelo;

import jakarta.persistence.*;

import java.util.UUID;

/** Los bytes de un {@link Sticker}. Mismo id que el sticker. Ver V25. */
@Entity
@Table(name = "stickers_datos")
public class DatosDeSticker {

    @Id
    @Column(name = "sticker_id")
    private UUID stickerId;

    @Column(nullable = false)
    private byte[] datos;

    public DatosDeSticker() {}

    public DatosDeSticker(UUID stickerId, byte[] datos) {
        this.stickerId = stickerId;
        this.datos = datos;
    }

    public UUID getStickerId() { return stickerId; }
    public void setStickerId(UUID stickerId) { this.stickerId = stickerId; }

    public byte[] getDatos() { return datos; }
    public void setDatos(byte[] datos) { this.datos = datos; }
}
