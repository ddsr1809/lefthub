package com.vocesdeizquierda.lefthub.ui

import com.vocesdeizquierda.lefthub.data.CanalListado
import com.vocesdeizquierda.lefthub.data.Creador
import com.vocesdeizquierda.lefthub.data.Productora

// Lo que decide si al seguir a alguien se pregunta antes, y qué canales se
// ofrecen para elegir. Sin nada de Compose: se prueba sin un teléfono.

/**
 * A quién se está por seguir cuando hay que preguntar antes: alguien con
 * varios canales de YouTube.
 *
 * @param esMedio un medio y no un creador: cambia la ruta de su pantalla de
 *                canales y nada más.
 */
data class PorSeguir(
    val id: String,
    val nombre: String,
    val canales: Int,
    val esMedio: Boolean = false
) {
    /** La pantalla donde se eligen sus canales uno por uno. */
    val rutaDeCanales: String get() = if (esMedio) "elegir/productora/$id" else "elegir/creador/$id"
}

/**
 * Hay que preguntar antes de seguirlo: tiene dos canales o más que se pueden
 * seguir por separado. Con uno solo (o ninguno) no hay nada que elegir y se
 * sigue de un toque, como siempre.
 */
fun porSeguir(creador: Creador?): PorSeguir? {
    if (creador == null) return null
    val canales = creador.canalesDeYouTube.count { it.sePuedeSeguir }
    return if (canales >= 2) PorSeguir(creador.id, creador.name, canales) else null
}

fun porSeguir(productora: Productora?): PorSeguir? {
    if (productora == null) return null
    val canales = productora.canales.count { it.sePuedeSeguir }
    return if (canales >= 2) PorSeguir(productora.id, productora.nombre, canales, esMedio = true) else null
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
