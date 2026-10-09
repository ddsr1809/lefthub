package com.voces.backend.acceso;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lo que decide qué se guarda de cada conexión: de qué IP viene, a qué país y
 * compañía corresponde, y si parece automática. Todo es lógica pura, sin base
 * de datos ni red.
 */
class AccesoTest {

    // -------------------------------------------------------------------------
    // IP del cliente detrás de Apache
    // -------------------------------------------------------------------------

    private static MockHttpServletRequest peticion(String directa, String reenviada) {
        MockHttpServletRequest p = new MockHttpServletRequest();
        p.setRemoteAddr(directa);
        if (reenviada != null) p.addHeader("X-Forwarded-For", reenviada);
        return p;
    }

    @Test
    @DisplayName("Detrás del proxy, la IP es la que añadió Apache")
    void ipDetrasDelProxy() {
        assertEquals("189.203.10.5",
                RegistroDeAcceso.ipDelCliente(peticion("172.18.0.1", "189.203.10.5")));
    }

    @Test
    @DisplayName("Una cabecera falsificada por el cliente no gana: vale el último valor")
    void cabeceraFalsificada() {
        assertEquals("189.203.10.5",
                RegistroDeAcceso.ipDelCliente(peticion("172.18.0.1", "8.8.8.8, 189.203.10.5")));
    }

    @Test
    @DisplayName("Si la conexión no viene del proxy, la cabecera se ignora")
    void sinProxyNoSeConfiaEnLaCabecera() {
        assertEquals("201.141.20.3",
                RegistroDeAcceso.ipDelCliente(peticion("201.141.20.3", "8.8.8.8")));
    }

    @Test
    @DisplayName("Una cabecera con basura cae a la IP de la conexión")
    void cabeceraConBasura() {
        assertEquals("127.0.0.1",
                RegistroDeAcceso.ipDelCliente(peticion("127.0.0.1", "<script>, desconocido")));
        assertEquals("127.0.0.1", RegistroDeAcceso.ipDelCliente(peticion("127.0.0.1", null)));
    }

    @Test
    @DisplayName("Redes privadas: Docker, bucle local y LAN sí; el resto no")
    void redesPrivadas() {
        for (String ip : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.1",
                "192.168.1.1", "::1", "fd00::1", "::ffff:172.18.0.1")) {
            assertTrue(RegistroDeAcceso.esRedPrivada(ip), ip);
        }
        for (String ip : List.of("172.32.0.1", "172.15.0.1", "8.8.8.8", "2806:2f0::1", "fcuk")) {
            assertFalse(RegistroDeAcceso.esRedPrivada(ip), ip);
        }
    }

    // -------------------------------------------------------------------------
    // Lectura de direcciones
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("IPv4 se convierte en su número; lo que no es IP se rechaza")
    void direccionesV4() {
        assertEquals(new GeoIp.Direccion(false, 0x08080808L), GeoIp.Direccion.de("8.8.8.8"));
        assertEquals(new GeoIp.Direccion(false, 0xFFFFFFFFL), GeoIp.Direccion.de("255.255.255.255"));
        assertEquals(new GeoIp.Direccion(false, 0x08080404L), GeoIp.Direccion.de("::ffff:8.8.4.4"));

        for (String mala : List.of("", "256.1.1.1", "1.2.3", "1.2.3.4.5", "a.b.c.d", "1..2.3",
                "localhost", "8.8.8.8, 1.1.1.1", "1.2.3.4:80")) {
            assertNull(GeoIp.Direccion.de(mala), mala);
        }
        assertNull(GeoIp.Direccion.de(null));
    }

    @Test
    @DisplayName("De IPv6 se guarda el prefijo de red, con o sin abreviar")
    void direccionesV6() {
        GeoIp.Direccion esperada = new GeoIp.Direccion(true, 0x280602F090000001L);
        assertEquals(esperada, GeoIp.Direccion.de("2806:2f0:9000:1::1"));
        assertEquals(esperada, GeoIp.Direccion.de("2806:02F0:9000:0001:0000:0000:0000:0001"));
        assertEquals(esperada, GeoIp.Direccion.de("2806:2f0:9000:1:aaaa:bbbb:cccc:dddd"));

        assertEquals(new GeoIp.Direccion(true, 0L), GeoIp.Direccion.de("::1"));
        assertEquals(new GeoIp.Direccion(true, 0x2001000000000000L), GeoIp.Direccion.de("2001::"));
        // Los 64 bits altos con el primero encendido: comprueba el orden sin signo.
        assertEquals(new GeoIp.Direccion(true, 0xFE80000000000000L), GeoIp.Direccion.de("fe80::1%eth0"));

        for (String mala : List.of(":", "1:2:3", "1::2::3", "12345::1", "g::1", "1:2:3:4:5:6:7:8:9")) {
            assertNull(GeoIp.Direccion.de(mala), mala);
        }
    }

    // -------------------------------------------------------------------------
    // Tablas de DB-IP
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("El CSV admite comillas, comas dentro del nombre y comillas dobles")
    void csv() {
        assertEquals(List.of("1.0.0.0", "1.0.0.255", "AU"), GeoIp.partir("1.0.0.0,1.0.0.255,AU"));
        assertEquals(List.of("1.0.0.0", "1.0.0.255", "13335", "Cloudflare, Inc."),
                GeoIp.partir("1.0.0.0,1.0.0.255,13335,\"Cloudflare, Inc.\""));
        assertEquals(List.of("a", "dijo \"hola\"", ""), GeoIp.partir("a,\"dijo \"\"hola\"\"\","));
    }

    private static GeoIp.Tabla tabla(String csv, boolean esAsn) throws IOException {
        return GeoIp.leer(new BufferedReader(new StringReader(csv)), esAsn);
    }

    private static String texto(GeoIp.Tabla t, String ip) {
        int fila = t.buscar(GeoIp.Direccion.de(ip));
        return fila < 0 ? null : t.textos[fila];
    }

    @Test
    @DisplayName("Cada IP cae en su rango; los huecos y los extremos no se confunden")
    void busquedaPorRango() throws IOException {
        GeoIp.Tabla paises = tabla("""
                1.0.0.0,1.0.0.255,AU
                1.0.1.0,1.0.3.255,CN
                189.203.0.0,189.203.255.255,MX
                esto no es una fila
                2001::,2001:0:ffff:ffff:ffff:ffff:ffff:ffff,US
                2806:2f0::,2806:2f0:ffff:ffff:ffff:ffff:ffff:ffff,MX
                fe80::,febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff,ZZ
                """, false);

        assertEquals(6, paises.filas());
        assertEquals("AU", texto(paises, "1.0.0.0"));
        assertEquals("AU", texto(paises, "1.0.0.255"));
        assertEquals("CN", texto(paises, "1.0.1.0"));
        assertEquals("CN", texto(paises, "1.0.3.255"));
        assertEquals("MX", texto(paises, "189.203.10.5"));
        assertEquals("MX", texto(paises, "2806:2f0:9000:1::1"));
        assertEquals("ZZ", texto(paises, "fe80::1"));

        assertNull(texto(paises, "0.255.255.255"), "antes del primer rango");
        assertNull(texto(paises, "1.0.4.0"), "justo después de un rango");
        assertNull(texto(paises, "200.1.1.1"), "después del último rango IPv4");
        assertNull(texto(paises, "2806:2f1::1"), "hueco entre rangos IPv6");
    }

    @Test
    @DisplayName("Un archivo desordenado se ordena al cargarlo")
    void archivoDesordenado() throws IOException {
        GeoIp.Tabla redes = tabla("""
                189.203.0.0,189.203.255.255,22884,TOTAL PLAY TELECOMUNICACIONES SA DE CV
                1.0.0.0,1.0.0.255,13335,"Cloudflare, Inc."
                8.8.8.0,8.8.8.255,15169,Google LLC
                """, true);

        assertEquals("Cloudflare, Inc.", texto(redes, "1.0.0.7"));
        assertEquals("Google LLC", texto(redes, "8.8.8.8"));
        int fila = redes.buscar(GeoIp.Direccion.de("189.203.10.5"));
        assertEquals(22884L, redes.numeros[fila]);
    }

    @Test
    @DisplayName("Sin tablas cargadas no hay respuesta, y tampoco hay error")
    void sinTablas() {
        GeoIp geo = new GeoIp(false, "http://127.0.0.1:1");
        geo.actualizar();
        assertFalse(geo.estaCargado());
        assertNull(geo.buscar("8.8.8.8"));
        assertNull(geo.buscar(null));
    }

    // -------------------------------------------------------------------------
    // Posibles bots
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Las apps y el navegador del panel no se marcan")
    void trafficoNormal() {
        String telcel = "RadioMovil Dipsa, S.A. de C.V.";
        assertNull(DetectorDeBots.evaluar("okhttp/4.12.0", telcel));
        assertNull(DetectorDeBots.evaluar("Relay/1 CFNetwork/1568.200.51 Darwin/24.1.0", "Uninet S.A. de C.V."));
        assertNull(DetectorDeBots.evaluar(
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
                "TOTAL PLAY TELECOMUNICACIONES SA DE CV"));
        assertNull(DetectorDeBots.evaluar("okhttp/4.12.0", null), "sin tablas cargadas no se marca a nadie");
        assertNull(DetectorDeBots.evaluar("okhttp/4.12.0", "Google Fiber Inc."));
    }

    @Test
    @DisplayName("Sin User-Agent, con una herramienta o desde un centro de datos, sí")
    void traficoSospechoso() {
        assertNotNull(DetectorDeBots.evaluar(null, "Uninet S.A. de C.V."));
        assertNotNull(DetectorDeBots.evaluar("  ", "Uninet S.A. de C.V."));
        assertNotNull(DetectorDeBots.evaluar("curl/8.5.0", "Uninet S.A. de C.V."));
        assertNotNull(DetectorDeBots.evaluar("python-requests/2.32.3", "Uninet S.A. de C.V."));
        assertNotNull(DetectorDeBots.evaluar("Mozilla/5.0 (compatible; Googlebot/2.1)", "Uninet S.A. de C.V."));
        assertNotNull(DetectorDeBots.evaluar("okhttp/4.12.0", "Amazon.com, Inc."));
        assertNotNull(DetectorDeBots.evaluar("okhttp/4.12.0", "DigitalOcean, LLC"));
        assertNotNull(DetectorDeBots.evaluar("okhttp/4.12.0", "Google LLC"));
    }
}
