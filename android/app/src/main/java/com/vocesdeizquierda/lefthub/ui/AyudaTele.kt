package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.vocesdeizquierda.lefthub.R

// Cómo ver un video en la tele.
//
// La app no transmite: abre el video en YouTube, y el botón de transmitir es
// de YouTube. YouTube no le da a ninguna otra app una forma de mandar un video
// suyo a la tele, así que lo que toca es explicar dónde está ese botón. Va con
// el dibujo al lado porque en YouTube el botón no tiene nombre escrito, y sin
// verlo no hay manera de reconocerlo.

/**
 * La explicación, sin título ni marco. La usan la tarjeta de Novedades y la
 * sección de Ajustes, para que digan exactamente lo mismo.
 */
@Composable
fun ComoVerEnLaTele(modifier: Modifier = Modifier) {
    val esquema = MaterialTheme.colorScheme

    Column(modifier) {
        Text(
            "Abre el video como siempre. Ya en YouTube, toca el video una vez y " +
                "busca arriba este dibujo:",
            style = MaterialTheme.typography.bodyLarge,
            color = esquema.onSurfaceVariant
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Espacio.md),
            modifier = Modifier
                .padding(vertical = Espacio.md)
                // Dibujo y frase se leen juntos, como una sola cosa.
                .clearAndSetSemantics {
                    contentDescription = "Una pantalla con ondas en una esquina. " +
                        "Tócalo y elige tu tele en la lista."
                }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(Tactil.principal)
                    .clip(RoundedCornerShape(12.dp))
                    .background(esquema.surfaceVariant)
                    .border(1.dp, esquema.outline, RoundedCornerShape(12.dp))
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_transmitir),
                    contentDescription = null,
                    tint = esquema.onSurface,
                    modifier = Modifier.size(32.dp)
                )
            }
            Text(
                "Tócalo y elige tu tele en la lista.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurface,
                modifier = Modifier.weight(1f)
            )
        }

        Text(
            "El dibujo solo sale si la tele y el teléfono están en el mismo wifi. " +
                "Sirve con Chromecast y con casi cualquier tele que tenga YouTube.",
            style = MaterialTheme.typography.bodySmall,
            color = esquema.onSurfaceVariant
        )
    }
}

/**
 * La explicación como tarjeta, para arriba de la lista de Novedades. Sale
 * hasta que la persona toca "Entendido".
 *
 * Tiene el mismo contorno que las tarjetas de video pero ninguna miniatura,
 * y el botón no va relleno: no debe parecer un video más.
 */
@Composable
fun TarjetaVerEnLaTele(onEntendido: () -> Unit, modifier: Modifier = Modifier) {
    val esquema = MaterialTheme.colorScheme
    val forma = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Espacio.lg)
            .clip(forma)
            .background(esquema.surface)
            .border(1.dp, esquema.outline, forma)
            .padding(Espacio.md)
    ) {
        Text(
            "¿Prefieres verlo en la tele?",
            style = MaterialTheme.typography.headlineMedium,
            color = esquema.onSurface,
            modifier = Modifier.padding(bottom = Espacio.sm)
        )
        ComoVerEnLaTele(Modifier.padding(bottom = Espacio.md))
        BotonGrande(
            titulo = "Entendido",
            subtitulo = "Esto se queda en Ajustes por si quieres volver a leerlo",
            variante = VarianteBoton.SECUNDARIO,
            onClick = onEntendido
        )
    }
}
