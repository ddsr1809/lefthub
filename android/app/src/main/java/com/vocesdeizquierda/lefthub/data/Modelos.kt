package com.vocesdeizquierda.lefthub.data

import java.time.Instant

// Los nombres de los campos se mantienen deliberadamente como estaban cuando
// los datos venian de Firestore. Asi ninguna pantalla cambia: la traduccion
// desde los nombres del servidor ocurre en ApiRelay, en un solo sitio.

data class Conexion(
    val url: String = "",
    val handle: String? = null,
    val channelId: String? = null
)

/**
 * Un canal del directorio: el YouTube de alguien, su TikTok, su página.
 *
 * Un creador puede tener varios, también en la misma plataforma, y cada uno
 * puede pertenecer a una productora. `creadorId` es el dueño y falta en el
 * canal propio de una productora; `productoraId` falta en el canal que es solo
 * de su creador. `tambien` son los demás creadores con los que aparece: lo que
 * publica el canal les llega también a quienes los siguen.
 */
data class Canal(
    val id: String = "",
    val plataforma: String = "youtube",
    /** Cómo se distingue de los otros del mismo dueño: "Clips", "Directos". */
    val nombre: String? = null,
    val url: String = "",
    val handle: String? = null,
    val channelId: String? = null,
    val creadorId: String? = null,
    val productoraId: String? = null,
    val tambien: List<String> = emptyList()
) {
    val esDeYouTube: Boolean get() = plataforma == "youtube" && !channelId.isNullOrBlank()
}

data class Creador(
    val id: String = "",
    val name: String = "",
    val category: String = "otros",
    val bio: String? = null,
    val photoUrl: String? = null,
    /** Un enlace por plataforma: el canal principal de cada una. */
    val platforms: Map<String, Conexion> = emptyMap(),
    val active: Boolean = true,
    /** Sus canales, en el orden en que se muestran, y después los de otros en los que aparece. */
    val canales: List<Canal> = emptyList(),
    /** Productoras en las que figura. */
    val productoras: List<String> = emptyList(),
    /**
     * La fila no es un creador sino una productora que aparece en el
     * directorio como uno más: su id es el de la productora, y al tocarla se
     * abre su ficha y se la sigue como productora.
     */
    val esProductora: Boolean = false
) {
    /** Plataformas en el orden en que se muestran, filtrando las vacías. */
    val conexionesOrdenadas: List<Pair<String, Conexion>>
        get() = ORDEN_PLATAFORMAS.mapNotNull { p -> platforms[p]?.let { p to it } }

    /**
     * Los canales que se pintan en su perfil.
     *
     * Un servidor anterior a los canales múltiples no manda `canales`: ahí se
     * arman a partir del enlace por plataforma, que es lo que hay.
     */
    val canalesVisibles: List<Canal>
        get() = canales.ifEmpty {
            conexionesOrdenadas.map { (plataforma, c) ->
                Canal(plataforma = plataforma, url = c.url, handle = c.handle,
                    channelId = c.channelId, creadorId = id)
            }
        }

    /** Sus canales de YouTube: de donde salen los videos y los avisos. */
    val canalesDeYouTube: List<Canal>
        get() = canalesVisibles.filter { it.plataforma == "youtube" }

    /** Todo lo demás: X, Instagram, TikTok, su página. Son enlaces, no avisan. */
    val redes: List<Canal>
        get() = canalesVisibles.filter { it.plataforma != "youtube" }

    companion object {
        // El mismo orden que usa el servidor (Dtos.PLATAFORMAS).
        val ORDEN_PLATAFORMAS = listOf(
            "youtube", "tiktok", "twitch", "instagram", "x", "facebook", "threads",
            "telegram", "spotify", "patreon", "web"
        )
    }
}

/**
 * La casa detrás de varios creadores.
 *
 * `canales` son los que le pertenecen: los propios (sin creador) y los de
 * creadores que se le asignaron. `creadores` son los que figuran en ella, que
 * no tienen por qué coincidir con los dueños de esos canales.
 */
data class Productora(
    val id: String = "",
    val nombre: String = "",
    val descripcion: String? = null,
    val logoUrl: String? = null,
    val canales: List<Canal> = emptyList(),
    val creadores: List<String> = emptyList(),
    /** Aparece también en el listado de creadores, como una fila más. */
    val enDirectorio: Boolean = false
) {
    /** Los que no son de ningún creador: el canal oficial de la casa. */
    val canalesPropios: List<Canal> get() = canales.filter { it.creadorId == null }
}

data class Publicacion(
    val id: String = "",
    val videoId: String = "",
    val creatorId: String = "",
    val creatorName: String? = null,
    val platform: String = "youtube",
    val title: String = "",
    val description: String? = null,
    val thumbnailUrl: String? = null,
    val url: String? = null,
    // Antes era com.google.firebase.Timestamp. Ahora es un Instant del JDK:
    // el servidor manda ISO-8601 y no hace falta ninguna biblioteca externa.
    val publishedAt: Instant? = null,
    val status: String = "ok",
    val overrideUrl: String? = null,
    val overridePlatform: String? = null,
    val esEnVivo: Boolean = false,
    val tipo: String = "video",
    /** La productora del canal donde salió, si tiene. */
    val productoraId: String? = null,
    val productoraNombre: String? = null
) {
    val fueMovido: Boolean get() = status == "moved" && !overrideUrl.isNullOrBlank()

    /** Un video corto (un Short). Nunca va mezclado con los demás. */
    val esCorto: Boolean get() = tipo == "short"

    /**
     * A nombre de quién se muestra: "Juan Pérez · Estudio X" cuando su canal
     * es de una productora, y solo la productora en su canal propio (ahí el
     * servidor ya manda su nombre como autor).
     */
    val firma: String
        get() = listOfNotNull(
            creatorName?.takeIf { it.isNotBlank() },
            productoraNombre?.takeIf { it.isNotBlank() && it != creatorName }
        ).joinToString(" · ")

    /**
     * A dónde lleva realmente el botón. Si el equipo redirigió el contenido
     * porque lo tumbaron de la plataforma original, el destino es el nuevo.
     */
    val destino: Destino
        get() = if (fueMovido) {
            Destino(overridePlatform ?: "web", overrideUrl, null)
        } else {
            Destino(platform, url, videoId)
        }
}

/**
 * Los últimos videos de un canal, para su ficha. Se piden al abrirla; no son
 * las novedades de la persona, sino lo que hay en ese canal.
 */
data class VideosDeCanal(
    val canalId: String = "",
    val cargando: Boolean = false,
    val lista: List<Publicacion> = emptyList(),
    val fallo: Boolean = false
)

data class Destino(
    val plataforma: String,
    val url: String?,
    val videoId: String?
)

data class Perfil(
    val favoritos: List<String> = emptyList(),
    val escalaTexto: String = "normal",
    val tema: String = "sistema",
    val avisos: Boolean = true,
    /** Productoras que sigue. Los creadores van en `favoritos`. */
    val productoras: List<String> = emptyList(),
    /** Quiere ver los videos cortos. Solo cuenta si están disponibles. */
    val cortos: Boolean = true,
    /**
     * El equipo permite los videos cortos. Si no, la app no enseña ni el
     * apartado ni la opción de Ajustes, diga lo que diga `cortos`. Un servidor
     * anterior no lo manda, y entonces es que no.
     */
    val cortosDisponibles: Boolean = false,
    /**
     * El equipo tiene encendidos los anuncios. Un servidor anterior no lo
     * manda, y entonces es que no hay.
     */
    val anuncios: Boolean = false,
    /** Esta cuenta ya no ve anuncios: los compró o canjeó un folio de regalo. */
    val sinAnuncios: Boolean = false,
    /**
     * El servidor puede confirmar compras con Google Play. Si no, la app no
     * ofrece comprar: cobrar algo que luego no se puede entregar es peor que
     * no ofrecerlo. El folio de regalo no depende de esto.
     */
    val compraDisponible: Boolean = false
) {
    /** Sigue a alguien, sea creador o productora. */
    val sigueAAlguien: Boolean get() = favoritos.isNotEmpty() || productoras.isNotEmpty()

    /** Ve anuncios: el equipo los tiene encendidos y la persona no los quitó. */
    val veAnuncios: Boolean get() = anuncios && !sinAnuncios

    /** Ve los videos cortos: el equipo los permite y la persona no los apagó. */
    val veCortos: Boolean get() = cortosDisponibles && cortos
}

// --- Suscripciones de YouTube ------------------------------------------------

/**
 * Lo que contesta el servidor.
 *
 * `suscritos` y `noSuscritos` son IDs de creadores y hablan de su canal
 * principal de YouTube. Las dos listas de canales son la respuesta completa,
 * canal por canal; un servidor anterior no las manda y llegan vacías.
 */
data class SuscripcionesYouTube(
    val suscritos: Set<String> = emptySet(),
    val noSuscritos: Set<String> = emptySet(),
    val verificadoEn: Instant? = null,
    val canalesSuscritos: Set<String> = emptySet(),
    val canalesNoSuscritos: Set<String> = emptySet()
)

enum class PermisoYouTube {
    /** Todavía no se ha preguntado. Es el estado al abrir la app. */
    DESCONOCIDO,
    CONCEDIDO,
    /** La persona no ha dado el permiso, lo retiró o lo apagó en Ajustes. */
    SIN_PERMISO
}

data class EstadoYouTube(
    val permiso: PermisoYouTube = PermisoYouTube.DESCONOCIDO,
    val suscripciones: SuscripcionesYouTube = SuscripcionesYouTube(),
    val verificando: Boolean = false
) {
    /**
     * `true` o `false` si se sabe; `null` si no: falta el permiso, el creador
     * no tiene canal de YouTube o aún no se ha comprobado. La interfaz no debe
     * pintar "no suscrito" cuando en realidad no lo sabemos.
     */
    fun suscritoA(creadorId: String): Boolean? = when (creadorId) {
        in suscripciones.suscritos -> true
        in suscripciones.noSuscritos -> false
        else -> null
    }

    /**
     * Lo mismo, para un canal concreto. Con un servidor anterior, que solo
     * sabe de creadores, se contesta con lo que se sepa del creador.
     */
    fun suscritoAlCanal(canal: Canal): Boolean? = when {
        !canal.esDeYouTube -> null
        canal.id in suscripciones.canalesSuscritos -> true
        canal.id in suscripciones.canalesNoSuscritos -> false
        canal.id.isBlank() && canal.creadorId != null -> suscritoA(canal.creadorId)
        else -> null
    }
}
