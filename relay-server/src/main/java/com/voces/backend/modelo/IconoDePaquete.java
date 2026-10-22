package com.voces.backend.modelo;

import jakarta.persistence.*;

import java.util.UUID;

/**
 * Los bytes del ícono de un {@link PaqueteStickers}. Mismo id que el
 * paquete; el tipo y el peso van en el paquete. Ver V25.
 */
@Entity
@Table(name = "paquetes_iconos")
public class IconoDePaquete {

    @Id
    @Column(name = "paquete_id")
    private UUID paqueteId;

    @Column(nullable = false)
    private byte[] datos;

    public IconoDePaquete() {}

    public IconoDePaquete(UUID paqueteId, byte[] datos) {
        this.paqueteId = paqueteId;
        this.datos = datos;
    }

    public UUID getPaqueteId() { return paqueteId; }
    public void setPaqueteId(UUID paqueteId) { this.paqueteId = paqueteId; }

    public byte[] getDatos() { return datos; }
    public void setDatos(byte[] datos) { this.datos = datos; }
}
