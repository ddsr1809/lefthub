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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vocesdeizquierda.lefthub.chat.ChatViewModel
import com.vocesdeizquierda.lefthub.chat.EstadoChat
import com.vocesdeizquierda.lefthub.chat.FaseChat
import com.vocesdeizquierda.lefthub.chat.MensajeChat
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val formatoHora = DateTimeFormatter.ofPattern("HH:mm")

/**
 * El chat con el equipo.
 *
 * Mientras está abierta, la pantalla no deja hacer capturas ni sale su
 * contenido en la lista de apps recientes: la conversación no se guarda en
 * ningún sitio, y tampoco debe quedarse en una foto.
 */
@Composable
fun ChatPantalla(
    modelo: ChatViewModel,
    onVolver: () -> Unit
) {
    val estado by modelo.estado.collectAsState()
    val contexto = LocalContext.current

    DisposableEffect(Unit) {
        val ventana = (contexto as? Activity)?.window
        ventana?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { ventana?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    ChatContenido(
        estado = estado,
        onEnviar = modelo::enviar,
        onReintentar = modelo::entrar,
        onVolver = onVolver
    )
}

@Composable
fun ChatContenido(
    estado: EstadoChat,
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
                "Chat con el equipo",
                style = MaterialTheme.typography.headlineMedium,
                color = esquema.onBackground
            )
            Text(
                when (estado.fase) {
                    FaseChat.ENTRANDO -> "Conectando…"
                    FaseChat.ESPERANDO -> "Esperando a que alguien del equipo entre."
                    FaseChat.CONECTADO -> "Conectado. Los mensajes van cifrados."
                    FaseChat.TERMINADO -> "La conversación terminó."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = Espacio.xs)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
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
            // Va dentro de la lista y no fijo arriba: con la letra grande, lo
            // fijo se comería el espacio de los mensajes.
            item {
                Column {
                    Text(
                        "Nada de esta conversación se guarda: ni en tu teléfono ni en " +
                            "nuestro servidor. Al salir de esta pantalla, se borra.",
                        style = MaterialTheme.typography.bodySmall,
                        color = esquema.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = Espacio.sm)
                    )
                    CodigoDeSeguridad(estado.codigo)
                }
            }
            items(estado.mensajes, key = { it.id }) { BurbujaDeChat(it) }
        }

        // --- Escribir ---------------------------------------------------------
        HorizontalDivider(color = esquema.outline)
        Column(Modifier.padding(Espacio.md)) {
            if (estado.fase == FaseChat.TERMINADO) {
                BotonGrande("Volver a entrar al chat", onClick = onReintentar)
                BotonGrande("Salir", variante = VarianteBoton.SECUNDARIO, onClick = onVolver)
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
    val listo = estado.fase == FaseChat.CONECTADO

    OutlinedTextField(
        value = texto,
        onValueChange = { onTexto(it.take(ChatViewModel.MAX_TEXTO)) },
        label = { Text(if (listo) "Escribe tu mensaje" else "Espera a que el equipo entre") },
        enabled = listo && !estado.enviando,
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
        habilitado = listo && !estado.enviando && texto.isNotBlank(),
        onClick = { onEnviar(texto) { onTexto("") } },
        modifier = Modifier.padding(top = Espacio.sm)
    )
}

/**
 * Las 8 cifras que el equipo ve también en su pantalla. Si coinciden, la
 * conversación es solo entre los dos.
 */
@Composable
private fun CodigoDeSeguridad(codigo: String?) {
    if (codigo == null) return
    val esquema = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .padding(bottom = Espacio.sm)
            .fillMaxWidth()
            .background(esquema.surface, RoundedCornerShape(12.dp))
            .padding(Espacio.md)
            .semantics(mergeDescendants = true) {
                contentDescription = "Código de seguridad: ${codigo.toCharArray().joinToString(" ")}. " +
                    "Si el equipo te dice el mismo, nadie más puede leer esta conversación."
            }
    ) {
        Text("Código de seguridad", style = MaterialTheme.typography.bodySmall, color = esquema.onSurfaceVariant)
        Text(
            codigo,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = esquema.onSurface
        )
        Text(
            "Si alguien del equipo te dice el mismo número, nadie más puede leer esta conversación.",
            style = MaterialTheme.typography.bodySmall,
            color = esquema.onSurfaceVariant
        )
    }
}

@Composable
private fun BurbujaDeChat(m: MensajeChat) {
    val esquema = MaterialTheme.colorScheme
    if (m.nota) {
        Text(
            m.texto,
            style = MaterialTheme.typography.bodySmall,
            color = esquema.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Espacio.xs)
        )
        return
    }

    val hora = formatoHora.format(m.hora.atZone(ZoneId.systemDefault()))
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.mio) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .wrapContentWidth(if (m.mio) Alignment.End else Alignment.Start)
                .background(
                    if (m.mio) esquema.primary else esquema.surface,
                    RoundedCornerShape(16.dp)
                )
                .padding(horizontal = Espacio.md, vertical = Espacio.sm)
                .semantics(mergeDescendants = true) {
                    contentDescription = (if (m.mio) "Tú" else "El equipo") + ", a las $hora: ${m.texto}"
                }
        ) {
            if (!m.mio) {
                Text("Equipo de Voces", style = MaterialTheme.typography.bodySmall, color = esquema.onSurfaceVariant)
            }
            Text(
                m.texto,
                style = MaterialTheme.typography.bodyLarge,
                color = if (m.mio) esquema.onPrimary else esquema.onSurface
            )
            Text(
                hora,
                style = MaterialTheme.typography.bodySmall,
                color = (if (m.mio) esquema.onPrimary else esquema.onSurfaceVariant).copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.End)
            )
        }
    }
}
