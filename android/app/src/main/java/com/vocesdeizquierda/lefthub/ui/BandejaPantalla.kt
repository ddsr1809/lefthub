package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vocesdeizquierda.lefthub.chat.BandejaViewModel
import com.vocesdeizquierda.lefthub.data.ConversacionDelChat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val horaDeBandeja = DateTimeFormatter.ofPattern("HH:mm")
private val diaDeBandeja = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("es-MX"))

/**
 * La bandeja del chat de un administrador: las cuentas a las que el equipo
 * les activó el chat, primero las que esperan respuesta. Solo se llega desde
 * Ajustes, y solo si la cuenta es del equipo.
 */
@Composable
fun BandejaPantalla(
    modelo: BandejaViewModel,
    onAbrir: (ConversacionDelChat) -> Unit,
    onVolver: () -> Unit
) {
    val estado by modelo.estado.collectAsState()
    val esquema = MaterialTheme.colorScheme

    LifecycleEventEffect(Lifecycle.Event.ON_START) { modelo.alVerse() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { modelo.alOcultarse() }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = Espacio.md, end = Espacio.md, top = Espacio.md)) {
            TextButton(
                onClick = onVolver,
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.heightIn(min = Tactil.minimo)
            ) {
                Text("‹ Volver", style = MaterialTheme.typography.labelLarge)
            }
            Text(
                "Chat con las cuentas",
                style = MaterialTheme.typography.headlineMedium,
                color = esquema.onBackground,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                "Para escribirle a alguien nuevo, actívale el chat en el panel (Usuarios → Ver).",
                style = MaterialTheme.typography.bodySmall,
                color = esquema.onSurfaceVariant,
                modifier = Modifier.padding(top = Espacio.xs, bottom = Espacio.sm)
            )
        }

        val error = estado.error
        when {
            estado.cargando -> Box(Modifier.fillMaxWidth().padding(Espacio.xl), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            error != null && estado.conversaciones.isEmpty() -> Vacio(
                titulo = "No se pudo cargar",
                mensaje = error,
                accion = { BotonGrande("Volver a intentar", onClick = modelo::alVerse) }
            )
            estado.conversaciones.isEmpty() -> Vacio(
                titulo = "Sin conversaciones",
                mensaje = "Ninguna cuenta tiene el chat activado todavía."
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = Espacio.md, vertical = Espacio.sm),
                verticalArrangement = Arrangement.spacedBy(Espacio.sm),
                modifier = Modifier.fillMaxSize()
            ) {
                items(estado.conversaciones, key = { it.id }) { c -> FilaDeConversacion(c) { onAbrir(c) } }
            }
        }
    }
}

@Composable
private fun FilaDeConversacion(c: ConversacionDelChat, onAbrir: () -> Unit) {
    val esquema = MaterialTheme.colorScheme
    val cuando = c.ultimoEn?.atZone(ZoneId.systemDefault())?.let {
        if (it.toLocalDate() == LocalDate.now()) horaDeBandeja.format(it) else diaDeBandeja.format(it)
    }
    val vistazo = c.ultimo?.let { (if (c.ultimoDeAdmin) "Equipo: " else "") + it } ?: "Sin mensajes todavía"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Tactil.principal)
            .background(esquema.surface, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onAbrir)
            .padding(Espacio.md)
            .semantics(mergeDescendants = true) {
                contentDescription = c.nombre +
                    (if (c.sinLeer > 0) ", ${c.sinLeer} sin leer" else "") +
                    ". " + vistazo
            }
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                c.nombre,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (c.sinLeer > 0) FontWeight.Bold else FontWeight.Normal,
                color = esquema.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                vistazo,
                style = MaterialTheme.typography.bodySmall,
                color = esquema.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = Espacio.sm)) {
            if (cuando != null) {
                Text(cuando, style = MaterialTheme.typography.bodySmall, color = esquema.onSurfaceVariant)
            }
            if (c.sinLeer > 0) {
                Badge(containerColor = esquema.primary, contentColor = esquema.onPrimary,
                    modifier = Modifier.padding(top = Espacio.xs)) {
                    Text(c.sinLeer.toString(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
