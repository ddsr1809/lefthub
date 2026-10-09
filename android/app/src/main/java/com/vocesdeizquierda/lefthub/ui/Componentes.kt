package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vocesdeizquierda.lefthub.data.CanalListado
import com.vocesdeizquierda.lefthub.data.Creador
import com.vocesdeizquierda.lefthub.data.Productora

enum class VarianteBoton { PRIMARIO, SECUNDARIO, PELIGRO }

/**
 * Botón principal. 56dp de alto mínimo (no fijo: tiene que poder crecer si el
 * texto crece), etiqueta siempre escrita, nunca un icono suelto que haya que
 * interpretar.
 */
@Composable
fun BotonGrande(
    titulo: String,
    modifier: Modifier = Modifier,
    subtitulo: String? = null,
    variante: VarianteBoton = VarianteBoton.PRIMARIO,
    habilitado: Boolean = true,
    onClick: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme
    val fondo = when (variante) {
        VarianteBoton.PRIMARIO -> esquema.primary
        VarianteBoton.SECUNDARIO -> esquema.surface
        VarianteBoton.PELIGRO -> Color.Transparent
    }
    val tinte = when (variante) {
        VarianteBoton.PRIMARIO -> esquema.onPrimary
        VarianteBoton.SECUNDARIO -> esquema.onSurface
        VarianteBoton.PELIGRO -> esquema.error
    }
    val borde = when (variante) {
        VarianteBoton.PRIMARIO -> null
        VarianteBoton.SECUNDARIO -> esquema.outline
        VarianteBoton.PELIGRO -> esquema.error
    }

    Button(
        onClick = onClick,
        enabled = habilitado,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = fondo, contentColor = tinte),
        border = borde?.let { androidx.compose.foundation.BorderStroke(1.dp, it) },
        contentPadding = PaddingValues(horizontal = Espacio.lg, vertical = Espacio.md),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Tactil.principal)
            .padding(bottom = Tactil.separacion)
            // Una sola etiqueta para el lector de pantalla, en vez de dos
            // fragmentos sueltos que TalkBack leería como si no tuvieran
            // relación entre sí.
            .semantics(mergeDescendants = true) {
                contentDescription = if (subtitulo != null) "$titulo. $subtitulo" else titulo
            }
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(titulo, style = MaterialTheme.typography.labelLarge, color = tinte)
            if (subtitulo != null) {
                Text(
                    subtitulo,
                    style = MaterialTheme.typography.bodySmall,
                    color = tinte.copy(alpha = 0.75f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** Fila del directorio. El área táctil abarca la fila entera, no solo el texto. */
@Composable
fun FilaCreador(
    creador: Creador,
    siguiendo: Boolean,
    onAbrir: () -> Unit,
    onSeguir: () -> Unit,
    // true o false si se sabe; null si no (sin permiso, sin canal o sin
    // comprobar todavía). Con null la fila queda como siempre.
    suscritoEnYouTube: Boolean? = null
) {
    val lugares = creador.canalesVisibles.size
    val textoYouTube = when (suscritoEnYouTube) {
        true -> "Suscrito en YouTube"
        false -> "Sin suscripción en YouTube"
        null -> null
    }

    FilaDeDirectorio(
        nombre = creador.name,
        fotoUrl = creador.photoUrl,
        // Sin el tema fijo de antes: ahora el directorio se agrupa con las
        // etiquetas que el equipo enciende, y van arriba, como filtro.
        detalle = "$lugares ${if (lugares == 1) "lugar" else "lugares"} donde publica",
        siguiendo = siguiendo,
        onAbrir = onAbrir,
        onSeguir = onSeguir,
        descripcionAlAbrir = "${creador.name}. " +
            (textoYouTube?.let { "$it. " } ?: "") + "Ver su perfil.",
        lineaExtra = textoYouTube,
        lineaExtraDestacada = suscritoEnYouTube == true
    )
}

/** Fila de una productora en el directorio. Mismo trato que la de un creador. */
@Composable
fun FilaProductora(
    productora: Productora,
    siguiendo: Boolean,
    onAbrir: () -> Unit,
    onSeguir: () -> Unit
) {
    val canales = productora.canales.size
    val creadores = productora.creadores.size
    val detalle = listOfNotNull(
        "Medio",
        "$canales ${if (canales == 1) "canal" else "canales"}".takeIf { canales > 0 },
        "$creadores ${if (creadores == 1) "creador" else "creadores"}".takeIf { creadores > 0 }
    ).joinToString(" · ")

    FilaDeDirectorio(
        nombre = productora.nombre,
        fotoUrl = productora.logoUrl,
        detalle = detalle,
        siguiendo = siguiendo,
        onAbrir = onAbrir,
        onSeguir = onSeguir,
        descripcionAlAbrir = "${productora.nombre}, medio. Ver sus canales y creadores."
    )
}

/**
 * Fila de un canal de YouTube en el directorio.
 *
 * A un canal no se le sigue: se sigue a su creador o a su productora, y eso
 * se hace en su ficha. Por eso aquí el botón de la derecha no es "Seguir"
 * sino "Ver", que abre la ficha del canal igual que tocar la fila.
 */
@Composable
fun FilaCanal(
    listado: CanalListado,
    onAbrir: () -> Unit,
    suscritoEnYouTube: Boolean? = null
) {
    val arroba = listado.canal.handle?.takeIf { it.isNotBlank() }?.let { "@" + it.removePrefix("@") }
    val textoYouTube = when (suscritoEnYouTube) {
        true -> "Suscrito en YouTube"
        false -> "Sin suscripción en YouTube"
        null -> null
    }

    FilaDeDirectorio(
        nombre = listado.titulo,
        fotoUrl = listado.fotoUrl,
        detalle = listOfNotNull("Canal de YouTube", arroba, listado.deQuien.takeIf { it.isNotBlank() })
            .joinToString(" · "),
        siguiendo = false,
        onAbrir = onAbrir,
        onSeguir = onAbrir,
        descripcionAlAbrir = "Canal de YouTube de ${listado.titulo}. " +
            (textoYouTube?.let { "$it. " } ?: "") + "Ver el canal y sus últimos videos.",
        lineaExtra = textoYouTube,
        lineaExtraDestacada = suscritoEnYouTube == true,
        soloVer = true
    )
}

/**
 * Lo que comparten las filas: a la izquierda quién es, y al tocarlo se abre
 * su ficha; a la derecha, el botón de seguir (o el de ver, en un canal).
 */
@Composable
private fun FilaDeDirectorio(
    nombre: String,
    fotoUrl: String?,
    detalle: String,
    siguiendo: Boolean,
    onAbrir: () -> Unit,
    onSeguir: () -> Unit,
    descripcionAlAbrir: String,
    lineaExtra: String? = null,
    lineaExtraDestacada: Boolean = false,
    // El botón de la derecha dice "Ver" y hace lo mismo que tocar la fila.
    soloVer: Boolean = false
) {
    val esquema = MaterialTheme.colorScheme

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Espacio.sm)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Espacio.md),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = Tactil.minimo)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onAbrir)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = descripcionAlAbrir
                }
                .padding(vertical = Espacio.sm)
        ) {
            Avatar(fotoUrl, nombre)
            Column(Modifier.weight(1f)) {
                Text(
                    nombre,
                    style = MaterialTheme.typography.bodyMedium,
                    color = esquema.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    detalle,
                    style = MaterialTheme.typography.bodySmall,
                    color = esquema.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Línea aparte y con palabras: la diferencia no puede depender
                // solo de un color ni de un icono que haya que interpretar.
                if (lineaExtra != null && lineaExtraDestacada) {
                    EtiquetaYouTube(lineaExtra, Modifier.padding(top = Espacio.xs))
                } else if (lineaExtra != null) {
                    Text(
                        lineaExtra,
                        style = MaterialTheme.typography.bodySmall,
                        color = esquema.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.width(Tactil.separacion))

        OutlinedButton(
            onClick = onSeguir,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (siguiendo) esquema.primary else Color.Transparent,
                contentColor = if (siguiendo) esquema.onPrimary else esquema.onBackground
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (siguiendo) esquema.primary else esquema.outline
            ),
            modifier = Modifier
                .heightIn(min = Tactil.minimo)
                .widthIn(min = 100.dp)
                .semantics {
                    if (soloVer) {
                        role = Role.Button
                        contentDescription = "Ver el canal de $nombre"
                    } else {
                        role = Role.Switch
                        contentDescription = if (siguiendo)
                            "Dejar de recibir avisos de $nombre"
                        else
                            "Recibir avisos de $nombre"
                    }
                }
        ) {
            Text(
                if (soloVer) "Ver" else if (siguiendo) "Siguiendo" else "Seguir",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    HorizontalDivider(color = esquema.outline)
}

/**
 * La etiqueta verde de "ya estás suscrito en YouTube".
 *
 * El verde no se usa para nada más en la app, y aun así la etiqueta lo dice
 * con palabras y con una palomita: quien no distingue el verde la lee igual.
 */
@Composable
internal fun EtiquetaYouTube(texto: String = "Suscrito en YouTube", modifier: Modifier = Modifier) {
    val esquema = MaterialTheme.colorScheme

    Text(
        "✓ $texto",
        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        color = esquema.onTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(esquema.tertiary)
            .padding(horizontal = Espacio.sm, vertical = Espacio.xs)
    )
}

/**
 * El anuncio que va arriba del Directorio y explica la etiqueta verde. Lleva
 * la etiqueta misma como muestra, para que no haya que imaginarse el color.
 */
@Composable
fun LeyendaYouTube(modifier: Modifier = Modifier) {
    val esquema = MaterialTheme.colorScheme
    val forma = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(forma)
            .background(esquema.surface)
            .border(1.dp, esquema.outline, forma)
            .padding(Espacio.md)
            // Para el lector de pantalla es una sola frase, no dos trozos.
            .semantics(mergeDescendants = true) {}
    ) {
        EtiquetaYouTube()
        Text(
            "Esta etiqueta verde marca a quién ya estás suscrito en YouTube. " +
                "Seguir aquí y suscribirte en YouTube son cosas distintas.",
            style = MaterialTheme.typography.bodySmall,
            color = esquema.onSurface,
            modifier = Modifier.padding(top = Espacio.sm)
        )
    }
}

@Composable
fun Avatar(url: String?, nombre: String, tamano: androidx.compose.ui.unit.Dp = 52.dp) {
    val esquema = MaterialTheme.colorScheme

    if (!url.isNullOrBlank()) {
        AsyncImage(
            model = url,
            // null a propósito: la imagen es decorativa. El nombre ya lo lee
            // TalkBack en el texto de al lado, y repetirlo sería ruido.
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(tamano)
                .clip(CircleShape)
                .background(esquema.surfaceVariant)
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(tamano)
                .clip(CircleShape)
                .background(esquema.surfaceVariant)
        ) {
            Text(
                nombre.take(1).uppercase(),
                style = MaterialTheme.typography.bodyMedium,
                color = esquema.onSurfaceVariant
            )
        }
    }
}

/** Pantalla vacía: siempre dice qué hacer, nunca solo "no hay nada". */
@Composable
fun Vacio(
    titulo: String,
    mensaje: String,
    accion: (@Composable () -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(Espacio.xl)
    ) {
        Text(
            titulo,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(Espacio.sm))
        Text(
            mensaje,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (accion != null) {
            Spacer(Modifier.height(Espacio.lg))
            accion()
        }
    }
}
