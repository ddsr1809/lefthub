package com.voces.backend.modelo;

import com.voces.backend.seguridad.Cifrado;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Algo que una cuenta autorizada agregó al directorio desde la app: un
 * creador, un medio o un canal. No hay aprobación previa, así que este
 * registro es lo que deja al equipo revisar después qué entró y quién lo
 * metió. Ver editor/EditorService y V24.
 */
@Entity
@Table(name = "altas_desde_app")
public class AltaDesdeApp {

    public static final String CREADOR = "creador";
    public static final String MEDIO = "medio";
    public static final String CANAL = "canal";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "usuario_id")
    private UUID usuarioId;

    /** El correo de quien lo agregó, como estaba entonces. Cifrado, como en usuarios. */
    @Column(columnDefinition = "text")
    @Convert(converter = Cifrado.AutorDeAlta.class)
    private String autor;

    @Column(nullable = false, columnDefinition = "text")
    private String tipo;

    @Column(name = "objeto_id", nullable = false)
    private UUID objetoId;

    @Column(nullable = false, columnDefinition = "text")
    private String nombre;

    @Column(name = "channel_id", columnDefinition = "text")
    private String channelId;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    public AltaDesdeApp() {}

    public AltaDesdeApp(Usuario autor, String tipo, UUID objetoId, String nombre, String channelId) {
        this.usuarioId = autor.getId();
        this.autor = autor.getEmail();
        this.tipo = tipo;
        this.objetoId = objetoId;
        this.nombre = nombre;
        this.channelId = channelId;
    }

    public Long getId() { return id; }
    public UUID getUsuarioId() { return usuarioId; }
    public String getAutor() { return autor; }
    public String getTipo() { return tipo; }
    public UUID getObjetoId() { return objetoId; }
    public String getNombre() { return nombre; }
    public String getChannelId() { return channelId; }
    public Instant getCreadoEn() { return creadoEn; }
}
