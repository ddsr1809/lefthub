package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vocesdeizquierda.lefthub.BuildConfig
import com.vocesdeizquierda.lefthub.EstadoApp
import com.vocesdeizquierda.lefthub.data.PermisoYouTube
import com.vocesdeizquierda.lefthub.enlaces.Enrutador

@Composable
fun AjustesPantalla(
    estado: EstadoApp,
    onGuardarPreferencia: (String, Any) -> Unit,
    onVincularGoogle: () -> Unit,
    onCerrarSesion: () -> Unit,
    onBorrarCuenta: () -> Unit,
    onConectarYouTube: () -> Unit = {},
    onDesconectarYouTube: () -> Unit = {}
) {
    val esquema = MaterialTheme.colorScheme
    val contexto = LocalContext.current
    var confirmandoBorrado by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Espacio.md)
    ) {
        Text(
            "Ajustes",
            style = MaterialTheme.typography.displayLarge,
            color = esquema.onBackground,
            modifier = Modifier.padding(bottom = Espacio.lg)
        )

        // --- Tamaño de letra ------------------------------------------------
        Seccion("Tamaño de la letra") {
            EscalaTexto.entries.forEach { escala ->
                val activa = estado.perfil.escalaTexto == escala.clave
                BotonGrande(
                    titulo = escala.etiqueta,
                    variante = if (activa) VarianteBoton.PRIMARIO else VarianteBoton.SECUNDARIO,
                    onClick = { onGuardarPreferencia("escalaTexto", escala.clave) }
                )
            }
            Text(
                "Esto se suma al tamaño de letra que ya tengas puesto en el teléfono. " +
                    "La pantalla se acomoda hasta el doble de grande sin cortar el texto.",
                style = MaterialTheme.typography.bodySmall,
                color = esquema.onSurfaceVariant
            )
        }

        // --- Colores --------------------------------------------------------
        Seccion("Colores") {
            BotonGrande(
                titulo = "Fondo oscuro",
                subtitulo = "Cansa menos la vista de noche",
                variante = if (estado.perfil.tema == "oscuro") VarianteBoton.PRIMARIO else VarianteBoton.SECUNDARIO,
                onClick = { onGuardarPreferencia("tema", "oscuro") }
            )
            BotonGrande(
                titulo = "Fondo claro",
                subtitulo = "Se lee mejor con mucha luz",
                variante = if (estado.perfil.tema == "claro") VarianteBoton.PRIMARIO else VarianteBoton.SECUNDARIO,
                onClick = { onGuardarPreferencia("tema", "claro") }
            )
            BotonGrande(
                titulo = "Como el teléfono",
                subtitulo = "Cambia solo según la hora o tus ajustes",
                variante = if (estado.perfil.tema == "sistema") VarianteBoton.PRIMARIO else VarianteBoton.SECUNDARIO,
                onClick = { onGuardarPreferencia("tema", "sistema") }
            )
        }

        // --- Cuenta ---------------------------------------------------------
        Seccion("Tu cuenta") {
            if (estado.esAnonimo) {
                Text(
                    "Estás usando la app como invitado. Funciona todo, pero si cambias de " +
                        "teléfono o borras la app, los creadores que sigues se pierden. " +
                        "Guarda tu cuenta para que te acompañen.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = esquema.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Espacio.md)
                )
                BotonGrande(
                    titulo = "Guardar con Google",
                    habilitado = !estado.ocupado,
                    onClick = onVincularGoogle
                )
                Text(
                    "Al guardar tu cuenta aceptas la política de privacidad y las condiciones " +
                        "de servicio. Las encuentras al final de esta pantalla.",
                    style = MaterialTheme.typography.bodySmall,
                    color = esquema.onSurfaceVariant
                )
            } else {
                Text(
                    "Tu cuenta está guardada" + (estado.correo?.let { " como $it" } ?: "") + ".",
                    style = MaterialTheme.typography.bodyLarge,
                    color = esquema.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Espacio.md)
                )
                BotonGrande(
                    titulo = "Cerrar sesión",
                    variante = VarianteBoton.SECUNDARIO,
                    onClick = onCerrarSesion
                )
            }
        }

        // --- YouTube --------------------------------------------------------
        // Solo con la cuenta guardada: las suscripciones son de una cuenta de
        // Google, y un invitado no tiene ninguna que consultar.
        if (BuildConfig.SUSCRIPCIONES_YOUTUBE && !estado.esAnonimo) {
            Seccion("Tus suscripciones de YouTube") {
                if (estado.youtube.permiso == PermisoYouTube.CONCEDIDO) {
                    Text(
                        "Cada vez que abres la app consultamos tu cuenta de YouTube para " +
                            "decirte a cuáles de estos creadores estás suscrito. Solo podemos " +
                            "leer: no suscribimos, comentamos ni cambiamos nada en tu nombre.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = esquema.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Espacio.md)
                    )
                    BotonGrande(
                        titulo = "Dejar de consultar YouTube",
                        subtitulo = "Borra lo que guardamos y retira el permiso",
                        variante = VarianteBoton.SECUNDARIO,
                        onClick = onDesconectarYouTube
                    )
                } else {
                    Text(
                        "Seguir a un creador aquí no es lo mismo que estar suscrito a su " +
                            "canal. Si conectas YouTube, te decimos en cuáles sí lo estás.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = esquema.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Espacio.md)
                    )
                    BotonGrande(
                        titulo = "Conectar con YouTube",
                        subtitulo = "Google te pedirá permiso para consultar tus suscripciones",
                        variante = VarianteBoton.SECUNDARIO,
                        habilitado = !estado.youtube.verificando,
                        onClick = onConectarYouTube
                    )
                }
            }
        }

        // --- Borrado --------------------------------------------------------
        Seccion("Borrar todo") {
            Text(
                "Borrar la cuenta elimina de verdad todos tus datos de nuestros servidores. " +
                    "No es una pausa ni una desactivación.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Espacio.md)
            )
            BotonGrande(
                titulo = "Borrar mi cuenta",
                variante = VarianteBoton.PELIGRO,
                habilitado = !estado.ocupado,
                onClick = { confirmandoBorrado = true }
            )
        }

        // --- Privacidad y condiciones ---------------------------------------
        // Las tiendas y las políticas de la API de YouTube piden que estos
        // documentos se puedan abrir desde dentro de la app.
        Seccion("Privacidad y condiciones") {
            Text(
                "Al usar la app aceptas nuestras condiciones de servicio y las Condiciones " +
                    "del Servicio de YouTube.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Espacio.md)
            )
            BotonGrande(
                titulo = "Política de privacidad",
                subtitulo = "Qué datos guardamos y cómo borrarlos",
                variante = VarianteBoton.SECUNDARIO,
                onClick = { Enrutador.abrirPagina(contexto, Enrutador.URL_PRIVACIDAD) }
            )
            BotonGrande(
                titulo = "Condiciones de servicio",
                subtitulo = "Se abren en el navegador",
                variante = VarianteBoton.SECUNDARIO,
                onClick = { Enrutador.abrirPagina(contexto, Enrutador.URL_CONDICIONES) }
            )
        }

        Text(
            "Esta app no reproduce videos. Solo te avisa cuando alguien publica y te lleva " +
                "a la app oficial donde está el contenido.",
            style = MaterialTheme.typography.bodySmall,
            color = esquema.onSurfaceVariant,
            modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.xxl)
        )
    }

    if (confirmandoBorrado) {
        AlertDialog(
            onDismissRequest = { confirmandoBorrado = false },
            title = { Text("Borrar tu cuenta") },
            text = {
                Text(
                    "Se elimina tu cuenta y todo lo que guardaste: los creadores que sigues " +
                        "y tus preferencias. No se puede deshacer."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmandoBorrado = false; onBorrarCuenta() },
                    modifier = Modifier.heightIn(min = Tactil.minimo)
                ) {
                    Text("Borrar cuenta", color = esquema.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmandoBorrado = false },
                    modifier = Modifier.heightIn(min = Tactil.minimo)
                ) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun Seccion(titulo: String, contenido: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = Espacio.lg)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(
            titulo,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.md)
        )
        contenido()
    }
}
