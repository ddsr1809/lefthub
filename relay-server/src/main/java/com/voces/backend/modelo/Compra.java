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
 * Una compra de "quitar los anuncios" que Google Play confirmó. Ver V11.
 */
@Entity
@Table(name = "compras")
public class Compra {

    public static final String GOOGLE = "google";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** La cuenta que trajo la compra de último. */
    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    @Column(nullable = false)
    private String tienda = GOOGLE;

    @Column(nullable = false)
    private String producto;

    /** El comprobante que da Google Play a la app. Uno por compra. */
    @Column(nullable = false, columnDefinition = "text")
    private String token;

    /** El número de pedido del recibo: GPA.1234-5678-9012-34567. */
    private String orden;

    @Column(name = "comprado_en")
    private Instant compradoEn;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUsuarioId() { return usuarioId; }
    public void setUsuarioId(UUID usuarioId) { this.usuarioId = usuarioId; }

    public String getTienda() { return tienda; }
    public void setTienda(String tienda) { this.tienda = tienda; }

    public String getProducto() { return producto; }
    public void setProducto(String producto) { this.producto = producto; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getOrden() { return orden; }
    public void setOrden(String orden) { this.orden = orden; }

    public Instant getCompradoEn() { return compradoEn; }
    public void setCompradoEn(Instant compradoEn) { this.compradoEn = compradoEn; }

    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }
}
