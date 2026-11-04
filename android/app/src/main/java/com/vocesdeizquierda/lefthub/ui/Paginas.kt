package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop

// Las pantallas con varias listas (el Directorio y Novedades) se recorren de
// dos maneras: tocando el botón con el nombre de la lista o deslizando un dedo
// de lado. Los botones se quedan aunque se pueda deslizar: son lo que dice que
// hay más listas, y lo que usa quien no se lleva bien con el gesto.

/**
 * Las páginas de una pantalla, unidas con cuál está elegida.
 *
 * Tocar un botón cambia [elegida] y las páginas se mueven hasta ahí; deslizar
 * mueve las páginas y, al asentarse, se avisa con [onDeslizar]. La primera
 * lectura no se avisa: al abrir la app las listas todavía no han llegado, hay
 * una sola página, y avisarla borraría la lista elegida.
 *
 * @param elegida el número de la página elegida.
 * @param cuantas cuántas páginas hay: cambia si una lista se llena o se vacía.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberPaginas(elegida: Int, cuantas: Int, onDeslizar: (Int) -> Unit): PagerState {
    val paginas = rememberPagerState(initialPage = elegida, pageCount = { cuantas })
    val avisar by rememberUpdatedState(onDeslizar)

    // Se compara con la asentada y no con la actual: mientras el dedo sigue
    // moviendo las páginas, esto no debe llevárselas de vuelta.
    LaunchedEffect(elegida, cuantas) {
        if (paginas.settledPage != elegida) paginas.animateScrollToPage(elegida)
    }

    LaunchedEffect(paginas) {
        snapshotFlow { paginas.settledPage }.drop(1).collect { avisar(it) }
    }

    return paginas
}

/**
 * Los botones de arriba, uno por lista, en una sola fila. La elegida va
 * rellena. Con la letra grande pueden no caber todos: la fila se desliza y se
 * mueve sola para que la elegida quede a la vista.
 */
@Composable
internal fun <T> SelectorDePaginas(
    opciones: List<Pair<T, String>>,
    elegida: T,
    onElegir: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val esquema = MaterialTheme.colorScheme
    val fila = rememberLazyListState()
    val indice = opciones.indexOfFirst { it.first == elegida }

    LaunchedEffect(indice) {
        val vista = fila.layoutInfo
        val boton = vista.visibleItemsInfo.firstOrNull { it.index == indice }
        val cabe = boton != null && boton.offset >= vista.viewportStartOffset &&
            boton.offset + boton.size <= vista.viewportEndOffset
        if (indice >= 0 && !cabe) fila.animateScrollToItem(indice)
    }

    LazyRow(
        state = fila,
        horizontalArrangement = Arrangement.spacedBy(Espacio.sm),
        contentPadding = PaddingValues(horizontal = Espacio.md),
        modifier = modifier
    ) {
        items(opciones) { (clave, nombre) ->
            val activa = clave == elegida
            Button(
                onClick = { onElegir(clave) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (activa) esquema.primary else esquema.surface,
                    contentColor = if (activa) esquema.onPrimary else esquema.onSurface
                ),
                border = if (activa) null
                else androidx.compose.foundation.BorderStroke(1.dp, esquema.outline),
                contentPadding = PaddingValues(horizontal = Espacio.lg, vertical = Espacio.sm),
                modifier = Modifier
                    .heightIn(min = Tactil.principal)
                    .semantics {
                        role = Role.Tab
                        selected = activa
                    }
            ) {
                Text(nombre, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
