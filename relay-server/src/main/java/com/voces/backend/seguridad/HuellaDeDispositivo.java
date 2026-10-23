package com.voces.backend.seguridad;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * El identificador de instalación que manda la app (device_id) se guarda solo
 * como huella SHA-256: {@code h1:<64 hex>}.
 *
 * Ese identificador es lo que la app presenta en /api/auth/anonimo para
 * recuperar su cuenta sin pedir nada a la persona. Es, en la práctica, una
 * contraseña. Guardado tal cual, quien tuviera una copia de la base podría
 * entrar en cualquier cuenta. La huella basta para reconocerlo y no sirve para
 * entrar: hay que presentar el identificador original.
 *
 * No hace falta llave: el identificador es un UUID al azar (122 bits), que no
 * se puede adivinar probando. Y así no depende de DATOS_INDICE.
 *
 * Los datos de antes los convierte CifradoDeDatosExistentes al arrancar, con
 * la misma fórmula en SQL: 'h1:' || encode(sha256(device_id), 'hex').
 */
public final class HuellaDeDispositivo {

    static final String PREFIJO = "h1:";
    private static final Pattern YA_ES_HUELLA = Pattern.compile("h1:[0-9a-f]{64}");

    private HuellaDeDispositivo() {}

    /**
     * La huella del identificador que manda la app. Siempre se calcula, aunque
     * lo que llegue parezca ya una huella: quien hubiera visto una en la base
     * no puede presentarla para entrar.
     */
    public static String de(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return null;
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(deviceId.getBytes(StandardCharsets.UTF_8));
            return PREFIJO + HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Esta JVM no tiene SHA-256", e);
        }
    }

    public static boolean esHuella(String valor) {
        return valor != null && YA_ES_HUELLA.matcher(valor).matches();
    }

    /**
     * La columna device_id. La entidad guarda ya la huella (AuthController la
     * calcula con {@link #de}); esto es la red de seguridad para que nunca
     * llegue a la base un identificador legible.
     */
    @Converter
    public static class Columna implements AttributeConverter<String, String> {

        @Override
        public String convertToDatabaseColumn(String valor) {
            if (valor == null) return null;
            return esHuella(valor) ? valor : de(valor);
        }

        @Override
        public String convertToEntityAttribute(String guardado) {
            return guardado;
        }
    }
}
