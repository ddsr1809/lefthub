package com.voces.backend.modelo;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Un mensaje del chat entre el equipo y una cuenta.
 *
 * El texto no está aquí en claro: {@code cifrado} es AES-256-GCM con una de
 * las llaves de CHAT_LLAVES, que no viven en la base. Lo cifra y lo descifra
 * chat/CifradorDeChat. Ver V23.
 */
@Entity
@Table(name = "mensajes_chat")
public class MensajeChat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** La cuenta con la que se habla: la conversación. */
    @Column(name = "usuario_id", nullable = false)
    private UUID usuarioId;

    @Column(name = "de_admin", nullable = false)
    private boolean deAdmin;

    /** El administrador que lo escribió; null si es de la persona. */
    @Column(name = "autor_id")
    private UUID autorId;

    @Column(nullable = false)
    private short llave;

    @Column(nullable = false)
    private byte[] iv;

    @Column(nullable = false)
    private byte[] cifrado;

    @Column(name = "creado_en", nullable = false)
    private Instant creadoEn = Instant.now();

    @Column(name = "leido_en")
    private Instant leidoEn;

    protected MensajeChat() {}

    public MensajeChat(UUID usuarioId, boolean deAdmin, UUID autorId, short llave, byte[] iv, byte[] cifrado) {
        this.usuarioId = usuarioId;
        this.deAdmin = deAdmin;
        this.autorId = autorId;
        this.llave = llave;
        this.iv = iv;
        this.cifrado = cifrado;
    }

    /**
     * La pasa a otra conversación, cifrada otra vez para ella: el cifrado va
     * atado a la cuenta y no se podría leer con la nueva. La fecha y si se
     * leyó se quedan como estaban.
     */
    public void pasarA(UUID otraCuenta, short llave, byte[] iv, byte[] cifrado) {
        this.usuarioId = otraCuenta;
        this.llave = llave;
        this.iv = iv;
        this.cifrado = cifrado;
    }

    public Long getId() { return id; }
    public UUID getUsuarioId() { return usuarioId; }
    public boolean isDeAdmin() { return deAdmin; }
    public UUID getAutorId() { return autorId; }
    public short getLlave() { return llave; }
    public byte[] getIv() { return iv; }
    public byte[] getCifrado() { return cifrado; }
    public Instant getCreadoEn() { return creadoEn; }
    public Instant getLeidoEn() { return leidoEn; }
}
