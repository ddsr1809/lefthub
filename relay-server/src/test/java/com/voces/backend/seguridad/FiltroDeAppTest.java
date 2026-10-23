package com.voces.backend.seguridad;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.voces.backend.seguridad.ModosDeLaApp.Modo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * El filtro que deja hablar con la API solo a la app original, y la
 * comprobación del token de App Check con llaves RSA hechas aquí mismo.
 */
class FiltroDeAppTest {

    private static final Instant AHORA = Instant.parse("2026-10-23T09:00:00Z");
    private static final Clock RELOJ = Clock.fixed(AHORA, ZoneOffset.UTC);
    private static final String PROYECTO = "1025901070678";
    private static final String APP = "1:1025901070678:android:abc123";

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    /** Modos fijos, sin base de datos. */
    private static ModosDeLaApp modos(Modo firma, Modo appCheck) {
        return new ModosDeLaApp(null) {
            @Override public Modos actuales() { return new Modos(firma, appCheck); }
        };
    }

    private static VerificadorAppCheck appCheckQueDa(VerificadorAppCheck.Resultado r) {
        return new VerificadorAppCheck(PROYECTO, "", null) {
            @Override public Resultado verificar(String token) { return token == null ? Resultado.FALTA : r; }
        };
    }

    private record Salida(MockHttpServletResponse respuesta, boolean paso) {}

    private static Salida correr(FiltroDeApp filtro, MockHttpServletRequest peticion) throws Exception {
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        MockFilterChain cadena = new MockFilterChain();
        filtro.doFilter(peticion, respuesta, cadena);
        return new Salida(respuesta, cadena.getRequest() != null);
    }

    private static MockHttpServletRequest peticion(String metodo, String ruta) {
        MockHttpServletRequest p = new MockHttpServletRequest(metodo, ruta);
        p.setServletPath(ruta);
        return p;
    }

    private static final FirmaDeApp FIRMA = new FirmaDeApp("1:" + FirmaDeAppTest.llave(0));

    private static void firmar(MockHttpServletRequest p, long hora) {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        p.addHeader(FirmaDeApp.CABECERA_LLAVE, "1");
        p.addHeader(FirmaDeApp.CABECERA_HORA, String.valueOf(hora));
        p.addHeader(FirmaDeApp.CABECERA_NONCE, nonce);
        p.addHeader(FirmaDeApp.CABECERA_FIRMA,
                FIRMA.firmar((short) 1, p.getMethod(), p.getServletPath(), String.valueOf(hora), nonce));
    }

    private static FiltroDeApp filtro(Modo firma, Modo appCheck, VerificadorAppCheck verificador, CifrasDeLaApp cifras) {
        return new FiltroDeApp(FIRMA, verificador, modos(firma, appCheck), cifras, RELOJ);
    }

    // --- Firma ----------------------------------------------------------------

    @Test
    @DisplayName("Avisando, una petición sin firma pasa pero se cuenta; la respuesta trae la hora del servidor")
    void avisar() throws Exception {
        CifrasDeLaApp cifras = new CifrasDeLaApp(RELOJ);
        Salida s = correr(filtro(Modo.AVISAR, Modo.APAGADO, appCheckQueDa(VerificadorAppCheck.Resultado.OK), cifras),
                peticion("GET", "/api/creadores"));

        assertTrue(s.paso());
        assertEquals(String.valueOf(AHORA.getEpochSecond()), s.respuesta().getHeader(FiltroDeApp.CABECERA_HORA_SERVIDOR));
        assertEquals(1, cifras.ultimas().get(0).firmaFalta());
    }

    @Test
    @DisplayName("Exigiendo: sin nada, 426 (la app vieja enseña «actualiza»); con firma mala, 403")
    void exigir() throws Exception {
        FiltroDeApp f = filtro(Modo.EXIGIR, Modo.APAGADO, appCheckQueDa(VerificadorAppCheck.Resultado.OK), new CifrasDeLaApp(RELOJ));

        Salida sinNada = correr(f, peticion("GET", "/api/creadores"));
        assertFalse(sinNada.paso());
        assertEquals(426, sinNada.respuesta().getStatus());

        MockHttpServletRequest mala = peticion("GET", "/api/creadores");
        firmar(mala, AHORA.getEpochSecond());
        mala.setServletPath("/api/perfil");   // firmada para otra ruta
        Salida s = correr(f, mala);
        assertFalse(s.paso());
        assertEquals(403, s.respuesta().getStatus());
        assertEquals("app", s.respuesta().getHeader(FiltroDeApp.CABECERA_MOTIVO));
    }

    @Test
    @DisplayName("Exigiendo, la petición firmada pasa; con la hora del teléfono mal, 403 «hora»")
    void firmada() throws Exception {
        FiltroDeApp f = filtro(Modo.EXIGIR, Modo.APAGADO, appCheckQueDa(VerificadorAppCheck.Resultado.OK), new CifrasDeLaApp(RELOJ));

        MockHttpServletRequest buena = peticion("POST", "/api/auth/anonimo");
        firmar(buena, AHORA.getEpochSecond());
        assertTrue(correr(f, buena).paso());

        MockHttpServletRequest atrasada = peticion("GET", "/api/creadores");
        firmar(atrasada, AHORA.minusSeconds(3600).getEpochSecond());
        Salida s = correr(f, atrasada);
        assertEquals(403, s.respuesta().getStatus());
        assertEquals("hora", s.respuesta().getHeader(FiltroDeApp.CABECERA_MOTIVO));
    }

    @Test
    @DisplayName("El panel pasa: por sus rutas, y con sesión de administrador por cualquiera")
    void panel() throws Exception {
        FiltroDeApp f = filtro(Modo.EXIGIR, Modo.EXIGIR, appCheckQueDa(VerificadorAppCheck.Resultado.MALO), new CifrasDeLaApp(RELOJ));

        assertTrue(correr(f, peticion("GET", "/api/admin/usuarios")).paso());
        assertTrue(correr(f, peticion("POST", "/api/auth/google")).paso());
        assertTrue(correr(f, peticion("GET", "/api/servidor")).paso());
        assertTrue(correr(f, peticion("GET", "/websub")).paso(), "lo que no es /api no se mira");

        assertFalse(correr(f, peticion("GET", "/api/stickers/1/imagen")).paso());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                UUID.randomUUID(), null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        assertTrue(correr(f, peticion("GET", "/api/stickers/1/imagen")).paso());
    }

    // --- App Check ------------------------------------------------------------

    @Test
    @DisplayName("Exigiendo App Check: token malo, 403; si Google no deja leer sus llaves, se deja pasar")
    void exigirAppCheck() throws Exception {
        MockHttpServletRequest conToken = peticion("GET", "/api/creadores");
        conToken.addHeader(VerificadorAppCheck.CABECERA, "token");
        Salida malo = correr(filtro(Modo.APAGADO, Modo.EXIGIR, appCheckQueDa(VerificadorAppCheck.Resultado.MALO),
                new CifrasDeLaApp(RELOJ)), conToken);
        assertEquals(403, malo.respuesta().getStatus());

        MockHttpServletRequest otra = peticion("GET", "/api/creadores");
        otra.addHeader(VerificadorAppCheck.CABECERA, "token");
        assertTrue(correr(filtro(Modo.APAGADO, Modo.EXIGIR, appCheckQueDa(VerificadorAppCheck.Resultado.SIN_VERIFICAR),
                new CifrasDeLaApp(RELOJ)), otra).paso());
    }

    private static String token(RSAKey llave, String emisor, List<String> audiencia, String app, Date caduca) throws Exception {
        JWTClaimsSet c = new JWTClaimsSet.Builder()
                .issuer(emisor).audience(audiencia).subject(app)
                .issueTime(new Date()).expirationTime(caduca).build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(llave.getKeyID()).type(JOSEObjectType.JWT).build(), c);
        jwt.sign(new RSASSASigner(llave));
        return jwt.serialize();
    }

    @Test
    @DisplayName("Un token de App Check vale solo si es de Google, de este proyecto, de esta app y vigente")
    void tokenAppCheck() throws Exception {
        RSAKey google = new RSAKeyGenerator(2048).keyID("k1").generate();
        RSAKey otra = new RSAKeyGenerator(2048).keyID("k1").generate();
        VerificadorAppCheck v = new VerificadorAppCheck(PROYECTO, APP,
                new ImmutableJWKSet<>(new JWKSet(google.toPublicJWK())));

        String emisor = "https://firebaseappcheck.googleapis.com/" + PROYECTO;
        List<String> aud = List.of("projects/" + PROYECTO, "projects/voces-izquierda");
        Date luego = new Date(System.currentTimeMillis() + 3_600_000);

        assertEquals(VerificadorAppCheck.Resultado.OK, v.verificar(token(google, emisor, aud, APP, luego)));
        assertEquals(VerificadorAppCheck.Resultado.FALTA, v.verificar(null));
        assertEquals(VerificadorAppCheck.Resultado.MALO, v.verificar("no.es.token"));
        assertEquals(VerificadorAppCheck.Resultado.MALO, v.verificar(token(otra, emisor, aud, APP, luego)),
                "firmado con otra llave");
        assertEquals(VerificadorAppCheck.Resultado.MALO, v.verificar(token(google,
                "https://firebaseappcheck.googleapis.com/999", aud, APP, luego)), "de otro proyecto");
        assertEquals(VerificadorAppCheck.Resultado.MALO, v.verificar(token(google, emisor,
                List.of("projects/999"), APP, luego)), "para otro proyecto");
        assertEquals(VerificadorAppCheck.Resultado.MALO, v.verificar(token(google, emisor, aud,
                "1:1025901070678:android:otra", luego)), "de otra app");
        assertEquals(VerificadorAppCheck.Resultado.MALO, v.verificar(token(google, emisor, aud, APP,
                new Date(System.currentTimeMillis() - 600_000))), "caducado");
    }

    @Test
    @DisplayName("APP_CHECK_PROYECTO vacío = sin App Check; con un nombre en vez del número, no arranca")
    void configuracionAppCheck() {
        assertFalse(new VerificadorAppCheck("", "", null).configurado());
        assertThrows(IllegalStateException.class, () -> new VerificadorAppCheck("voces-izquierda", "", null));
    }
}
