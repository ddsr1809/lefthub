package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.vocesdeizquierda.lefthub.data.Publicacion
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
    onReportar: (Publicacion) -> Unit,
    cuantasProductoras: Int = 0,
    /** Los videos cortos de quienes sigue. Solo se enseñan con `verCortos`. */
    cortos: List<Publicacion> = emptyList(),
    /** El equipo permite los cortos y la persona no los apagó en Ajustes. */
    verCortos: Boolean = false,
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

    // En qué apartado está. Sobrevive a girar el teléfono; al abrir la app
    // siempre se empieza por los videos de siempre.
    var enCortos by rememberSaveable { mutableStateOf(false) }
    // Si los cortos se apagan mientras se estaban viendo, se vuelve a los videos.
    val viendoCortos = verCortos && enCortos
    val lista = if (viendoCortos) cortos else publicaciones

    if (!hayFavoritos) {
        Vacio(
            titulo = "Todavía no sigues a nadie",
            mensaje = "Elige a los creadores y medios que te interesan y te avisaremos aquí cada vez que publiquen algo nuevo.",
            accion = { BotonGrande("Ver el directorio", onClick = onIrAlDirectorio) }
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

        if (verCortos) {
            item {
                SelectorDeApartado(
                    enCortos = viendoCortos,
                    onElegir = { enCortos = it }
                )
            }
        }

        // Cómo verlo en la tele. Solo con los videos de siempre y solo si
        // alguno se abre en YouTube, que es de donde sale el botón.
        if (ayudaTele && !viendoCortos && lista.any { it.destino.plataforma == "youtube" }) {
            item(key = "ayuda-tele") {
                TarjetaVerEnLaTele(onEntendido = onAyudaTeleEntendida)
            }
        }

        if (lista.isEmpty()) {
            item {
                Vacio(
                    titulo = if (viendoCortos) "Sin videos cortos por ahora"
                    else "Sin novedades por ahora",
                    mensaje = "Sigues a ${aQuienSigue(cuantosFavoritos, cuantasProductoras)}. " +
                        if (viendoCortos) "En cuanto publiquen un video corto, aparece aquí."
                        else "En cuanto publiquen algo, el aviso llega a este teléfono."
                )
            }
        }

        lista.forEachIndexed { posicion, publicacion ->
            item(key = publicacion.id) {
                TarjetaPublicacion(
                    publicacion = publicacion,
                    abierto = publicacion.videoId in abiertos,
                    onAbrir = {
                        val destino = publicacion.destino
                        Enrutador.abrirVideo(
                            contexto,
                            destino.plataforma,
                            destino.videoId,
                            destino.url,
                            campana = "novedades"
                        )
                        Abiertos.marcar(contexto, publicacion.videoId)
                    },
                    onReportar = { onReportar(publicacion) }
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

/**
 * Los dos apartados de Novedades. Dos botones grandes con su nombre escrito,
 * uno al lado del otro; el que está elegido va relleno.
 */
@Composable
private fun SelectorDeApartado(enCortos: Boolean, onElegir: (Boolean) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Espacio.sm),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Espacio.md)
    ) {
        BotonGrande(
            titulo = "Videos",
            modifier = Modifier
                .weight(1f)
                .semantics { selected = !enCortos },
            variante = if (!enCortos) VarianteBoton.PRIMARIO else VarianteBoton.SECUNDARIO,
            onClick = { onElegir(false) }
        )
        BotonGrande(
            titulo = "Videos cortos",
            modifier = Modifier
                .weight(1f)
                .semantics { selected = enCortos },
            variante = if (enCortos) VarianteBoton.PRIMARIO else VarianteBoton.SECUNDARIO,
            onClick = { onElegir(true) }
        )
    }
}

/**
 * La tarjeta de un video. La usan Novedades y la ficha de un canal.
 *
 * Un video que la persona ya abrió se distingue de uno que no por tres cosas
 * a la vez, para que no dependa solo del color: la leyenda escrita ("Nuevo" o
 * "Ya lo abriste"), el fondo de la tarjeta con la miniatura apagada, y el
 * botón, que deja de ir relleno.
 */
@Composable
internal fun TarjetaPublicacion(
    publicacion: Publicacion,
    onAbrir: () -> Unit,
    onReportar: () -> Unit,
    abierto: Boolean = false
) {
    val esquema = MaterialTheme.colorScheme
    val destino = publicacion.destino

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
                        "Abrir el video ${publicacion.title} de ${publicacion.firma}. " +
                        if (abierto) "Ya lo abriste." else "Nuevo."
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
                EtiquetaDeEstado(abierto)
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

/**
 * La leyenda que dice si el video ya se abrió. Va escrita, no solo pintada:
 * quien no distingue bien los colores la lee igual.
 */
@Composable
private fun EtiquetaDeEstado(abierto: Boolean) {
    val esquema = MaterialTheme.colorScheme
    val forma = RoundedCornerShape(8.dp)

    Text(
        if (abierto) "✓ Ya lo abriste" else "Nuevo",
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
                    if (abierto) "Ya lo abriste." else "Nuevo."
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
            // La misma leyenda que en Novedades: "Nuevo" o "✓ Ya lo abriste".
            EtiquetaDeEstado(abierto)
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
private fun aQuienSigue(creadores: Int, productoras: Int): String = listOfNotNull(
    "$creadores ${if (creadores == 1) "creador" else "creadores"}".takeIf { creadores > 0 },
    "$productoras ${if (productoras == 1) "medio" else "medios"}".takeIf { productoras > 0 }
).joinToString(" y ")

/**
 * Fechas en palabras. "hace 2 horas" se entiende de un vistazo; una marca
 * como 09/09/2026 15:04 obliga a hacer la resta mentalmente.
 */
private fun tiempoRelativo(marca: Instant?): String {
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
