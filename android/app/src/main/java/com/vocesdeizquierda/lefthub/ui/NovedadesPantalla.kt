package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.Instant
import com.vocesdeizquierda.lefthub.anuncios.BannersDelFeed
import com.vocesdeizquierda.lefthub.anuncios.Huecos
import com.vocesdeizquierda.lefthub.data.Abiertos
import com.vocesdeizquierda.lefthub.data.Cifras
import com.vocesdeizquierda.lefthub.data.Publicacion
import com.vocesdeizquierda.lefthub.data.VideosDeCanal
import com.vocesdeizquierda.lefthub.enlaces.Enrutador
import java.text.SimpleDateFormat
import java.util.Locale

// Nada de scroll infinito ni recomendaciones. Aquí solo aparece lo que
// publicaron las personas que el usuario eligió seguir, en orden de tiempo.
// Esa previsibilidad es la propuesta de valor entera.
//
// Los videos cortos (Shorts) no van en esa lista. Si el equipo los permite y
// la persona no los apagó, tienen su propio apartado, al que se entra a
// propósito con un botón; si no, aquí no hay ni rastro de ellos.
//
// «Lo más visto» es el otro apartado: lo que más se ha visto de todo el
// directorio, lo siga la persona o no, en las últimas 24 horas o en la
// semana. Sirve para descubrir, pero no se mezcla con lo de arriba: la
// lista de siempre sigue siendo solo de quienes eligió. Y aunque no siga a
// nadie todavía, lo más visto se puede ver.
//
// Los anuncios, cuando los hay, van entre los videos como una tarjeta más,
// con otra forma y con la palabra "Publicidad" escrita arriba: nadie debe
// tocar uno creyendo que es un video. Dónde cae cada uno lo decide Huecos.
//
// Arriba de la lista, hasta que la persona la da por leída, va la explicación
// de cómo ver un video en la tele. Después vive en Ajustes.

@Composable
fun NovedadesPantalla(
    publicaciones: List<Publicacion>,
    hayFavoritos: Boolean,
    cuantosFavoritos: Int,
    onIrAlDirectorio: () -> Unit,
    cuantasProductoras: Int = 0,
    /** Canales de YouTube que sigue sueltos, sin seguir a su creador ni a su medio. */
    cuantosCanales: Int = 0,
    /** Los videos cortos de quienes sigue. Solo se enseñan con `verCortos`. */
    cortos: List<Publicacion> = emptyList(),
    /** El equipo permite los cortos y la persona no los apagó en Ajustes. */
    verCortos: Boolean = false,
    /** «Lo más visto», por periodo ("hoy", "semana"). Ver AppViewModel.cargarMasVistas. */
    masVistas: Map<String, VideosDeCanal> = emptyMap(),
    /** Pide lo más visto de ese periodo: al entrar al apartado o al cambiar de periodo. */
    onCargarMasVistas: (String) -> Unit = {},
    /** Los videos que ya abrió desde la app: su tarjeta se ve distinta. */
    abiertos: Set<String> = emptySet(),
    /**
     * Los anuncios de la lista. Null si esta persona no ve anuncios: el
     * equipo los tiene apagados, los quitó, o todavía no se pueden pedir.
     */
    anuncios: BannersDelFeed? = null,
    /** El botón "Quitar los anuncios" que va debajo de cada uno. */
    onQuitarAnuncios: () -> Unit = {},
    /** Todavía no ha leído cómo ver un video en la tele. Ver AyudaTele. */
    ayudaTele: Boolean = false,
    onAyudaTeleEntendida: () -> Unit = {}
) {
    val contexto = LocalContext.current

    // En qué apartado está, y en «Lo más visto», de qué periodo. Sobreviven
    // a girar el teléfono; al abrir la app siempre se empieza por los videos
    // de siempre.
    var elegido by rememberSaveable { mutableStateOf(Apartado.VIDEOS) }
    var periodo by rememberSaveable { mutableStateOf(PERIODO_HOY) }
    // Si los cortos se apagan mientras se estaban viendo, se vuelve a los videos.
    val apartado = if (elegido == Apartado.CORTOS && !verCortos) Apartado.VIDEOS else elegido
    val delPeriodo = masVistas[periodo] ?: VideosDeCanal(periodo, cargando = true)
    val lista = when (apartado) {
        Apartado.VIDEOS -> publicaciones
        Apartado.CORTOS -> cortos
        Apartado.MAS_VISTO -> delPeriodo.lista
    }

    // Lo más visto cambia a lo largo del día: se pide cada vez que se entra.
    LaunchedEffect(apartado, periodo) {
        if (apartado == Apartado.MAS_VISTO) onCargarMasVistas(periodo)
    }

    if (!hayFavoritos && apartado != Apartado.MAS_VISTO) {
        Vacio(
            titulo = "Todavía no sigues a nadie",
            mensaje = "Elige a los creadores y medios que te interesan y te avisaremos aquí cada vez que publiquen algo nuevo.",
            accion = {
                Column {
                    BotonGrande("Ver el directorio", onClick = onIrAlDirectorio)
                    BotonGrande(
                        "Ver lo más visto",
                        subtitulo = "De todos los creadores del directorio",
                        variante = VarianteBoton.SECUNDARIO,
                        onClick = { elegido = Apartado.MAS_VISTO }
                    )
                }
            }
        )
        return
    }

    // El anuncio ocupa el ancho de una tarjeta: la pantalla menos el margen
    // de la lista a cada lado, y menos el borde de la tarjeta, que no debe
    // pisar el anuncio ni un punto.
    val anchoDeAnuncio = LocalConfiguration.current.screenWidthDp - 2 * Espacio.md.value.toInt() - 2
    val cuantosAnuncios = if (anuncios != null) Huecos.cuantos(lista.size) else 0

    // Se piden solo los que caben en esta lista, y una sola vez: los que ya
    // están pedidos se quedan aunque la persona cambie de pestaña y vuelva.
    LaunchedEffect(anuncios, cuantosAnuncios, anchoDeAnuncio) {
        anuncios?.asegurar(contexto, cuantosAnuncios, anchoDeAnuncio)
    }

    LazyColumn(
        contentPadding = PaddingValues(Espacio.md),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                "Novedades",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = Espacio.lg)
            )
        }

        item {
            SelectorDeApartado(
                apartado = apartado,
                conCortos = verCortos,
                onElegir = { elegido = it }
            )
        }

        if (apartado == Apartado.MAS_VISTO) {
            item(key = "periodo") {
                ElegirPeriodo(periodo = periodo, onElegir = { periodo = it })
            }
            item(key = "explicacion") {
                Text(
                    "Lo que más se ha visto de todos los creadores del directorio, los sigas o no, " +
                        if (periodo == PERIODO_HOY) "en las últimas 24 horas." else "en los últimos 7 días.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Espacio.md)
                )
            }
        }

        // Cómo verlo en la tele. Solo con los videos de siempre y solo si
        // alguno se abre en YouTube, que es de donde sale el botón.
        if (ayudaTele && apartado == Apartado.VIDEOS && lista.any { it.destino.plataforma == "youtube" }) {
            item(key = "ayuda-tele") {
                TarjetaVerEnLaTele(onEntendido = onAyudaTeleEntendida)
            }
        }

        if (lista.isEmpty()) {
            item {
                when {
                    apartado == Apartado.MAS_VISTO && delPeriodo.cargando -> Text(
                        "Buscando lo más visto…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    apartado == Apartado.MAS_VISTO && delPeriodo.fallo -> Vacio(
                        titulo = "No se pudo traer lo más visto",
                        mensaje = "Revisa tu conexión e inténtalo otra vez.",
                        accion = {
                            BotonGrande("Intentar otra vez", onClick = { onCargarMasVistas(periodo) })
                        }
                    )

                    apartado == Apartado.MAS_VISTO -> Vacio(
                        titulo = if (periodo == PERIODO_HOY) "Nada todavía hoy" else "Nada esta semana",
                        mensaje = if (periodo == PERIODO_HOY)
                            "En las últimas 24 horas nadie del directorio ha publicado, o YouTube aún no cuenta sus vistas. Prueba con «Esta semana»."
                        else "En los últimos 7 días nadie del directorio ha publicado, o YouTube aún no cuenta sus vistas."
                    )

                    else -> Vacio(
                        titulo = if (apartado == Apartado.CORTOS) "Sin videos cortos por ahora"
                        else "Sin novedades por ahora",
                        mensaje = "Sigues a ${aQuienSigue(cuantosFavoritos, cuantasProductoras, cuantosCanales)}. " +
                            if (apartado == Apartado.CORTOS) "En cuanto publiquen un video corto, aparece aquí."
                            else "En cuanto publiquen algo, el aviso llega a este teléfono."
                    )
                }
            }
        }

        lista.forEachIndexed { posicion, publicacion ->
            item(key = publicacion.id) {
                TarjetaPublicacion(
                    publicacion = publicacion,
                    abierto = publicacion.videoId in abiertos,
                    // En «Lo más visto» cada tarjeta dice en qué lugar va y cuántas vistas tiene.
                    puesto = if (apartado == Apartado.MAS_VISTO) posicion + 1 else null,
                    onAbrir = {
                        val destino = publicacion.destino
                        Enrutador.abrirVideo(
                            contexto,
                            destino.plataforma,
                            destino.videoId,
                            destino.url,
                            campana = if (apartado == Apartado.MAS_VISTO) "mas_visto" else "novedades"
                        )
                        Abiertos.marcar(contexto, publicacion.videoId)
                    }
                    // Sin "El enlace no funciona": en Novedades la tarjeta
                    // queda solo con su botón de ver.
                )
            }

            // Después de este video, ¿toca anuncio? Solo se pinta si Google
            // ya lo entregó: mientras carga, o si no hay ninguno que dar, la
            // lista sigue como si nada, sin un recuadro vacío.
            val hueco = Huecos.tras(posicion)
            val banner = if (hueco != null) anuncios?.banners?.getOrNull(hueco) else null
            if (banner != null && banner.cargado) {
                item(key = "anuncio-$hueco") {
                    TarjetaAnuncio(banner = banner, onQuitar = onQuitarAnuncios)
                }
            }
        }
    }
}

/** Los apartados de Novedades. */
internal enum class Apartado { VIDEOS, MAS_VISTO, CORTOS }

internal const val PERIODO_HOY = "hoy"
internal const val PERIODO_SEMANA = "semana"

/**
 * Los apartados de Novedades: botones grandes con su nombre escrito; el que
 * está elegido va relleno. Tres en una fila no caben con letra grande, así
 * que con los cortos encendidos «Lo más visto» baja a su propia fila, a lo
 * ancho.
 */
@Composable
private fun SelectorDeApartado(apartado: Apartado, conCortos: Boolean, onElegir: (Apartado) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Espacio.md)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Espacio.sm),
            modifier = Modifier.fillMaxWidth()
        ) {
            BotonDeApartado("Videos", Apartado.VIDEOS, apartado, onElegir, Modifier.weight(1f))
            if (conCortos) {
                BotonDeApartado("Videos cortos", Apartado.CORTOS, apartado, onElegir, Modifier.weight(1f))
            } else {
                BotonDeApartado("Lo más visto", Apartado.MAS_VISTO, apartado, onElegir, Modifier.weight(1f))
            }
        }
        if (conCortos) {
            BotonDeApartado("Lo más visto", Apartado.MAS_VISTO, apartado, onElegir)
        }
    }
}

@Composable
private fun BotonDeApartado(
    titulo: String,
    este: Apartado,
    elegido: Apartado,
    onElegir: (Apartado) -> Unit,
    modifier: Modifier = Modifier
) {
    BotonGrande(
        titulo = titulo,
        modifier = modifier.semantics { selected = este == elegido },
        variante = if (este == elegido) VarianteBoton.PRIMARIO else VarianteBoton.SECUNDARIO,
        onClick = { onElegir(este) }
    )
}

/**
 * «Hoy» o «Esta semana», dentro de Lo más visto. Más pequeños que los
 * botones de arriba, porque van por debajo de ellos. El elegido va relleno
 * y además lleva una palomita: no depende solo del color.
 */
@Composable
private fun ElegirPeriodo(periodo: String, onElegir: (String) -> Unit) {
    val esquema = MaterialTheme.colorScheme
    val forma = RoundedCornerShape(50)

    Row(
        horizontalArrangement = Arrangement.spacedBy(Espacio.sm),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Espacio.sm)
    ) {
        listOf(PERIODO_HOY to "Hoy", PERIODO_SEMANA to "Esta semana").forEach { (clave, nombre) ->
            val esteElegido = clave == periodo
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Tactil.minimo)
                    .clip(forma)
                    .background(if (esteElegido) esquema.primary else esquema.surface)
                    .border(1.dp, if (esteElegido) esquema.primary else esquema.outline, forma)
                    .selectable(selected = esteElegido, role = Role.Tab, onClick = { onElegir(clave) })
                    .padding(horizontal = Espacio.md, vertical = Espacio.sm)
            ) {
                Text(
                    if (esteElegido) "✓ $nombre" else nombre,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (esteElegido) esquema.onPrimary else esquema.onSurface
                )
            }
        }
    }
}

/**
 * La tarjeta de un video. La usan Novedades y la ficha de un canal.
 *
 * Un video que la persona ya abrió se distingue de uno que no por tres cosas
 * a la vez, para que no dependa solo del color: la leyenda escrita ("Nuevo",
 * "Ya lo abriste" o "Ya lo viste"), el fondo de la tarjeta con la miniatura
 * apagada, y el botón, que deja de ir relleno.
 */
@Composable
internal fun TarjetaPublicacion(
    publicacion: Publicacion,
    onAbrir: () -> Unit,
    /** "El enlace no funciona". null: la tarjeta no lo enseña (en Novedades). */
    onReportar: (() -> Unit)? = null,
    abierto: Boolean = false,
    /** En «Lo más visto», en qué lugar va: la tarjeta lo dice con sus vistas. */
    puesto: Int? = null
) {
    val esquema = MaterialTheme.colorScheme
    val destino = publicacion.destino
    val lugar = puesto?.let { n ->
        listOfNotNull("Número $n", publicacion.vistas?.let { Cifras.vistas(it) }).joinToString(" · ")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Espacio.lg)
            .clip(RoundedCornerShape(20.dp))
            .background(if (abierto) esquema.surfaceVariant else esquema.surface)
            .border(1.dp, esquema.outline, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onAbrir)
                .semantics {
                    contentDescription =
                        (lugar?.let { "$it. " } ?: "") +
                        "Abrir el video ${publicacion.title} de ${publicacion.firma}. " +
                        estadoEnPalabras(abierto, publicacion.videoId in Abiertos.aMano, conPunto = true)
                }
        ) {
            if (!publicacion.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = publicacion.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(esquema.surfaceVariant)
                        .alpha(if (abierto) 0.45f else 1f)
                )
            }

            Column(Modifier.padding(Espacio.md)) {
                if (lugar != null) {
                    Text(
                        lugar,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        color = esquema.primary,
                        modifier = Modifier.padding(bottom = Espacio.xs)
                    )
                }
                EtiquetaDeEstado(abierto, visto = publicacion.videoId in Abiertos.aMano)
                Text(
                    // Quién lo publicó y, si su canal es de una productora, cuál.
                    listOf(publicacion.firma, tiempoRelativo(publicacion.publishedAt))
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = esquema.onSurfaceVariant
                )
                Text(
                    publicacion.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = esquema.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )

                if (publicacion.fueMovido) {
                    Text(
                        "Este video cambió de lugar. El botón te lleva al sitio nuevo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = esquema.primary,
                        modifier = Modifier.padding(top = Espacio.sm)
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = Espacio.md).padding(bottom = Espacio.sm)) {
            BotonGrande(
                titulo = when {
                    publicacion.esEnVivo -> "Ver en vivo"
                    publicacion.esCorto -> "Ver el video corto"
                    else -> "Ver el video"
                },
                subtitulo = "Se abre en ${Enrutador.nombreDe(destino.plataforma)}",
                // Un directo que sigue al aire conserva el botón relleno
                // aunque ya se haya abierto: es lo único que no puede esperar.
                variante = if (abierto && !publicacion.esEnVivo) VarianteBoton.SECUNDARIO
                else VarianteBoton.PRIMARIO,
                onClick = onAbrir
            )

            if (onReportar != null) {
                TextButton(
                    onClick = onReportar,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .heightIn(min = Tactil.minimo)
                ) {
                    Text(
                        "El enlace no funciona",
                        style = MaterialTheme.typography.bodySmall,
                        color = esquema.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * "Nuevo", "✓ Ya lo abriste" o "✓ Ya lo viste": lo que dice la leyenda.
 * [conPunto] es la versión para el lector de pantalla, sin la palomita.
 */
internal fun estadoEnPalabras(abierto: Boolean, visto: Boolean, conPunto: Boolean = false): String =
    when {
        !abierto -> if (conPunto) "Nuevo." else "Nuevo"
        visto -> if (conPunto) "Ya lo viste." else "✓ Ya lo viste"
        else -> if (conPunto) "Ya lo abriste." else "✓ Ya lo abriste"
    }

/**
 * La leyenda que dice si el video ya se abrió o se vio. Va escrita, no solo
 * pintada: quien no distingue bien los colores la lee igual.
 *
 * @param visto la persona lo marcó con "Ya lo vi": dice "Ya lo viste" en vez
 *              de "Ya lo abriste". Solo cuenta con [abierto].
 */
@Composable
internal fun EtiquetaDeEstado(abierto: Boolean, visto: Boolean = false) {
    val esquema = MaterialTheme.colorScheme
    val forma = RoundedCornerShape(8.dp)

    Text(
        estadoEnPalabras(abierto, visto),
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        color = if (abierto) esquema.onSurface else esquema.onPrimary,
        modifier = Modifier
            .padding(bottom = Espacio.sm)
            .clip(forma)
            .background(if (abierto) esquema.surface else esquema.primary)
            .then(if (abierto) Modifier.border(1.dp, esquema.outline, forma) else Modifier)
            .padding(horizontal = Espacio.sm, vertical = Espacio.xs)
    )
}

/**
 * Un video en una sola fila: miniatura pequeña, título y cuándo salió. Es la
 * versión corta de la tarjeta de Novedades, para el mini feed de la ficha de
 * un creador o de un medio. Lleva su misma leyenda de "Nuevo" o "Ya lo
 * abriste". Toda la fila se toca y abre el video.
 *
 * @param sinFirmarPor el nombre de quien es la ficha: de sus propios videos
 *                     no hace falta repetirlo; de los que salieron en el
 *                     canal de otro, sí se dice de quién.
 */
@Composable
internal fun FilaVideo(
    publicacion: Publicacion,
    onAbrir: () -> Unit,
    abierto: Boolean = false,
    sinFirmarPor: String? = null
) {
    val esquema = MaterialTheme.colorScheme
    val detalle = listOf(
        if (publicacion.esEnVivo) "En vivo" else "",
        publicacion.firma.takeIf { it != sinFirmarPor }.orEmpty(),
        tiempoRelativo(publicacion.publishedAt)
    ).filter { it.isNotBlank() }.joinToString(" · ")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Espacio.md),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Espacio.sm)
            .clip(RoundedCornerShape(12.dp))
            .background(if (abierto) esquema.surfaceVariant else esquema.surface)
            .border(1.dp, esquema.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onAbrir)
            .heightIn(min = Tactil.principal)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "Abrir el video ${publicacion.title}. $detalle. " +
                    estadoEnPalabras(abierto, publicacion.videoId in Abiertos.aMano, conPunto = true)
            }
            .padding(Espacio.sm)
    ) {
        if (!publicacion.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = publicacion.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(112.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(esquema.surfaceVariant)
                    .alpha(if (abierto) 0.45f else 1f)
            )
        }
        Column(Modifier.weight(1f)) {
            // La misma leyenda que en Novedades: "Nuevo", "✓ Ya lo abriste" o
            // "✓ Ya lo viste".
            EtiquetaDeEstado(abierto, visto = publicacion.videoId in Abiertos.aMano)
            Text(
                publicacion.title,
                style = MaterialTheme.typography.bodyMedium,
                color = esquema.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (detalle.isNotBlank()) {
                Text(
                    detalle,
                    style = MaterialTheme.typography.bodySmall,
                    color = esquema.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** "2 creadores", "1 medio" o "2 creadores y 1 medio". */
private fun aQuienSigue(creadores: Int, productoras: Int, canales: Int = 0): String = enumerar(
    listOfNotNull(
        "$creadores ${if (creadores == 1) "creador" else "creadores"}".takeIf { creadores > 0 },
        "$productoras ${if (productoras == 1) "medio" else "medios"}".takeIf { productoras > 0 },
        "$canales ${if (canales == 1) "canal" else "canales"}".takeIf { canales > 0 }
    )
)

/**
 * Fechas en palabras. "hace 2 horas" se entiende de un vistazo; una marca
 * como 09/09/2026 15:04 obliga a hacer la resta mentalmente.
 */
internal fun tiempoRelativo(marca: Instant?): String {
    val fecha = marca?.let { java.util.Date.from(it) } ?: return ""
    val minutos = (System.currentTimeMillis() - fecha.time) / 60_000

    return when {
        minutos < 2 -> "hace un momento"
        minutos < 60 -> "hace $minutos minutos"
        minutos < 120 -> "hace una hora"
        minutos < 1440 -> "hace ${minutos / 60} horas"
        minutos < 2880 -> "ayer"
        minutos < 10080 -> "hace ${minutos / 1440} días"
        else -> SimpleDateFormat("d 'de' MMMM", Locale("es", "MX")).format(fecha)
    }
}
