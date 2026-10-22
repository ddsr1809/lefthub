package com.voces.backend.stickers;

/**
 * Lo que hace falta saber de una imagen para aceptarla como sticker o como
 * ícono de un paquete: formato, tamaño y, si es animada, cuánto dura.
 *
 * Lee solo las cabeceras, sin decodificar la imagen: Java no trae lector de
 * WebP, y para comprobar lo que pide WhatsApp basta con la estructura del
 * archivo. Ver https://developers.google.com/speed/webp/docs/riff_container
 *
 * @param tipo       image/webp o image/png
 * @param ancho      en píxeles
 * @param alto       en píxeles
 * @param animado    WebP con animación (más de un cuadro)
 * @param cuadros    cuántos cuadros tiene; 1 si no es animado
 * @param duracionMs lo que dura una vuelta de la animación; 0 si no es animado
 * @param cuadroMasCortoMs la duración del cuadro más corto; 0 si no es animado
 */
public record Imagen(String tipo, int ancho, int alto, boolean animado,
                     int cuadros, long duracionMs, long cuadroMasCortoMs) {

    public static final String WEBP = "image/webp";
    public static final String PNG = "image/png";

    /**
     * Lee la cabecera. Devuelve null si no es un WebP o un PNG que se pueda
     * leer: un JPEG, un GIF, un archivo cortado o cualquier otra cosa.
     */
    public static Imagen leer(byte[] b) {
        if (b == null || b.length < 30) return null;
        try {
            if (esPng(b)) return png(b);
            if (esWebp(b)) return webp(b);
        } catch (IndexOutOfBoundsException e) {
            // Archivo cortado: las cabeceras prometen más de lo que hay.
            return null;
        }
        return null;
    }

    private static boolean esPng(byte[] b) {
        return (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0d && b[5] == 0x0a && b[6] == 0x1a && b[7] == 0x0a;
    }

    private static boolean esWebp(byte[] b) {
        return b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    /** El primer bloque de un PNG es siempre IHDR: ancho y alto en 4 bytes cada uno. */
    private static Imagen png(byte[] b) {
        if (!(b[12] == 'I' && b[13] == 'H' && b[14] == 'D' && b[15] == 'R')) return null;
        int ancho = be32(b, 16);
        int alto = be32(b, 20);
        if (ancho <= 0 || alto <= 0) return null;
        return new Imagen(PNG, ancho, alto, false, 1, 0, 0);
    }

    /**
     * Un WebP es un contenedor RIFF con bloques. El primero dice el formato:
     * VP8 (con pérdida), VP8L (sin pérdida) o VP8X (extendido: transparencia,
     * animación). Solo el extendido puede ser animado, y entonces cada cuadro
     * es un bloque ANMF con su duración.
     */
    private static Imagen webp(byte[] b) {
        // RIFF dice cuánto mide el archivo: si llegó menos, está cortado.
        int fin = 8 + le32(b, 4);
        if (fin < 20 || fin > b.length) return null;
        String primero = fourcc(b, 12);
        switch (primero) {
            case "VP8 " -> {
                // Cuadro clave: la firma 9d 01 2a y luego ancho y alto en 14 bits.
                int p = 20 + 3;
                if ((b[p] & 0xff) != 0x9d || (b[p + 1] & 0xff) != 0x01 || (b[p + 2] & 0xff) != 0x2a) return null;
                int ancho = le16(b, p + 3) & 0x3fff;
                int alto = le16(b, p + 5) & 0x3fff;
                return new Imagen(WEBP, ancho, alto, false, 1, 0, 0);
            }
            case "VP8L" -> {
                // Firma 0x2f y luego ancho-1 y alto-1 en 14 bits cada uno.
                if ((b[20] & 0xff) != 0x2f) return null;
                int bits = le32(b, 21);
                int ancho = (bits & 0x3fff) + 1;
                int alto = ((bits >>> 14) & 0x3fff) + 1;
                return new Imagen(WEBP, ancho, alto, false, 1, 0, 0);
            }
            case "VP8X" -> {
                boolean conAnimacion = (b[20] & 0x02) != 0;
                int ancho = le24(b, 24) + 1;
                int alto = le24(b, 27) + 1;
                if (!conAnimacion) return new Imagen(WEBP, ancho, alto, false, 1, 0, 0);

                int cuadros = 0;
                long total = 0;
                long masCorto = Long.MAX_VALUE;
                int p = 12;
                while (p + 8 <= fin) {
                    String nombre = fourcc(b, p);
                    int largo = le32(b, p + 4);
                    if (largo < 0) return null;
                    if ("ANMF".equals(nombre)) {
                        // X(3) Y(3) ancho-1(3) alto-1(3) duración(3) banderas(1)
                        long duracion = le24(b, p + 8 + 12);
                        cuadros++;
                        total += duracion;
                        masCorto = Math.min(masCorto, duracion);
                    }
                    // Los bloques ocupan un número par de bytes.
                    p += 8 + largo + (largo & 1);
                }
                if (cuadros == 0) return null;
                // Una "animación" de un solo cuadro es, en la práctica, una imagen fija.
                return new Imagen(WEBP, ancho, alto, cuadros > 1, cuadros,
                        cuadros > 1 ? total : 0, cuadros > 1 ? masCorto : 0);
            }
            default -> {
                return null;
            }
        }
    }

    private static String fourcc(byte[] b, int p) {
        return new String(b, p, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private static int le16(byte[] b, int p) {
        return (b[p] & 0xff) | (b[p + 1] & 0xff) << 8;
    }

    private static int le24(byte[] b, int p) {
        return (b[p] & 0xff) | (b[p + 1] & 0xff) << 8 | (b[p + 2] & 0xff) << 16;
    }

    private static int le32(byte[] b, int p) {
        return (b[p] & 0xff) | (b[p + 1] & 0xff) << 8 | (b[p + 2] & 0xff) << 16 | (b[p + 3] & 0xff) << 24;
    }

    private static int be32(byte[] b, int p) {
        return (b[p] & 0xff) << 24 | (b[p + 1] & 0xff) << 16 | (b[p + 2] & 0xff) << 8 | (b[p + 3] & 0xff);
    }
}
