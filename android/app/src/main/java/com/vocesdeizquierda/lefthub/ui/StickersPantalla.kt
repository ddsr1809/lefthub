package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vocesdeizquierda.lefthub.data.Creador
import com.vocesdeizquierda.lefthub.data.PaqueteDeStickers
import com.vocesdeizquierda.lefthub.stickers.Bajada
import com.vocesdeizquierda.lefthub.stickers.EstadoStickers
import java.io.File

/**
 * El apartado de Stickers: los creadores que tienen stickers. Al tocar uno se
 * abren los suyos.
 *
 * Es un apartado aparte, con su pestaña, y solo sale si el equipo lo encendió
 * para esta cuenta desde el panel.
 */
@Composable
fun StickersPantalla(
    estado: EstadoStickers,
    /** Los del directorio, para la foto de cada creador. */
    creadores: List<Creador>,
    onAbrirCreador: (String) -> Unit,
    onReintentar: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme

    when {
        estado.cargando -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = esquema.primary)
        }

        estado.noDisponible -> Vacio(
            titulo = "Stickers",
            mensaje = estado.error ?: "Los stickers no están disponibles por ahora."
        )

        estado.error != null && estado.paquetes.isEmpty() -> Vacio(
            titulo = "No pudimos traer los stickers",
            mensaje = estado.error.orEmpty(),
            accion = { BotonGrande(titulo = "Intentar otra vez", onClick = onReintentar) }
        )

        estado.paquetes.isEmpty() -> Vacio(
            titulo = "Todavía no hay stickers",
            mensaje = "Cuando el equipo publique stickers de algún creador, aparecerán aquí."
        )

        else -> LazyColumn(
            contentPadding = PaddingValues(Espacio.md),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Text(
                    "Stickers",
                    style = MaterialTheme.typography.headlineMedium,
                    color = esquema.onBackground,
                    modifier = Modifier.semantics { heading() }
                )
                Text(
                    "De los creadores, para mandarlos por WhatsApp, Telegram, Signal, Messenger y otras apps. " +
                        "Toca un creador para ver los suyos.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = esquema.onSurfaceVariant,
                    modifier = Modifier.padding(top = Espacio.xs, bottom = Espacio.md)
                )
            }
            items(estado.creadores, key = { it }) { id ->
                val paquetes = estado.deCreador(id)
                val delDirectorio = creadores.firstOrNull { it.id == id }
                FilaDeCreadorConStickers(
                    nombre = delDirectorio?.name ?: paquetes.first().creador,
                    foto = delDirectorio?.photoUrl,
                    paquetes = paquetes,
                    onAbrir = { onAbrirCreador(id) }
                )
            }
        }
    }
}

@Composable
private fun FilaDeCreadorConStickers(
    nombre: String,
    foto: String?,
    paquetes: List<PaqueteDeStickers>,
    onAbrir: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme
    val cuantos = paquetes.sumOf { it.stickers.size }
    val detalle = (if (paquetes.size == 1) "1 paquete" else "${paquetes.size} paquetes") +
        " · $cuantos stickers"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Espacio.sm)
            .clip(RoundedCornerShape(12.dp))
            .background(esquema.surface)
            .border(1.dp, esquema.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onAbrir)
            .heightIn(min = Tactil.principal + 16.dp)
            .padding(horizontal = Espacio.md, vertical = Espacio.sm)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "$nombre. $detalle. Ver sus stickers."
            }
    ) {
        Avatar(url = foto, nombre = nombre)
        Column(
            Modifier
                .weight(1f)
                .padding(start = Espacio.md)
        ) {
            Text(nombre, style = MaterialTheme.typography.bodyMedium, color = esquema.onSurface)
            Text(detalle, style = MaterialTheme.typography.bodySmall, color = esquema.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.headlineMedium, color = esquema.onSurfaceVariant)
    }
}

/**
 * Los stickers de un creador, paquete por paquete. Se bajan al teléfono al
 * entrar; ya bajados, cada paquete se agrega entero a WhatsApp, y cada
 * sticker se manda suelto a cualquier app tocándolo.
 */
@Composable
fun StickersDeCreadorPantalla(
    nombre: String,
    foto: String?,
    paquetes: List<PaqueteDeStickers>,
    bajadas: Map<String, Bajada>,
    /** La imagen bajada de un sticker, o null si todavía no está. */
    imagen: (paqueteId: String, stickerId: String) -> File?,
    /** Hay algún WhatsApp en el teléfono. */
    hayWhatsApp: Boolean,
    /** Los paquetes que WhatsApp dice que ya tiene. */
    enWhatsApp: Set<String>,
    cargando: Boolean,
    onReintentar: (PaqueteDeStickers) -> Unit,
    onAgregarAWhatsApp: (PaqueteDeStickers) -> Unit,
    onMandar: (File) -> Unit,
    onVolver: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme

    LazyColumn(
        contentPadding = PaddingValues(Espacio.md),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            TextButton(
                onClick = onVolver,
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.heightIn(min = Tactil.minimo)
            ) {
                Text("‹ Volver", style = MaterialTheme.typography.labelLarge)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(url = foto, nombre = nombre)
                Text(
                    "Stickers de $nombre",
                    style = MaterialTheme.typography.headlineMedium,
                    color = esquema.onBackground,
                    modifier = Modifier
                        .padding(start = Espacio.md)
                        .semantics { heading() }
                )
            }
            Text(
                "Toca un sticker para mandarlo por Telegram, Signal, Messenger o cualquier otra app." +
                    if (hayWhatsApp) " Para tener el paquete entero en WhatsApp, usa el botón de abajo de cada uno." else "",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant,
                modifier = Modifier.padding(top = Espacio.sm, bottom = Espacio.md)
            )
        }

        if (paquetes.isEmpty()) {
            item {
                if (cargando) {
                    Box(Modifier.fillMaxWidth().padding(Espacio.xl), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = esquema.primary)
                    }
                } else {
                    Text(
                        "Este creador ya no tiene stickers publicados.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = esquema.onSurfaceVariant
                    )
                }
            }
        }

        items(paquetes, key = { it.id }) { p ->
            TarjetaDePaquete(
                paquete = p,
                bajada = bajadas[p.id] ?: Bajada(bajando = true),
                imagen = { imagen(p.id, it) },
                hayWhatsApp = hayWhatsApp,
                yaEnWhatsApp = p.id in enWhatsApp,
                onReintentar = { onReintentar(p) },
                onAgregarAWhatsApp = { onAgregarAWhatsApp(p) },
                onMandar = onMandar
            )
        }
    }
}

@Composable
private fun TarjetaDePaquete(
    paquete: PaqueteDeStickers,
    bajada: Bajada,
    imagen: (String) -> File?,
    hayWhatsApp: Boolean,
    yaEnWhatsApp: Boolean,
    onReintentar: () -> Unit,
    onAgregarAWhatsApp: () -> Unit,
    onMandar: (File) -> Unit
) {
    val esquema = MaterialTheme.colorScheme
    val cuantos = paquete.stickers.size

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Espacio.lg)
            .clip(RoundedCornerShape(16.dp))
            .background(esquema.surface)
            .border(1.dp, esquema.outline, RoundedCornerShape(16.dp))
            .padding(Espacio.md)
    ) {
        Text(
            paquete.nombre,
            style = MaterialTheme.typography.labelLarge,
            color = esquema.onSurface,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            "$cuantos stickers" + if (paquete.animado) ", animados" else "",
            style = MaterialTheme.typography.bodySmall,
            color = esquema.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Espacio.sm)
        )

        when {
            bajada.error != null -> {
                Text(
                    bajada.error.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = esquema.error,
                    modifier = Modifier
                        .padding(vertical = Espacio.sm)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                )
                BotonGrande(titulo = "Intentar otra vez", variante = VarianteBoton.SECUNDARIO, onClick = onReintentar)
            }

            !bajada.lista -> {
                Text(
                    "Bajando los stickers a tu teléfono…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = esquema.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Espacio.sm)
                )
                if (bajada.de > 0) {
                    LinearProgressIndicator(
                        progress = { bajada.van.toFloat() / bajada.de },
                        color = esquema.primary,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(color = esquema.primary, modifier = Modifier.fillMaxWidth())
                }
            }

            else -> {
                // Cuatro por fila. Sin rejilla perezosa: va dentro de una lista
                // que ya se desplaza, y como mucho son 30.
                paquete.stickers.chunked(POR_FILA).forEachIndexed { fila, grupo ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Espacio.sm),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = Espacio.sm)
                    ) {
                        grupo.forEachIndexed { i, s ->
                            val archivo = imagen(s.id)
                            val numero = fila * POR_FILA + i + 1
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(esquema.surfaceVariant)
                                    .then(
                                        if (archivo != null) Modifier.clickable { onMandar(archivo) } else Modifier
                                    )
                                    .semantics(mergeDescendants = true) {
                                        role = Role.Button
                                        contentDescription = "Sticker $numero" +
                                            (s.emojis.firstOrNull()?.let { " $it" } ?: "") + ". Toca para mandarlo."
                                    }
                            ) {
                                if (archivo != null) {
                                    AsyncImage(
                                        model = archivo,
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(Espacio.xs)
                                    )
                                }
                            }
                        }
                        repeat(POR_FILA - grupo.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                Spacer(Modifier.height(Espacio.sm))
                if (hayWhatsApp) {
                    BotonGrande(
                        titulo = if (yaEnWhatsApp) "Ya está en tu WhatsApp" else "Agregar a WhatsApp",
                        subtitulo = if (yaEnWhatsApp) "Tócalo para ponerlo al día" else "El paquete entero, con sus $cuantos stickers",
                        variante = if (yaEnWhatsApp) VarianteBoton.SECUNDARIO else VarianteBoton.PRIMARIO,
                        onClick = onAgregarAWhatsApp
                    )
                } else {
                    Text(
                        "Para tener el paquete entero necesitas WhatsApp. Sin él, puedes mandar cada sticker tocándolo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = esquema.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private const val POR_FILA = 4
