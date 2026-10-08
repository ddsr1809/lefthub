package com.tuempresa.relay.anuncios;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * La forma de un folio de regalo: cómo se inventa, cómo se limpia lo que
 * alguien teclea y cómo se pinta. Lógica pura, sin base de datos.
 *
 * Son diez letras y números, que se enseñan en dos grupos de cinco:
 * ABCDE-FGHJK. Lo va a copiar a mano una persona mayor desde un mensaje o un
 * papel, así que el alfabeto no lleva los caracteres que se confunden al
 * leer: ni el cero ni la O, ni el uno ni la I ni la L.
 *
 * Con 31 caracteres y diez posiciones hay unos 800 billones de folios
 * posibles. Aunque hubiera mil sin usar, acertar uno probando al azar pide
 * cientos de miles de millones de intentos: lo que protege un folio es eso, y
 * no que el límite de intentos de AnunciosController aguante.
 */
public final class Folios {

    static final String ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    static final int LARGO = 10;

    private static final SecureRandom AZAR = new SecureRandom();

    private Folios() {}

    /** Un folio nuevo, como se guarda: sin guion. */
    public static String nuevo() {
        StringBuilder folio = new StringBuilder(LARGO);
        for (int i = 0; i < LARGO; i++) {
            folio.append(ALFABETO.charAt(AZAR.nextInt(ALFABETO.length())));
        }
        return folio.toString();
    }

    /**
     * Lo que alguien tecleó, como se guarda: en mayúsculas y sin guiones,
     * espacios ni puntos. Devuelve null si con eso no puede ser un folio, y
     * entonces ni se pregunta a la base de datos.
     */
    public static String normalizar(String tecleado) {
        if (tecleado == null) return null;

        StringBuilder limpio = new StringBuilder(LARGO);
        for (char c : tecleado.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c == '-' || c == '.' || c == '_' || Character.isWhitespace(c)) continue;
            if (ALFABETO.indexOf(c) < 0) return null;
            if (limpio.length() == LARGO) return null;
            limpio.append(c);
        }
        return limpio.length() == LARGO ? limpio.toString() : null;
    }

    /** Como se enseña y se reparte: ABCDE-FGHJK. */
    public static String pintar(String codigo) {
        if (codigo == null || codigo.length() != LARGO) return codigo;
        return codigo.substring(0, LARGO / 2) + "-" + codigo.substring(LARGO / 2);
    }
}
