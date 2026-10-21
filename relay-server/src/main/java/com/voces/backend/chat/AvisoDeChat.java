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
 * Los avisos de "tienes un mensaje" del chat.
 *
 * Dos destinos: la persona, cuando el equipo le escribe, y los
 * administradores, cuando ella contesta. Los dos son topics de FCM, y un
 * teléfono se puede suscribir a cualquier topic que conozca. Si fueran
 * "chat_" + el id de la cuenta, otro podría suscribirse al de alguien más y
 * enterarse de cuándo le escribe el equipo, o al de los administradores y
 * saber cuándo escribe cualquiera. Por eso salen de un HMAC con el secreto del
 * servidor: el perfil da a cada cuenta solo el suyo, y el del equipo solo a
 * los administradores.
 *
 * Ninguno lleva el texto del mensaje. Ver PushService.avisarChat.
 */
@Component
public class AvisoDeChat {

    /** Un aviso por conversación y destino en este tiempo, aunque lleguen varios mensajes. */
    static final Duration ENTRE_AVISOS = Duration.ofMinutes(2);

    private static final String RESPALDO = "lefthub-chat-sin-secreto";

    private final PushService push;
    private final byte[] secreto;
    private final Clock reloj;
    private final Map<String, Instant> ultimos = new ConcurrentHashMap<>();

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
        // Sin secreto (un arranque local a medio configurar) los topics siguen
        // siendo estables, solo que adivinables. El servidor exige el secreto
        // del JWT de todos modos para emitir sesiones.
        this.secreto = (secreto == null || secreto.isBlank() ? RESPALDO : secreto)
                .getBytes(StandardCharsets.UTF_8);
        this.reloj = reloj;
    }

    /** El de una cuenta: chat_ y 32 caracteres hexadecimales. */
    public String topicDe(UUID usuarioId) {
        return "chat_" + firma("chat:" + usuarioId);
    }

    /** El de los administradores. Todos los del equipo comparten este. */
    public String topicDelEquipo() {
        return "chatequipo_" + firma("chat-equipo");
    }

    /**
     * El equipo le escribió: aviso a la persona. Al tocarlo, la app abre su chat.
     *
     * @return true si se mandó y FCM lo aceptó
     */
    public boolean avisarPersona(UUID usuarioId) {
        if (reciente("persona:" + usuarioId)) return false;
        return push.avisarChat(topicDe(usuarioId),
                "Tienes un mensaje del equipo de Voces",
                "Toca para leerlo.",
                Map.of("tipo", "chat"),
                "chat") == null;
    }

    /**
     * La persona contestó: aviso a los administradores. Al tocarlo, la app de
     * un administrador abre esa conversación. Solo va el id de la cuenta, que
     * no dice nada a quien no tenga acceso al panel.
     */
    public boolean avisarEquipo(UUID usuarioId) {
        if (reciente("equipo:" + usuarioId)) return false;
        return push.avisarChat(topicDelEquipo(),
                "Mensaje nuevo en el chat",
                "Una cuenta te escribió. Toca para leerlo.",
                Map.of("tipo", "chat_equipo", "usuarioId", usuarioId.toString()),
                "chat_" + usuarioId) == null;
    }

    // -------------------------------------------------------------------------

    /** true si a ese destino ya se le avisó hace poco; si no, lo anota. */
    private boolean reciente(String clave) {
        Instant ahora = reloj.instant();
        Instant anterior = ultimos.get(clave);
        if (anterior != null && anterior.plus(ENTRE_AVISOS).isAfter(ahora)) return true;
        ultimos.put(clave, ahora);
        // De paso, que el mapa no crezca sin límite.
        ultimos.values().removeIf(t -> t.plus(ENTRE_AVISOS).isBefore(ahora));
        return false;
    }

    private String firma(String texto) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secreto, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(texto.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 no disponible", e);
        }
    }
}
