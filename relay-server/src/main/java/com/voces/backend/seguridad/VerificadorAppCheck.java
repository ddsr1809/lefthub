package com.voces.backend.seguridad;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.voces.backend.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Comprueba el token de Firebase App Check que manda la app en la cabecera
 * X-Firebase-AppCheck.
 *
 * Ese token lo emite Firebase solo después de que Play Integrity confirma que
 * quien lo pide es la app original (firmada con la llave de Google Play),
 * instalada desde Play, en un teléfono Android de verdad. Es lo que una llave
 * dentro del APK no puede probar: quien la saque y la use desde un script no
 * consigue este token.
 *
 * Es un JWT firmado por Google (RS256). Se verifica aquí con las llaves
 * públicas de App Check, sin llamar a Google en cada petición: el emisor tiene
 * que ser este proyecto de Firebase, la audiencia también, y la app (sub), una
 * de APP_CHECK_APPS si se dio la lista.
 *
 * Si las llaves públicas de Google no se pueden bajar, el resultado es
 * SIN_VERIFICAR y la petición no se rechaza por eso: un tropiezo de Google no
 * debe dejar fuera a toda la app.
 */
@Service
public class VerificadorAppCheck {

    private static final Logger log = LoggerFactory.getLogger(VerificadorAppCheck.class);

    public static final String CABECERA = "X-Firebase-AppCheck";
    static final String JWKS = "https://firebaseappcheck.googleapis.com/v1/jwks";

    public enum Resultado {
        /** Token válido de una de nuestras apps. */
        OK,
        /** Sin token. */
        FALTA,
        /** Token falso, caducado, de otro proyecto o de otra app. */
        MALO,
        /** No se pudieron bajar las llaves de Google: no se sabe. */
        SIN_VERIFICAR
    }

    private final String proyecto;
    private final Set<String> apps;
    private volatile ConfigurableJWTProcessor<SecurityContext> procesador;
    private final JWKSource<SecurityContext> fuente;

    @Autowired
    public VerificadorAppCheck(RelayProperties config) {
        this(config.llaveDeApp().appCheckProyecto(), config.llaveDeApp().appCheckApps(), null);
    }

    /** @param fuente las llaves públicas; null = las de Google. Para las pruebas. */
    VerificadorAppCheck(String proyecto, String apps, JWKSource<SecurityContext> fuente) {
        this.proyecto = proyecto == null ? "" : proyecto.trim();
        this.apps = apps == null ? Set.of() : Arrays.stream(apps.split(","))
                .map(String::trim).filter(a -> !a.isEmpty()).collect(Collectors.toUnmodifiableSet());
        this.fuente = fuente;
        if (!this.proyecto.isEmpty() && !this.proyecto.matches("\\d+")) {
            throw new IllegalStateException("APP_CHECK_PROYECTO es el NÚMERO del proyecto de Firebase "
                    + "(project_number en google-services.json), no su nombre.");
        }
        if (this.proyecto.isEmpty()) {
            log.warn("Sin APP_CHECK_PROYECTO: no se comprueba App Check");
        } else {
            log.info("App Check: proyecto {}, {}", this.proyecto,
                    this.apps.isEmpty() ? "cualquier app del proyecto" : this.apps.size() + " app(s) permitida(s)");
        }
    }

    public boolean configurado() {
        return !proyecto.isEmpty();
    }

    /**
     * Las apps de Firebase del proyecto, como se llaman en el panel. Son los
     * mobilesdk_app_id de google-services.json; una que no esté aquí sale con
     * el final de su identificador.
     */
    static final Map<String, String> NOMBRES_DE_APPS = Map.of(
            "1:1025901070678:android:bdaed1e83362e0074b6ef7", "Producción",
            "1:1025901070678:android:1b22a606c86d110b4b6ef7", "Pruebas",
            "1:1025901070678:android:77a8355ef485c9794b6ef7", "Developer");

    public static String nombreDeApp(String id) {
        if (id == null || id.isBlank()) return null;
        String nombre = NOMBRES_DE_APPS.get(id);
        return nombre != null ? nombre : "Otra app (…" + id.substring(Math.max(0, id.length() - 6)) + ")";
    }

    /**
     * La app (el `sub`) de un token que verificar() ya dio por bueno, sin
     * volver a comprobar su firma. Solo se llama con tokens buenos: de uno
     * malo, el `sub` lo podría haber escrito cualquiera.
     */
    public static String appDelToken(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            String sub = SignedJWT.parse(token.trim()).getJWTClaimsSet().getSubject();
            return sub == null || sub.isBlank() || sub.length() > 120 ? null : sub;
        } catch (Exception e) {
            return null;
        }
    }

    public Resultado verificar(String token) {
        if (token == null || token.isBlank()) return Resultado.FALTA;
        if (!configurado()) return Resultado.SIN_VERIFICAR;
        try {
            JWTClaimsSet claims = procesador().process(token.trim(), null);
            if (!apps.isEmpty() && !apps.contains(claims.getSubject())) {
                log.debug("Token de App Check de una app no permitida: {}", claims.getSubject());
                return Resultado.MALO;
            }
            return Resultado.OK;
        } catch (KeySourceException e) {
            log.warn("No se pudieron leer las llaves de App Check: {}", e.getMessage());
            return Resultado.SIN_VERIFICAR;
        } catch (Exception e) {
            log.debug("Token de App Check rechazado: {}", e.getMessage());
            return Resultado.MALO;
        }
    }

    private ConfigurableJWTProcessor<SecurityContext> procesador() throws Exception {
        ConfigurableJWTProcessor<SecurityContext> p = procesador;
        if (p != null) return p;
        synchronized (this) {
            if (procesador != null) return procesador;
            JWKSource<SecurityContext> claves = fuente != null ? fuente
                    : JWKSourceBuilder.create(URI.create(JWKS).toURL()).retrying(true).build();
            DefaultJWTProcessor<SecurityContext> nuevo = new DefaultJWTProcessor<>();
            nuevo.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, claves));
            // La audiencia trae el número y el nombre del proyecto: basta con
            // que esté el número. El emisor tiene que ser exactamente este.
            //
            // HashSet y no Set.of: nimbus pregunta si la lista contiene null
            // (para saber si acepta tokens sin audiencia), y un Set.of lanza
            // NullPointerException ante esa pregunta. Con Set.of, todo token
            // salía MALO, también los buenos.
            nuevo.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(
                    new HashSet<>(List.of("projects/" + proyecto)),
                    new JWTClaimsSet.Builder().issuer("https://firebaseappcheck.googleapis.com/" + proyecto).build(),
                    new HashSet<>(List.of("sub", "exp")),
                    new HashSet<>()));
            procesador = nuevo;
            return nuevo;
        }
    }
}
