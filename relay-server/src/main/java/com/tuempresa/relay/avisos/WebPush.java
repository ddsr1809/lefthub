package com.tuempresa.relay.avisos;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Web Push: el aviso que recibe un navegador (o el panel instalado como app)
 * aunque esté cerrado.
 *
 * Es el estándar abierto, no FCM: el navegador entrega al panel una dirección
 * de su propio servicio de avisos (el de Google en Chrome, el de Apple en
 * Safari, el de Mozilla en Firefox) y dos claves. Para avisar se le manda a
 * esa dirección el mensaje cifrado con esas claves (RFC 8291) y firmado con
 * las nuestras (VAPID, RFC 8292). No hace falta cuenta ni proyecto en ningún
 * sitio: por eso no usa las credenciales de Firebase de PushService.
 *
 * Todo sale del JDK, sin bibliotecas: son un acuerdo de claves, tres HMAC y
 * un AES-GCM. Y sin Spring, para poder probarlo entero sin levantar nada.
 */
public final class WebPush {

    /**
     * Nuestro par de claves, el mismo para todos los dispositivos.
     *
     * @param publica el punto de la curva sin comprimir (65 bytes) en
     *                base64url: tal cual lo pide el navegador al suscribirse
     * @param privada la clave privada en PKCS#8, en base64url
     */
    public record Claves(String publica, String privada) {}

    /** Lo que el navegador entrega al suscribirse. */
    public record Suscripcion(String endpoint, String p256dh, String auth) {}

    /** Lo que contestó el servicio de avisos del navegador. */
    public record Resultado(int estado, String detalle) {

        public boolean entregado() {
            return estado >= 200 && estado < 300;
        }

        /**
         * La suscripción ya no existe: desinstalaron la app, borraron los
         * datos del navegador o quitaron el permiso. No va a volver.
         */
        public boolean caducada() {
            return estado == 404 || estado == 410;
        }
    }

    /**
     * Lo más que se cifra en un aviso. El límite real es 4096 bytes para el
     * mensaje entero; nos quedamos lejos porque un aviso son dos renglones.
     */
    public static final int MAXIMO = 3000;

    /**
     * Los servicios de avisos a los que se manda. La dirección la trae el
     * navegador, o sea, quien llama a la API: sin esta lista, el servidor
     * haría peticiones a donde le dijeran.
     */
    private static final List<String> SERVICIOS = List.of(
            "fcm.googleapis.com",           // Chrome, Edge en Android, Samsung, Brave, Opera
            "push.apple.com",               // Safari y las apps web en iPhone, iPad y Mac
            "push.services.mozilla.com",    // Firefox
            "notify.windows.com");          // Edge en Windows

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom AZAR = new SecureRandom();
    private static final ECParameterSpec P256 = curva();

    private final HttpClient http;

    public WebPush() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    WebPush(HttpClient http) {
        this.http = http;
    }

    // -------------------------------------------------------------------------
    // Claves
    // -------------------------------------------------------------------------

    public static Claves nuevasClaves() {
        KeyPair par = nuevoPar();
        return new Claves(
                B64.encodeToString(punto((ECPublicKey) par.getPublic())),
                B64.encodeToString(par.getPrivate().getEncoded()));
    }

    /**
     * ¿Se le puede mandar a esa dirección? Devuelve null si sí, o el motivo.
     */
    public static String motivoDeRechazo(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) return "Falta la dirección del dispositivo.";
        if (endpoint.length() > 2000) return "La dirección del dispositivo es demasiado larga.";

        URI uri;
        try {
            uri = URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            return "La dirección del dispositivo no es una URL.";
        }
        String anfitrion = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || anfitrion == null || uri.getUserInfo() != null) {
            return "La dirección del dispositivo no es una URL https.";
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return "La dirección del dispositivo usa un puerto que no es el de https.";
        }

        String host = anfitrion.toLowerCase(Locale.ROOT);
        boolean conocido = SERVICIOS.stream().anyMatch(s -> host.equals(s) || host.endsWith("." + s));
        return conocido ? null
                : "Ese navegador usa un servicio de avisos que el servidor no conoce (" + host + ").";
    }

    /**
     * ¿Son dos claves de navegador bien formadas? Así un dispositivo con
     * datos rotos se rechaza al registrarlo y no en cada envío.
     */
    public static boolean clavesValidas(String p256dh, String auth) {
        try {
            claveDelNavegador(p256dh);
            return decodificar(auth).length == 16;
        } catch (RuntimeException | GeneralSecurityException e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Envío
    // -------------------------------------------------------------------------

    /**
     * Manda un aviso a un dispositivo.
     *
     * @param contacto a quién escribir si el servicio de avisos detecta un
     *                 problema: una URL https o un mailto:. Apple lo exige
     * @param tema     opcional: si hay un aviso anterior con el mismo tema que
     *                 el dispositivo todavía no recibió (estaba apagado), este
     *                 lo sustituye en vez de sumarse
     * @param vigencia cuánto guarda el servicio el aviso si el dispositivo no
     *                 está conectado
     */
    public Resultado enviar(Suscripcion a, Claves claves, String contacto,
                            byte[] carga, String tema, Duration vigencia) {
        try {
            URI destino = URI.create(a.endpoint());
            byte[] cuerpo = cifrar(carga, a.p256dh(), a.auth());

            HttpRequest.Builder peticion = HttpRequest.newBuilder(destino)
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", cabeceraVapid(claves, destino, contacto, Instant.now()))
                    .header("Content-Encoding", "aes128gcm")
                    .header("Content-Type", "application/octet-stream")
                    .header("TTL", String.valueOf(vigencia.toSeconds()))
                    .header("Urgency", "high")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo));
            if (tema != null && !tema.isBlank()) peticion.header("Topic", temaSeguro(tema));

            HttpResponse<String> respuesta = http.send(peticion.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            String detalle = respuesta.body() == null ? "" : respuesta.body().strip();
            return new Resultado(respuesta.statusCode(),
                    detalle.length() > 300 ? detalle.substring(0, 300) : detalle);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resultado(0, "Se interrumpió el envío.");

        } catch (Exception e) {
            // Sin red, dirección rota, claves rotas: nada de eso debe tumbar a
            // quien avisa. El motivo queda apuntado en el dispositivo.
            return new Resultado(0, e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""));
        }
    }

    /**
     * El tema viaja en una cabecera que solo admite 32 caracteres del
     * alfabeto de base64url. De lo que nos den se queda con un resumen.
     */
    static String temaSeguro(String tema) {
        if (tema.length() <= 32 && tema.matches("[A-Za-z0-9_-]+")) return tema;
        try {
            byte[] resumen = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(tema.getBytes(StandardCharsets.UTF_8));
            return B64.encodeToString(Arrays.copyOf(resumen, 24));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    // -------------------------------------------------------------------------
    // VAPID (RFC 8292): la firma que dice que el aviso es nuestro
    // -------------------------------------------------------------------------

    /**
     * La cabecera Authorization. Lleva un JWT firmado con nuestra clave que
     * vale solo para el servicio de avisos de ese dispositivo y por doce
     * horas (el máximo permitido son veinticuatro).
     */
    static String cabeceraVapid(Claves claves, URI destino, String contacto, Instant ahora) {
        try {
            String origen = destino.getScheme() + "://" + destino.getAuthority();
            String cabecera = "{\"typ\":\"JWT\",\"alg\":\"ES256\"}";
            String datos = "{\"aud\":\"" + json(origen) + "\","
                    + "\"exp\":" + ahora.plus(Duration.ofHours(12)).getEpochSecond() + ","
                    + "\"sub\":\"" + json(contacto) + "\"}";

            String firmado = B64.encodeToString(cabecera.getBytes(StandardCharsets.UTF_8))
                    + "." + B64.encodeToString(datos.getBytes(StandardCharsets.UTF_8));

            PrivateKey privada = KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(decodificar(claves.privada())));

            // P1363 es el formato que pide JWT: r y s seguidos, 64 bytes. El
            // de siempre en Java (DER) no lo acepta ningún servicio de avisos.
            Signature firma = Signature.getInstance("SHA256withECDSAinP1363Format");
            firma.initSign(privada);
            firma.update(firmado.getBytes(StandardCharsets.US_ASCII));

            return "vapid t=" + firmado + "." + B64.encodeToString(firma.sign())
                    + ", k=" + claves.publica();

        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo firmar el aviso.", e);
        }
    }

    private static String json(String texto) {
        return texto.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // -------------------------------------------------------------------------
    // Cifrado (RFC 8291 sobre RFC 8188, "aes128gcm")
    // -------------------------------------------------------------------------

    /** Cifra para ese navegador con una clave de un solo uso y sal al azar. */
    public static byte[] cifrar(byte[] claro, String p256dh, String auth) throws GeneralSecurityException {
        byte[] sal = new byte[16];
        AZAR.nextBytes(sal);
        return cifrar(claro, claveDelNavegador(p256dh), decodificar(auth), nuevoPar(), sal);
    }

    /**
     * El cifrado con todo a la vista, para poder comprobarlo contra un
     * ejemplo conocido: la clave de un solo uso y la sal vienen de fuera.
     */
    static byte[] cifrar(byte[] claro, ECPublicKey navegador, byte[] auth,
                         KeyPair efimera, byte[] sal) throws GeneralSecurityException {

        if (claro.length > MAXIMO) {
            throw new IllegalArgumentException("El aviso no cabe: " + claro.length + " bytes.");
        }
        if (auth.length != 16 || sal.length != 16) {
            throw new IllegalArgumentException("La clave de autenticación y la sal son de 16 bytes.");
        }

        byte[] puntoNavegador = punto(navegador);
        byte[] puntoNuestro = punto((ECPublicKey) efimera.getPublic());

        // El secreto que solo conocen ese navegador y este mensaje.
        KeyAgreement acuerdo = KeyAgreement.getInstance("ECDH");
        acuerdo.init(efimera.getPrivate());
        acuerdo.doPhase(navegador, true);
        byte[] compartido = acuerdo.generateSecret();

        // Se mezcla con la clave de autenticación del navegador: quien solo
        // conozca su clave pública no puede fabricar un aviso válido.
        byte[] info = unir("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), puntoNavegador, puntoNuestro);
        byte[] material = hkdf(auth, compartido, info, 32);

        byte[] clave = hkdf(sal, material, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = hkdf(sal, material, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        // Un solo registro: el texto y el byte 0x02, que marca que es el último.
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(clave, "AES"), new GCMParameterSpec(128, nonce));
        byte[] cifrado = aes.doFinal(unir(claro, new byte[] { 2 }));

        // Cabecera: sal, tamaño de registro, y nuestra clave de un solo uso.
        ByteBuffer cabecera = ByteBuffer.allocate(16 + 4 + 1 + puntoNuestro.length);
        cabecera.put(sal).putInt(4096).put((byte) puntoNuestro.length).put(puntoNuestro);

        return unir(cabecera.array(), cifrado);
    }

    /** HKDF de un solo bloque (hasta 32 bytes), que es todo lo que hace falta. */
    private static byte[] hkdf(byte[] sal, byte[] material, byte[] info, int largo) throws GeneralSecurityException {
        byte[] prk = hmac(sal, material);
        return Arrays.copyOf(hmac(prk, unir(info, new byte[] { 1 })), largo);
    }

    private static byte[] hmac(byte[] clave, byte[] datos) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(clave, "HmacSHA256"));
        return mac.doFinal(datos);
    }

    // -------------------------------------------------------------------------
    // Curva P-256
    // -------------------------------------------------------------------------

    private static ECParameterSpec curva() {
        try {
            AlgorithmParameters parametros = AlgorithmParameters.getInstance("EC");
            parametros.init(new ECGenParameterSpec("secp256r1"));
            return parametros.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Este JDK no trae la curva P-256.", e);
        }
    }

    static KeyPair nuevoPar() {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("EC");
            generador.initialize(new ECGenParameterSpec("secp256r1"), AZAR);
            return generador.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Este JDK no trae la curva P-256.", e);
        }
    }

    /** La clave pública de un navegador: 65 bytes, 0x04 y las dos coordenadas. */
    static ECPublicKey claveDelNavegador(String p256dh) throws GeneralSecurityException {
        byte[] bytes = decodificar(p256dh);
        if (bytes.length != 65 || bytes[0] != 4) {
            throw new IllegalArgumentException("La clave del navegador no es un punto de P-256.");
        }
        ECPoint punto = new ECPoint(
                new BigInteger(1, Arrays.copyOfRange(bytes, 1, 33)),
                new BigInteger(1, Arrays.copyOfRange(bytes, 33, 65)));
        if (!estaEnLaCurva(punto)) {
            throw new IllegalArgumentException("La clave del navegador no es un punto de P-256.");
        }
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(punto, P256));
    }

    /** y² = x³ + ax + b. Una clave fuera de la curva serviría para sonsacar la nuestra. */
    private static boolean estaEnLaCurva(ECPoint punto) {
        BigInteger p = ((java.security.spec.ECFieldFp) P256.getCurve().getField()).getP();
        BigInteger x = punto.getAffineX(), y = punto.getAffineY();
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) return false;
        BigInteger derecha = x.pow(3)
                .add(P256.getCurve().getA().multiply(x))
                .add(P256.getCurve().getB())
                .mod(p);
        return y.multiply(y).mod(p).equals(derecha);
    }

    static byte[] punto(ECPublicKey clave) {
        byte[] salida = new byte[65];
        salida[0] = 4;
        copiar(clave.getW().getAffineX(), salida, 1);
        copiar(clave.getW().getAffineY(), salida, 33);
        return salida;
    }

    /** Una coordenada en 32 bytes justos: BigInteger quita o añade ceros. */
    private static void copiar(BigInteger numero, byte[] destino, int desde) {
        byte[] bytes = numero.toByteArray();
        int sobran = Math.max(0, bytes.length - 32);
        int cuantos = bytes.length - sobran;
        System.arraycopy(bytes, sobran, destino, desde + 32 - cuantos, cuantos);
    }

    // -------------------------------------------------------------------------

    /** El navegador manda base64url; algunos, base64 normal o con relleno. */
    static byte[] decodificar(String texto) {
        if (texto == null) throw new IllegalArgumentException("Falta una clave.");
        String limpio = texto.trim().replace('+', '-').replace('/', '_').replace("=", "");
        return Base64.getUrlDecoder().decode(limpio);
    }

    private static byte[] unir(byte[]... trozos) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        for (byte[] trozo : trozos) salida.writeBytes(trozo);
        return salida.toByteArray();
    }
}
