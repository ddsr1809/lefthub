package com.voces.backend.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.voces.backend.config.RelayProperties;
import com.voces.backend.modelo.Usuario;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Tokens de sesion propios.
 *
 * Firmados con HMAC-SHA256 y una clave simetrica. No hace falta gestion de
 * claves publicas porque el unico que emite y el unico que verifica somos
 * nosotros mismos.
 *
 * Duran 30 dias a proposito: el publico objetivo incluye gente que abre la app
 * una vez por semana, y obligarles a volver a entrar cada hora seria un
 * despropósito.
 *
 * Que un token sea valido no basta para entrar: FiltroJwt comprueba ademas en
 * la base (seguridad/EstadoDeSesiones) que la cuenta sigue existiendo, que
 * nadie cerró sus sesiones despues de emitirlo, y que sigue siendo
 * administradora si el token dice que lo es. Asi se puede cerrar una sesión
 * robada o quitar el rol sin esperar a que caduque.
 */
@Service
public class ServicioJwt {

    private static final Logger log = LoggerFactory.getLogger(ServicioJwt.class);

    private final RelayProperties config;
    private byte[] clave;

    public ServicioJwt(RelayProperties config) {
        this.config = config;
    }

    @PostConstruct
    void comprobarClave() {
        String secreto = config.jwt().secreto();

        // HMAC-SHA256 exige al menos 256 bits. Con una clave mas corta Nimbus
        // falla al firmar, y es mejor enterarse al arrancar que en el primer
        // inicio de sesion de un usuario real.
        if (secreto == null || secreto.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("""

                    JWT_SECRETO debe tener al menos 32 caracteres.
                    Genera uno con:  openssl rand -hex 32
                    """);
        }
        this.clave = secreto.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Emite el token de sesion de un usuario.
     *
     * @param admin si esta sesion puede usar el panel. Solo cuenta si la
     *              cuenta es administradora: se emite con el rol solo al
     *              entrar con Google o Apple, nunca con el identificador del
     *              teléfono (ver AuthController.anonimo).
     */
    public String emitir(Usuario usuario, boolean admin) {
        Instant ahora = Instant.now();
        Instant expira = ahora.plus(config.jwt().diasValidez(), ChronoUnit.DAYS);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(usuario.getId().toString())
                .issuer(config.jwt().emisor())
                .issueTime(Date.from(ahora))
                .expirationTime(Date.from(expira))
                .claim("adm", admin && usuario.isEsAdmin())
                .claim("prv", usuario.getProveedor())
                .build();

        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(clave));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("No se pudo firmar el token de sesión", e);
        }
    }

    /**
     * Contenido util de un token ya validado.
     *
     * @param emitido cuándo se emitió (claim iat, con precisión de segundos),
     *                para saber si es anterior a un "cerrar sesiones".
     */
    public record Sesion(UUID usuarioId, boolean esAdmin, String proveedor, Instant emitido) {}

    /**
     * Verifica firma y caducidad. Devuelve vacio ante cualquier problema en
     * lugar de lanzar: un token invalido no es una excepcion, es simplemente
     * una peticion sin sesion.
     */
    public Optional<Sesion> verificar(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);

            if (!jwt.verify(new MACVerifier(clave))) {
                return Optional.empty();
            }

            JWTClaimsSet claims = jwt.getJWTClaimsSet();

            Date expira = claims.getExpirationTime();
            if (expira == null || expira.toInstant().isBefore(Instant.now())) {
                return Optional.empty();
            }

            if (!config.jwt().emisor().equals(claims.getIssuer())) {
                return Optional.empty();
            }

            Date emitido = claims.getIssueTime();
            if (emitido == null) {
                return Optional.empty();
            }

            return Optional.of(new Sesion(
                    UUID.fromString(claims.getSubject()),
                    Boolean.TRUE.equals(claims.getClaim("adm")),
                    String.valueOf(claims.getClaim("prv")),
                    emitido.toInstant()
            ));

        } catch (Exception e) {
            log.debug("Token rechazado: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
