package com.tuempresa.relay.avisos;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECPrivateKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El aviso que recibe el panel instalado: que salga cifrado como manda el
 * estándar, firmado con nuestra clave y solo hacia servicios de avisos de
 * verdad. Sin red: lo que se envía va a un servidor de pega en esta máquina.
 */
class WebPushTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private static byte[] d(String texto) {
        return Base64.getUrlDecoder().decode(texto);
    }

    // -------------------------------------------------------------------------
    // Cifrado
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("El cifrado da, byte a byte, el ejemplo del estándar (RFC 8291, apéndice A)")
    void ejemploDelEstandar() throws Exception {
        String claro = "When I grow up, I want to be a watermelon";
        String nuestraPublica = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
        String nuestraPrivada = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
        String delNavegador = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
        String sal = "DGv6ra1nlYgDCS1FRnbzlw";
        String auth = "BTBZMqHH6r4Tts7J_aSIgg";
        String esperado = "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIg"
                + "Dll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZ"
                + "wbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

        ECPublicKey publica = WebPush.claveDelNavegador(nuestraPublica);
        PrivateKey privada = KeyFactory.getInstance("EC").generatePrivate(
                new ECPrivateKeySpec(new BigInteger(1, d(nuestraPrivada)), publica.getParams()));

        byte[] cuerpo = WebPush.cifrar(claro.getBytes(StandardCharsets.UTF_8),
                WebPush.claveDelNavegador(delNavegador), d(auth), new KeyPair(publica, privada), d(sal));

        assertEquals(esperado, B64.encodeToString(cuerpo));
    }

    @Test
    @DisplayName("Cada aviso sale con sal y clave nuevas: dos iguales no se parecen")
    void cadaAvisoEsDistinto() throws Exception {
        KeyPair navegador = WebPush.nuevoPar();
        String p256dh = B64.encodeToString(WebPush.punto((ECPublicKey) navegador.getPublic()));
        String auth = B64.encodeToString(new byte[16]);
        byte[] claro = "hola".getBytes(StandardCharsets.UTF_8);

        byte[] uno = WebPush.cifrar(claro, p256dh, auth);
        byte[] otro = WebPush.cifrar(claro, p256dh, auth);

        // 16 de sal, 4 de tamaño, 1 + 65 de clave, y el texto con su marca y su sello.
        assertEquals(16 + 4 + 1 + 65 + claro.length + 1 + 16, uno.length);
        assertFalse(java.util.Arrays.equals(uno, otro));
    }

    @Test
    @DisplayName("Un aviso que no cabe se rechaza antes de mandarlo")
    void avisoDemasiadoLargo() {
        KeyPair navegador = WebPush.nuevoPar();
        String p256dh = B64.encodeToString(WebPush.punto((ECPublicKey) navegador.getPublic()));
        assertThrows(IllegalArgumentException.class, () ->
                WebPush.cifrar(new byte[WebPush.MAXIMO + 1], p256dh, B64.encodeToString(new byte[16])));
    }

    // -------------------------------------------------------------------------
    // Claves
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Las claves que se crean son las que pide el navegador: un punto de 65 bytes")
    void clavesNuevas() throws Exception {
        WebPush.Claves claves = WebPush.nuevasClaves();
        byte[] publica = d(claves.publica());
        assertEquals(65, publica.length);
        assertEquals(4, publica[0]);
        assertNotNull(WebPush.claveDelNavegador(claves.publica()));
        assertNotEquals(claves.publica(), WebPush.nuevasClaves().publica());
    }

    @Test
    @DisplayName("Las claves de un navegador se comprueban al registrarlo")
    void clavesDelNavegador() {
        KeyPair navegador = WebPush.nuevoPar();
        String p256dh = B64.encodeToString(WebPush.punto((ECPublicKey) navegador.getPublic()));
        String auth = B64.encodeToString(new byte[16]);

        assertTrue(WebPush.clavesValidas(p256dh, auth));
        // Algunos navegadores mandan base64 normal, con relleno.
        assertTrue(WebPush.clavesValidas(
                Base64.getEncoder().encodeToString(d(p256dh)), Base64.getEncoder().encodeToString(new byte[16])));

        assertFalse(WebPush.clavesValidas(p256dh, B64.encodeToString(new byte[8])), "auth corto");
        assertFalse(WebPush.clavesValidas("BAAA", auth), "clave corta");
        assertFalse(WebPush.clavesValidas("no es base64 !!", auth));
        assertFalse(WebPush.clavesValidas(null, auth));

        // 65 bytes con buena pinta pero que no caen en la curva.
        byte[] fuera = d(p256dh);
        fuera[64] ^= 1;
        assertFalse(WebPush.clavesValidas(B64.encodeToString(fuera), auth), "punto fuera de la curva");
    }

    // -------------------------------------------------------------------------
    // A dónde se manda
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Solo se aceptan direcciones de los servicios de avisos de los navegadores")
    void direccionesAceptadas() {
        assertNull(WebPush.motivoDeRechazo("https://fcm.googleapis.com/fcm/send/abc:def"));
        assertNull(WebPush.motivoDeRechazo("https://fcm.googleapis.com/wp/abc"));
        assertNull(WebPush.motivoDeRechazo("https://web.push.apple.com/QGuO2xyz"));
        assertNull(WebPush.motivoDeRechazo("https://updates.push.services.mozilla.com/wpush/v2/gAAA"));
        assertNull(WebPush.motivoDeRechazo("https://wns2-by3p.notify.windows.com/w/?token=BQYAAAD"));
        assertNull(WebPush.motivoDeRechazo("  https://FCM.googleapis.com/fcm/send/abc  "));
    }

    @Test
    @DisplayName("El servidor no hace peticiones a donde le digan")
    void direccionesRechazadas() {
        assertNotNull(WebPush.motivoDeRechazo(null));
        assertNotNull(WebPush.motivoDeRechazo(""));
        assertNotNull(WebPush.motivoDeRechazo("no es una url"));
        assertNotNull(WebPush.motivoDeRechazo("http://fcm.googleapis.com/fcm/send/abc"), "sin cifrar");
        assertNotNull(WebPush.motivoDeRechazo("https://localhost/x"));
        assertNotNull(WebPush.motivoDeRechazo("https://127.0.0.1:8080/api/admin/creadores"));
        assertNotNull(WebPush.motivoDeRechazo("https://169.254.169.254/latest/meta-data"));
        assertNotNull(WebPush.motivoDeRechazo("https://ejemplo.com/fcm.googleapis.com"));
        assertNotNull(WebPush.motivoDeRechazo("https://fcm.googleapis.com.ejemplo.com/x"), "dominio que solo empieza igual");
        assertNotNull(WebPush.motivoDeRechazo("https://malfcm.googleapis.com/x"), "no es un subdominio");
        assertNotNull(WebPush.motivoDeRechazo("https://fcm.googleapis.com@ejemplo.com/x"), "usuario en la URL");
        assertNotNull(WebPush.motivoDeRechazo("https://fcm.googleapis.com:8443/x"), "otro puerto");
        assertNotNull(WebPush.motivoDeRechazo("https://fcm.googleapis.com/" + "a".repeat(2000)), "demasiado larga");
    }

    @Test
    @DisplayName("El tema cabe siempre en la cabecera: 32 caracteres de base64url")
    void temaDeLaCabecera() {
        assertEquals("reporte-dQw4w9WgXcQ", WebPush.temaSeguro("reporte-dQw4w9WgXcQ"));
        assertEquals("canales", WebPush.temaSeguro("canales"));

        String largo = WebPush.temaSeguro("reporte-" + "x".repeat(60));
        assertEquals(32, largo.length());
        assertTrue(largo.matches("[A-Za-z0-9_-]+"));
        assertEquals(largo, WebPush.temaSeguro("reporte-" + "x".repeat(60)), "el mismo tema da el mismo resumen");

        assertTrue(WebPush.temaSeguro("con espacios y ñ").matches("[A-Za-z0-9_-]{32}"));
    }

    // -------------------------------------------------------------------------
    // Firma y envío
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("La firma vale para ese servicio de avisos, caduca y se verifica con la clave pública")
    void firma() throws Exception {
        WebPush.Claves claves = WebPush.nuevasClaves();
        Instant ahora = Instant.parse("2026-10-08T18:00:00Z");

        String cabecera = WebPush.cabeceraVapid(claves,
                URI.create("https://fcm.googleapis.com/fcm/send/abc"), "https://leftapp.vocesdeizquierda.com", ahora);

        assertTrue(cabecera.startsWith("vapid t="));
        assertTrue(cabecera.endsWith(", k=" + claves.publica()));

        String[] partes = cabecera.substring("vapid t=".length(), cabecera.indexOf(", k=")).split("\\.");
        assertEquals(3, partes.length);
        assertEquals("{\"typ\":\"JWT\",\"alg\":\"ES256\"}", new String(d(partes[0]), StandardCharsets.UTF_8));
        assertEquals("{\"aud\":\"https://fcm.googleapis.com\",\"exp\":" + (ahora.getEpochSecond() + 12 * 3600)
                        + ",\"sub\":\"https://leftapp.vocesdeizquierda.com\"}",
                new String(d(partes[1]), StandardCharsets.UTF_8));

        byte[] sello = d(partes[2]);
        assertEquals(64, sello.length, "r y s seguidos, no DER");

        Signature verificador = Signature.getInstance("SHA256withECDSAinP1363Format");
        verificador.initVerify(WebPush.claveDelNavegador(claves.publica()));
        verificador.update((partes[0] + "." + partes[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verificador.verify(sello));
    }

    @Test
    @DisplayName("El envío lleva lo que exige el servicio de avisos y entiende lo que contesta")
    void envio() throws Exception {
        Map<String, String> visto = new ConcurrentHashMap<>();
        HttpServer pega = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pega.createContext("/", intercambio -> {
            byte[] cuerpo = intercambio.getRequestBody().readAllBytes();
            String ruta = intercambio.getRequestURI().getPath();
            if (ruta.endsWith("/ok")) {
                intercambio.getRequestHeaders().forEach((k, v) -> visto.put(k.toLowerCase(), v.get(0)));
                visto.put("metodo", intercambio.getRequestMethod());
                visto.put("bytes", String.valueOf(cuerpo.length));
            }
            byte[] respuesta = ruta.endsWith("/ya-no") ? "{\"reason\":\"Unregistered\"}".getBytes() : new byte[0];
            int estado = ruta.endsWith("/ok") ? 201 : ruta.endsWith("/ya-no") ? 410 : 403;
            intercambio.sendResponseHeaders(estado, respuesta.length == 0 ? -1 : respuesta.length);
            if (respuesta.length > 0) intercambio.getResponseBody().write(respuesta);
            intercambio.close();
        });
        pega.start();

        try {
            String base = "http://127.0.0.1:" + pega.getAddress().getPort();
            KeyPair navegador = WebPush.nuevoPar();
            String p256dh = B64.encodeToString(WebPush.punto((ECPublicKey) navegador.getPublic()));
            String auth = B64.encodeToString(new byte[16]);
            WebPush.Claves claves = WebPush.nuevasClaves();
            byte[] carga = Aviso.dePrueba().carga();
            WebPush push = new WebPush();

            WebPush.Resultado bien = push.enviar(new WebPush.Suscripcion(base + "/fcm/send/ok", p256dh, auth),
                    claves, "https://leftapp.vocesdeizquierda.com", carga, "prueba", Duration.ofHours(24));
            assertTrue(bien.entregado());
            assertFalse(bien.caducada());
            assertEquals("POST", visto.get("metodo"));
            assertEquals("aes128gcm", visto.get("content-encoding"));
            assertEquals("86400", visto.get("ttl"));
            assertEquals("high", visto.get("urgency"));
            assertEquals("prueba", visto.get("topic"));
            assertTrue(visto.get("authorization").startsWith("vapid t="));
            assertEquals(String.valueOf(86 + carga.length + 17), visto.get("bytes"));

            WebPush.Resultado yaNo = push.enviar(new WebPush.Suscripcion(base + "/fcm/send/ya-no", p256dh, auth),
                    claves, "https://x", carga, null, Duration.ofHours(1));
            assertFalse(yaNo.entregado());
            assertTrue(yaNo.caducada(), "410: ese dispositivo hay que darlo de baja");
            assertTrue(yaNo.detalle().contains("Unregistered"));

            WebPush.Resultado rechazado = push.enviar(new WebPush.Suscripcion(base + "/fcm/send/otra", p256dh, auth),
                    claves, "https://x", carga, null, Duration.ofHours(1));
            assertEquals(403, rechazado.estado());
            assertFalse(rechazado.entregado());
            assertFalse(rechazado.caducada(), "un 403 no borra el dispositivo");

        } finally {
            pega.stop(0);
        }

        // Sin nadie escuchando: no revienta, lo cuenta.
        KeyPair navegador = WebPush.nuevoPar();
        WebPush.Resultado sinRed = new WebPush().enviar(
                new WebPush.Suscripcion("http://127.0.0.1:1/x",
                        B64.encodeToString(WebPush.punto((ECPublicKey) navegador.getPublic())),
                        B64.encodeToString(new byte[16])),
                WebPush.nuevasClaves(), "https://x", new byte[] { 1 }, null, Duration.ofHours(1));
        assertEquals(0, sinRed.estado());
        assertFalse(sinRed.entregado());
        assertFalse(sinRed.caducada());
    }
}
