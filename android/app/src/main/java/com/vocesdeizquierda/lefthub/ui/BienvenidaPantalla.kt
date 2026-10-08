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
 * Lo primero que se ve al instalar la app.
 *
 * Hasta que la persona toca "Aceptar y continuar" no se crea ninguna cuenta ni
 * se le manda nada al servidor. Lo piden las políticas de la API de YouTube
 * (aceptar la política de privacidad antes de usar la app) y la ley mexicana
 * de datos personales: los creadores que alguien sigue pueden revelar sus
 * opiniones políticas, y ese dato necesita consentimiento expreso.
 *
 * No hay botón de rechazar: quien no está de acuerdo cierra la app.
 */
@Composable
fun BienvenidaPantalla(onAceptar: () -> Unit) {
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
                "Voces de Izquierda",
                style = MaterialTheme.typography.displayLarge,
                color = esquema.onBackground,
                modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.md)
            )
            Text(
                "Te avisa cuando publican los creadores que sigues y te lleva al video en " +
                    "su app oficial.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Espacio.lg)
            )

            HorizontalDivider(color = esquema.outline)
            Text(
                "Antes de empezar",
                style = MaterialTheme.typography.headlineMedium,
                color = esquema.onBackground,
                modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.md)
            )
            Punto(
                "Para funcionar guardamos a qué creadores sigues, tus ajustes y los datos " +
                    "de tu conexión, como tu dirección IP."
            )
            Punto(
                "Los creadores que sigues pueden dar a entender tus opiniones políticas. " +
                    "Solo usamos ese dato para mostrarte sus novedades y enviarte sus " +
                    "avisos. No lo compartimos."
            )
            // Va con todas las letras porque es lo que cambia para la
            // persona: hay publicidad, y la pone Google con sus propios datos.
            Punto(
                "La app puede mostrar anuncios de Google entre los videos. Para elegirlos " +
                    "y medirlos, Google usa el identificador de publicidad de tu teléfono " +
                    "y datos de tu conexión. No le decimos a quién sigues ni qué videos " +
                    "abres. Los anuncios se pueden quitar desde Ajustes."
            )
            Punto(
                "No hace falta dar tu nombre ni tu correo, y puedes borrar tu cuenta y " +
                    "tus datos cuando quieras desde Ajustes."
            )

            BotonGrande(
                titulo = "Política de privacidad",
                subtitulo = "Se abre en el navegador",
                variante = VarianteBoton.SECUNDARIO,
                modifier = Modifier.padding(top = Espacio.sm),
                onClick = { Enrutador.abrirPagina(contexto, Enrutador.URL_PRIVACIDAD) }
            )
            BotonGrande(
                titulo = "Condiciones de servicio",
                subtitulo = "Se abren en el navegador",
                variante = VarianteBoton.SECUNDARIO,
                onClick = { Enrutador.abrirPagina(contexto, Enrutador.URL_CONDICIONES) }
            )

            Text(
                "Al tocar «Aceptar y continuar» aceptas la política de privacidad, las " +
                    "condiciones de servicio y las Condiciones del Servicio de YouTube, y " +
                    "consientes que guardemos los creadores que sigas.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onBackground,
                modifier = Modifier.padding(top = Espacio.md, bottom = Espacio.md)
            )
            BotonGrande(
                titulo = "Aceptar y continuar",
                onClick = onAceptar
            )
            Text(
                "Si no estás de acuerdo, cierra la app. No se crea ninguna cuenta.",
                style = MaterialTheme.typography.bodySmall,
                color = esquema.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Espacio.xxl)
            )
        }
    }
}

@Composable
private fun Punto(texto: String) {
    Text(
        texto,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Espacio.md)
    )
}
