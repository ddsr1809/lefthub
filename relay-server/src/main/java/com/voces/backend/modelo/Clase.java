package com.voces.backend.modelo;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Qué tipo de cuenta es un creador, un medio o un canal. La app ordena sus
 * listas con esto: primero lo general, después la televisión pública y al
 * final la política y el gobierno. Se elige en el panel.
 */
public final class Clase {

    /** Lo de siempre: creadores, medios y canales que no son políticos. */
    public static final String GENERAL = "general";
    /** Canales de televisión pública. */
    public static final String TELEVISION_PUBLICA = "television_publica";
    /** Figuras políticas y cuentas de gobierno. */
    public static final String POLITICA = "politica";

    /** En el orden en que salen en la app. */
    public static final List<String> TODAS = List.of(GENERAL, TELEVISION_PUBLICA, POLITICA);

    private Clase() {}

    /**
     * La clase pedida, comprobada.
     *
     * @return null si no viene (no se toca), o la clase.
     * @throws ResponseStatusException 400 si no es ninguna de las tres.
     */
    public static String validar(String pedida) {
        if (pedida == null) return null;
        String limpia = pedida.trim();
        if (!TODAS.contains(limpia)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tipo de cuenta no válido: " + pedida + ". Usa general, television_publica o politica.");
        }
        return limpia;
    }

    /**
     * Le pone a un canal la clase pedida. Para un canal, vacío quiere decir
     * "la de su dueño".
     *
     * @param pedida null: no se toca; vacía: la de su dueño; si no, esa clase.
     */
    public static void aplicar(Canal canal, String pedida) {
        if (pedida == null) return;
        canal.setClase(pedida.isBlank() ? null : validar(pedida));
    }

    /** La de un canal: la suya, o la de su dueño si no tiene. */
    public static String deCanal(Canal canal, Creador dueno, Productora medio) {
        if (canal.getClase() != null) return canal.getClase();
        if (dueno != null) return dueno.getClase();
        if (medio != null) return medio.getClase();
        return GENERAL;
    }
}
