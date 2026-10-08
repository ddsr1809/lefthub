package com.tuempresa.relay.avisos;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Un aviso para quien administra: lo que se lee en la notificación y a qué
 * sección del panel lleva al tocarla.
 *
 * El texto está escrito para entenderse sin abrir nada: qué pasó y con quién.
 *
 * @param etiqueta agrupa: un aviso con la misma etiqueta que otro que sigue en
 *                 pantalla lo sustituye en vez de apilarse
 * @param vista    la sección del panel: reportes, canales...
 */
public record Aviso(String titulo, String cuerpo, String vista, String etiqueta) {

    static final int MAX_TITULO = 80;
    static final int MAX_CUERPO = 240;

    public Aviso {
        titulo = recortar(titulo, MAX_TITULO);
        cuerpo = recortar(cuerpo, MAX_CUERPO);
    }

    // -------------------------------------------------------------------------
    // Qué se dice en cada caso
    // -------------------------------------------------------------------------

    /**
     * Llegó un reporte. Cualquiera de los datos puede faltar: un reporte puede
     * señalar un video que el servidor no conoce, o solo a un creador.
     */
    public static Aviso deReporte(String motivo, String detalle, String videoId,
                                  String tituloDelVideo, String creador, long idDelReporte) {

        if ("apple_revoke_failed".equals(motivo)) {
            return new Aviso("Falló la revocación con Apple",
                    "Al borrar una cuenta no se pudo cortar su vínculo con Apple."
                            + (vacio(detalle) ? "" : " " + detalle.strip()),
                    "reportes", "reporte-" + idDelReporte);
        }

        boolean enlaceRoto = vacio(motivo) || "enlace_roto".equals(motivo);
        String titulo = enlaceRoto ? "Reporte nuevo: enlace roto" : "Reporte nuevo";

        String que = !vacio(tituloDelVideo) ? "«" + tituloDelVideo.strip() + "»"
                : !vacio(videoId) ? "El video " + videoId
                : null;

        String cuerpo;
        if (que != null && !vacio(creador)) cuerpo = que + ", de " + creador.strip() + ".";
        else if (que != null) cuerpo = que + ".";
        else if (!vacio(creador)) cuerpo = "Sobre " + creador.strip() + ".";
        else cuerpo = "Ábrelo para ver de qué se trata.";

        // Lo que escribió la persona, cuando no es el motivo de siempre.
        if (!enlaceRoto) cuerpo += " Motivo: " + motivo.strip();

        return new Aviso(titulo, cuerpo, "reportes",
                !vacio(videoId) ? "reporte-" + videoId : "reporte-" + idDelReporte);
    }

    /** Uno o varios canales llevan un rato sin poder recibir publicaciones. */
    public static Aviso deCanales(List<String> nombres) {
        if (nombres.size() == 1) {
            return new Aviso("Un canal dejó de recibir publicaciones",
                    nombres.get(0) + ": su suscripción de YouTube no está activa, así que sus "
                            + "videos nuevos no generan avisos. Ábrelo para reintentar.",
                    "canales", "canales");
        }
        return new Aviso(nombres.size() + " canales dejaron de recibir publicaciones",
                enumerar(nombres) + ": sus suscripciones de YouTube no están activas. "
                        + "Ábrelos para reintentar.",
                "canales", "canales");
    }

    public static Aviso dePrueba() {
        return new Aviso("Aviso de prueba",
                "Si lees esto, los avisos del panel llegan bien a este dispositivo.",
                "avisos", "prueba");
    }

    /** "A", "A y B", "A, B y C", y de ahí en adelante "A, B y 3 más". */
    static String enumerar(List<String> nombres) {
        if (nombres.size() == 1) return nombres.get(0);
        if (nombres.size() == 2) return nombres.get(0) + " y " + nombres.get(1);
        if (nombres.size() == 3) return nombres.get(0) + ", " + nombres.get(1) + " y " + nombres.get(2);
        return nombres.get(0) + ", " + nombres.get(1) + " y " + (nombres.size() - 2) + " más";
    }

    // -------------------------------------------------------------------------
    // Cómo viaja
    // -------------------------------------------------------------------------

    /** El JSON que recibe el panel (sw.js) y convierte en notificación. */
    public byte[] carga() {
        String json = "{\"titulo\":" + cadena(titulo)
                + ",\"cuerpo\":" + cadena(cuerpo)
                + ",\"vista\":" + cadena(vista)
                + ",\"etiqueta\":" + cadena(etiqueta) + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    static String cadena(String texto) {
        StringBuilder salida = new StringBuilder("\"");
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '"' -> salida.append("\\\"");
                case '\\' -> salida.append("\\\\");
                case '\n' -> salida.append("\\n");
                case '\r' -> salida.append("\\r");
                case '\t' -> salida.append("\\t");
                default -> {
                    if (c < 0x20) salida.append(String.format("\\u%04x", (int) c));
                    else salida.append(c);
                }
            }
        }
        return salida.append('"').toString();
    }

    /** Corta sin partir un emoji ni otro carácter de dos unidades. */
    static String recortar(String texto, int maximo) {
        if (texto == null) return "";
        String limpio = texto.strip();
        if (limpio.length() <= maximo) return limpio;
        int corte = maximo - 1;
        if (Character.isHighSurrogate(limpio.charAt(corte - 1))) corte--;
        return limpio.substring(0, corte).stripTrailing() + "…";
    }

    private static boolean vacio(String texto) {
        return texto == null || texto.isBlank();
    }
}
