package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/**
 * Lo único que se ve cuando el servidor no atiende a la app: está en
 * mantenimiento o no responde.
 *
 * Sin servidor la app no tiene nada que enseñar. En vez de listas vacías y
 * errores sueltos, se dice qué pasa y que no hay que hacer nada: la pantalla
 * vuelve a preguntar sola cada medio minuto y, cuando el servidor contesta,
 * la app arranca de nuevo.
 *
 * @param titulo       qué pasa, en pocas palabras
 * @param mensaje      el detalle. En mantenimiento es lo que escribió el equipo
 * @param onReintentar pregunta otra vez al servidor
 */
@Composable
fun ServidorPantalla(titulo: String, mensaje: String, onReintentar: () -> Unit) {
    val esquema = MaterialTheme.colorScheme

    LaunchedEffect(Unit) {
        while (true) {
            delay(INTERVALO_MS)
            onReintentar()
        }
    }

    Scaffold(containerColor = esquema.background) { relleno ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(relleno)
                .verticalScroll(rememberScrollState())
                .padding(Espacio.md)
        ) {
            Text(
                titulo,
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
                "No tienes que hacer nada: la app vuelve a intentarlo sola. Tu cuenta y " +
                    "los creadores que sigues no se pierden.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Espacio.lg)
            )
            BotonGrande(
                titulo = "Intentar ahora",
                variante = VarianteBoton.SECUNDARIO,
                onClick = onReintentar
            )
        }
    }
}

private const val INTERVALO_MS = 30_000L
