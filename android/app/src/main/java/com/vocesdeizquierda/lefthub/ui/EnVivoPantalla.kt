package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vocesdeizquierda.lefthub.data.Abiertos
import com.vocesdeizquierda.lefthub.data.Creador
import com.vocesdeizquierda.lefthub.data.DirectoEnVivo
import com.vocesdeizquierda.lefthub.data.GrupoEnVivo
import com.vocesdeizquierda.lefthub.data.ListaEnVivo
import com.vocesdeizquierda.lefthub.data.Productora
import com.vocesdeizquierda.lefthub.data.Publicacion
import com.vocesdeizquierda.lefthub.enlaces.Enrutador
import kotlinx.coroutines.delay

// Quién está transmitiendo ahora mismo en todo el directorio.
//
// El orden lo decide el servidor: primero a quienes sigue la persona en la
// app, después sus suscripciones de YouTube y al final los demás. Cada grupo
// lleva su título escrito, para que se entienda por qué alguien va arriba. Un
// grupo sin nadie no se pinta.
//
// La tarjeta empieza por la persona, no por el video: aquí lo que se busca es
// quién está al aire. Como en el resto de la app, la app no reproduce nada; el
// botón lleva a YouTube.

/**
 * Cada cuánto se vuelve a preguntar mientras la pantalla está a la vista. El
 * vigilante del servidor revisa cada dos minutos: más seguido no traería nada.
 */
private const val CADA_CUANTO_MS = 60_000L

@Composable
fun EnVivoPantalla(
    enVivo: ListaEnVivo,
    onCargar: () -> Unit,
    creadores: List<Creador> = emptyList(),
    productoras: List<Productora> = emptyList()
) {
    val contexto = LocalContext.current
    val ciclo = LocalLifecycleOwner.current

    // Un directo empieza y termina sin avisar a esta pantalla. Se pregunta al
    // entrar y luego cada minuto, solo mientras se ve: con la app en segundo
    // plano o en otra pestaña no se gasta nada.
    LaunchedEffect(ciclo) {
        ciclo.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                onCargar()
                delay(CADA_CUANTO_MS)
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(Espacio.md),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                "En vivo",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = Espacio.lg)
            )
        }

        if (enVivo.lista.isEmpty()) {
            item {
                when {
                    enVivo.fallo -> Vacio(
                        titulo = "No se pudo saber quién está en vivo",
                        mensaje = "Revisa tu conexión e inténtalo otra vez.",
                        accion = { BotonGrande("Intentar otra vez", onClick = onCargar) }
                    )

                    enVivo.cargando -> Text(
                        "Buscando quién está en vivo…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    else -> Vacio(
                        titulo = "Nadie está en vivo ahora",
                        mensaje = "Cuando alguien del directorio empiece a transmitir, aparecerá aquí."
                    )
                }
            }
        }

        enVivo.lista.groupBy { it.grupo }.forEach { (grupo, directos) ->
            item(key = "grupo-$grupo") { TituloDeGrupo(grupo) }

            items(directos, key = { it.publicacion.id }) { directo ->
                val publicacion = directo.publicacion
                TarjetaEnVivo(
                    publicacion = publicacion,
                    fotoUrl = fotoDe(publicacion, creadores, productoras),
                    onAbrir = {
                        val destino = publicacion.destino
                        Enrutador.abrirVideo(
                            contexto,
                            destino.plataforma,
                            destino.videoId,
                            destino.url,
                            campana = "en_vivo"
                        )
                        Abiertos.marcar(contexto, publicacion.videoId)
                    }
                )
            }
        }
    }
}

/** Lo que dice el título de cada grupo. Lo desconocido va con los demás. */
internal fun tituloDeGrupo(grupo: String): String = when (grupo) {
    GrupoEnVivo.SEGUIDOS -> "A quienes sigues"
    GrupoEnVivo.SUSCRITOS -> "Tus suscripciones de YouTube"
    else -> "Otros en vivo"
}

/** La foto de quien transmite: la suya o, en el canal propio de un medio, el logo. */
private fun fotoDe(
    publicacion: Publicacion,
    creadores: List<Creador>,
    productoras: List<Productora>
): String? =
    creadores.firstOrNull { it.id == publicacion.creatorId }?.photoUrl
        ?: productoras.firstOrNull { it.id == publicacion.productoraId }?.logoUrl

@Composable
private fun TituloDeGrupo(grupo: String) {
    Text(
        tituloDeGrupo(grupo),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .padding(bottom = Espacio.sm)
            .semantics { heading() }
    )
}

/**
 * Una persona al aire: su foto y nombre, la leyenda «EN VIVO» y de qué está
 * hablando. Toda la parte de arriba se toca igual que el botón.
 */
@Composable
private fun TarjetaEnVivo(
    publicacion: Publicacion,
    fotoUrl: String?,
    onAbrir: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme
    val forma = RoundedCornerShape(20.dp)
    val nombre = publicacion.firma

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Espacio.md)
            .clip(forma)
            .background(esquema.surface)
            .border(1.dp, esquema.outline, forma)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Espacio.md),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onAbrir)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$nombre está en vivo: ${publicacion.title}. Abrir el directo."
                }
                .padding(Espacio.md)
        ) {
            Avatar(fotoUrl, nombre, tamano = 64.dp)

            Column(Modifier.weight(1f)) {
                // Escrita, no solo pintada: se lee aunque no se distinga el rojo.
                Text(
                    "EN VIVO",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = esquema.onError,
                    modifier = Modifier
                        .padding(bottom = Espacio.xs)
                        .clip(RoundedCornerShape(8.dp))
                        .background(esquema.error)
                        .padding(horizontal = Espacio.sm, vertical = 2.dp)
                )
                Text(
                    nombre,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = esquema.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    publicacion.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = esquema.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        Column(Modifier.padding(horizontal = Espacio.md).padding(bottom = Espacio.sm)) {
            BotonGrande(
                titulo = "Ver en vivo",
                subtitulo = "Se abre en ${Enrutador.nombreDe(publicacion.destino.plataforma)}",
                onClick = onAbrir
            )
        }
    }
}
