package com.voces.backend.chat;

import com.voces.backend.chat.NovedadesDeChat.Lado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** La espera larga del chat y quién tiene la conversación abierta. */
class NovedadesDeChatTest {

    @Test
    @DisplayName("Quien espera despierta en cuanto llega un mensaje de su conversación")
    void despierta() throws Exception {
        NovedadesDeChat n = new NovedadesDeChat();
        UUID ana = UUID.randomUUID();
        n.anunciar(ana, 5);

        assertTrue(n.esperar(ana, 4, Duration.ZERO), "ya había uno después del 4");

        CompletableFuture<Boolean> espera = CompletableFuture.supplyAsync(() -> n.esperar(ana, 5, Duration.ofSeconds(10)));
        Thread.sleep(100);
        n.anunciar(UUID.randomUUID(), 99);
        Thread.sleep(100);
        assertFalse(espera.isDone(), "un mensaje de otra conversación no la despierta");
        n.anunciar(ana, 6);
        assertTrue(espera.get(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Sin mensajes, la espera termina sola")
    void terminaSola() {
        NovedadesDeChat n = new NovedadesDeChat();
        long antes = System.nanoTime();
        assertFalse(n.esperar(UUID.randomUUID(), 0, Duration.ofMillis(200)));
        assertTrue(System.nanoTime() - antes >= Duration.ofMillis(190).toNanos());
    }

    @Test
    @DisplayName("Abierta mientras pregunta; al salir o al pasar el rato, ya no")
    void presencia() {
        AvisoDeChatTest.Reloj reloj = new AvisoDeChatTest.Reloj();
        NovedadesDeChat n = new NovedadesDeChat(reloj);
        UUID ana = UUID.randomUUID();

        assertFalse(n.presente(ana, Lado.USUARIO));
        n.visto(ana, Lado.USUARIO);
        assertTrue(n.presente(ana, Lado.USUARIO));
        assertFalse(n.presente(ana, Lado.ADMIN), "cada lado aparte");

        reloj.ahora = reloj.ahora.plus(NovedadesDeChat.PRESENTE).plusSeconds(1);
        assertFalse(n.presente(ana, Lado.USUARIO));

        n.visto(ana, Lado.ADMIN);
        n.salio(ana, Lado.ADMIN);
        assertFalse(n.presente(ana, Lado.ADMIN));
    }
}
