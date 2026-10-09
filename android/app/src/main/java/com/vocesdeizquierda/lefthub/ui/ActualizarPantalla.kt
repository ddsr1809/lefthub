package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vocesdeizquierda.lefthub.enlaces.Enrutador

/**
 * Lo único que se ve cuando esta versión de la app ya no se atiende.
 *
 * El servidor deja de responder a las versiones que el equipo da de baja
 * (contesta 426) y la app no puede hacer nada hasta que se actualiza. En vez
 * de pantallas vacías y errores sueltos, se explica qué pasa y se ofrece el
 * único botón que lo arregla.
 *
 * @param mensaje el aviso que escribió el servidor, pensado para leerse tal cual
 */
@Composable
fun ActualizarPantalla(mensaje: String) {
    val esquema = MaterialTheme.colorScheme
    val contexto = LocalContext.current

    Scaffold(containerColor = esquema.background) { relleno ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(relleno)
                .verticalScroll(rememberScrollState())
                .padding(Espacio.md)
        ) {
            Text(
                "Hay que actualizar la app",
                style = MaterialTheme.typography.displayLarge,
                color = esquema.onBackground,
                modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.md)
            )
            Text(
                mensaje,
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onBackground,
                modifier = Modifier.padding(bottom = Espacio.md)
            )
            Text(
                "Tu cuenta y los creadores que sigues no se pierden: al actualizar " +
                    "encontrarás todo como lo dejaste.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Espacio.lg)
            )
            BotonGrande(
                titulo = "Actualizar",
                subtitulo = "Se abre Google Play",
                onClick = {
                    Enrutador.abrirPagina(
                        contexto,
                        "https://play.google.com/store/apps/details?id=" + contexto.packageName
                    )
                }
            )
        }
    }
}
