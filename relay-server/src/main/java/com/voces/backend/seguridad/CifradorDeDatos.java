package com.voces.backend.seguridad;

import com.voces.backend.config.RelayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Cifra los datos personales que se guardan en la base: el correo, la IP, el
 * refresh token de Apple y los comprobantes de compra de Google Play.
 *
 * Quien se lleve una copia de la base (un respaldo extraviado, un volcado
 * hecho a mano, un disco del VPS) ve solo texto cifrado. Las llaves viven en
 * el .env del VPS y nunca tocan la base.
 *
 * <h2>Cómo se guarda un valor</h2>
 *
 * {@code c1:<número de llave>:<base64(iv ‖ cifrado ‖ etiqueta)>}
 *
 * AES-256-GCM, con un IV al azar por valor. Como datos adicionales va el
 * nombre de la columna: un correo cifrado copiado a la columna de la IP no se
 * descifra. Mismo esquema que el chat (ver chat/CifradorDeChat), con llaves
 * propias.
 *
 * <h2>Cómo se busca sin descifrar</h2>
 *
 * El panel busca cuentas por correo o IP exactos, y una compra se reconoce por
 * su comprobante. Para eso cada uno lleva además una huella: HMAC-SHA256 con
 * DATOS_INDICE, una llave aparte. La huella sirve para comparar igualdad y no
 * deja recuperar el dato: sin la llave, ni siquiera probando las cuatro mil
 * millones de IPv4 posibles.
 *
 * <h2>Las llaves</h2>
 *
 * DATOS_LLAVES funciona como CHAT_LLAVES: "1:BASE64", y para cambiar de llave
 * se agrega "2:BASE64" sin quitar la 1. Se cifra con la de número más alto.
 * DATOS_INDICE no se cambia nunca: con otra, las huellas guardadas dejarían de
 * coincidir.
 *
 * Sin llaves el servidor no arranca: guardar los datos sin cifrar por
 * descuido sería peor. scripts/deploy-vps.sh y local.sh las crean solas la
 * primera vez.
 *
 * <h2>Por qué hay una instancia estática</h2>
 *
 * Hibernate crea los convertidores de las columnas (ver Cifrado) por su
 * cuenta. Para no depender de cómo los construya, piden el cifrador a
 * {@link #actual()}, que queda listo cuando Spring crea este bean, antes que
 * la base (ver JpaEsperaAlCifrador).
 */
@Component
public class CifradorDeDatos {

    private static final Logger log = LoggerFactory.getLogger(CifradorDeDatos.class);

    /** Columnas cifradas. El nombre entra en los datos adicionales de GCM. */
    public static final String CORREO = "usuarios.email";
    public static final String IP = "usuarios.ip";
    public static final String APPLE = "usuarios.apple_refresh";
    public static final String COMPROBANTE = "compras.token";
    public static final String ORDEN = "compras.orden";
    public static final String AUTOR_DE_ALTA = "altas_desde_app.autor";

    static final String PREFIJO = "c1:";
    private static final String VERSION = "lefthub-datos-v1";
    private static final int LARGO_LLAVE = 32;
    private static final int LARGO_IV = 12;
    private static final int BITS_ETIQUETA = 128;

    private static volatile CifradorDeDatos actual;

    private final Map<Short, SecretKey> llaves;
    private final short llaveActual;
    private final SecretKey indice;
    private final SecureRandom azar = new SecureRandom();

    @Autowired
    public CifradorDeDatos(RelayProperties config) {
        this(config.datos().llaves(), config.datos().indice());
    }

    public CifradorDeDatos(String llaves, String indice) {
        this.llaves = leerLlaves(llaves);
        if (this.llaves.isEmpty()) {
            throw new IllegalStateException("""

                    Falta DATOS_LLAVES: sin ella los datos de las personas se guardarían sin cifrar.
                    Genera una con:  echo "1:$(openssl rand -base64 32)"
                    En el VPS la crea sola scripts/deploy-vps.sh; en local, ./local.sh.
                    """);
        }
        this.llaveActual = Collections.max(this.llaves.keySet());
        this.indice = leerIndice(indice);
        actual = this;
        log.info("Datos personales cifrados: {} llave(s), se cifra con la número {}",
                this.llaves.size(), llaveActual);
    }

    /** El cifrador del servidor. Lanza si todavía no se ha creado. */
    public static CifradorDeDatos actual() {
        CifradorDeDatos c = actual;
        if (c == null) {
            throw new IllegalStateException("El cifrador de datos todavía no está listo.");
        }
        return c;
    }

    /**
     * La huella de un valor, o null si no hay valor o si el cifrador no se ha
     * creado (en las pruebas que arman una cuenta a mano, sin Spring).
     */
    public static String huellaSiSePuede(String valor, String campo) {
        CifradorDeDatos c = actual;
        return c == null ? null : c.huella(valor, campo);
    }

    // -------------------------------------------------------------------------

    public static boolean estaCifrado(String guardado) {
        return guardado != null && guardado.startsWith(PREFIJO);
    }

    /**
     * El valor listo para la base. Uno ya cifrado se deja tal cual: es el que
     * no se pudo leer (ver {@link #descifrar}) y hay que conservarlo.
     */
    public String cifrar(String texto, String campo) {
        if (texto == null || estaCifrado(texto)) return texto;

        byte[] iv = new byte[LARGO_IV];
        azar.nextBytes(iv);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, llaves.get(llaveActual), new GCMParameterSpec(BITS_ETIQUETA, iv));
            c.updateAAD(datosAdicionales(campo, llaveActual));
            byte[] cifrado = c.doFinal(texto.getBytes(StandardCharsets.UTF_8));

            byte[] todo = ByteBuffer.allocate(iv.length + cifrado.length).put(iv).put(cifrado).array();
            return PREFIJO + llaveActual + ":" + Base64.getEncoder().encodeToString(todo);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo cifrar " + campo, e);
        }
    }

    /**
     * El valor legible.
     *
     * Lo que no empieza por c1: es de antes de cifrar y se devuelve tal cual:
     * CifradoDeDatosExistentes lo cifra al arrancar, pero mientras tanto se
     * tiene que poder leer.
     *
     * Si no se puede leer (la llave ya no está en DATOS_LLAVES, o lo guardado
     * no es lo que se cifró para esa columna) se devuelve también tal cual,
     * cifrado. No null: Hibernate escribe todas las columnas al guardar una
     * cuenta, y con null la siguiente visita de esa persona borraría su
     * correo para siempre. Cifrado, {@link #cifrar} lo deja como está y el
     * dato vuelve a leerse en cuanto se repone la llave. Nunca se registra el
     * contenido.
     */
    public String descifrar(String guardado, String campo) {
        if (guardado == null || !estaCifrado(guardado)) return guardado;
        String claro = intentarDescifrar(guardado, campo);
        return claro != null ? claro : guardado;
    }

    /** Como {@link #descifrar}, pero null si no se puede leer. */
    String intentarDescifrar(String guardado, String campo) {
        int dos = guardado.indexOf(':', PREFIJO.length());
        if (dos < 0) {
            log.warn("Un valor de {} no tiene el formato esperado", campo);
            return null;
        }
        short llave;
        byte[] todo;
        try {
            llave = Short.parseShort(guardado.substring(PREFIJO.length(), dos));
            todo = Base64.getDecoder().decode(guardado.substring(dos + 1));
        } catch (IllegalArgumentException e) {
            log.warn("Un valor de {} no tiene el formato esperado", campo);
            return null;
        }

        SecretKey k = llaves.get(llave);
        if (k == null) {
            log.warn("Un valor de {} está cifrado con la llave {}, que ya no está en DATOS_LLAVES", campo, llave);
            return null;
        }
        if (todo.length <= LARGO_IV) {
            log.warn("Un valor de {} está incompleto", campo);
            return null;
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(BITS_ETIQUETA, todo, 0, LARGO_IV));
            c.updateAAD(datosAdicionales(campo, llave));
            byte[] claro = c.doFinal(todo, LARGO_IV, todo.length - LARGO_IV);
            return new String(claro, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Un valor de {} no se pudo descifrar (llave {})", campo, llave);
            return null;
        }
    }

    /**
     * HMAC-SHA256 del valor normalizado, en hexadecimal. Null si no hay valor.
     *
     * El correo y la IP se pasan a minúsculas y sin espacios: "Ana@Gmail.com "
     * y "ana@gmail.com" son la misma cuenta. El comprobante de compra se usa
     * tal cual.
     */
    public String huella(String valor, String campo) {
        if (valor == null) return null;
        String normal = COMPROBANTE.equals(campo) ? valor : valor.trim().toLowerCase(Locale.ROOT);
        if (normal.isEmpty()) return null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(indice);
            mac.update((VERSION + "|" + campo + "|").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(normal.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo calcular la huella de " + campo, e);
        }
    }

    // -------------------------------------------------------------------------

    private static byte[] datosAdicionales(String campo, short llave) {
        return (VERSION + "|" + campo + "|" + llave).getBytes(StandardCharsets.UTF_8);
    }

    /** "1:BASE64,2:BASE64". Lanza con un mensaje claro si algo no cuadra. */
    static Map<Short, SecretKey> leerLlaves(String texto) {
        Map<Short, SecretKey> mapa = new TreeMap<>();
        if (texto == null || texto.isBlank()) return mapa;

        for (String parte : texto.split(",")) {
            String p = parte.trim();
            if (p.isEmpty()) continue;
            int dos = p.indexOf(':');
            if (dos <= 0) throw malFormada("DATOS_LLAVES", "cada llave va como número:base64, por ejemplo 1:abc...=");

            short numero;
            try {
                numero = Short.parseShort(p.substring(0, dos).trim());
            } catch (NumberFormatException e) {
                throw malFormada("DATOS_LLAVES", "el número de llave «" + p.substring(0, dos) + "» no es un número");
            }
            if (numero <= 0) throw malFormada("DATOS_LLAVES", "los números de llave empiezan en 1");

            byte[] bytes = base64(p.substring(dos + 1).trim(), "DATOS_LLAVES", "la llave " + numero);
            if (mapa.put(numero, new SecretKeySpec(bytes, "AES")) != null) {
                throw malFormada("DATOS_LLAVES", "la llave " + numero + " está repetida");
            }
        }
        return mapa;
    }

    static SecretKey leerIndice(String texto) {
        if (texto == null || texto.isBlank()) {
            throw new IllegalStateException("""

                    Falta DATOS_INDICE: es la llave de las huellas con las que se busca un correo o una IP.
                    Genera una con:  openssl rand -base64 32
                    No la cambies nunca: con otra, las búsquedas dejarían de encontrar lo ya guardado.
                    """);
        }
        return new SecretKeySpec(base64(texto.trim(), "DATOS_INDICE", "la llave"), "HmacSHA256");
    }

    private static byte[] base64(String texto, String variable, String cual) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(texto);
        } catch (IllegalArgumentException e) {
            throw malFormada(variable, cual + " no es base64");
        }
        if (bytes.length != LARGO_LLAVE) {
            throw malFormada(variable, cual + " tiene " + bytes.length
                    + " bytes y deben ser 32 (openssl rand -base64 32)");
        }
        return bytes;
    }

    private static IllegalStateException malFormada(String variable, String motivo) {
        return new IllegalStateException(variable + " está mal escrita: " + motivo + ".");
    }
}
