package com.vocesdeizquierda.lefthub.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vocesdeizquierda.lefthub.chat.ChatViewModel
import com.vocesdeizquierda.lefthub.chat.EstadoChat
import com.vocesdeizquierda.lefthub.data.MensajeDelChat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val formatoHora = DateTimeFormatter.ofPattern("HH:mm")
private val formatoDia = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("es-MX"))

/**
 * El chat: la persona con el equipo, o un administrador con una cuenta.
 *
 * Los mensajes se guardan cifrados en el servidor; aquí solo están mientras la
 * pantalla está abierta. Con la pantalla a la vista llegan al momento; si la
 * app pasa a segundo plano deja de esperarlos y lo que llegue trae aviso.
 * Mientras se ve, la pantalla no deja hacer capturas ni sale en apps recientes.
 *
 * @param titulo para un administrador, la cuenta con la que habla
 */
@Composable
fun ChatPantalla(
    modelo: ChatViewModel,
    onVolver: () -> Unit,
    titulo: String = "Chat con el equipo"
) {
    val estado by modelo.estado.collectAsState()
    val contexto = LocalContext.current

    DisposableEffect(Unit) {
        val ventana = (contexto as? Activity)?.window
        ventana?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { ventana?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { modelo.alVerse() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { modelo.alOcultarse() }

    ChatContenido(
        estado = estado,
        titulo = titulo,
        comoEquipo = modelo.comoEquipo,
        onEnviar = modelo::enviar,
        onReintentar = modelo::cargar,
        onVolver = onVolver
    )
}

@Composable
fun ChatContenido(
    estado: EstadoChat,
    titulo: String,
    comoEquipo: Boolean,
    onEnviar: (String, () -> Unit) -> Unit,
    onReintentar: () -> Unit,
    onVolver: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme
    val lista = rememberLazyListState()
    // Lo escrito vive solo aquí: no se guarda ni al girar la pantalla.
    var texto by remember { mutableStateOf("") }

    // Al último mensaje. El primer elemento de la lista es la explicación,
    // así que el último mensaje está en la posición `size`.
    LaunchedEffect(estado.mensajes.size) {
        if (estado.mensajes.isNotEmpty()) lista.animateScrollToItem(estado.mensajes.size)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // El teclado, menos la barra de navegación, que la pantalla de
            // fuera ya descontó: así la caja de texto queda justo encima.
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))
    ) {
        // --- Cabecera ---------------------------------------------------------
        Column(Modifier.padding(start = Espacio.md, end = Espacio.md, top = Espacio.md)) {
            TextButton(
                onClick = onVolver,
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.heightIn(min = Tactil.minimo)
            ) {
                Text("‹ Volver", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                titulo,
                style = MaterialTheme.typography.headlineMedium,
                color = esquema.onBackground,
                modifier = Modifier.semantics { heading() }
            )
            if (estado.sinConexion) {
                Text(
                    "Sin conexión. Reintentando…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = esquema.error,
                    modifier = Modifier
                        .padding(top = Espacio.xs)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }

        // --- Mensajes ---------------------------------------------------------
        LazyColumn(
            state = lista,
            contentPadding = PaddingValues(Espacio.md),
            verticalArrangement = Arrangement.spacedBy(Espacio.sm),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            item {
                Text(
                    if (comoEquipo) "La persona ve tus mensajes como «Equipo de Voces», sin tu correo."
                    else "Los mensajes se guardan cifrados y solo los lee el equipo de Voces. " +
                        "Puedes contestar cuando quieras: te avisamos cuando te escriban.",
                    style = MaterialTheme.typography.bodySmall,
                    color = esquema.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = Espacio.sm)
                )
            }
            if (estado.cargando) {
                item {
                    Box(Modifier.fillMaxWidth().padding(Espacio.lg), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (estado.mensajes.isEmpty() && estado.terminado == null) {
                item {
                    Text(
                        if (comoEquipo) "Todavía no se han escrito. Lo que mandes le llega con un aviso."
                        else "Todavía no hay mensajes. Escribe aquí abajo y el equipo te contestará.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = esquema.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = Espacio.lg)
                    )
                }
            }
            items(estado.mensajes, key = { it.id }) { BurbujaDeChat(it, comoEquipo) }
        }

        // --- Escribir ---------------------------------------------------------
        HorizontalDivider(color = esquema.outline)
        Column(Modifier.padding(Espacio.md)) {
            val motivo = estado.terminado
            if (motivo != null) {
                Text(
                    motivo,
                    style = MaterialTheme.typography.bodyLarge,
                    color = esquema.onSurfaceVariant,
                    modifier = Modifier
                        .padding(bottom = Espacio.md)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
                BotonGrande("Volver a intentar", variante = VarianteBoton.SECUNDARIO, onClick = onReintentar)
                BotonGrande("Salir", onClick = onVolver)
            } else {
                CajaDeMensaje(estado, texto, { texto = it }, onEnviar)
            }
        }
    }
}

@Composable
private fun CajaDeMensaje(
    estado: EstadoChat,
    texto: String,
    onTexto: (String) -> Unit,
    onEnviar: (String, () -> Unit) -> Unit
) {
    val esquema = MaterialTheme.colorScheme

    OutlinedTextField(
        value = texto,
        onValueChange = { onTexto(it.take(ChatViewModel.MAX_TEXTO)) },
        label = { Text("Escribe tu mensaje") },
        enabled = !estado.enviando && !estado.cargando,
        textStyle = MaterialTheme.typography.bodyLarge,
        maxLines = 5,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth()
    )
    estado.errorAlEnviar?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = esquema.error,
            modifier = Modifier
                .padding(top = Espacio.xs)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
    BotonGrande(
        titulo = if (estado.enviando) "Enviando…" else "Enviar",
        habilitado = !estado.enviando && !estado.cargando && texto.isNotBlank(),
        onClick = { onEnviar(texto) { onTexto("") } },
        modifier = Modifier.padding(top = Espacio.sm)
    )
}

/** "14:05" si es de hoy; "3 oct · 14:05" si no. */
internal fun cuandoDelChat(m: MensajeDelChat): String {
    val local = m.creadoEn.atZone(ZoneId.systemDefault())
    val hora = formatoHora.format(local)
    return if (local.toLocalDate() == LocalDate.now()) hora else formatoDia.format(local) + " · " + hora
}

@Composable
private fun BurbujaDeChat(m: MensajeDelChat, comoEquipo: Boolean) {
    val esquema = MaterialTheme.colorScheme
    // Del lado de quien mira: la persona ve los suyos a la derecha; un
    // administrador, los del equipo.
    val mio = m.deAdmin == comoEquipo
    val cuando = cuandoDelChat(m)
    val quien: String? = when {
        mio && comoEquipo && m.autor != null && m.autor != "Tú" -> m.autor
        mio || comoEquipo -> null
        else -> m.autor ?: "Equipo de Voces"
    }
    val estadoLeido = if (mio) (if (m.leidoEn != null) " · Leído" else " · Enviado") else ""

    Box(Modifier.fillMaxWidth(), contentAlignment = if (mio) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .wrapContentWidth(if (mio) Alignment.End else Alignment.Start)
                .background(
                    if (mio) esquema.primary else esquema.surface,
                    RoundedCornerShape(16.dp)
                )
                .padding(horizontal = Espacio.md, vertical = Espacio.sm)
                .semantics(mergeDescendants = true) {
                    contentDescription = (if (mio) "Tú" else (quien ?: "La persona")) +
                        ", $cuando$estadoLeido: ${m.texto}"
                }
        ) {
            if (quien != null) {
                Text(
                    quien,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (mio) esquema.onPrimary.copy(alpha = 0.85f) else esquema.onSurfaceVariant
                )
            }
            Text(
                m.texto,
                style = MaterialTheme.typography.bodyLarge,
                color = if (mio) esquema.onPrimary else esquema.onSurface
            )
            Text(
                cuando + estadoLeido,
                style = MaterialTheme.typography.bodySmall,
                color = (if (mio) esquema.onPrimary else esquema.onSurfaceVariant).copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}
