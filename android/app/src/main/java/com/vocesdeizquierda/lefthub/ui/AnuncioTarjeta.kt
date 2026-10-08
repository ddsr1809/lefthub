package com.vocesdeizquierda.lefthub.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vocesdeizquierda.lefthub.anuncios.Banner

/**
 * La tarjeta de un anuncio en Novedades.
 *
 * Tiene que distinguirse de la de un video sin tener que fijarse: lleva la
 * palabra "Publicidad" escrita arriba, otro fondo, y no tiene ni miniatura
 * ni botón de "Ver el video". Debajo, separado del anuncio para que un toque
 * con el pulso inseguro no caiga donde no es, va el botón para quitarlos.
 */
@Composable
internal fun TarjetaAnuncio(banner: Banner, onQuitar: () -> Unit) {
    MarcoDeAnuncio(onQuitar = onQuitar) {
        AndroidView(
            // La vista del anuncio es siempre la misma, guardada fuera de la
            // lista. Al salir de la pantalla al deslizar y volver, se cuelga
            // otra vez aquí en lugar de pedir un anuncio nuevo.
            factory = {
                (banner.vista.parent as? ViewGroup)?.removeView(banner.vista)
                banner.vista
            },
            modifier = Modifier.wrapContentSize()
        )
    }
}

/**
 * El marco de la tarjeta, sin el anuncio dentro. Va aparte para poder verlo
 * en Previews.kt, donde no hay anuncios de verdad.
 */
@Composable
internal fun MarcoDeAnuncio(onQuitar: () -> Unit, anuncio: @Composable () -> Unit) {
    val esquema = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Espacio.lg)
            .clip(RoundedCornerShape(20.dp))
            .background(esquema.surfaceVariant)
            .border(1.dp, esquema.outline, RoundedCornerShape(20.dp))
    ) {
        Text(
            "Publicidad",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = esquema.onSurfaceVariant,
            modifier = Modifier
                .padding(horizontal = Espacio.md)
                .padding(top = Espacio.md, bottom = Espacio.sm)
                .semantics { heading() }
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            anuncio()
        }

        TextButton(
            onClick = onQuitar,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = Espacio.md, bottom = Espacio.sm)
                .heightIn(min = Tactil.minimo)
        ) {
            Text(
                "Quitar los anuncios",
                style = MaterialTheme.typography.bodySmall,
                color = esquema.onSurfaceVariant
            )
        }
    }
}
