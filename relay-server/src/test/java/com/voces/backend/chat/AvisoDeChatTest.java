package com.voces.backend.chat;

import com.voces.backend.push.PushService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Los topics de los avisos del chat, qué llevan y cada cuánto se mandan. */
class AvisoDeChatTest {

    private static final String SECRETO = "secreto-de-prueba-con-32-caracteres!!";

    /** Apunta lo que se habría mandado, sin hablar con FCM. */
    static final class PushDePrueba extends PushService {
        record Envio(String topic, String titulo, String cuerpo, Map<String, String> datos) {}
        final List<Envio> enviados = new ArrayList<>();
        PushDePrueba() { super(null, null); }
        @Override public String avisarChat(String topic, String titulo, String cuerpo,
                                           Map<String, String> datos, String etiqueta) {
            enviados.add(new Envio(topic, titulo, cuerpo, datos));
            return null;
        }
    }

    static final class Reloj extends Clock {
        Instant ahora = Instant.parse("2026-10-21T10:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zona) { return this; }
        @Override public Instant instant() { return ahora; }
    }

    @Test
    @DisplayName("Los topics son estables, válidos para FCM y no se deducen sin el secreto")
    void topics() {
        UUID cuenta = UUID.fromString("7f1c2b5e-0000-4000-8000-000000000001");
        AvisoDeChat uno = new AvisoDeChat(new PushDePrueba(), SECRETO, Clock.systemUTC());
        AvisoDeChat otroSecreto = new AvisoDeChat(new PushDePrueba(), "otro-secreto-de-prueba-con-32-caracteres", Clock.systemUTC());

        String topic = uno.topicDe(cuenta);
        assertTrue(topic.matches("chat_[0-9a-f]{32}"), topic);
        assertEquals(topic, uno.topicDe(cuenta));
        assertNotEquals(topic, uno.topicDe(UUID.randomUUID()));
        assertNotEquals(topic, otroSecreto.topicDe(cuenta), "sin el secreto del servidor no se puede calcular");

        String equipo = uno.topicDelEquipo();
        assertTrue(equipo.matches("chatequipo_[0-9a-f]{32}"), equipo);
        assertNotEquals(equipo, otroSecreto.topicDelEquipo());
    }

    @Test
    @DisplayName("Ningún aviso lleva el texto; el del equipo dice qué conversación abrir")
    void contenido() {
        PushDePrueba push = new PushDePrueba();
        AvisoDeChat aviso = new AvisoDeChat(push, SECRETO, Clock.systemUTC());
        UUID cuenta = UUID.randomUUID();

        assertTrue(aviso.avisarPersona(cuenta));
        assertTrue(aviso.avisarEquipo(cuenta));

        PushDePrueba.Envio persona = push.enviados.get(0);
        assertEquals(aviso.topicDe(cuenta), persona.topic());
        assertEquals(Map.of("tipo", "chat"), persona.datos());

        PushDePrueba.Envio equipo = push.enviados.get(1);
        assertEquals(aviso.topicDelEquipo(), equipo.topic());
        assertEquals(Map.of("tipo", "chat_equipo", "usuarioId", cuenta.toString()), equipo.datos());
    }

    @Test
    @DisplayName("Varios mensajes seguidos traen un solo aviso por destino")
    void unoCadaPoco() {
        PushDePrueba push = new PushDePrueba();
        Reloj reloj = new Reloj();
        AvisoDeChat aviso = new AvisoDeChat(push, SECRETO, reloj);
        UUID cuenta = UUID.randomUUID();

        assertTrue(aviso.avisarPersona(cuenta));
        assertFalse(aviso.avisarPersona(cuenta));
        assertTrue(aviso.avisarEquipo(cuenta), "el del equipo va aparte");
        assertTrue(aviso.avisarPersona(UUID.randomUUID()), "a otra cuenta sí");

        reloj.ahora = reloj.ahora.plus(AvisoDeChat.ENTRE_AVISOS).plus(Duration.ofSeconds(1));
        assertTrue(aviso.avisarPersona(cuenta));
        assertEquals(4, push.enviados.size());
    }
}
