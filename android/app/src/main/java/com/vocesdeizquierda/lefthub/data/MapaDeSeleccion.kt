package com.vocesdeizquierda.lefthub.data

import java.time.Duration
import java.time.Instant

/**
 * Un creador o un medio en el mapa.
 *
 * @param voces   lo sigue en esta app
 * @param youtube suscrita a su canal principal; null si no se sabe
 */
data class EntradaDelMapa(val id: String, val voces: Boolean, val youtube: Boolean? = null)

/**
 * Un canal de YouTube en el mapa.
 *
 * @param voces   recibe sus videos aquí, por la vía que sea
 * @param suelto  lo sigue por sí solo, sin seguir a su creador ni a su medio
 * @param youtube suscrita a él en YouTube; null si no se sabe
 */
data class CanalDelMapa(
    val id: String,
    val voces: Boolean,
    val suelto: Boolean = false,
    val youtube: Boolean? = null
)

/**
 * Lo que la cuenta tiene seleccionado: qué sigue aquí y a qué está suscrita en
 * YouTube, por separado para creadores, medios y canales. De cinco canales
 * puede seguir los cinco aquí y estar suscrita a tres en YouTube.
 *
 * Lo arma el servidor (GET /api/mapa). El teléfono guarda una copia en
 * [AlmacenDeMapa] y al abrir solo pregunta si [huella] sigue siendo la misma.
 */
data class MapaDeSeleccion(
    val huella: String,
    val youtubeVerificadoEn: Instant? = null,
    val creadores: List<EntradaDelMapa> = emptyList(),
    val medios: List<EntradaDelMapa> = emptyList(),
    val canales: List<CanalDelMapa> = emptyList()
) {
    /** Los creadores que sigue. */
    val favoritos: List<String> get() = creadores.filter { it.voces }.map { it.id }

    /** Los medios que sigue. */
    val productoras: List<String> get() = medios.filter { it.voces }.map { it.id }

    /** Los canales que sigue por sí solos. */
    val canalesSueltos: List<String> get() = canales.filter { it.suelto }.map { it.id }

    /** YouTube ya contestó alguna vez, y hay etiquetas que pintar. */
    val sabeDeYouTube: Boolean get() = youtubeVerificadoEn != null

    /** La parte de YouTube, tal como la contesta /api/youtube/suscripciones. */
    val suscripciones: SuscripcionesYouTube
        get() = SuscripcionesYouTube(
            suscritos = creadores.filter { it.youtube == true }.map { it.id }.toSet(),
            noSuscritos = creadores.filter { it.youtube == false }.map { it.id }.toSet(),
            verificadoEn = youtubeVerificadoEn,
            canalesSuscritos = canales.filter { it.youtube == true }.map { it.id }.toSet(),
            canalesNoSuscritos = canales.filter { it.youtube == false }.map { it.id }.toSet()
        )

    /**
     * Sin nada de YouTube. Se queda sin huella para que la siguiente
     * comprobación traiga lo que diga el servidor, sea lo que sea.
     */
    fun sinYouTube(): MapaDeSeleccion = MapaDeSeleccion(
        huella = "",
        creadores = creadores.filter { it.voces }.map { it.copy(youtube = null) },
        medios = medios.filter { it.voces }.map { it.copy(youtube = null) },
        canales = canales.filter { it.voces || it.suelto }.map { it.copy(youtube = null) }
    )

    /** Lo de YouTube no se puede conservar más de 30 días sin volver a comprobarlo. */
    fun youtubeCaducado(ahora: Instant): Boolean {
        val cuando = youtubeVerificadoEn ?: return false
        return Duration.between(cuando, ahora) > RETENCION_YOUTUBE
    }

    private companion object {
        val RETENCION_YOUTUBE: Duration = Duration.ofDays(30)
    }
}
