package com.voces.backend.stickers;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * Lo que WhatsApp exige a un paquete de stickers. Si algo no se cumple,
 * WhatsApp rechaza el paquete entero al agregarlo, con un error que la
 * persona no entiende. Por eso se revisa aquí, al subirlo, y el panel
 * enseña el motivo.
 *
 * Telegram, Signal y Messenger son menos exigentes: lo que vale para
 * WhatsApp vale para todos.
 *
 * Los mensajes van escritos para el panel, tal cual.
 */
public final class Reglas {

    private Reglas() {}

    public static final int LADO_STICKER = 512;
    public static final int LADO_ICONO = 96;

    public static final int PESO_FIJO = 100 * 1024;
    public static final int PESO_ANIMADO = 500 * 1024;
    public static final int PESO_ICONO = 50 * 1024;

    public static final int MINIMO_POR_PAQUETE = 3;
    public static final int MAXIMO_POR_PAQUETE = 30;

    public static final int MAXIMO_EMOJIS = 3;
    public static final int MAXIMO_NOMBRE = 128;

    /** Una vuelta de la animación no puede pasar de 10 segundos. */
    public static final long DURACION_MAXIMA_MS = 10_000;
    /** Ningún cuadro puede durar menos de 8 milisegundos. */
    public static final long CUADRO_MINIMO_MS = 8;

    /** Cuando no se escribe ninguno. WhatsApp pide al menos uno. */
    public static final String EMOJI_POR_DEFECTO = "😀";

    /** Revisa un sticker y dice qué es. Lanza 400 con el motivo si no vale. */
    public static Imagen sticker(byte[] datos) {
        Imagen img = Imagen.leer(datos);
        if (img == null || !Imagen.WEBP.equals(img.tipo())) {
            throw invalido("El sticker tiene que ser una imagen WebP. El panel convierte solo los PNG y JPG fijos; "
                    + "un animado súbelo ya en WebP.");
        }
        if (img.ancho() != LADO_STICKER || img.alto() != LADO_STICKER) {
            throw invalido("El sticker mide " + img.ancho() + " × " + img.alto()
                    + " y tiene que medir exactamente 512 × 512.");
        }
        int tope = img.animado() ? PESO_ANIMADO : PESO_FIJO;
        if (datos.length > tope) {
            throw invalido("El sticker pesa " + kb(datos.length) + " y "
                    + (img.animado() ? "uno animado" : "uno fijo") + " no puede pasar de " + kb(tope) + ".");
        }
        if (img.animado()) {
            if (img.duracionMs() > DURACION_MAXIMA_MS) {
                throw invalido("La animación dura " + img.duracionMs() / 1000.0
                        + " segundos y no puede pasar de 10.");
            }
            if (img.cuadroMasCortoMs() < CUADRO_MINIMO_MS) {
                throw invalido("La animación tiene cuadros de menos de 8 milisegundos; WhatsApp no la acepta.");
            }
        }
        return img;
    }

    /** Revisa el ícono del paquete: PNG o WebP de 96 × 96, hasta 50 KB. */
    public static Imagen icono(byte[] datos) {
        Imagen img = Imagen.leer(datos);
        if (img == null) {
            throw invalido("El ícono tiene que ser una imagen PNG o WebP.");
        }
        if (img.animado()) {
            throw invalido("El ícono no puede ser animado.");
        }
        if (img.ancho() != LADO_ICONO || img.alto() != LADO_ICONO) {
            throw invalido("El ícono mide " + img.ancho() + " × " + img.alto()
                    + " y tiene que medir exactamente 96 × 96.");
        }
        if (datos.length > PESO_ICONO) {
            throw invalido("El ícono pesa " + kb(datos.length) + " y no puede pasar de 50 KB.");
        }
        return img;
    }

    /**
     * Los emojis de un sticker, limpios: de 1 a 3, separados por espacios o
     * comas. Sin ninguno, uno por defecto. Una palabra no vale.
     */
    public static String emojis(String texto) {
        List<String> lista = listaDeEmojis(texto);
        if (lista.isEmpty()) return EMOJI_POR_DEFECTO;
        if (lista.size() > MAXIMO_EMOJIS) {
            throw invalido("Cada sticker lleva como mucho 3 emojis.");
        }
        for (String e : lista) {
            if (e.length() > 32 || e.codePoints().anyMatch(Character::isLetter)) {
                throw invalido("«" + e + "» no es un emoji. Escribe solo emojis, separados por un espacio.");
            }
        }
        return String.join(" ", lista);
    }

    public static List<String> listaDeEmojis(String texto) {
        List<String> lista = new ArrayList<>();
        if (texto == null) return lista;
        for (String parte : texto.trim().split("[\\s,]+")) {
            if (!parte.isBlank()) lista.add(parte);
        }
        return lista;
    }

    /** El nombre de un paquete, limpio. Vacío no vale. */
    public static String nombre(String texto) {
        String limpio = texto == null ? "" : texto.trim().replaceAll("\\s+", " ");
        if (limpio.isEmpty()) throw invalido("Ponle un nombre al paquete.");
        return limpio.length() > MAXIMO_NOMBRE ? limpio.substring(0, MAXIMO_NOMBRE) : limpio;
    }

    /**
     * Lo que le falta a un paquete para poder publicarse. Vacío si está listo.
     * Lo enseña el panel junto a cada paquete.
     */
    public static List<String> loQueFalta(int stickers, boolean conIcono) {
        List<String> falta = new ArrayList<>();
        if (stickers < MINIMO_POR_PAQUETE) {
            int faltan = MINIMO_POR_PAQUETE - stickers;
            falta.add(faltan == 1 ? "Falta 1 sticker: WhatsApp pide al menos 3."
                    : "Faltan " + faltan + " stickers: WhatsApp pide al menos 3.");
        }
        if (!conIcono) falta.add("Falta el ícono del paquete.");
        return falta;
    }

    static String kb(int bytes) {
        return Math.round(bytes / 1024.0) + " KB";
    }

    private static ResponseStatusException invalido(String mensaje) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, mensaje);
    }
}
