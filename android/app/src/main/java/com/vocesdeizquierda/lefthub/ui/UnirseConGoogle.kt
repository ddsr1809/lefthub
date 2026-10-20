package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * La invitación a unirse con la cuenta de Google.
 *
 * Sale al abrir la app, después de aceptar las condiciones, mientras la
 * persona use la app sin cuenta (como invitada). Es opcional: "Ahora no" la
 * cierra y no vuelve a salir hasta la próxima vez que se abra la app. Ajustes
 * conserva el mismo botón para cuando quiera.
 *
 * Ocupa la pantalla entera y no una ventanita: con la letra grande, una
 * ventana pequeña obliga a deslizar dentro de ella, y eso a este público le
 * cuesta.
 *
 * No pide el permiso de YouTube: ese tiene un cupo de cuentas mientras Google
 * no apruebe la verificación, y se pide aparte, en Ajustes.
 *
 * @param ocupado Google está abriendo su pantalla o contestando.
 * @param noSeConecto ya lo intentó y no quedó conectada (canceló o falló).
 * @param conYouTube la comprobación de suscripciones está encendida: se
 *                   menciona como una ventaja más.
 */
@Composable
fun UnirseConGoogle(
    ocupado: Boolean,
    noSeConecto: Boolean,
    conYouTube: Boolean,
    onUnirse: () -> Unit,
    onAhoraNo: () -> Unit
) {
    Dialog(
        onDismissRequest = onAhoraNo,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            ContenidoUnirseConGoogle(ocupado, noSeConecto, conYouTube, onUnirse, onAhoraNo)
        }
    }
}

/** El contenido, aparte de la ventana, para poder verlo en Previews.kt. */
@Composable
internal fun ContenidoUnirseConGoogle(
    ocupado: Boolean,
    noSeConecto: Boolean,
    conYouTube: Boolean,
    onUnirse: () -> Unit,
    onAhoraNo: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Espacio.lg)
    ) {
        Text(
            "Únete con tu cuenta de Google",
            style = MaterialTheme.typography.displayLarge,
            color = esquema.onBackground,
            modifier = Modifier
                .padding(top = Espacio.xl, bottom = Espacio.md)
                .semantics { heading() }
        )
        Text(
            "Es opcional, pero la app funciona mejor:",
            style = MaterialTheme.typography.bodyLarge,
            color = esquema.onBackground,
            modifier = Modifier.padding(bottom = Espacio.md)
        )

        listOfNotNull(
            "No pierdes a quién sigues si cambias de teléfono o se te pierde.",
            "Puedes usar la app en otro teléfono o tableta con lo mismo que sigues.",
            "Te decimos en qué canales ya estás suscrito en YouTube.".takeIf { conYouTube }
        ).forEach { ventaja ->
            Text(
                "✓ $ventaja",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onBackground,
                modifier = Modifier.padding(bottom = Espacio.sm)
            )
        }

        Text(
            "Solo guardamos tu correo para reconocer tu cuenta. No publicamos nada en tu nombre.",
            style = MaterialTheme.typography.bodySmall,
            color = esquema.onSurfaceVariant,
            modifier = Modifier.padding(top = Espacio.sm, bottom = Espacio.lg)
        )

        if (noSeConecto && !ocupado) {
            Text(
                "No se conectó. Puedes intentarlo otra vez o dejarlo para después.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.error,
                modifier = Modifier.padding(bottom = Espacio.md)
            )
        }

        BotonGrande(
            titulo = if (ocupado) "Conectando con Google…" else "Unirme con mi cuenta de Google",
            subtitulo = if (ocupado) null else "Google te pedirá elegir tu cuenta",
            habilitado = !ocupado,
            onClick = onUnirse
        )
        BotonGrande(
            titulo = "Ahora no",
            subtitulo = "Te lo volveremos a ofrecer al abrir la app. También está en Ajustes",
            variante = VarianteBoton.SECUNDARIO,
            habilitado = !ocupado,
            onClick = onAhoraNo
        )
    }
}
