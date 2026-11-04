package com.voces.backend.directorio;

import java.text.Collator;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Las reglas de la pestaña «En vivo» de la app: quién sale y en qué orden.
 *
 * Salen todos los que están al aire en el directorio, la persona los siga o
 * no. El orden es, primero, de quién se trata para ella:
 *
 *   1. los que sigue en la app (la misma regla que Novedades),
 *   2. los canales a los que está suscrita en YouTube,
 *   3. los demás.
 *
 * Quien cumple las dos primeras va en el primer grupo, una sola vez. Dentro
 * de cada grupo van por nombre: la lista se vuelve a pedir cada minuto, y un
 * orden por espectadores movería las tarjetas debajo del dedo de la persona.
 *
 * Lógica pura, sin Spring, para poder probarla sola.
 */
final class EnVivo {

    private EnVivo() {}

    static final String SEGUIDOS = "app";
    static final String SUSCRITOS = "youtube";
    static final String OTROS = "otros";

    private static final List<String> ORDEN = List.of(SEGUIDOS, SUSCRITOS, OTROS);

    /**
     * El grupo de un directo.
     *
     * @param creador           de quién es; null en el canal propio de un medio
     * @param canal             dónde sale; null si perdió su canal
     * @param creadoresSeguidos los creadores que sigue la persona
     * @param canalesSeguidos   los canales que sigue, también a través de un
     *                          medio o de un creador que aparece en ellos
     * @param canalesSuscritos  los canales a los que está suscrita en YouTube
     */
    static String grupoDe(UUID creador, UUID canal, Collection<UUID> creadoresSeguidos,
                          Collection<UUID> canalesSeguidos, Collection<UUID> canalesSuscritos) {
        if ((creador != null && creadoresSeguidos.contains(creador))
                || (canal != null && canalesSeguidos.contains(canal))) {
            return SEGUIDOS;
        }
        return canal != null && canalesSuscritos.contains(canal) ? SUSCRITOS : OTROS;
    }

    /**
     * Por grupo y después por nombre, sin distinguir acentos ni mayúsculas.
     * El último criterio solo desempata, para que dos directos del mismo
     * nombre no se turnen el lugar entre una consulta y la siguiente.
     */
    static <T> List<T> ordenar(Collection<T> directos, Function<T, String> grupo,
                               Function<T, String> nombre, Function<T, String> desempate) {
        Collator porNombre = Collator.getInstance(Locale.forLanguageTag("es"));
        porNombre.setStrength(Collator.PRIMARY);

        return directos.stream()
                .sorted(Comparator.<T>comparingInt(d -> ORDEN.indexOf(grupo.apply(d)))
                        .thenComparing(d -> nombre.apply(d) == null ? "" : nombre.apply(d), porNombre)
                        .thenComparing(desempate))
                .toList();
    }
}
