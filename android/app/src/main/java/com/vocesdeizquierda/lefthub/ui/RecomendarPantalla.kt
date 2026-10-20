package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vocesdeizquierda.lefthub.data.Recomendacion
import com.vocesdeizquierda.lefthub.recomendar.EstadoRecomendar
import com.vocesdeizquierda.lefthub.recomendar.RecomendarViewModel

/**
 * "Recomendar un canal": la persona propone un canal de YouTube para el
 * directorio y ve qué pasó con los que ya propuso.
 *
 * Solo se llega desde Ajustes, y solo si el equipo se lo permite a esta
 * cuenta. Lo que se pide es lo mínimo: el enlace o el nombre. El porqué es
 * opcional.
 */
@Composable
fun RecomendarPantalla(
    modelo: RecomendarViewModel,
    onVolver: () -> Unit
) {
    val estado by modelo.estado.collectAsState()
    RecomendarContenido(
        estado = estado,
        onRecomendar = modelo::recomendar,
        onReintentar = modelo::cargar,
        onVolver = onVolver
    )
}

@Composable
fun RecomendarContenido(
    estado: EstadoRecomendar,
    onRecomendar: (String, String, () -> Unit) -> Unit,
    onReintentar: () -> Unit,
    onVolver: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars))
            .verticalScroll(rememberScrollState())
            .padding(Espacio.md)
    ) {
        TextButton(
            onClick = onVolver,
            contentPadding = PaddingValues(horizontal = 0.dp),
            modifier = Modifier.heightIn(min = Tactil.minimo)
        ) {
            Text("‹ Volver", style = MaterialTheme.typography.labelLarge)
        }
        Text(
            "Recomendar un canal",
            style = MaterialTheme.typography.headlineMedium,
            color = esquema.onBackground,
            modifier = Modifier.semantics { heading() }
        )

        if (estado.noDisponible) {
            Text(
                estado.error ?: "Por ahora no se pueden recomendar canales.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = Espacio.md, bottom = Espacio.lg)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
            BotonGrande(titulo = "Volver", variante = VarianteBoton.SECUNDARIO, onClick = onVolver)
        } else {
            FormularioYLista(estado, onRecomendar, onReintentar)
        }
    }
}

/** Lo que se ve mientras se puede recomendar: el formulario y las que ya mandó. */
@Composable
private fun ColumnScope.FormularioYLista(
    estado: EstadoRecomendar,
    onRecomendar: (String, String, () -> Unit) -> Unit,
    onReintentar: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme
    // Lo escrito sobrevive a girar el teléfono, no a salir de la pantalla.
    var canal by rememberSaveable { mutableStateOf("") }
    var motivo by rememberSaveable { mutableStateOf("") }
    var comoCopiar by rememberSaveable { mutableStateOf(false) }

    Text(
        "¿Conoces un canal de YouTube que debería estar aquí? Dinos cuál. " +
            "El equipo lo revisa antes de agregarlo al directorio.",
        style = MaterialTheme.typography.bodyLarge,
        color = esquema.onSurfaceVariant,
        modifier = Modifier.padding(top = Espacio.xs, bottom = Espacio.lg)
    )

    // --- El canal -------------------------------------------------------
    OutlinedTextField(
        value = canal,
        onValueChange = { canal = it.take(RecomendarViewModel.MAX_TEXTO) },
        label = { Text("Enlace o nombre del canal") },
        supportingText = {
            Text("Puedes pegar el enlace del canal o de uno de sus videos. Si no lo tienes, escribe su nombre.")
        },
        textStyle = MaterialTheme.typography.bodyLarge,
        singleLine = false,
        maxLines = 3,
        enabled = !estado.enviando,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next
        ),
        modifier = Modifier.fillMaxWidth()
    )

    TextButton(
        onClick = { comoCopiar = !comoCopiar },
        contentPadding = PaddingValues(horizontal = 0.dp),
        modifier = Modifier.heightIn(min = Tactil.minimo)
    ) {
        Text(
            if (comoCopiar) "Ocultar la ayuda" else "¿Cómo copio el enlace?",
            style = MaterialTheme.typography.labelLarge
        )
    }
    if (comoCopiar) {
        Surface(
            color = esquema.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Espacio.md)
        ) {
            Column(Modifier.padding(Espacio.md)) {
                PasoDeAyuda(1, "En la app de YouTube, abre el canal o uno de sus videos.")
                PasoDeAyuda(2, "Toca «Compartir» y luego «Copiar enlace».")
                PasoDeAyuda(3, "Vuelve aquí, deja el dedo apretado sobre el recuadro de arriba y toca «Pegar».")
            }
        }
    }

    // --- Por qué --------------------------------------------------------
    OutlinedTextField(
        value = motivo,
        onValueChange = { motivo = it.take(RecomendarViewModel.MAX_MOTIVO) },
        label = { Text("¿Por qué lo recomiendas? (no es obligatorio)") },
        textStyle = MaterialTheme.typography.bodyLarge,
        minLines = 2,
        maxLines = 5,
        enabled = !estado.enviando,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Espacio.xs)
    )

    // --- Resultado ------------------------------------------------------
    val aviso = estado.error ?: estado.gracias
    if (aviso != null) {
        Text(
            aviso,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (estado.error != null) esquema.error else esquema.primary,
            modifier = Modifier
                .padding(top = Espacio.md)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
    }

    val sinCupo = !estado.cargando && estado.quedanHoy <= 0
    BotonGrande(
        titulo = if (estado.enviando) "Enviando…" else "Enviar recomendación",
        habilitado = canal.isNotBlank() && !estado.enviando && !sinCupo,
        onClick = {
            onRecomendar(canal, motivo) {
                canal = ""
                motivo = ""
                comoCopiar = false
            }
        },
        modifier = Modifier.padding(top = Espacio.md)
    )
    if (sinCupo) {
        Text(
            "Ya mandaste las recomendaciones de hoy. Gracias; mañana puedes mandar más.",
            style = MaterialTheme.typography.bodySmall,
            color = esquema.onSurfaceVariant
        )
    }

    // --- Las que ya mandó -----------------------------------------------
    HorizontalDivider(color = esquema.outline, modifier = Modifier.padding(top = Espacio.lg))
    Text(
        "Tus recomendaciones",
        style = MaterialTheme.typography.headlineMedium,
        color = esquema.onBackground,
        modifier = Modifier
            .padding(top = Espacio.lg, bottom = Espacio.md)
            .semantics { heading() }
    )
    when {
        estado.cargando -> Text(
            "Cargando…",
            style = MaterialTheme.typography.bodyLarge,
            color = esquema.onSurfaceVariant
        )
        estado.recomendaciones.isEmpty() && estado.error != null && estado.gracias == null -> {
            BotonGrande(titulo = "Volver a intentar", variante = VarianteBoton.SECUNDARIO, onClick = onReintentar)
        }
        estado.recomendaciones.isEmpty() -> Text(
            "Todavía no has recomendado ningún canal.",
            style = MaterialTheme.typography.bodyLarge,
            color = esquema.onSurfaceVariant
        )
        else -> estado.recomendaciones.forEach { FilaRecomendacion(it) }
    }
}

@Composable
private fun PasoDeAyuda(numero: Int, texto: String) {
    Row(Modifier.padding(vertical = Espacio.xs)) {
        Text(
            "$numero.",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )
        Text(texto, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FilaRecomendacion(r: Recomendacion) {
    val esquema = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Espacio.md)
            .semantics(mergeDescendants = true) {}
    ) {
        Avatar(url = r.fotoUrl, nombre = r.nombre, tamano = 44.dp)
        Column(
            Modifier
                .weight(1f)
                .padding(start = Espacio.md)
        ) {
            Text(
                r.nombre,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = esquema.onBackground,
                maxLines = 2
            )
            Text(
                r.estadoEnPalabras,
                style = MaterialTheme.typography.bodyLarge,
                color = if (r.enDirectorio || r.estado == Recomendacion.AGREGADO) esquema.primary else esquema.onSurfaceVariant
            )
            val cuando = tiempoRelativo(r.creadoEn)
            if (cuando.isNotEmpty()) {
                Text(
                    "Mandada: $cuando",
                    style = MaterialTheme.typography.bodySmall,
                    color = esquema.onSurfaceVariant
                )
            }
        }
    }
}
