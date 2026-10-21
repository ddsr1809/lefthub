package com.voces.backend.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** El cifrado de los mensajes guardados: que se lean solo donde deben. */
class CifradorDeChatTest {

    private static final UUID ANA = UUID.randomUUID();
    private static final UUID LUIS = UUID.randomUUID();

    private static String llave() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Base64.getEncoder().encodeToString(b);
    }

    @Test
    @DisplayName("Lo cifrado se lee igual, y en la base no queda el texto")
    void idaYVuelta() {
        CifradorDeChat c = new CifradorDeChat("1:" + llave());
        String texto = "Hola, ¿cómo te va con la app? ñandú 👋";
        CifradorDeChat.Cifrado x = c.cifrar(ANA, true, texto);

        assertEquals(1, x.llave());
        assertEquals(12, x.iv().length);
        assertFalse(new String(x.datos(), StandardCharsets.ISO_8859_1).contains("Hola"));
        assertEquals(texto, c.descifrar(ANA, true, x.llave(), x.iv(), x.datos()));

        CifradorDeChat.Cifrado y = c.cifrar(ANA, true, texto);
        assertNotEquals(Base64.getEncoder().encodeToString(x.iv()), Base64.getEncoder().encodeToString(y.iv()),
                "cada mensaje con su IV");
    }

    @Test
    @DisplayName("Un mensaje no se puede pasar a otra conversación ni cambiar de autor")
    void atadoASuConversacion() {
        CifradorDeChat c = new CifradorDeChat("1:" + llave());
        CifradorDeChat.Cifrado x = c.cifrar(ANA, false, "de Ana");

        assertNull(c.descifrar(LUIS, false, x.llave(), x.iv(), x.datos()), "en otra conversación");
        assertNull(c.descifrar(ANA, true, x.llave(), x.iv(), x.datos()), "como si fuera del equipo");

        byte[] alterado = x.datos().clone();
        alterado[0] ^= 1;
        assertNull(c.descifrar(ANA, false, x.llave(), x.iv(), alterado), "alterado en la base");
    }

    @Test
    @DisplayName("Con una llave nueva se cifra con ella y lo viejo se sigue leyendo")
    void cambiarDeLlave() {
        String vieja = llave();
        CifradorDeChat antes = new CifradorDeChat("1:" + vieja);
        CifradorDeChat.Cifrado x = antes.cifrar(ANA, true, "viejo");

        CifradorDeChat despues = new CifradorDeChat(" 1:" + vieja + " , 2:" + llave() + " ");
        assertEquals("viejo", despues.descifrar(ANA, true, x.llave(), x.iv(), x.datos()));
        assertEquals(2, despues.cifrar(ANA, true, "nuevo").llave());

        CifradorDeChat sinLaVieja = new CifradorDeChat("2:" + llave());
        assertNull(sinLaVieja.descifrar(ANA, true, x.llave(), x.iv(), x.datos()));
    }

    @Test
    @DisplayName("Sin llaves el chat está apagado; con una mal escrita, el servidor no arranca")
    void configuracion() {
        CifradorDeChat sin = new CifradorDeChat("");
        assertFalse(sin.listo());
        ResponseStatusException e = assertThrows(ResponseStatusException.class, () -> sin.cifrar(ANA, true, "x"));
        assertEquals(503, e.getStatusCode().value());

        for (String mala : new String[] { "abc", "1:no-es-base64!", "1:" + Base64.getEncoder().encodeToString(new byte[16]),
                "0:" + llave(), "x:" + llave(), "1:" + llave() + ",1:" + llave() }) {
            assertThrows(IllegalStateException.class, () -> new CifradorDeChat(mala), mala);
        }
    }
}
