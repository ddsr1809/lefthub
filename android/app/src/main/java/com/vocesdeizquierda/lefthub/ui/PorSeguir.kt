package com.vocesdeizquierda.lefthub.ui

import com.vocesdeizquierda.lefthub.data.Canal
import com.vocesdeizquierda.lefthub.data.CanalListado
import com.vocesdeizquierda.lefthub.data.Creador
import com.vocesdeizquierda.lefthub.data.Perfil
import com.vocesdeizquierda.lefthub.data.Productora

// Lo que decide si al seguir a alguien se pregunta antes, qué canales se
// ofrecen y qué se sigue al final. Sin nada de Compose: se prueba sin un
// teléfono.

/**
 * A quién se está por seguir, con sus canales de YouTube: la ventana que sale
 * antes dice cuántos y cuáles son, y deja quitar los que no quiera.
 *
 * @param canales sus canales de YouTube, en el orden de su ficha. Nunca vacía.
 * @param esMedio un medio y no un creador.
 */
data class PorSeguir(
    val id: String,
    val nombre: String,
    val canales: List<CanalListado>,
    val esMedio: Boolean = false
) {
    /**
     * A dónde lleva "verlos antes de decidir": con un solo canal, a la ficha
     * de ese canal; con varios, a la pantalla con todos ellos.
     */
    val rutaDeCanales: String
        get() = when {
            canales.size == 1 -> "canal/${canales.single().canal.id}"
            esMedio -> "elegir/productora/$id"
            else -> "elegir/creador/$id"
        }

    /** Ya sigue sueltos algunos de sus canales, sin seguirlo a él entero. */
    fun parcial(perfil: Perfil): Boolean = canales.any { perfil.sigueCanal(it.canal) }

    /**
     * Cómo se abre la ventana: si ya sigue algunos de sus canales, con esos
     * marcados, para cambiar cuáles; si no, con todos.
     */
    fun marcasIniciales(perfil: Perfil): Set<String> =
        canales.filter { perfil.sigueCanal(it.canal) }.map { it.canal.id }.toSet()
            .ifEmpty { canales.map { it.canal.id }.toSet() }

    /**
     * Su único canal, si lo sigue suelto. Tocar "Siguiendo" en su fila deja
     * entonces de seguir ese canal, sin ventana: no hay nada que elegir.
     */
    fun unicoSeguido(perfil: Perfil): Canal? =
        canales.singleOrNull()?.canal?.takeIf { perfil.sigueCanal(it) }

    /** Qué seguir y qué dejar con lo que quedó marcado en la ventana. */
    fun queSeguir(marcados: Set<String>, perfil: Perfil): PlanDeSeguir {
        val suyos = canales.map { it.canal }
        // Todos marcados: se sigue al creador (o al medio) entero, que trae
        // también los canales que se le agreguen después. Los que seguía
        // sueltos sobran; si no se dejan, al dejar de seguirlo un día le
        // seguirían llegando esos.
        if (suyos.all { it.id in marcados }) {
            return PlanDeSeguir(todo = true, dejar = suyos.filter { perfil.sigueCanal(it) })
        }
        return PlanDeSeguir(
            todo = false,
            seguir = suyos.filter { it.id in marcados && !perfil.recibe(it) },
            dejar = suyos.filter { it.id !in marcados && perfil.sigueCanal(it) }
        )
    }
}

/**
 * Lo que se hace al tocar el botón de la ventana.
 *
 * @param todo   seguir al creador (o al medio) entero.
 * @param seguir canales que empezar a seguir sueltos.
 * @param dejar  canales sueltos que dejar de seguir.
 */
data class PlanDeSeguir(
    val todo: Boolean,
    val seguir: List<Canal> = emptyList(),
    val dejar: List<Canal> = emptyList()
)

/** Cuántos de estos canales sigue sueltos. */
fun Perfil.sueltosDe(canales: List<Canal>): Int = canales.count { it.sePuedeSeguir && sigueCanal(it) }

/**
 * Hay que preguntar antes de seguirlo si tiene algún canal de YouTube: la
 * ventana dice a cuántos canales se va a seguir. Sin ninguno (solo redes
 * sociales) no hay avisos que elegir y se sigue de un toque, como siempre.
 *
 * @param todos el listado de canales del Directorio, de canalesDelDirectorio.
 */
fun porSeguir(creador: Creador?, todos: List<CanalListado>): PorSeguir? {
    if (creador == null) return null
    val canales = canalesParaElegir(creador, null, todos)
    return if (canales.isEmpty()) null else PorSeguir(creador.id, creador.name, canales)
}

fun porSeguir(productora: Productora?, todos: List<CanalListado>): PorSeguir? {
    if (productora == null) return null
    val canales = canalesParaElegir(null, productora, todos)
    return if (canales.isEmpty()) null else PorSeguir(productora.id, productora.nombre, canales, esMedio = true)
}

/**
 * Los canales de un creador o de un medio que se pueden seguir sueltos, en el
 * orden de su ficha (no el alfabético de la lista), con lo necesario para
 * pintar su fila.
 *
 * @param todos el listado de canales del Directorio, de canalesDelDirectorio.
 */
fun canalesParaElegir(
    creador: Creador?,
    productora: Productora?,
    todos: List<CanalListado>
): List<CanalListado> {
    val suyos = (creador?.canalesDeYouTube ?: productora?.canales.orEmpty()).filter { it.sePuedeSeguir }
    val porId = todos.associateBy { it.canal.id }
    return suyos.mapNotNull { porId[it.id] }.distinctBy { it.canal.id }
}

/**
 * Lo que sigue, arriba; lo demás, debajo. Cada parte conserva el orden que
 * traía (alfabético).
 */
fun <T> seguidosPrimero(lista: List<T>, sigue: (T) -> Boolean): Pair<List<T>, List<T>> =
    lista.partition(sigue)
