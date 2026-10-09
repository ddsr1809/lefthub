package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.vocesdeizquierda.lefthub.EstadoApp
import com.vocesdeizquierda.lefthub.EstadoFolio
import com.vocesdeizquierda.lefthub.anuncios.Anuncios
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
    onDesconectarYouTube: () -> Unit = {},
    /** Abre la pantalla de pago de Google Play. */
    onComprarSinAnuncios: () -> Unit = {},
    onCanjearFolio: (String) -> Unit = {},
    /** Se cerró la ventanita del folio sin canjear. */
    onFolioCerrado: () -> Unit = {},
    /** Abre el formulario de Google para cambiar la elección de privacidad. */
    onPrivacidadDeAnuncios: () -> Unit = {}
) {
    val esquema = MaterialTheme.colorScheme
    val contexto = LocalContext.current
    var confirmandoBorrado by remember { mutableStateOf(false) }
    var pidiendoFolio by rememberSaveable { mutableStateOf(false) }

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

        // --- Videos cortos --------------------------------------------------
        // Solo si el equipo los permite. Si los apaga desde el panel, esta
        // sección desaparece entera: no hay nada que elegir.
        if (estado.perfil.cortosDisponibles) {
            Seccion("Videos cortos") {
                Text(
                    "Son los videos de menos de tres minutos que se ven en vertical " +
                        "(los Shorts de YouTube). Van aparte, en su propio apartado de " +
                        "Novedades, y nunca se mezclan con los demás videos.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = esquema.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Espacio.md)
                )
                BotonGrande(
                    titulo = "Verlos",
                    subtitulo = "En su apartado, y con aviso cuando publiquen uno",
                    variante = if (estado.perfil.cortos) VarianteBoton.PRIMARIO else VarianteBoton.SECUNDARIO,
                    onClick = { onGuardarPreferencia("cortos", true) }
                )
                BotonGrande(
                    titulo = "No verlos",
                    subtitulo = "Solo los videos de siempre, sin avisos de cortos",
                    variante = if (!estado.perfil.cortos) VarianteBoton.PRIMARIO else VarianteBoton.SECUNDARIO,
                    onClick = { onGuardarPreferencia("cortos", false) }
                )
            }
        }

        // --- Ver en la tele -------------------------------------------------
        // La misma explicación que sale una vez en Novedades. Aquí se queda
        // para siempre, por si hace falta volver a leerla.
        Seccion("Ver los videos en la tele") {
            ComoVerEnLaTele()
        }

        // --- Anuncios -------------------------------------------------------
        // Solo si el equipo los tiene encendidos. Apagados no hay nada que
        // quitar, y la sección desaparece entera.
        if (estado.perfil.anuncios) {
            Seccion("Anuncios") {
                if (estado.perfil.sinAnuncios) {
                    Text(
                        "Ya no ves anuncios en esta cuenta. Gracias.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = esquema.onSurfaceVariant
                    )
                    if (estado.esAnonimo) {
                        Text(
                            "Guarda tu cuenta con Google, aquí abajo, para que siga así " +
                                "si cambias de teléfono.",
                            style = MaterialTheme.typography.bodySmall,
                            color = esquema.onSurfaceVariant,
                            modifier = Modifier.padding(top = Espacio.sm)
                        )
                    }
                } else {
                    Text(
                        "La app muestra anuncios entre los videos de Novedades. Puedes " +
                            "quitarlos para siempre con un solo pago, o con un folio de " +
                            "regalo si te dieron uno.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = esquema.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Espacio.md)
                    )

                    // El botón de comprar solo sale cuando Google Play ya
                    // dijo el precio: nadie debe pagar sin saber cuánto.
                    val precio = estado.precioSinAnuncios
                    if (estado.perfil.compraDisponible && precio != null) {
                        BotonGrande(
                            titulo = "Quitar los anuncios",
                            subtitulo = "Un solo pago de $precio. Se cobra en Google Play",
                            onClick = onComprarSinAnuncios
                        )
                    }
                    BotonGrande(
                        titulo = "Tengo un folio de regalo",
                        subtitulo = "Quita los anuncios sin pagar",
                        variante = VarianteBoton.SECUNDARIO,
                        onClick = { pidiendoFolio = true }
                    )

                    // Solo donde la ley pide consentimiento para los anuncios
                    // (Europa, por ejemplo). En el resto, este botón no existe.
                    if (Anuncios.hayOpcionesDePrivacidad) {
                        BotonGrande(
                            titulo = "Privacidad de los anuncios",
                            subtitulo = "Cambia lo que elegiste sobre el uso de tus datos",
                            variante = VarianteBoton.SECUNDARIO,
                            onClick = onPrivacidadDeAnuncios
                        )
                    }
                }
            }
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
        if (estado.perfil.youtubeActivo && !estado.esAnonimo) {
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
                        "y tus preferencias. No se puede deshacer." +
                        // Un folio se gasta al usarlo: no hay forma de devolverlo.
                        if (estado.perfil.sinAnuncios)
                            " Si quitaste los anuncios con un folio de regalo, eso también " +
                                "se pierde. Si los compraste, Google Play te devuelve la " +
                                "compra sin pagar otra vez."
                        else ""
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

    // Al canjearlo bien el perfil pasa a "sin anuncios" y la ventanita se
    // cierra sola; si falla, se queda abierta con el motivo para corregirlo.
    if (pidiendoFolio && !estado.perfil.sinAnuncios) {
        VentanaDeFolio(
            folio = estado.folio,
            onCanjear = onCanjearFolio,
            onCerrar = { pidiendoFolio = false; onFolioCerrado() }
        )
    }
    LaunchedEffect(estado.perfil.sinAnuncios) {
        if (estado.perfil.sinAnuncios) pidiendoFolio = false
    }
}

/**
 * Donde se teclea el folio de regalo.
 *
 * Es el único sitio de la app donde hay que escribir, así que perdona todo lo
 * que puede: da igual poner el guion o no, las minúsculas se vuelven
 * mayúsculas solas, y si el folio no vale lo dice aquí mismo, sin cerrar la
 * ventana ni borrar lo escrito.
 */
@Composable
private fun VentanaDeFolio(
    folio: EstadoFolio,
    onCanjear: (String) -> Unit,
    onCerrar: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme
    var codigo by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Folio de regalo") },
        text = {
            Column {
                Text(
                    "Escribe el folio tal como te lo dieron. Son diez letras y números, " +
                        "como ABCDE-FGHJK. Sirve una sola vez."
                )
                OutlinedTextField(
                    value = codigo,
                    // Un folio no pasa de once caracteres con el guion; el
                    // margen es por si alguien pone espacios de más.
                    onValueChange = { codigo = it.uppercase().take(24) },
                    label = { Text("Folio") },
                    singleLine = true,
                    enabled = !folio.enviando,
                    isError = folio.error != null,
                    textStyle = MaterialTheme.typography.bodyLarge,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Espacio.md)
                )
                if (folio.error != null) {
                    Text(
                        folio.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = esquema.error,
                        modifier = Modifier
                            .padding(top = Espacio.sm)
                            // Que el lector de pantalla lo diga al aparecer.
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCanjear(codigo) },
                enabled = codigo.isNotBlank() && !folio.enviando,
                modifier = Modifier.heightIn(min = Tactil.minimo)
            ) {
                Text(if (folio.enviando) "Comprobando…" else "Canjear")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCerrar,
                modifier = Modifier.heightIn(min = Tactil.minimo)
            ) { Text("Cancelar") }
        }
    )
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
