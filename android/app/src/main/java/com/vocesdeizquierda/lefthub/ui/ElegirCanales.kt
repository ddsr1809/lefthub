package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vocesdeizquierda.lefthub.data.Canal
import com.vocesdeizquierda.lefthub.data.CanalListado
import com.vocesdeizquierda.lefthub.data.EstadoYouTube
import com.vocesdeizquierda.lefthub.data.Perfil

/**
 * Antes de seguir a alguien con varios canales: seguirlo es seguir todos, y
 * se dice. Si prefiere, pasa a la pantalla donde elige uno por uno. También
 * puede dejarlo para después: en su ficha cada canal tiene su botón.
 */
@Composable
fun VentanaSeguirTodos(
    por: PorSeguir,
    onSeguirTodos: () -> Unit,
    onElegir: () -> Unit,
    onCancelar: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text("¿Seguir a ${por.nombre}?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "${por.nombre} tiene ${por.canales} canales de YouTube. " +
                        "Al seguir a ${por.nombre} te avisamos de lo que publique en todos ellos.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    "Si prefieres, elige solo los canales que te interesan. " +
                        "Puedes hacerlo ahora o más tarde, desde su ficha.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = Espacio.md, bottom = Espacio.lg)
                )
                BotonGrande(
                    titulo = "Seguir todos sus canales",
                    subtitulo = "Los ${por.canales}, y los que se agreguen después",
                    onClick = onSeguirTodos
                )
                BotonGrande(
                    titulo = "Elegir qué canales seguir",
                    subtitulo = "Te enseñamos sus canales para que escojas",
                    variante = VarianteBoton.SECUNDARIO,
                    onClick = onElegir
                )
            }
        },
        // Los dos caminos van arriba, grandes. Aquí queda solo no hacer nada.
        confirmButton = {
            TextButton(
                onClick = onCancelar,
                modifier = Modifier.heightIn(min = Tactil.minimo)
            ) { Text("Ahora no", style = MaterialTheme.typography.labelLarge) }
        }
    )
}

/**
 * Los canales de un creador o de un medio, para seguir los que quiera.
 *
 * Es la misma fila que la lista "Canales de YouTube" del Directorio, con el
 * mismo botón de seguir. Si ya sigue a todos (sigue al creador o al medio),
 * se dice arriba: para quedarse solo con algunos primero hay que dejar de
 * seguirlos a todos, y el botón para eso está ahí mismo.
 *
 * @param nombre   de quién son; null si ya no está en el directorio.
 * @param listados sus canales de YouTube, ya en el orden de la lista.
 */
@Composable
fun ElegirCanalesPantalla(
    nombre: String?,
    listados: List<CanalListado>,
    sigueATodos: Boolean,
    perfil: Perfil,
    onSeguirCanal: (Canal) -> Unit,
    onSeguirTodos: () -> Unit,
    onAbrirCanal: (String) -> Unit,
    onListo: () -> Unit,
    youtube: EstadoYouTube = EstadoYouTube()
) {
    val esquema = MaterialTheme.colorScheme

    if (nombre == null || listados.isEmpty()) {
        Vacio(
            titulo = "No hay canales que elegir",
            mensaje = "Puede que lo hayamos retirado del directorio. Vuelve atrás para ver los demás.",
            accion = { BotonGrande("Volver", onClick = onListo) }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Espacio.md)
    ) {
        Text(
            "Canales de $nombre",
            style = MaterialTheme.typography.displayLarge,
            color = esquema.onBackground,
            modifier = Modifier.padding(top = Espacio.md, bottom = Espacio.sm)
        )

        if (sigueATodos) {
            Text(
                "✓ Ya sigues todos sus canales. Si quieres quedarte solo con algunos, " +
                    "primero deja de seguirlos todos y luego elige aquí los que quieras.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Espacio.md)
            )
            BotonGrande(
                titulo = "Dejar de seguir todos",
                subtitulo = "Y elegir aquí solo los que quiero",
                variante = VarianteBoton.SECUNDARIO,
                onClick = onSeguirTodos
            )
        } else {
            Text(
                "Toca «Seguir» en los canales de los que quieres recibir avisos. " +
                    "Puedes cambiarlo cuando quieras.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Espacio.md)
            )
        }

        listados.forEachIndexed { i, listado ->
            FilaCanal(
                listado = listado,
                onAbrir = { onAbrirCanal(listado.canal.id) },
                suscritoEnYouTube = youtube.suscritoAlCanal(listado.canal),
                siguiendo = perfil.recibe(listado.canal),
                onSeguir = { onSeguirCanal(listado.canal) },
                conFranja = i % 2 == 0
            )
        }

        Spacer(Modifier.height(Espacio.lg))
        BotonGrande(titulo = "Listo", onClick = onListo)

        if (!sigueATodos) {
            BotonGrande(
                titulo = "Mejor seguir todos sus canales",
                variante = VarianteBoton.SECUNDARIO,
                onClick = onSeguirTodos
            )
        }
        Spacer(Modifier.height(Espacio.xxl))
    }
}
