package com.voces.backend.chat;

import com.voces.backend.chat.SalasDeChat.Entrada;
import com.voces.backend.chat.SalasDeChat.Evento;
import com.voces.backend.chat.SalasDeChat.Lado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Las salas del chat: quién entra, qué recoge cada lado y cuándo se borra todo. */
class SalasDeChatTest {

    private static final UUID PERSONA = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID OTRO_ADMIN = UUID.randomUUID();
    private static final Duration YA = Duration.ZERO;

    /** Un reloj que avanza solo cuando la prueba lo pide. */
    private static final class Reloj extends Clock {
        Instant ahora = Instant.parse("2026-10-20T10:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zona) { return this; }
        @Override public Instant instant() { return ahora; }
        void avanzar(Duration d) { ahora = ahora.plus(d); }
    }

    private static String llave(int semilla) {
        byte[] b = new byte[65];
        Arrays.fill(b, (byte) semilla);
        b[0] = 0x04;
        return Base64.getEncoder().encodeToString(b);
    }

    private static final String IV = Base64.getEncoder().encodeToString(new byte[12]);
    private static final String CIFRADO = Base64.getEncoder().encodeToString(new byte[40]);

    private static List<String> tipos(List<Evento> eventos) {
        return eventos.stream().map(Evento::tipo).toList();
    }

    private static HttpStatus estadoDe(Runnable r) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, r::run);
        return HttpStatus.valueOf(e.getStatusCode().value());
    }

    @Test
    @DisplayName("Cada lado recibe la llave del otro, entre quien entre primero")
    void llaves() {
        SalasDeChat salas = new SalasDeChat();

        Entrada admin = salas.entrar(PERSONA, Lado.ADMIN, ADMIN, llave(1));
        assertNull(admin.claveDelOtro(), "la persona todavía no estaba");

        Entrada persona = salas.entrar(PERSONA, Lado.USUARIO, PERSONA, llave(2));
        assertEquals(llave(1), persona.claveDelOtro());

        List<Evento> alAdmin = salas.recibir(PERSONA, Lado.ADMIN, admin.conexion(), YA);
        assertEquals(List.of("llave"), tipos(alAdmin));
        assertEquals(llave(2), alAdmin.get(0).clave());
    }

    @Test
    @DisplayName("Un mensaje llega una sola vez al otro lado y no queda copia")
    void mensajes() {
        SalasDeChat salas = new SalasDeChat();
        Entrada admin = salas.entrar(PERSONA, Lado.ADMIN, ADMIN, llave(1));
        Entrada persona = salas.entrar(PERSONA, Lado.USUARIO, PERSONA, llave(2));
        salas.recibir(PERSONA, Lado.ADMIN, admin.conexion(), YA);

        salas.enviar(PERSONA, Lado.ADMIN, admin.conexion(), IV, CIFRADO);
        salas.enviar(PERSONA, Lado.ADMIN, admin.conexion(), IV, CIFRADO);

        List<Evento> recibidos = salas.recibir(PERSONA, Lado.USUARIO, persona.conexion(), YA);
        assertEquals(List.of("mensaje", "mensaje"), tipos(recibidos));
        assertEquals(CIFRADO, recibidos.get(0).cifrado());

        assertTrue(salas.recibir(PERSONA, Lado.USUARIO, persona.conexion(), YA).isEmpty(), "ya se recogió");
        assertTrue(salas.recibir(PERSONA, Lado.ADMIN, admin.conexion(), YA).isEmpty(), "no vuelve al que lo mandó");
    }

    @Test
    @DisplayName("La consulta espera a que llegue algo y despierta en cuanto llega")
    void esperaLarga() throws Exception {
        SalasDeChat salas = new SalasDeChat();
        Entrada admin = salas.entrar(PERSONA, Lado.ADMIN, ADMIN, llave(1));
        Entrada persona = salas.entrar(PERSONA, Lado.USUARIO, PERSONA, llave(2));
        salas.recibir(PERSONA, Lado.ADMIN, admin.conexion(), YA);

        CompletableFuture<List<Evento>> espera = CompletableFuture.supplyAsync(() ->
                salas.recibir(PERSONA, Lado.ADMIN, admin.conexion(), Duration.ofSeconds(10)));
        Thread.sleep(100);
        salas.enviar(PERSONA, Lado.USUARIO, persona.conexion(), IV, CIFRADO);

        assertEquals(List.of("mensaje"), tipos(espera.get(2, TimeUnit.SECONDS)));
    }

    @Test
    @DisplayName("No se escribe a quien no está, ni se cuela un tercero")
    void sinOtroLado() {
        SalasDeChat salas = new SalasDeChat();
        Entrada admin = salas.entrar(PERSONA, Lado.ADMIN, ADMIN, llave(1));

        assertEquals(HttpStatus.CONFLICT, estadoDe(() -> salas.enviar(PERSONA, Lado.ADMIN, admin.conexion(), IV, CIFRADO)));
        assertEquals(HttpStatus.GONE, estadoDe(() -> salas.recibir(PERSONA, Lado.ADMIN, "inventada", YA)));
        assertEquals(HttpStatus.GONE, estadoDe(() -> salas.recibir(PERSONA, Lado.USUARIO, admin.conexion(), YA)),
                "la conexión del admin no sirve del lado de la persona");
        assertEquals(HttpStatus.GONE, estadoDe(() -> salas.recibir(UUID.randomUUID(), Lado.USUARIO, "x", YA)));
    }

    @Test
    @DisplayName("Si otro administrador toma la conversación, el primero queda fuera")
    void relevo() {
        SalasDeChat salas = new SalasDeChat();
        Entrada primero = salas.entrar(PERSONA, Lado.ADMIN, ADMIN, llave(1));
        Entrada persona = salas.entrar(PERSONA, Lado.USUARIO, PERSONA, llave(2));
        Entrada segundo = salas.entrar(PERSONA, Lado.ADMIN, OTRO_ADMIN, llave(3));
        assertEquals(llave(2), segundo.claveDelOtro());

        List<Evento> alPrimero = salas.recibir(PERSONA, Lado.ADMIN, primero.conexion(), YA);
        assertEquals("Otro administrador tomó esta conversación.", alPrimero.get(alPrimero.size() - 1).motivo());
        assertEquals(HttpStatus.GONE, estadoDe(() -> salas.enviar(PERSONA, Lado.ADMIN, primero.conexion(), IV, CIFRADO)));

        // La persona recibe la llave nueva y sigue hablando, ahora con el segundo.
        List<Evento> aPersona = salas.recibir(PERSONA, Lado.USUARIO, persona.conexion(), YA);
        assertEquals(llave(3), aPersona.get(aPersona.size() - 1).clave());
        assertEquals(OTRO_ADMIN, salas.estado(PERSONA).adminId());
    }

    @Test
    @DisplayName("Si la persona sale, el equipo se entera; si sale el equipo, se acaba")
    void salir() {
        SalasDeChat salas = new SalasDeChat();
        Entrada admin = salas.entrar(PERSONA, Lado.ADMIN, ADMIN, llave(1));
        Entrada persona = salas.entrar(PERSONA, Lado.USUARIO, PERSONA, llave(2));
        salas.recibir(PERSONA, Lado.ADMIN, admin.conexion(), YA);

        salas.salir(PERSONA, Lado.USUARIO, persona.conexion());
        assertEquals(List.of("salio"), tipos(salas.recibir(PERSONA, Lado.ADMIN, admin.conexion(), YA)));
        assertFalse(salas.estado(PERSONA).usuarioDentro());

        Entrada vuelve = salas.entrar(PERSONA, Lado.USUARIO, PERSONA, llave(4));
        salas.enviar(PERSONA, Lado.ADMIN, admin.conexion(), IV, CIFRADO);
        salas.salir(PERSONA, Lado.ADMIN, admin.conexion());

        List<Evento> aPersona = salas.recibir(PERSONA, Lado.USUARIO, vuelve.conexion(), YA);
        assertEquals(List.of("cerrada"), tipos(aPersona), "al cerrar, lo pendiente se tira");
        assertEquals(0, salas.cuantas());
    }

    @Test
    @DisplayName("Quitar el chat a la cuenta cierra la conversación en el acto")
    void cerrarDesdeElPanel() {
        SalasDeChat salas = new SalasDeChat();
        Entrada persona = salas.entrar(PERSONA, Lado.USUARIO, PERSONA, llave(2));

        salas.cerrar(PERSONA, "El equipo desactivó el chat.");

        assertEquals("El equipo desactivó el chat.",
                salas.recibir(PERSONA, Lado.USUARIO, persona.conexion(), YA).get(0).motivo(),
                "quien estaba esperando se entera");
        assertEquals(HttpStatus.GONE, estadoDe(() -> salas.recibir(PERSONA, Lado.USUARIO, persona.conexion(), YA)));
    }

    @Test
    @DisplayName("Quien deja de preguntar se va, y lo parado se cierra solo")
    void limpieza() {
        Reloj reloj = new Reloj();
        SalasDeChat salas = new SalasDeChat(reloj);

        // La persona cerró la app sin despedirse; el equipo sigue ahí.
        Entrada admin = salas.entrar(PERSONA, Lado.ADMIN, ADMIN, llave(1));
        salas.entrar(PERSONA, Lado.USUARIO, PERSONA, llave(2));
        salas.recibir(PERSONA, Lado.ADMIN, admin.conexion(), YA);
        reloj.avanzar(Duration.ofSeconds(90));
        salas.recibir(PERSONA, Lado.ADMIN, admin.conexion(), YA);
        reloj.avanzar(Duration.ofSeconds(60));
        salas.limpiar();
        assertEquals(List.of("salio"), tipos(salas.recibir(PERSONA, Lado.ADMIN, admin.conexion(), YA)));

        // Nadie escribe en media hora: se cierra aunque el panel siga preguntando.
        for (int i = 0; i < 31; i++) {
            reloj.avanzar(Duration.ofMinutes(1));
            salas.recibir(PERSONA, Lado.ADMIN, admin.conexion(), YA);
        }
        salas.limpiar();
        assertEquals(0, salas.cuantas());
    }

    @Test
    @DisplayName("Solo se aceptan llaves P-256 y mensajes con su IV y su etiqueta")
    void formato() {
        SalasDeChat salas = new SalasDeChat();
        for (String mala : new String[] { null, "", "no es base64!", Base64.getEncoder().encodeToString(new byte[65]),
                Base64.getEncoder().encodeToString(new byte[33]) }) {
            assertEquals(HttpStatus.BAD_REQUEST, estadoDe(() -> salas.entrar(PERSONA, Lado.USUARIO, PERSONA, mala)), String.valueOf(mala));
        }

        Entrada admin = salas.entrar(PERSONA, Lado.ADMIN, ADMIN, llave(1));
        salas.entrar(PERSONA, Lado.USUARIO, PERSONA, llave(2));
        String c = admin.conexion();

        assertEquals(HttpStatus.BAD_REQUEST, estadoDe(() -> salas.enviar(PERSONA, Lado.ADMIN, c,
                Base64.getEncoder().encodeToString(new byte[16]), CIFRADO)));
        assertEquals(HttpStatus.BAD_REQUEST, estadoDe(() -> salas.enviar(PERSONA, Lado.ADMIN, c, IV,
                Base64.getEncoder().encodeToString(new byte[16]))));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, estadoDe(() -> salas.enviar(PERSONA, Lado.ADMIN, c, IV,
                "A".repeat(SalasDeChat.MAX_CIFRADO + 4))));
    }

    @Test
    @DisplayName("Si nadie los lee, los mensajes no se acumulan sin fin")
    void tope() {
        SalasDeChat salas = new SalasDeChat();
        Entrada admin = salas.entrar(PERSONA, Lado.ADMIN, ADMIN, llave(1));
        salas.entrar(PERSONA, Lado.USUARIO, PERSONA, llave(2));

        for (int i = 0; i < SalasDeChat.MAX_PENDIENTES; i++) {
            salas.enviar(PERSONA, Lado.ADMIN, admin.conexion(), IV, CIFRADO);
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                estadoDe(() -> salas.enviar(PERSONA, Lado.ADMIN, admin.conexion(), IV, CIFRADO)));
    }
}
