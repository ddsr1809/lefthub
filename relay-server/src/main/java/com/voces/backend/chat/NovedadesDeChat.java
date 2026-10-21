package com.voces.backend.chat;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Despierta a quien espera mensajes nuevos de una conversación, y sabe quién
 * la tiene abierta ahora mismo.
 *
 * La pantalla del chat (en la app o en el panel) pregunta "¿algo después del
 * mensaje N?" y, si no hay nada, se queda esperando hasta 25 segundos. Aquí
 * espera, y al guardarse un mensaje se despierta en el acto. Así los mensajes
 * aparecen al momento sin preguntar cada segundo.
 *
 * Solo memoria: los mensajes están en la base. Si el servidor se reinicia, lo
 * peor que pasa es que una espera termina vacía y la pantalla vuelve a
 * preguntar.
 *
 * ReentrantLock y no synchronized: con hilos virtuales, esperar dentro de un
 * synchronized bloquea un hilo del sistema; con un Condition, no.
 */
@Component
public class NovedadesDeChat {

    /** Quien preguntó hace menos que esto sigue con la conversación abierta. */
    static final Duration PRESENTE = Duration.ofSeconds(40);

    public enum Lado { ADMIN, USUARIO }

    private final ReentrantLock cerrojo = new ReentrantLock();
    private final Condition hayAlgo = cerrojo.newCondition();
    /** El último mensaje de cada conversación que se ha anunciado. */
    private final Map<UUID, Long> ultimos = new ConcurrentHashMap<>();
    /** Cuándo preguntó por última vez cada lado de cada conversación. */
    private final Map<UUID, Instant> vistoAdmin = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> vistoUsuario = new ConcurrentHashMap<>();
    private final Clock reloj;

    @Autowired
    public NovedadesDeChat() {
        this(Clock.systemUTC());
    }

    NovedadesDeChat(Clock reloj) {
        this.reloj = reloj;
    }

    /** Se guardó el mensaje {@code id} en esa conversación. */
    public void anunciar(UUID usuarioId, long id) {
        ultimos.merge(usuarioId, id, Math::max);
        cerrojo.lock();
        try {
            hayAlgo.signalAll();
        } finally {
            cerrojo.unlock();
        }
    }

    /**
     * Espera a que haya algo después de {@code despues} en esa conversación,
     * como mucho {@code espera}. Devuelve si lo hubo. Si no sabe cuál es el
     * último (el servidor se acaba de reiniciar), espera al siguiente aviso.
     */
    public boolean esperar(UUID usuarioId, long despues, Duration espera) {
        long limite = System.nanoTime() + espera.toNanos();
        cerrojo.lock();
        try {
            while (true) {
                Long ultimo = ultimos.get(usuarioId);
                if (ultimo != null && ultimo > despues) return true;
                long resta = limite - System.nanoTime();
                if (resta <= 0) return false;
                hayAlgo.awaitNanos(resta);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            cerrojo.unlock();
        }
    }

    /** Ese lado tiene la conversación abierta: acaba de preguntar. */
    public void visto(UUID usuarioId, Lado lado) {
        (lado == Lado.ADMIN ? vistoAdmin : vistoUsuario).put(usuarioId, reloj.instant());
    }

    /** Ese lado la tiene abierta ahora: no hace falta mandarle un aviso. */
    public boolean presente(UUID usuarioId, Lado lado) {
        Instant visto = (lado == Lado.ADMIN ? vistoAdmin : vistoUsuario).get(usuarioId);
        return visto != null && visto.plus(PRESENTE).isAfter(reloj.instant());
    }

    /** Ese lado cerró la pantalla del chat. */
    public void salio(UUID usuarioId, Lado lado) {
        (lado == Lado.ADMIN ? vistoAdmin : vistoUsuario).remove(usuarioId);
    }

    /**
     * Se borró la conversación: que no quede nada de ella aquí. Quien
     * estuviera esperando termina su espera vacía y, al volver a preguntar,
     * se entera (el chat quitado responde 403).
     */
    public void olvidar(UUID usuarioId) {
        ultimos.remove(usuarioId);
        vistoAdmin.remove(usuarioId);
        vistoUsuario.remove(usuarioId);
    }
}
