package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.vocesdeizquierda.lefthub.data.Canal
import com.vocesdeizquierda.lefthub.data.CanalListado
import com.vocesdeizquierda.lefthub.data.EstadoYouTube
import com.vocesdeizquierda.lefthub.data.Perfil

/**
 * Antes de seguir a alguien: dice a cuántos canales de YouTube se va a seguir
 * y cuáles son, con una marca en cada uno. La persona puede:
 *
 *  - dejarlos todos marcados y seguir: sigue al creador entero, y con él
 *    los canales que se le agreguen después;
 *  - quitar la marca a los que no quiere: sigue solo los marcados, uno por uno;
 *  - ir a verlos antes de decidir (la ficha del canal, o la pantalla con todos);
 *  - o dejarlo para después.
 *
 * @param onSeguir recibe los ids de los canales que dejó marcados.
 */
@Composable
fun VentanaAlSeguir(
    por: PorSeguir,
    perfil: Perfil,
    onSeguir: (Set<String>) -> Unit,
    onVerCanales: () -> Unit,
    onCancelar: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme
    var marcados by remember(por) { mutableStateOf(por.marcasIniciales(perfil)) }
    val cuantos = por.canales.size
    val elegidos = marcados.size
    // Ya sigue algunos sueltos: la ventana sirve para cambiar cuáles.
    val parcial = por.parcial(perfil)

    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(if (parcial) "Canales de ${por.nombre}" else "¿Seguir a ${por.nombre}?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (cuantos == 1) {
                    val unico = por.canales.single()
                    Text(
                        "Vas a seguir su canal de YouTube:",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        unico.nombreCanal,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Espacio.sm)
                    )
                    Text(
                        "Te avisamos cada vez que publique un video.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = Espacio.sm, bottom = Espacio.lg)
                    )
                } else {
                    Text(
                        if (parcial)
                            "Sigues ${perfil.sueltosDe(por.canales.map { it.canal })} de sus $cuantos canales " +
                                "de YouTube. Marca los que quieras seguir y quita la marca a los que no."
                        else
                            "Vas a seguir sus $cuantos canales de YouTube y te avisamos de lo que " +
                                "publique en cada uno. Si no quieres alguno, tócalo para quitarle la marca.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = Espacio.md)
                    )
                    por.canales.forEachIndexed { i, listado ->
                        CanalConMarca(
                            listado = listado,
                            // De quién es, solo si no es la misma persona que se sigue.
                            deOtro = listado.dueno.takeIf { it != por.nombre },
                            marcado = listado.canal.id in marcados,
                            conFranja = i % 2 == 0,
                            onCambiar = { si ->
                                marcados = if (si) marcados + listado.canal.id else marcados - listado.canal.id
                            }
                        )
                    }
                    Spacer(Modifier.height(Espacio.lg))
                }

                BotonGrande(
                    titulo = when {
                        cuantos == 1 -> "Seguir"
                        elegidos == cuantos -> "Seguir los $cuantos canales"
                        elegidos == 1 -> "Seguir solo 1 canal"
                        elegidos == 0 && parcial -> "Dejar de seguir sus canales"
                        elegidos == 0 -> "Marca al menos un canal"
                        else -> "Seguir solo $elegidos canales"
                    },
                    subtitulo = when {
                        elegidos == 0 && parcial -> "Ya no te avisaremos de ninguno"
                        elegidos == 0 -> null
                        elegidos == cuantos -> "También los que se le agreguen después"
                        else -> "Puedes cambiarlo cuando quieras en su ficha"
                    },
                    habilitado = elegidos > 0 || parcial,
                    onClick = { onSeguir(marcados) }
                )
                BotonGrande(
                    titulo = if (cuantos == 1) "Ver el canal primero" else "Ver sus canales antes de decidir",
                    subtitulo = "Ahí puedes seguirlos uno por uno",
                    variante = VarianteBoton.SECUNDARIO,
                    onClick = onVerCanales
                )
            }
        },
        // Los caminos van arriba, grandes. Aquí queda solo no hacer nada.
        confirmButton = {
            TextButton(
                onClick = onCancelar,
                modifier = Modifier.heightIn(min = Tactil.minimo)
            ) { Text("Ahora no", style = MaterialTheme.typography.labelLarge, color = esquema.primary) }
        }
    )
}

/**
 * Un canal con su casilla. Toda la fila se toca, no solo la casilla, y el
 * lector de pantalla la anuncia como una casilla con su nombre.
 */
@Composable
private fun CanalConMarca(
    listado: CanalListado,
    deOtro: String?,
    marcado: Boolean,
    conFranja: Boolean,
    onCambiar: (Boolean) -> Unit
) {
    val esquema = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Tactil.minimo)
            .clip(RoundedCornerShape(12.dp))
            .background(if (conFranja) LocalFranja.current else Color.Transparent)
            .toggleable(value = marcado, role = Role.Checkbox, onValueChange = onCambiar)
            .padding(horizontal = Espacio.sm, vertical = Espacio.sm)
    ) {
        // Sin onCheckedChange: la fila entera ya cambia la marca.
        Checkbox(checked = marcado, onCheckedChange = null)
        Column(Modifier.padding(start = Espacio.sm)) {
            Text(
                listado.nombreCanal,
                style = MaterialTheme.typography.bodyMedium,
                color = esquema.onBackground
            )
            val detalle = deOtro?.let { if (listado.duenoEsMedio) "Del medio $it" else "De $it" }.orEmpty()
            if (detalle.isNotBlank()) {
                Text(
                    detalle,
                    style = MaterialTheme.typography.bodySmall,
                    color = esquema.onSurfaceVariant
                )
            }
        }
    }
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
