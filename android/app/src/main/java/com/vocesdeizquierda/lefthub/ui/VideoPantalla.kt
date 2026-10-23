package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import coil.compose.AsyncImage
import androidx.compose.ui.unit.dp
import com.vocesdeizquierda.lefthub.data.Destino
import com.vocesdeizquierda.lefthub.data.FichaDeVideo
import com.vocesdeizquierda.lefthub.enlaces.Enrutador

/**
 * La ficha de un video. Es a donde lleva tocar una notificación: antes se
 * abría YouTube directamente, y ahora la persona ve primero de qué video se
 * trata y decide.
 *
 * De arriba abajo: la miniatura, de quién es y cuándo salió, el título, el
 * botón grande para verlo y, debajo y bien separado, el espacio para un
 * anuncio. El anuncio solo sale si el equipo los tiene encendidos y la
 * persona no los quitó (ver [anuncio]); si no, el hueco no existe.
 *
 * Debajo del anuncio va "Ya lo vi": para marcar el video como visto sin
 * abrirlo (lo vio en la tele, en otro lado). Pregunta antes, "¿Seguro ya lo
 * viste?", para que un toque sin querer no lo marque. La marca va con la
 * cuenta (ver Abiertos).
 *
 * @param visto la persona ya lo marcó con "Ya lo vi".
 * @param onMarcarVisto dijo que sí en la pregunta.
 * @param anuncio el espacio de publicidad, ya decidido fuera: null si no toca.
 */
@Composable
fun VideoPantalla(
    ficha: FichaDeVideo,
    abierto: Boolean,
    onVer: (Destino) -> Unit,
    visto: Boolean = false,
    onMarcarVisto: () -> Unit = {},
    onVolver: () -> Unit,
    onAbrirCreador: (String) -> Unit = {},
    onAbrirCanal: (String) -> Unit = {},
    onReportar: () -> Unit = {},
    anuncio: (@Composable () -> Unit)? = null
) {
    val esquema = MaterialTheme.colorScheme
    val publicacion = ficha.publicacion
    val destino = ficha.destino

    if (destino == null) {
        Vacio(
            titulo = "Este video ya no está",
            mensaje = "Puede que lo hayamos retirado del directorio. En Novedades tienes lo último de quienes sigues.",
            accion = { BotonGrande("Ir a Novedades", onClick = onVolver) }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Espacio.md)
    ) {
        if (!publicacion?.thumbnailUrl.isNullOrBlank()) {
            AsyncImage(
                model = publicacion?.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(esquema.surfaceVariant)
            )
        }

        Column(Modifier.padding(top = Espacio.md)) {
            EtiquetaDeEstado(abierto || visto, visto = visto)
            if (publicacion != null) {
                Text(
                    listOf(publicacion.firma, tiempoRelativo(publicacion.publishedAt))
                        .filter { it.isNotBlank() }
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = esquema.onSurfaceVariant
                )
            }
            Text(
                when {
                    publicacion != null -> publicacion.title
                    ficha.cargando -> "Cargando el video…"
                    else -> "Video nuevo"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = esquema.onBackground,
                modifier = Modifier
                    .padding(top = Espacio.sm, bottom = Espacio.lg)
                    .semantics { heading() }
            )
        }

        if (ficha.esMovido) {
            Text(
                "Este video cambió de lugar. El botón te lleva al sitio nuevo.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.primary,
                modifier = Modifier.padding(bottom = Espacio.md)
            )
        }

        BotonGrande(
            titulo = when {
                publicacion?.esEnVivo == true -> "Ver en vivo"
                publicacion?.esCorto == true -> "Ver el video corto"
                else -> "Ver el video"
            },
            subtitulo = "Se abre en ${Enrutador.nombreDe(destino.plataforma)}",
            onClick = { onVer(destino) }
        )

        // La publicidad, apartada del botón de ver para que un toque con el
        // pulso inseguro no caiga en el anuncio.
        if (anuncio != null) {
            Spacer(Modifier.height(Espacio.lg))
            anuncio()
        }

        // Marcarlo como visto sin abrirlo. Una vez marcado, el botón da paso
        // a una línea que lo dice: no hay nada más que hacer con él.
        var preguntando by rememberSaveable { mutableStateOf(false) }
        if (visto) {
            Text(
                "✓ Marcaste que ya lo viste.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Espacio.md)
            )
        } else {
            BotonGrande(
                titulo = "Ya lo vi",
                subtitulo = "Márcalo como visto sin abrirlo",
                variante = VarianteBoton.SECUNDARIO,
                onClick = { preguntando = true }
            )
        }
        if (preguntando) {
            PreguntaYaLoViste(
                onSi = {
                    preguntando = false
                    onMarcarVisto()
                },
                onNo = { preguntando = false }
            )
        }

        // Para seguir explorando: de quién es el video.
        val creadorId = publicacion?.creatorId?.takeIf { it.isNotBlank() }
        val canalId = publicacion?.canalId
        when {
            creadorId != null -> BotonGrande(
                titulo = "Ver la ficha de ${publicacion?.creatorName ?: "quien lo publicó"}",
                subtitulo = "Sus canales y sus últimos videos",
                variante = VarianteBoton.SECUNDARIO,
                onClick = { onAbrirCreador(creadorId) }
            )
            canalId != null -> BotonGrande(
                titulo = "Ver el canal",
                subtitulo = "Sus últimos videos",
                variante = VarianteBoton.SECUNDARIO,
                onClick = { onAbrirCanal(canalId) }
            )
        }

        BotonGrande(
            titulo = "Ir a Novedades",
            subtitulo = "Lo último de quienes sigues",
            variante = VarianteBoton.SECUNDARIO,
            onClick = onVolver
        )

        if (publicacion != null) {
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
        Spacer(Modifier.height(Espacio.xxl))
    }
}

/**
 * "¿Seguro ya lo viste?": la confirmación de "Ya lo vi". Las dos respuestas
 * van escritas enteras para que no haya duda de qué hace cada una.
 */
@Composable
private fun PreguntaYaLoViste(onSi: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text("¿Seguro ya lo viste?") },
        text = {
            Text(
                "Lo vamos a marcar como visto en tu cuenta. Así lo verás también si cierras " +
                    "la sesión y vuelves a entrar, o en otro teléfono.",
                style = MaterialTheme.typography.bodyLarge
            )
        },
        confirmButton = {
            TextButton(onClick = onSi, modifier = Modifier.heightIn(min = Tactil.minimo)) {
                Text("Sí, ya lo vi")
            }
        },
        dismissButton = {
            TextButton(onClick = onNo, modifier = Modifier.heightIn(min = Tactil.minimo)) {
                Text("No, todavía no")
            }
        }
    )
}
