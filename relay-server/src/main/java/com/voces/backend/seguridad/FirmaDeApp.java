package com.voces.backend.seguridad;

import com.voces.backend.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * La firma con la llave de la app: cada petición de la app oficial lleva una
 * firma HMAC-SHA256 hecha con una llave que va dentro de la app (APP_LLAVES en
 * el servidor, llave_app_* en android/firma.properties).
 *
 * <h2>Qué se firma</h2>
 *
 * <pre>
 * lefthub-app-v1
 * GET
 * /api/creadores
 * 1761206400        (X-App-Hora: segundos desde 1970)
 * q8Zl3...          (X-App-Nonce: al azar, una sola vez)
 * </pre>
 *
 * La app manda X-App-Llave (número de llave), X-App-Hora, X-App-Nonce y
 * X-App-Firma (base64url del HMAC). El servidor rechaza una firma de hace más
 * de cinco minutos o un nonce ya visto: una petición copiada no sirve otra
 * vez. La hora del teléfono se corrige con X-Servidor-Hora, que el servidor
 * devuelve en cada respuesta: un teléfono con la hora mal no se queda fuera.
 *
 * <h2>Lo que no es</h2>
 *
 * La llave va dentro del APK, y quien lo descompile puede sacarla. Frena a
 * scripts, escáneres y bots que llaman a la API sin la app; no a alguien
 * decidido. Lo que prueba de verdad que la petición viene de la app original
 * en un teléfono real es App Check (ver VerificadorAppCheck). Las dos se
 * piden juntas.
 *
 * <h2>Cambiar de llave</h2>
 *
 * Como DATOS_LLAVES: se agrega "2:BASE64" sin quitar la 1, se publica la app
 * con la 2, y cuando ya nadie use versiones con la 1, se quita.
 */
@Component
public class FirmaDeApp {

    private static final Logger log = LoggerFactory.getLogger(FirmaDeApp.class);

    public static final String CABECERA_LLAVE = "X-App-Llave";
    public static final String CABECERA_HORA = "X-App-Hora";
    public static final String CABECERA_NONCE = "X-App-Nonce";
    public static final String CABECERA_FIRMA = "X-App-Firma";

    static final String VERSION = "lefthub-app-v1";
    static final Duration VENTANA = Duration.ofMinutes(5);
    private static final int LARGO_LLAVE = 32;
    private static final int TOPE_NONCES = 200_000;

    /** Cómo salió la comprobación de una petición. */
    public enum Resultado {
        /** Firma buena, a tiempo y nunca vista. */
        OK,
        /** No trae ninguna de las cabeceras: una app anterior, o alguien sin la app. */
        FALTA,
        /** Trae firma, pero no cuadra: llave desconocida o firma equivocada. */
        MALA,
        /** Firma buena, pero de hace más de cinco minutos (o la hora del teléfono está mal). */
        HORA,
        /** Firma buena, pero ese nonce ya se usó: una petición repetida. */
        REPETIDA
    }

    private final Map<Short, SecretKeySpec> llaves;
    private final Map<String, Instant> vistos = new ConcurrentHashMap<>();

    @Autowired
    public FirmaDeApp(RelayProperties config) {
        this(config.llaveDeApp().llaves());
    }

    public FirmaDeApp(String llaves) {
        this.llaves = leer(llaves);
        if (this.llaves.isEmpty()) {
            log.warn("Sin APP_LLAVES: no se comprueba la firma de la app");
        } else {
            log.info("Firma de la app: {} llave(s)", this.llaves.size());
        }
    }

    public boolean configurada() {
        return !llaves.isEmpty();
    }

    /** El texto que se firma. Igual que en la app (data/FirmaDeApp.kt). */
    static String texto(String metodo, String ruta, String hora, String nonce) {
        return VERSION + "\n" + metodo.toUpperCase(Locale.ROOT) + "\n" + ruta + "\n" + hora + "\n" + nonce;
    }

    /** La firma, en base64url sin relleno. Para las pruebas y para documentar. */
    String firmar(short llave, String metodo, String ruta, String hora, String nonce) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(hmac(llaves.get(llave), texto(metodo, ruta, hora, nonce)));
    }

    public Resultado verificar(String llave, String hora, String nonce, String firma,
                               String metodo, String ruta, Instant ahora) {
        if (vacio(llave) && vacio(hora) && vacio(nonce) && vacio(firma)) return Resultado.FALTA;
        if (vacio(llave) || vacio(hora) || vacio(nonce) || vacio(firma)) return Resultado.MALA;
        if (nonce.length() < 16 || nonce.length() > 64) return Resultado.MALA;

        SecretKeySpec k;
        long segundos;
        byte[] recibida;
        try {
            k = llaves.get(Short.parseShort(llave.trim()));
            segundos = Long.parseLong(hora.trim());
            recibida = Base64.getUrlDecoder().decode(firma.trim());
        } catch (IllegalArgumentException e) {
            return Resultado.MALA;
        }
        if (k == null) return Resultado.MALA;

        byte[] esperada = hmac(k, texto(metodo, ruta, hora.trim(), nonce));
        if (!MessageDigest.isEqual(esperada, recibida)) return Resultado.MALA;

        Instant cuando = Instant.ofEpochSecond(segundos);
        if (Duration.between(cuando, ahora).abs().compareTo(VENTANA) > 0) return Resultado.HORA;

        return anotarNonce(nonce, ahora) ? Resultado.OK : Resultado.REPETIDA;
    }

    /** true si el nonce es nuevo; lo deja anotado hasta que pasa la ventana. */
    private boolean anotarNonce(String nonce, Instant ahora) {
        if (vistos.size() >= TOPE_NONCES) {
            Instant viejo = ahora.minus(VENTANA.multipliedBy(2));
            vistos.values().removeIf(t -> t.isBefore(viejo));
            if (vistos.size() >= TOPE_NONCES) {
                log.warn("Demasiados nonces recordados; se olvidan los anteriores");
                vistos.clear();
            }
        }
        return vistos.putIfAbsent(nonce, ahora) == null;
    }

    private static byte[] hmac(SecretKeySpec llave, String texto) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(llave);
            return mac.doFinal(texto.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular la firma de la app", e);
        }
    }

    private static boolean vacio(String s) {
        return s == null || s.isBlank();
    }

    /** "1:BASE64,2:BASE64". Vacío = sin firma. Mal escrita: el servidor no arranca. */
    static Map<Short, SecretKeySpec> leer(String texto) {
        Map<Short, SecretKeySpec> mapa = new TreeMap<>();
        if (texto == null || texto.isBlank()) return Collections.unmodifiableMap(mapa);
        for (String parte : texto.split(",")) {
            String p = parte.trim();
            if (p.isEmpty()) continue;
            int dos = p.indexOf(':');
            if (dos <= 0) throw mal("cada llave va como número:base64, por ejemplo 1:abc...=");
            short numero;
            byte[] bytes;
            try {
                numero = Short.parseShort(p.substring(0, dos).trim());
                bytes = Base64.getDecoder().decode(p.substring(dos + 1).trim());
            } catch (IllegalArgumentException e) {
                throw mal("«" + p.substring(0, Math.min(p.length(), dos + 4)) + "…» no es número:base64");
            }
            if (numero <= 0) throw mal("los números de llave empiezan en 1");
            if (bytes.length != LARGO_LLAVE) {
                throw mal("la llave " + numero + " tiene " + bytes.length + " bytes y deben ser 32 (openssl rand -base64 32)");
            }
            if (mapa.put(numero, new SecretKeySpec(bytes, "HmacSHA256")) != null) {
                throw mal("la llave " + numero + " está repetida");
            }
        }
        return Collections.unmodifiableMap(mapa);
    }

    private static IllegalStateException mal(String motivo) {
        return new IllegalStateException("APP_LLAVES está mal escrita: " + motivo + ".");
    }
}
