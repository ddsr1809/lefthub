package com.voces.backend.recomendaciones;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Qué dice de un canal lo que alguien escribió en "Recomendar un canal".
 *
 * La gente pega lo que tenga a mano: el enlace del canal, el de un video que
 * vio, un @handle o solo el nombre. Aquí no se llama a YouTube; solo se saca
 * lo que haya que preguntarle. Es estático para probarlo sin servidor.
 */
public final class LectorDeCanal {

    private LectorDeCanal() {}

    /**
     * Lo que se encontró. Como mucho viene una de las tres cosas, en este
     * orden de preferencia: el ID del canal, su @handle o el ID de un video
     * suyo. Las tres en null: escribió un nombre, y el equipo lo busca a mano.
     */
    public record Entrada(String channelId, String handle, String videoId) {
        public boolean vacia() { return channelId == null && handle == null && videoId == null; }
    }

    /** El ID canónico de un canal: UC y 22 caracteres más. */
    private static final Pattern ID_CANAL = Pattern.compile("(?<![\\w-])(UC[\\w-]{22})(?![\\w-])");

    /** youtube.com/@handle, con o sin https y con o sin www/m. */
    private static final Pattern HANDLE_EN_ENLACE = Pattern.compile(
            "youtube\\.com/@([\\p{L}\\p{N}._·-]+)", Pattern.CASE_INSENSITIVE);

    /** Solo el @handle, sin nada más. Un correo (ana@gmail.com) no lo es. */
    private static final Pattern HANDLE_SOLO = Pattern.compile("^@([\\p{L}\\p{N}._·-]+)$");

    /** Las direcciones antiguas: youtube.com/c/Nombre y youtube.com/user/Nombre. */
    private static final Pattern NOMBRE_ANTIGUO = Pattern.compile(
            "youtube\\.com/(?:c|user)/([\\p{L}\\p{N}._-]+)", Pattern.CASE_INSENSITIVE);

    /** El enlace de un video: youtu.be/ID, watch?v=ID, /shorts/ID, /live/ID, /embed/ID. */
    private static final Pattern VIDEO = Pattern.compile(
            "(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:[^#\\s]*&)?v=|shorts/|live/|embed/))([\\w-]{11})(?![\\w-])",
            Pattern.CASE_INSENSITIVE);

    public static Entrada leer(String texto) {
        if (texto == null) return new Entrada(null, null, null);
        String t = texto.strip();

        Matcher m = ID_CANAL.matcher(t);
        if (m.find()) return new Entrada(m.group(1), null, null);

        m = HANDLE_EN_ENLACE.matcher(t);
        if (m.find()) return new Entrada(null, limpiar(m.group(1)), null);

        m = HANDLE_SOLO.matcher(t);
        if (m.find()) return new Entrada(null, limpiar(m.group(1)), null);

        m = VIDEO.matcher(t);
        if (m.find()) return new Entrada(null, null, m.group(1));

        // Muchos canales antiguos pusieron como @handle el mismo nombre de su
        // dirección /c/ o /user/: vale la pena preguntarlo así.
        m = NOMBRE_ANTIGUO.matcher(t);
        if (m.find()) return new Entrada(null, limpiar(m.group(1)), null);

        return new Entrada(null, null, null);
    }

    /** Un handle no termina en punto: el de "mira youtube.com/@ana." es "ana". */
    private static String limpiar(String handle) {
        String h = handle;
        while (h.endsWith(".") || h.endsWith("·")) h = h.substring(0, h.length() - 1);
        return h.isEmpty() ? null : h;
    }

    /**
     * Para reconocer el mismo nombre escrito dos veces: sin mayúsculas, sin
     * espacios de más y sin la arroba del principio.
     */
    public static String normalizar(String texto) {
        if (texto == null) return "";
        String t = texto.strip().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
        return t.startsWith("@") ? t.substring(1) : t;
    }
}
