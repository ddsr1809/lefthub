package com.voces.backend.chat;

import com.voces.backend.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Cifra y descifra el texto de los mensajes del chat que se guardan en la base.
 *
 * AES-256-GCM: cifra y además detecta cualquier cambio en lo guardado. Cada
 * mensaje lleva su propio IV al azar y, como datos adicionales, la cuenta de
 * la conversación y quién lo escribió. Así un mensaje cifrado no se puede
 * copiar a otra conversación, ni hacer pasar uno de la persona por uno del
 * equipo: no se descifraría.
 *
 * Las llaves vienen de CHAT_LLAVES y nunca tocan la base. Se cifra con la de
 * número más alto; las anteriores se conservan para leer lo que se cifró con
 * ellas. Para cambiar de llave se agrega una nueva con el número siguiente y
 * se dejan las demás.
 *
 * Sin llaves el chat queda apagado y el resto del servidor sigue igual. Con
 * una llave mal escrita el servidor no arranca: guardar mensajes con una llave
 * equivocada sería peor que no guardarlos, y el despliegue vuelve solo a la
 * versión anterior.
 */
@Component
public class CifradorDeChat {

    private static final Logger log = LoggerFactory.getLogger(CifradorDeChat.class);

    static final String VERSION = "lefthub-chat-v2";
    private static final int LARGO_LLAVE = 32;
    private static final int LARGO_IV = 12;
    private static final int BITS_ETIQUETA = 128;

    /** Un mensaje ya cifrado, listo para guardarse. */
    public record Cifrado(short llave, byte[] iv, byte[] datos) {}

    private final Map<Short, SecretKey> llaves;
    private final short actual;
    private final SecureRandom azar = new SecureRandom();

    @Autowired
    public CifradorDeChat(RelayProperties config) {
        this(config.chat().llaves());
    }

    CifradorDeChat(String llaves) {
        this.llaves = leer(llaves);
        this.actual = this.llaves.isEmpty() ? 0 : Collections.max(this.llaves.keySet());
        if (this.llaves.isEmpty()) {
            log.warn("Chat sin configurar: falta CHAT_LLAVES. El chat con el equipo no estará disponible.");
        } else {
            log.info("Chat listo: {} llave(s), se cifra con la número {}", this.llaves.size(), actual);
        }
    }

    public boolean listo() {
        return !llaves.isEmpty();
    }

    /** Lanza un 503 con el motivo si el chat no se puede usar en este servidor. */
    public void exigir() {
        if (!listo()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El chat no está disponible en este momento.");
        }
    }

    public Cifrado cifrar(UUID usuarioId, boolean deAdmin, String texto) {
        exigir();
        byte[] iv = new byte[LARGO_IV];
        azar.nextBytes(iv);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, llaves.get(actual), new GCMParameterSpec(BITS_ETIQUETA, iv));
            c.updateAAD(datosAdicionales(usuarioId, deAdmin, actual));
            return new Cifrado(actual, iv, c.doFinal(texto.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cifrar el mensaje", e);
        }
    }

    /**
     * El texto, o null si no se puede leer: la llave ya no está en
     * CHAT_LLAVES, o lo guardado no es lo que se cifró. Nunca se registra el
     * contenido, ni siquiera al fallar.
     */
    public String descifrar(UUID usuarioId, boolean deAdmin, short llave, byte[] iv, byte[] datos) {
        SecretKey k = llaves.get(llave);
        if (k == null) return null;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(BITS_ETIQUETA, iv));
            c.updateAAD(datosAdicionales(usuarioId, deAdmin, llave));
            return new String(c.doFinal(datos), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Un mensaje del chat de {} no se pudo descifrar (llave {})", usuarioId, llave);
            return null;
        }
    }

    // -------------------------------------------------------------------------

    private static byte[] datosAdicionales(UUID usuarioId, boolean deAdmin, short llave) {
        return (VERSION + "|" + usuarioId + "|" + (deAdmin ? "admin" : "usuario") + "|" + llave)
                .getBytes(StandardCharsets.UTF_8);
    }

    /** "1:BASE64,2:BASE64". Lanza con un mensaje claro si algo no cuadra. */
    static Map<Short, SecretKey> leer(String texto) {
        Map<Short, SecretKey> mapa = new TreeMap<>();
        if (texto == null || texto.isBlank()) return mapa;

        for (String parte : texto.split(",")) {
            String p = parte.trim();
            if (p.isEmpty()) continue;
            int dos = p.indexOf(':');
            if (dos <= 0) throw malFormada("cada llave va como número:base64, por ejemplo 1:abc...=");

            short numero;
            try {
                numero = Short.parseShort(p.substring(0, dos).trim());
            } catch (NumberFormatException e) {
                throw malFormada("el número de llave «" + p.substring(0, dos) + "» no es un número");
            }
            if (numero <= 0) throw malFormada("los números de llave empiezan en 1");

            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(p.substring(dos + 1).trim());
            } catch (IllegalArgumentException e) {
                throw malFormada("la llave " + numero + " no es base64");
            }
            if (bytes.length != LARGO_LLAVE) {
                throw malFormada("la llave " + numero + " tiene " + bytes.length
                        + " bytes y deben ser 32 (openssl rand -base64 32)");
            }
            if (mapa.put(numero, new SecretKeySpec(bytes, "AES")) != null) {
                throw malFormada("la llave " + numero + " está repetida");
            }
        }
        return mapa;
    }

    private static IllegalStateException malFormada(String motivo) {
        return new IllegalStateException("CHAT_LLAVES está mal escrita: " + motivo + ".");
    }
}
