package com.voces.backend.chat;

import com.voces.backend.config.RelayProperties;
import com.voces.backend.push.PushService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * El aviso de "el equipo quiere hablar contigo".
 *
 * Los avisos de FCM van por topics, y un teléfono se puede suscribir a
 * cualquier topic que conozca. Si el del chat fuera "chat_" + el id de la
 * cuenta, otro podría suscribirse al de alguien más y enterarse de cuándo le
 * escribe el equipo. Por eso sale de un HMAC con el secreto del servidor: el
 * perfil se lo da solo a su dueño y nadie puede calcular el de otro.
 */
@Component
public class AvisoDeChat {

    /** Un aviso por cuenta en este tiempo, aunque se abra y cierre varias veces. */
    static final Duration ENTRE_AVISOS = Duration.ofMinutes(2);

    private static final String RESPALDO = "lefthub-chat-sin-secreto";

    private final PushService push;
    private final byte[] secreto;
    private final Clock reloj;
    private final Map<UUID, Instant> ultimos = new ConcurrentHashMap<>();

    /**
     * El que usa Spring. @Autowired hace falta porque hay otro constructor,
     * el de las pruebas: con dos y sin marca, Spring busca uno sin
     * argumentos, no lo encuentra y el servidor no arranca.
     */
    @Autowired
    public AvisoDeChat(PushService push, RelayProperties config) {
        this(push, config.jwt().secreto(), Clock.systemUTC());
    }

    AvisoDeChat(PushService push, String secreto, Clock reloj) {
        this.push = push;
        // Sin secreto (un arranque local a medio configurar) el topic sigue
        // siendo estable, solo que adivinable. El servidor exige el secreto
        // del JWT de todos modos para emitir sesiones.
        this.secreto = (secreto == null || secreto.isBlank() ? RESPALDO : secreto)
                .getBytes(StandardCharsets.UTF_8);
        this.reloj = reloj;
    }

    /** chat_ y 32 caracteres hexadecimales. Los topics de FCM los admiten. */
    public String topicDe(UUID usuarioId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secreto, "HmacSHA256"));
            byte[] firma = mac.doFinal(("chat:" + usuarioId).getBytes(StandardCharsets.UTF_8));
            return "chat_" + HexFormat.of().formatHex(firma).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 no disponible", e);
        }
    }

    /**
     * Manda el aviso, salvo que ya se haya mandado hace poco.
     *
     * @return true si se mandó y FCM lo aceptó
     */
    public boolean invitar(UUID usuarioId) {
        Instant ahora = reloj.instant();
        Instant anterior = ultimos.get(usuarioId);
        if (anterior != null && anterior.plus(ENTRE_AVISOS).isAfter(ahora)) return false;

        ultimos.put(usuarioId, ahora);
        // De paso, que el mapa no crezca sin límite.
        ultimos.values().removeIf(t -> t.plus(ENTRE_AVISOS).isBefore(ahora));

        return push.avisarChat(topicDe(usuarioId)) == null;
    }
}
