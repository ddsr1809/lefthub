package com.voces.backend.version;

import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Comparar versiones de la app y decidir si una petición llega de una app
 * demasiado vieja. Sin Spring ni servlets, para poder probarlo solo.
 *
 * Las apps dicen quiénes son con dos cabeceras:
 *
 *   X-App-Version: 1.0.1-pruebas
 *   X-App-Plataforma: android
 *
 * Las primeras versiones de la app no mandan ninguna. Una petición sin versión
 * cuenta como la más vieja de todas, y sin plataforma cuenta como Android,
 * que es la única app que salió sin estas cabeceras.
 *
 * Hay dos formas de dejar fuera a una app: una versión mínima por plataforma
 * (todo lo anterior queda fuera) y una lista de versiones dadas de baja una a
 * una desde el panel, escritas como "android:1.0.0". La app que no dice su
 * versión es "android:0".
 */
public final class VersionDeApp {

    public static final String CABECERA_VERSION = "X-App-Version";
    public static final String CABECERA_PLATAFORMA = "X-App-Plataforma";

    /** El panel de administración no es una app: nunca se le pide versión. */
    public static final String PANEL = "panel";
    public static final String ANDROID = "android";
    public static final String IOS = "ios";

    /** La versión de la app que no dice cuál es. */
    public static final String SIN_VERSION = "0";

    // Hasta cuatro números separados por puntos, con o sin "v" delante. Lo
    // que venga después ("-pruebas", "-developer") no cuenta.
    private static final Pattern NUMEROS =
            Pattern.compile("^v?(\\d{1,6})(?:\\.(\\d{1,6}))?(?:\\.(\\d{1,6}))?(?:\\.(\\d{1,6}))?");

    private VersionDeApp() {}

    /** Los números de una versión. Lo que no se entiende cuenta como 0.0.0.0. */
    static int[] numeros(String version) {
        int[] partes = new int[4];
        if (version == null) return partes;
        Matcher m = NUMEROS.matcher(version.trim());
        if (!m.find()) return partes;
        for (int i = 0; i < partes.length; i++) {
            String parte = m.group(i + 1);
            if (parte != null) partes[i] = Integer.parseInt(parte);
        }
        return partes;
    }

    /** Negativo si a es anterior a b, cero si son la misma, positivo si es posterior. */
    public static int comparar(String a, String b) {
        int[] x = numeros(a);
        int[] y = numeros(b);
        for (int i = 0; i < x.length; i++) {
            if (x[i] != y[i]) return Integer.compare(x[i], y[i]);
        }
        return 0;
    }

    /**
     * De qué plataforma es la petición: "ios", "panel" o, en cualquier otro
     * caso, "android".
     */
    public static String plataforma(String cabecera) {
        String texto = cabecera == null ? "" : cabecera.trim();
        if (PANEL.equalsIgnoreCase(texto)) return PANEL;
        if (IOS.equalsIgnoreCase(texto)) return IOS;
        return ANDROID;
    }

    /**
     * La versión tal como se guarda y se enseña: solo sus números ("1.0.2"),
     * sin la "v" ni el sufijo del sabor. Null si no se entiende o no viene.
     */
    public static String limpia(String version) {
        if (version == null) return null;
        Matcher m = NUMEROS.matcher(version.trim());
        if (!m.find()) return null;
        String numeros = m.group();
        return numeros.startsWith("v") ? numeros.substring(1) : numeros;
    }

    /** Cómo se escribe una versión dada de baja: "android:1.0.0". */
    public static String baja(String plataforma, String version) {
        String limpia = limpia(version);
        return (IOS.equals(plataforma(plataforma)) ? IOS : ANDROID) + ":"
                + (limpia == null ? SIN_VERSION : limpia);
    }

    /** Si esa versión de esa plataforma está en la lista de dadas de baja. */
    public static boolean dadaDeBaja(Collection<String> bajas, String plataforma, String version) {
        if (bajas == null || bajas.isEmpty()) return false;
        String prefijo = (IOS.equals(plataforma(plataforma)) ? IOS : ANDROID) + ":";
        for (String baja : bajas) {
            // Por números y no por texto: "1.0" y "1.0.0" son la misma.
            if (baja != null && baja.startsWith(prefijo)
                    && comparar(version, baja.substring(prefijo.length())) == 0) {
                return true;
            }
        }
        return false;
    }

    /** Lo mismo que la de abajo, sin versiones dadas de baja. */
    public static boolean rechazada(String minimaAndroid, String minimaIos, String metodo,
                                    String ruta, String plataforma, String version) {
        return rechazada(minimaAndroid, minimaIos, List.of(), metodo, ruta, plataforma, version);
    }

    /**
     * Si hay que rechazar la petición por venir de una app anterior a la
     * mínima o de una versión dada de baja.
     *
     * @param minimaAndroid la mínima de Android; vacía = no hay mínima
     * @param minimaIos     la mínima de iOS; vacía = no hay mínima
     * @param bajas         las versiones dadas de baja, como "android:1.0.0"
     * @param metodo        GET, POST...
     * @param ruta          la ruta pedida, sin dominio
     * @param plataforma    la cabecera X-App-Plataforma, o null
     * @param version       la cabecera X-App-Version, o null
     */
    public static boolean rechazada(String minimaAndroid, String minimaIos, Collection<String> bajas,
                                    String metodo, String ruta, String plataforma, String version) {
        if (!deUnaApp(metodo, ruta, plataforma)) return false;
        String de = plataforma(plataforma);

        if (dadaDeBaja(bajas, de, version)) return true;

        String minima = IOS.equals(de) ? minimaIos : minimaAndroid;
        if (minima == null || minima.isBlank()) return false;

        return comparar(version, minima) < 0;
    }

    /**
     * Si la petición la hace una app. Es a las que se les puede cortar el
     * paso, por versión o por mantenimiento; el panel y todo lo demás siguen.
     */
    public static boolean deUnaApp(String metodo, String ruta, String plataforma) {
        // La consulta previa de CORS del navegador no lleva cabeceras propias.
        if ("OPTIONS".equalsIgnoreCase(metodo)) return false;
        if (!esDeLaApp(ruta)) return false;
        return !PANEL.equals(plataforma(plataforma));
    }

    /**
     * Las rutas que usa la app. Fuera quedan el webhook, las tareas internas
     * y la salud; el panel (/api/admin), las fotos, que el teléfono pide al
     * pintar una imagen y sin cabeceras, y /api/servidor, que es justo donde
     * se pregunta cuál es la mínima.
     */
    static boolean esDeLaApp(String ruta) {
        if (ruta == null || !ruta.startsWith("/api/")) return false;
        return !ruta.startsWith("/api/admin/")
                && !ruta.startsWith("/api/fotos/")
                && !ruta.equals("/api/servidor");
    }
}
