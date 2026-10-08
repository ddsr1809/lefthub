package com.tuempresa.relay.modelo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Un navegador, o el panel instalado como app, al que se le avisa cuando pasa
 * algo que alguien del equipo tiene que atender. Ver V12.
 *
 * Lo que guarda es lo que el navegador entrega al suscribirse a Web Push: la
 * dirección de su servicio de avisos y las dos claves para cifrarle.
 */
@Entity
@Table(name = "dispositivos_admin")
public class DispositivoAdmin {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    @Column(nullable = false, unique = true, columnDefinition = "text")
    private String endpoint;

    @Column(nullable = false, columnDefinition = "text")
    private String p256dh;

    @Column(nullable = false, columnDefinition = "text")
    private String auth;

    /** "Chrome en Android": lo pone el panel para que se reconozca en la lista. */
    @Column(columnDefinition = "text")
    private String nombre;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    /** La última vez que el panel, abierto en ese dispositivo, lo confirmó. */
    @Column(name = "visto_en", nullable = false)
    private Instant vistoEn = Instant.now();

    /** El último aviso que su servicio de avisos aceptó. */
    @Column(name = "ultimo_envio")
    private Instant ultimoEnvio;

    /** Por qué falló el último envío. Null si salió bien. */
    @Column(name = "ultimo_error", columnDefinition = "text")
    private String ultimoError;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getUsuarioId() { return usuarioId; }
    public void setUsuarioId(UUID usuarioId) { this.usuarioId = usuarioId; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getP256dh() { return p256dh; }
    public void setP256dh(String p256dh) { this.p256dh = p256dh; }

    public String getAuth() { return auth; }
    public void setAuth(String auth) { this.auth = auth; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    public Instant getCreadoEn() { return creadoEn; }
    public void setCreadoEn(Instant creadoEn) { this.creadoEn = creadoEn; }

    public Instant getVistoEn() { return vistoEn; }
    public void setVistoEn(Instant vistoEn) { this.vistoEn = vistoEn; }

    public Instant getUltimoEnvio() { return ultimoEnvio; }
    public void setUltimoEnvio(Instant ultimoEnvio) { this.ultimoEnvio = ultimoEnvio; }

    public String getUltimoError() { return ultimoError; }
    public void setUltimoError(String ultimoError) { this.ultimoError = ultimoError; }
}
