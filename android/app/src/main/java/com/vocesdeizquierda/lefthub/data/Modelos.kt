package com.vocesdeizquierda.lefthub.data

import com.vocesdeizquierda.lefthub.BuildConfig
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
    val tambien: List<String> = emptyList(),
    /**
     * Su nombre en YouTube ("Claudia Sheinbaum Clips"). Lo pone el servidor
     * solo; puede no estar todavía, y un servidor anterior no lo manda.
     */
    val titulo: String? = null,
    /**
     * Su tipo de cuenta, ya resuelto por el servidor con el de su dueño. Ver
     * ClaseDeCuenta. Un servidor anterior no lo manda: es general.
     */
    val clase: String = ClaseDeCuenta.GENERAL
) {
    val esDeYouTube: Boolean get() = plataforma == "youtube" && !channelId.isNullOrBlank()

    /** @usuario, si lo tiene. */
    val arroba: String? get() = handle?.takeIf { it.isNotBlank() }?.let { "@" + it.removePrefix("@") }

    /**
     * Cómo se llama: su nombre en YouTube, que es el que la persona va a ver
     * al abrirlo; si todavía no se sabe, la etiqueta que le puso el equipo en
     * el panel ("Clips") o su usuario, sin la arroba. null si no hay ninguno.
     */
    val nombreVisible: String?
        get() = titulo?.takeIf { it.isNotBlank() } ?: nombre?.takeIf { it.isNotBlank() }
            ?: arroba?.removePrefix("@")

    /**
     * El nombre tal como se enseña: en mayúsculas, "HISTORIANETWORK". Así se
     * distingue de un vistazo del nombre de la persona, que va debajo.
     */
    val nombreEnPantalla: String?
        get() = nombreVisible?.uppercase()

    /**
     * Se puede seguir por sí solo: es un canal de YouTube y el servidor lo
     * manda con su id. Los de un servidor anterior no lo traen.
     */
    val sePuedeSeguir: Boolean get() = esDeYouTube && id.isNotBlank()
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
    val esProductora: Boolean = false,
    /** General, televisión pública o política y gobierno. Ver ClaseDeCuenta. */
    val clase: String = ClaseDeCuenta.GENERAL
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
    val enDirectorio: Boolean = false,
    /** General, televisión pública o política y gobierno. Ver ClaseDeCuenta. */
    val clase: String = ClaseDeCuenta.GENERAL
) {
    /** Los que no son de ningún creador: el canal oficial de la casa. */
    val canalesPropios: List<Canal> get() = canales.filter { it.creadorId == null }
}

/**
 * Una etiqueta del directorio, con lo que la lleva.
 *
 * Las crea y las enciende el equipo desde el panel; el servidor solo manda las
 * encendidas. Sustituyen a los temas fijos de antes (comida, cine, política…).
 * Una misma etiqueta puede estar en creadores, en medios y en canales.
 */
data class Etiqueta(
    val id: String = "",
    val nombre: String = "",
    val creadores: Set<String> = emptySet(),
    val productoras: Set<String> = emptySet(),
    val canales: Set<String> = emptySet()
)

/**
 * Un canal de YouTube tal como sale en el listado de canales del directorio:
 * el canal y lo que hace falta para pintar su fila sin buscar nada más.
 */
data class CanalListado(
    val canal: Canal,
    /** Cómo se llama el canal, en mayúsculas. Va primero en su fila. Ver Canal.nombreEnPantalla. */
    val nombreCanal: String,
    /** De quién es: su creador o, si no tiene, su medio. Va debajo del nombre. */
    val dueno: String,
    val fotoUrl: String? = null,
    /** El tema de su dueño, para filtrar igual que a los creadores. */
    val categoria: String = "otros",
    /** "Del medio Estudio X", "Con Ana y Luis"… lo que haya; vacío si es solo de su creador. */
    val deQuien: String = "",
    /** Es el canal propio de un medio, sin creador. */
    val duenoEsMedio: Boolean = false,
    /** Su tipo de cuenta: el suyo o el de su dueño. */
    val clase: String = ClaseDeCuenta.GENERAL
) {
    /** En una frase, para un aviso: "Clips, de Juan Pérez", o solo el dueño si se llama igual. */
    val titulo: String
        get() = if (nombreCanal.equals(dueno, ignoreCase = true)) dueno else "$nombreCanal, de $dueno"
}

/**
 * Todos los canales de YouTube del directorio, uno por canal y por orden
 * alfabético.
 *
 * Un mismo canal llega repetido: en la ficha de su dueño, en la de cada
 * creador con el que aparece y en la de su productora. Aquí sale una vez.
 * Los de un servidor anterior a las fichas de canal no traen id y no se
 * listan: sin id no hay ficha que abrir.
 */
fun canalesDelDirectorio(creadores: List<Creador>, productoras: List<Productora>): List<CanalListado> {
    val personas = creadores.filter { !it.esProductora }.associateBy { it.id }
    // Una productora puede llegar por su lista o como una fila más de creadores.
    val casas = productoras.associateBy { it.id }
    val casasComoFila = creadores.filter { it.esProductora }.associateBy { it.id }

    val vistos = LinkedHashMap<String, Canal>()
    (creadores.flatMap { it.canales } + productoras.flatMap { it.canales })
        .filter { it.esDeYouTube && it.id.isNotBlank() }
        .forEach { vistos.putIfAbsent(it.id, it) }

    return vistos.values.map { canal ->
        val dueno = canal.creadorId?.let { personas[it] }
        val casa = canal.productoraId?.let { casas[it] }
        val casaFila = canal.productoraId?.let { casasComoFila[it] }
        val nombreCasa = casa?.nombre ?: casaFila?.name

        val quien = dueno?.name ?: nombreCasa ?: "Canal de YouTube"
        val con = canal.tambien.mapNotNull { personas[it]?.name }.filter { it != dueno?.name }

        CanalListado(
            canal = canal,
            nombreCanal = canal.nombreEnPantalla ?: quien.uppercase(),
            dueno = quien,
            fotoUrl = dueno?.photoUrl ?: casa?.logoUrl ?: casaFila?.photoUrl,
            categoria = dueno?.category ?: casaFila?.category ?: "otros",
            deQuien = listOfNotNull(
                // Solo si no es ya el dueño: en su canal propio, la casa es quien firma.
                nombreCasa?.takeIf { dueno != null }?.let { "Del medio $it" },
                con.takeIf { it.isNotEmpty() }?.let { "Con ${enumerar(it)}" }
            ).joinToString(" · "),
            duenoEsMedio = dueno == null && nombreCasa != null,
            clase = canal.clase
        )
    }.sortedWith(compareBy({ it.nombreCanal.lowercase() }, { it.dueno.lowercase() }))
}

/**
 * El tipo de cuenta de un creador, un medio o un canal, que el equipo elige
 * en el panel. Las listas del Directorio van en este orden: primero lo
 * general, después la televisión pública y al final la política y el gobierno.
 */
object ClaseDeCuenta {
    const val GENERAL = "general"
    const val TELEVISION_PUBLICA = "television_publica"
    const val POLITICA = "politica"

    val ORDEN = listOf(GENERAL, TELEVISION_PUBLICA, POLITICA)

    /** Una que esta versión no conoce cuenta como general. */
    fun normal(clase: String?): String = if (clase in ORDEN) clase!! else GENERAL
}

/**
 * Una lista del Directorio en grupos, cada uno con su título (o sin él).
 *
 * Si hay de más de un tipo de cuenta, un grupo por tipo, en el orden de
 * ClaseDeCuenta, con el título de [titulos]; dentro de cada uno, arriba lo que
 * sigue. Si todo es del mismo tipo, como antes: lo que sigue con
 * [tituloSigues] y lo demás con [tituloDemas] (sin títulos si solo hay de una
 * de las dos). En todos los casos, dentro de cada parte, lo más nuevo arriba.
 */
fun <T> enGruposDelDirectorio(
    lista: List<T>,
    clase: (T) -> String,
    sigue: (T) -> Boolean,
    cuando: (T) -> Instant?,
    titulos: Map<String, String>,
    tituloSigues: String,
    tituloDemas: String
): List<Pair<String?, List<T>>> {
    val ordenada = porNovedad(lista, cuando)
    val porClase = ClaseDeCuenta.ORDEN
        .map { c -> c to ordenada.filter { ClaseDeCuenta.normal(clase(it)) == c } }
        .filter { it.second.isNotEmpty() }

    if (porClase.size <= 1) {
        val (siguidos, demas) = ordenada.partition(sigue)
        return if (siguidos.isNotEmpty() && demas.isNotEmpty())
            listOf(tituloSigues to siguidos, tituloDemas to demas)
        else listOf(null to ordenada)
    }
    return porClase.map { (c, grupo) ->
        val (siguidos, demas) = grupo.partition(sigue)
        titulos[c] to (siguidos + demas)
    }
}

/** Cuándo publicó por última vez alguien con estos canales; null si nunca (o no se sabe). */
fun ultimoVideoDe(canales: List<Canal>, ultimos: Map<String, Instant>): Instant? =
    canales.mapNotNull { ultimos[it.id] }.maxOrNull()

/**
 * Lo más nuevo arriba: ordena por la fecha del último video, del más reciente
 * al más viejo. Quien nunca ha publicado va al final. Entre iguales se queda
 * el orden que traía la lista (alfabético).
 */
fun <T> porNovedad(lista: List<T>, cuando: (T) -> Instant?): List<T> =
    lista.sortedWith(compareBy(nullsLast(reverseOrder<Instant>())) { cuando(it) })

/** "Ana", "Ana y Luis", "Ana, Luis y Rosa". */
private fun enumerar(nombres: List<String>): String =
    if (nombres.size <= 1) nombres.joinToString()
    else nombres.dropLast(1).joinToString(", ") + " y " + nombres.last()

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
    val productoraNombre: String? = null,
    /** El canal donde salió: su ficha se abre desde la del video. */
    val canalId: String? = null,
    /**
     * Cuántas vistas tenía en YouTube la última vez que el servidor preguntó.
     * Solo viene en «Lo más visto»; en Novedades y las fichas es null.
     */
    val vistas: Long? = null
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

/**
 * Lo que trae una notificación al tocarla: el video del que avisa y a dónde
 * llevaba. Con esto se abre su ficha en la app; lo demás (título, miniatura,
 * de quién es) se le pide al servidor.
 *
 * @param tipo "publicacion", o "movido" si avisa de que el video cambió de lugar.
 */
data class VideoDeAviso(
    val videoId: String,
    val plataforma: String = "youtube",
    val url: String? = null,
    val tipo: String? = null
) {
    val esMovido: Boolean get() = tipo == "movido"

    /** A dónde lleva "Ver el video" mientras no llega lo que dice el servidor. */
    val destino: Destino
        get() = Destino(plataforma, url, videoId.takeIf { !esMovido })
}

/**
 * La ficha del video abierta desde una notificación.
 *
 * @param publicacion lo que dice el servidor; null mientras llega, o si no
 *                    se pudo leer: entonces se usa lo que traía el aviso.
 */
data class FichaDeVideo(
    val aviso: VideoDeAviso? = null,
    val publicacion: Publicacion? = null,
    val cargando: Boolean = false
) {
    val videoId: String? get() = aviso?.videoId

    /** A dónde lleva "Ver el video": lo del servidor si llegó (puede haberse movido después). */
    val destino: Destino? get() = publicacion?.destino ?: aviso?.destino

    val esMovido: Boolean get() = publicacion?.fueMovido ?: (aviso?.esMovido == true)
}


data class Perfil(
    val favoritos: List<String> = emptyList(),
    val escalaTexto: String = "normal",
    val tema: String = "sistema",
    val avisos: Boolean = true,
    /** Productoras que sigue. Los creadores van en `favoritos`. */
    val productoras: List<String> = emptyList(),
    /**
     * Canales de YouTube que sigue por sí solos. Seguir a un creador o a un
     * medio es seguir todos sus canales, y esos no vienen aquí.
     */
    val canales: List<String> = emptyList(),
    /** Quiere ver los videos cortos. Solo cuenta si están disponibles. */
    val cortos: Boolean = true,
    /**
     * Quiere una notificación por cada video corto. Apagado de entrada: verlos
     * en su apartado no trae avisos si no se piden. Un servidor anterior no
     * lo manda, y entonces es que no.
     */
    val avisosCortos: Boolean = false,
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
    val compraDisponible: Boolean = false,
    /**
     * El equipo encendió desde el panel la comprobación de suscripciones de
     * YouTube. Un servidor anterior no lo manda, y entonces es que no.
     */
    val youtube: Boolean = false,
    /**
     * El equipo le activó el chat a esta cuenta: Ajustes enseña "Hablar con
     * el equipo". Un servidor anterior no lo manda, y entonces es que no.
     */
    val chat: Boolean = false,
    /** El topic de FCM del aviso del chat. Solo viene con el chat activado. */
    val chatAviso: String? = null,
    /** Mensajes del equipo que todavía no ha leído. */
    val chatSinLeer: Long = 0,
    /** Esta cuenta es del equipo: puede entrar al panel y contestar el chat. */
    val esAdmin: Boolean = false,
    /**
     * Solo para administradores, y solo si el servidor tiene el chat: el
     * topic del aviso de "una cuenta te escribió". Con él, Ajustes enseña la
     * bandeja de conversaciones.
     */
    val chatAvisoEquipo: String? = null,
    /** Solo para administradores: conversaciones con algo sin leer. */
    val chatEquipoSinLeer: Long = 0,
    /**
     * Puede recomendar canales: Ajustes enseña "Recomendar un canal". Lo
     * decide el equipo desde el panel. Un servidor anterior no lo manda, y
     * entonces es que no.
     */
    val recomendar: Boolean = false,
    /**
     * El equipo la autorizó a agregar creadores, medios y canales desde la
     * app, sin aprobación: Ajustes enseña "Agregar al directorio". Un
     * servidor anterior no lo manda, y entonces es que no.
     */
    val editor: Boolean = false,
    /**
     * Ve el apartado de Stickers. Lo decide el equipo desde el panel. Un
     * servidor anterior no lo manda, y entonces es que no.
     */
    val stickers: Boolean = false
) {
    /**
     * La app ofrece y hace la comprobación de YouTube: esta compilación la
     * trae y el panel la tiene encendida. Se apaga y se enciende sin publicar
     * otra versión, que es lo que hace falta mientras Google no aprueba la
     * verificación del permiso.
     */
    val youtubeActivo: Boolean
        get() = BuildConfig.SUSCRIPCIONES_YOUTUBE && youtube

    /** Sigue a alguien: un creador, un medio o un canal suelto. */
    val sigueAAlguien: Boolean
        get() = favoritos.isNotEmpty() || productoras.isNotEmpty() || canales.isNotEmpty()

    /** Sigue ese canal por sí solo. */
    fun sigueCanal(canal: Canal): Boolean = canal.id.isNotBlank() && canal.id in canales

    /**
     * Ya le llegan los videos de ese canal sin seguirlo suelto: porque sigue
     * a su creador, a su medio o a alguno de los creadores con los que
     * aparece. Es la misma cuenta que hace el servidor para Novedades.
     */
    fun loRecibePorOtros(canal: Canal): Boolean =
        (canal.creadorId != null && canal.creadorId in favoritos) ||
            (canal.productoraId != null && canal.productoraId in productoras) ||
            canal.tambien.any { it in favoritos }

    /** Le llegan los videos de ese canal, por la vía que sea. */
    fun recibe(canal: Canal): Boolean = sigueCanal(canal) || loRecibePorOtros(canal)

    /** Ve anuncios: el equipo los tiene encendidos y la persona no los quitó. */
    val veAnuncios: Boolean get() = anuncios && !sinAnuncios

    /** Ve los videos cortos: el equipo los permite y la persona no los apagó. */
    val veCortos: Boolean get() = cortosDisponibles && cortos

    /** Le llegan avisos de los videos cortos: los ve y además los pidió. */
    val recibeAvisosCortos: Boolean get() = veCortos && avisosCortos
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
) {
    /** El servidor contestó canal por canal, y no solo por creador. */
    val sabeDeCanales: Boolean
        get() = canalesSuscritos.isNotEmpty() || canalesNoSuscritos.isNotEmpty()

    /**
     * Canales a los que se estaba suscrito en `antes` y aquí ya no.
     *
     * Cuenta solo lo que YouTube contestó las dos veces: un canal que salió
     * del directorio, o del que ahora no se sabe nada, no es una baja.
     */
    fun canalesPerdidosDesde(antes: SuscripcionesYouTube): Set<String> =
        antes.canalesSuscritos intersect canalesNoSuscritos

    /** Lo mismo por creador, para un servidor que no sabe de canales. */
    fun creadoresPerdidosDesde(antes: SuscripcionesYouTube): Set<String> =
        antes.suscritos intersect noSuscritos
}

/**
 * La persona dejó de estar suscrita en YouTube a alguien del directorio.
 *
 * Solo se sabe al comprobar, que es al abrir la app y al volver de YouTube:
 * el servidor no puede preguntarle a YouTube con la app cerrada, así que esto
 * no llega como notificación sino como un aviso en pantalla.
 */
data class BajasDeYouTube(
    /** Cómo se llama cada canal en el Directorio: "Juan Pérez · Clips". */
    val nombres: List<String>,
    /** El canal, si es uno solo: el aviso ofrece ir a su ficha. */
    val canalId: String? = null
) {
    val texto: String
        get() {
            // Con muchos de golpe, la lista entera no cabe en un aviso.
            val quienes = if (nombres.size <= TOPE_DE_NOMBRES) enumerar(nombres)
            else enumerar(nombres.take(TOPE_DE_NOMBRES - 1) + "${nombres.size - TOPE_DE_NOMBRES + 1} canales más")

            return "Antes estabas suscrito en YouTube a $quienes, y ahora ya no. " +
                "Si fue sin querer, abre su canal en YouTube y vuelve a suscribirte. " +
                "Los avisos de esta app no cambian."
        }

    private companion object {
        const val TOPE_DE_NOMBRES = 6
    }
}

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
    /** Hay alguna respuesta de YouTube, y por tanto etiquetas que pintar. */
    val haySuscripciones: Boolean
        get() = suscripciones.sabeDeCanales ||
            suscripciones.suscritos.isNotEmpty() || suscripciones.noSuscritos.isNotEmpty()

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

/**
 * Un canal que la persona recomendó para el directorio.
 *
 * [titulo] y [fotoUrl] son los del canal en YouTube, si el servidor lo pudo
 * identificar; si la persona escribió solo un nombre, vienen en null y se
 * enseña lo que escribió.
 */
data class Recomendacion(
    val id: Long,
    val texto: String,
    val motivo: String? = null,
    val titulo: String? = null,
    val fotoUrl: String? = null,
    /** pendiente | agregado | descartado */
    val estado: String = PENDIENTE,
    /** El canal ya está en el directorio, lo haya marcado el equipo o no. */
    val enDirectorio: Boolean = false,
    val creadoEn: Instant? = null
) {
    val nombre: String get() = titulo ?: texto

    /** En palabras, para la persona que la mandó. */
    val estadoEnPalabras: String
        get() = when {
            enDirectorio || estado == AGREGADO -> "Ya está en el directorio"
            estado == DESCARTADO -> "El equipo lo revisó y por ahora no lo agregó"
            else -> "El equipo todavía no lo revisa"
        }

    companion object {
        const val PENDIENTE = "pendiente"
        const val AGREGADO = "agregado"
        const val DESCARTADO = "descartado"
    }
}

/** Lo que mandó esta cuenta y cuántas puede mandar todavía hoy. */
data class MisRecomendaciones(
    val recomendaciones: List<Recomendacion> = emptyList(),
    val quedanHoy: Int = 0
)

/**
 * Un mensaje del chat con el equipo, ya en claro: el servidor lo guarda
 * cifrado y lo descifra al entregarlo. En el teléfono no se guarda.
 *
 * @param autor quién lo escribió. A la persona siempre le llega "Equipo de
 *              Voces"; a un administrador, "Tú" o el correo de quien fue.
 */
data class MensajeDelChat(
    val id: Long,
    val texto: String,
    val deAdmin: Boolean,
    val autor: String?,
    val creadoEn: Instant,
    /** Cuándo lo vio el otro lado. Null mientras no lo ha abierto. */
    val leidoEn: Instant?
)

/** Una conversación en la bandeja de un administrador. */
data class ConversacionDelChat(
    val id: String,
    val nombre: String,
    val ultimo: String?,
    val ultimoEn: Instant?,
    val ultimoDeAdmin: Boolean,
    val sinLeer: Long
)

// --- Agregar al directorio desde la app -------------------------------------

/** Qué se agrega. La clave es la que usa el servidor. */
enum class TipoDeAlta(val clave: String) {
    CREADOR("creador"), MEDIO("medio"), CANAL("canal");

    companion object {
        fun de(clave: String?): TipoDeAlta? = entries.firstOrNull { it.clave == clave }
    }
}

/** Un canal de YouTube encontrado al buscarlo para agregarlo. */
data class CanalEncontrado(
    val channelId: String,
    val titulo: String? = null,
    val fotoUrl: String? = null,
    val handle: String? = null,
    /** Suscriptores tal como los da YouTube, en texto. Null si los oculta. */
    val suscriptores: String? = null,
    /** Nombre de quien ya lo tiene en el directorio. Null si no está. */
    val yaEsDe: String? = null
) {
    val libre: Boolean get() = yaEsDe == null
}

/** Lo que se acaba de agregar: a qué ficha llevar a la persona. */
data class Agregado(
    val tipo: TipoDeAlta,
    val id: String,
    val nombre: String,
    val mensaje: String,
    /** Algo que conviene saber aunque haya salido bien. Null si nada. */
    val aviso: String? = null
)

data class AltaDesdeApp(
    val tipo: TipoDeAlta,
    val id: String,
    val nombre: String,
    val creadoEn: Instant? = null
)

data class LoQueAgregue(
    val quedanHoy: Int = 0,
    val altas: List<AltaDesdeApp> = emptyList()
)

/**
 * Un paquete de stickers de un creador, como lo da el servidor. Las
 * direcciones son relativas al servidor y llevan la versión: si el equipo
 * cambia algo en el panel, cambia la versión y la app lo vuelve a bajar.
 */
data class PaqueteDeStickers(
    val id: String,
    val creadorId: String,
    /** El nombre del creador. WhatsApp lo enseña como "publicado por". */
    val creador: String,
    val nombre: String,
    val animado: Boolean = false,
    val version: Int = 1,
    val icono: String,
    val stickers: List<StickerDelPaquete> = emptyList()
)

data class StickerDelPaquete(
    val id: String,
    val emojis: List<String> = emptyList(),
    val imagen: String
)
