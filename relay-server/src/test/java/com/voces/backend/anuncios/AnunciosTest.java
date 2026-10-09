package com.voces.backend.anuncios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voces.backend.anuncios.EstadoDeCompra.Pago;
import com.voces.backend.modelo.Usuario;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Las decisiones de las que depende que alguien deje de ver anuncios: qué es
 * un folio y qué no, cuántos equivocados se pueden mandar seguidos, cuándo
 * una respuesta de Google Play es una compra pagada y qué queda apuntado en
 * la cuenta. Lógica pura, sin red ni base de datos.
 */
class AnunciosTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String texto) throws Exception {
        return JSON.readTree(texto);
    }

    // -------------------------------------------------------------------------
    // La forma de un folio
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Un folio nuevo tiene diez caracteres y ninguno de los que se confunden al leer")
    void folioNuevo() {
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String folio = Folios.nuevo();
            assertEquals(Folios.LARGO, folio.length());
            assertTrue(folio.chars().allMatch(c -> Folios.ALFABETO.indexOf(c) >= 0), folio);
            assertFalse(folio.matches(".*[01OIL].*"), folio);
            // Lo que se crea tiene que poder canjearse tal cual.
            assertEquals(folio, Folios.normalizar(folio));
            vistos.add(folio);
        }
        assertEquals(500, vistos.size(), "no deberían repetirse");
    }

    @Test
    @DisplayName("Se acepta como lo teclee la persona: con guion, con espacios o en minúsculas")
    void folioTecleado() {
        assertEquals("ABCDEFGHJK", Folios.normalizar("ABCDE-FGHJK"));
        assertEquals("ABCDEFGHJK", Folios.normalizar("abcde-fghjk"));
        assertEquals("ABCDEFGHJK", Folios.normalizar("  abcde fghjk \n"));
        assertEquals("ABCDEFGHJK", Folios.normalizar("ABCDEFGHJK"));
        assertEquals("ABCDEFGHJK", Folios.normalizar("AB-CD-EF-GH-JK"));
    }

    @Test
    @DisplayName("Lo que no puede ser un folio ni se le pregunta a la base de datos")
    void folioImposible() {
        assertNull(Folios.normalizar(null));
        assertNull(Folios.normalizar(""));
        assertNull(Folios.normalizar("ABCDE"), "corto");
        assertNull(Folios.normalizar("ABCDE-FGHJKM"), "largo");
        assertNull(Folios.normalizar("ABCDE-FGHJ0"), "el cero no está en el alfabeto");
        assertNull(Folios.normalizar("ABCDE-FGHJO"), "la O tampoco");
        assertNull(Folios.normalizar("ABCDE-FGHJ%"));
        assertNull(Folios.normalizar("ABCDE-FGHJÑ"));
        assertNull(Folios.normalizar("' or 1=1 --"));
    }

    @Test
    @DisplayName("Se pinta en dos grupos de cinco")
    void folioPintado() {
        assertEquals("ABCDE-FGHJK", Folios.pintar("ABCDEFGHJK"));
        assertEquals("ABCDEFGHJK", Folios.normalizar(Folios.pintar("ABCDEFGHJK")));
        // Algo que no es un folio se deja como está.
        assertEquals("ABC", Folios.pintar("ABC"));
        assertNull(Folios.pintar(null));
    }

    // -------------------------------------------------------------------------
    // Cuántos folios equivocados seguidos
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Cinco folios equivocados de margen; al sexto hay que esperar")
    void margenDeIntentos() {
        Intentos intentos = new Intentos();
        UUID cuenta = UUID.randomUUID();
        Instant ahora = Instant.parse("2026-10-08T12:00:00Z");

        for (int i = 0; i < Intentos.MARGEN; i++) {
            assertTrue(intentos.puede(cuenta, ahora), "el intento " + (i + 1) + " debería pasar");
            intentos.fallo(cuenta, ahora);
        }
        assertFalse(intentos.puede(cuenta, ahora));

        // Pasado el tiempo de una recarga, vuelve a haber un intento. Uno.
        Instant despues = ahora.plus(Intentos.RECARGA);
        assertTrue(intentos.puede(cuenta, despues));
        intentos.fallo(cuenta, despues);
        assertFalse(intentos.puede(cuenta, despues));
    }

    @Test
    @DisplayName("Los fallos de una cuenta no le quitan margen a otra")
    void intentosPorCuenta() {
        Intentos intentos = new Intentos();
        UUID una = UUID.randomUUID();
        UUID otra = UUID.randomUUID();
        Instant ahora = Instant.parse("2026-10-08T12:00:00Z");

        for (int i = 0; i < Intentos.MARGEN; i++) intentos.fallo(una, ahora);

        assertFalse(intentos.puede(una, ahora));
        assertTrue(intentos.puede(otra, ahora));
    }

    @Test
    @DisplayName("El margen se recupera entero con el tiempo, pero no pasa de cinco")
    void margenRecuperado() {
        Intentos intentos = new Intentos();
        UUID cuenta = UUID.randomUUID();
        Instant ahora = Instant.parse("2026-10-08T12:00:00Z");

        for (int i = 0; i < Intentos.MARGEN; i++) intentos.fallo(cuenta, ahora);

        Instant alDiaSiguiente = ahora.plus(Duration.ofDays(1));
        for (int i = 0; i < Intentos.MARGEN; i++) {
            assertTrue(intentos.puede(cuenta, alDiaSiguiente));
            intentos.fallo(cuenta, alDiaSiguiente);
        }
        assertFalse(intentos.puede(cuenta, alDiaSiguiente));
    }

    // -------------------------------------------------------------------------
    // Qué contesta Google Play
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Una compra pagada y sin reconocer, tal como la manda Google")
    void compraPagada() throws Exception {
        EstadoDeCompra estado = EstadoDeCompra.de(json("""
                {
                  "kind": "androidpublisher#productPurchase",
                  "purchaseTimeMillis": "1791460800000",
                  "purchaseState": 0,
                  "consumptionState": 0,
                  "orderId": "GPA.3345-1234-5678-90123",
                  "acknowledgementState": 0,
                  "regionCode": "MX"
                }
                """));

        assertEquals(Pago.PAGADO, estado.pago());
        assertFalse(estado.reconocida(), "todavía hay que reconocerla");
        assertEquals("GPA.3345-1234-5678-90123", estado.orden());
        assertEquals(Instant.ofEpochMilli(1791460800000L), estado.compradoEn());
    }

    @Test
    @DisplayName("Una compra ya reconocida no se vuelve a reconocer")
    void compraReconocida() throws Exception {
        EstadoDeCompra estado = EstadoDeCompra.de(json("""
                {"purchaseTimeMillis": 1791460800000, "purchaseState": 0,
                 "orderId": "GPA.1", "acknowledgementState": 1}
                """));

        assertEquals(Pago.PAGADO, estado.pago());
        assertTrue(estado.reconocida());
    }

    @Test
    @DisplayName("Cancelada, devuelta o pendiente de pago: no quita los anuncios")
    void compraSinPagar() throws Exception {
        assertEquals(Pago.CANCELADO, EstadoDeCompra.de(json(
                "{\"purchaseTimeMillis\": \"1\", \"orderId\": \"GPA.1\", \"purchaseState\": 1}")).pago());
        assertEquals(Pago.PENDIENTE, EstadoDeCompra.de(json(
                "{\"purchaseTimeMillis\": \"1\", \"orderId\": \"GPA.1\", \"purchaseState\": 2}")).pago());
        // Un estado que Google invente mañana no es una compra pagada.
        assertEquals(Pago.CANCELADO, EstadoDeCompra.de(json(
                "{\"purchaseTimeMillis\": \"1\", \"orderId\": \"GPA.1\", \"purchaseState\": 7}")).pago());
        assertEquals(Pago.CANCELADO, EstadoDeCompra.de(json(
                "{\"purchaseTimeMillis\": \"1\", \"orderId\": \"GPA.1\", \"purchaseState\": \"PENDING\"}")).pago());
    }

    @Test
    @DisplayName("Una respuesta vacía o que no es una compra no da nada por pagado")
    void respuestaQueNoEsUnaCompra() throws Exception {
        assertEquals(Pago.CANCELADO, EstadoDeCompra.de(null).pago());
        assertEquals(Pago.CANCELADO, EstadoDeCompra.de(json("{}")).pago());
        assertEquals(Pago.CANCELADO, EstadoDeCompra.de(json("[]")).pago());
        assertEquals(Pago.CANCELADO, EstadoDeCompra.de(json("\"ok\"")).pago());
        assertEquals(Pago.CANCELADO, EstadoDeCompra.de(json("{\"purchaseState\": 0}")).pago());
        assertEquals(Pago.CANCELADO, EstadoDeCompra.de(json(
                "{\"error\": {\"code\": 404, \"message\": \"not found\"}}")).pago());
    }

    @Test
    @DisplayName("Una compra de promoción llega sin número de pedido y vale igual")
    void compraSinPedido() throws Exception {
        EstadoDeCompra estado = EstadoDeCompra.de(json(
                "{\"purchaseTimeMillis\": \"1791460800000\", \"purchaseType\": 1}"));

        assertEquals(Pago.PAGADO, estado.pago());
        assertNull(estado.orden());
        assertNotNull(estado.compradoEn());
    }

    // -------------------------------------------------------------------------
    // Lo que queda apuntado en la cuenta
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Quitar los anuncios apunta el motivo y la fecha; devolverlos, los borra")
    void marcaEnLaCuenta() {
        Usuario usuario = new Usuario();
        assertFalse(usuario.isSinAnuncios());
        assertNull(usuario.getSinAnunciosOrigen());

        usuario.quitarAnuncios(Usuario.POR_FOLIO);
        assertTrue(usuario.isSinAnuncios());
        assertEquals(Usuario.POR_FOLIO, usuario.getSinAnunciosOrigen());
        assertNotNull(usuario.getSinAnunciosDesde());

        usuario.devolverAnuncios();
        assertFalse(usuario.isSinAnuncios());
        assertNull(usuario.getSinAnunciosOrigen());
        assertNull(usuario.getSinAnunciosDesde());
    }

    @Test
    @DisplayName("Quien ya no ve anuncios conserva el motivo y la fecha de la primera vez")
    void laPrimeraVezManda() {
        Instant compra = Instant.parse("2026-10-01T10:00:00Z");
        Usuario usuario = new Usuario();

        usuario.quitarAnuncios(Usuario.POR_COMPRA, compra);
        usuario.quitarAnuncios(Usuario.POR_PANEL);

        assertEquals(Usuario.POR_COMPRA, usuario.getSinAnunciosOrigen());
        assertEquals(compra, usuario.getSinAnunciosDesde());
    }

    @Test
    @DisplayName("Una compra sin fecha se apunta con la de hoy")
    void sinFecha() {
        Usuario usuario = new Usuario();
        usuario.quitarAnuncios(Usuario.POR_COMPRA, null);

        assertTrue(usuario.isSinAnuncios());
        assertNotNull(usuario.getSinAnunciosDesde());
    }
}
