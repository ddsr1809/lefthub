package com.vocesdeizquierda.lefthub.anuncios

/**
 * Dónde va cada anuncio dentro de la lista de Novedades.
 *
 * El primero sale después del segundo video y, a partir de ahí, uno cada
 * seis. Nunca más de tres: Novedades es una lista corta de lo que publicó la
 * gente que la persona eligió, y tiene que seguir pareciéndolo.
 *
 * Con un solo video no hay ningún anuncio. Una pantalla con más publicidad
 * que contenido es además lo que AdMob prohíbe.
 *
 * Lógica pura, sin nada de Android, para poder probarla sola.
 */
object Huecos {

    /** Después de cuántos videos sale el primero. */
    const val PRIMERO = 2

    /** Cuántos videos hay entre un anuncio y el siguiente. */
    const val CADA = 6

    const val MAXIMO = 3

    /**
     * Si después del video que está en `posicion` (contando desde cero) va un
     * anuncio, dice cuál: 0 para el primero, 1 para el segundo... Si no va
     * ninguno, null.
     */
    fun tras(posicion: Int): Int? {
        val desdeElPrimero = posicion - (PRIMERO - 1)
        if (desdeElPrimero < 0 || desdeElPrimero % CADA != 0) return null
        return (desdeElPrimero / CADA).takeIf { it < MAXIMO }
    }

    /** Cuántos anuncios caben en una lista de `videos` videos. */
    fun cuantos(videos: Int): Int =
        (0 until videos).count { tras(it) != null }
}
