package com.voces.backend.version;

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
 * Las apps anteriores a la 1.0.1 no mandan ninguna. Una petición sin versión
 * cuenta como la más vieja de todas, y sin plataforma cuenta como Android,
 * que es la única app que salió sin estas cabeceras.
 */
public final class VersionDeApp {

    public static final String CABECERA_VERSION = "X-App-Version";
    public static final String CABECERA_PLATAFORMA = "X-App-Plataforma";

    /** El panel de administración no es una app: nunca se le pide versión. */
    public static final String PANEL = "panel";

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
     * Si hay que rechazar la petición por venir de una app anterior a la mínima.
     *
     * @param minimaAndroid la mínima de Android; vacía = no hay mínima
     * @param minimaIos     la mínima de iOS; vacía = no hay mínima
     * @param metodo        GET, POST...
     * @param ruta          la ruta pedida, sin dominio
     * @param plataforma    la cabecera X-App-Plataforma, o null
     * @param version       la cabecera X-App-Version, o null
     */
    public static boolean rechazada(String minimaAndroid, String minimaIos, String metodo,
                                    String ruta, String plataforma, String version) {
        // La consulta previa de CORS del navegador no lleva cabeceras propias.
        if ("OPTIONS".equalsIgnoreCase(metodo)) return false;
        if (!esDeLaApp(ruta)) return false;
        if (plataforma != null && PANEL.equalsIgnoreCase(plataforma.trim())) return false;

        boolean ios = plataforma != null && "ios".equalsIgnoreCase(plataforma.trim());
        String minima = ios ? minimaIos : minimaAndroid;
        if (minima == null || minima.isBlank()) return false;

        return comparar(version, minima) < 0;
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
