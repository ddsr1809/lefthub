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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** El topic del aviso de chat y cada cuánto se manda. */
class AvisoDeChatTest {

    /** Apunta los topics a los que se habría mandado, sin hablar con FCM. */
    private static final class PushDePrueba extends PushService {
        final List<String> enviados = new ArrayList<>();
        PushDePrueba() { super(null, null); }
        @Override public String avisarChat(String topic) { enviados.add(topic); return null; }
    }

    private static final class Reloj extends Clock {
        Instant ahora = Instant.parse("2026-10-20T10:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zona) { return this; }
        @Override public Instant instant() { return ahora; }
    }

    @Test
    @DisplayName("El topic es estable, válido para FCM y no se deduce del id de la cuenta")
    void topic() {
        UUID cuenta = UUID.fromString("7f1c2b5e-0000-4000-8000-000000000001");
        AvisoDeChat uno = new AvisoDeChat(new PushDePrueba(), "secreto-de-prueba-con-32-caracteres!!", Clock.systemUTC());
        AvisoDeChat otroSecreto = new AvisoDeChat(new PushDePrueba(), "otro-secreto-de-prueba-con-32-caracteres", Clock.systemUTC());

        String topic = uno.topicDe(cuenta);
        assertTrue(topic.matches("chat_[0-9a-f]{32}"), topic);
        assertEquals(topic, uno.topicDe(cuenta));
        assertNotEquals(topic, uno.topicDe(UUID.randomUUID()));
        assertNotEquals(topic, otroSecreto.topicDe(cuenta), "sin el secreto del servidor no se puede calcular");
        assertFalse(topic.contains(cuenta.toString().replace("-", "").substring(0, 8)));
    }

    @Test
    @DisplayName("Abrir y cerrar varias veces no llena el teléfono de avisos")
    void unoCadaPoco() {
        PushDePrueba push = new PushDePrueba();
        Reloj reloj = new Reloj();
        AvisoDeChat aviso = new AvisoDeChat(push, "secreto-de-prueba-con-32-caracteres!!", reloj);
        UUID cuenta = UUID.randomUUID();

        assertTrue(aviso.invitar(cuenta));
        assertFalse(aviso.invitar(cuenta));
        assertTrue(aviso.invitar(UUID.randomUUID()), "a otra cuenta sí");

        reloj.ahora = reloj.ahora.plus(AvisoDeChat.ENTRE_AVISOS).plus(Duration.ofSeconds(1));
        assertTrue(aviso.invitar(cuenta));
        assertEquals(3, push.enviados.size());
        assertEquals(aviso.topicDe(cuenta), push.enviados.get(0));
    }
}
