package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vocesdeizquierda.lefthub.BuildConfig
import com.vocesdeizquierda.lefthub.data.Abiertos
import com.vocesdeizquierda.lefthub.data.Creador
import com.vocesdeizquierda.lefthub.data.EstadoYouTube
import com.vocesdeizquierda.lefthub.data.Productora
import com.vocesdeizquierda.lefthub.data.VideosDeCanal
import com.vocesdeizquierda.lefthub.enlaces.Enrutador

/**
 * Ficha de una productora: la casa detrás de varios creadores.
 *
 * Seguirla no es seguir a cada persona que trabaja con ella: avisa de lo que
 * se publica en los canales que le pertenecen, que son los que se listan aquí.
 * A cada creador se le sigue aparte, desde su fila.
 */
@Composable
fun ProductoraPantalla(
    productora: Productora?,
    siguiendo: Boolean,
    onSeguir: () -> Unit,
    onVolver: () -> Unit,
    // El directorio entero: de aquí salen el nombre del dueño de cada canal y
    // las filas de los creadores que figuran en ella.
    creadores: List<Creador> = emptyList(),
    favoritos: List<String> = emptyList(),
    youtube: EstadoYouTube = EstadoYouTube(),
    onSeguirCreador: (String) -> Unit = {},
    onAbrirCreador: (String) -> Unit = {},
    onAbrirCanal: (String) -> Unit = {},
    /** Sus últimos videos: el mini feed de arriba. */
    videos: VideosDeCanal = VideosDeCanal(),
    /** Los videos que ya abrió desde la app: su fila se ve distinta. */
    abiertos: Set<String> = emptySet()
) {
    val esquema = MaterialTheme.colorScheme
    val contexto = LocalContext.current

    if (productora == null) {
        Vacio(
            titulo = "Este medio ya no está",
            mensaje = "Puede que lo hayamos retirado del directorio. Vuelve al listado para ver los demás.",
            accion = { BotonGrande("Volver al directorio", onClick = onVolver) }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Espacio.md)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Espacio.lg)
        ) {
            Avatar(productora.logoUrl, productora.nombre, tamano = 88.dp)
            Text(
                productora.nombre,
                style = MaterialTheme.typography.displayLarge,
                color = esquema.onBackground,
                modifier = Modifier.padding(top = Espacio.md)
            )
            if (!productora.descripcion.isNullOrBlank()) {
                Text(
                    productora.descripcion,
                    style = MaterialTheme.typography.bodyLarge,
                    color = esquema.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(top = Espacio.sm)
                )
            }
        }

        BotonGrande(
            titulo = if (siguiendo) "Ya recibes sus avisos" else "Avísame cuando publique",
            subtitulo = if (siguiendo)
                "Toca para dejar de recibirlos"
            else
                "Te avisamos de lo que salga en los canales de este medio",
            variante = if (siguiendo) VarianteBoton.SECUNDARIO else VarianteBoton.PRIMARIO,
            onClick = onSeguir
        )

        // Lo último que salió en sus canales: unos pocos videos en filas
        // pequeñas. Si no hay ninguno, el apartado no sale.
        if (videos.lista.isNotEmpty()) {
            Text(
                "Sus últimos videos",
                style = MaterialTheme.typography.headlineMedium,
                color = esquema.onBackground,
                modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.md)
            )
            videos.lista.forEach { publicacion ->
                FilaVideo(
                    publicacion = publicacion,
                    abierto = publicacion.videoId in abiertos,
                    sinFirmarPor = productora.nombre,
                    onAbrir = {
                        val destino = publicacion.destino
                        Enrutador.abrirVideo(
                            contexto,
                            destino.plataforma,
                            destino.videoId,
                            destino.url,
                            campana = "ficha_productora"
                        )
                        Abiertos.marcar(contexto, publicacion.videoId)
                    }
                )
            }
        }

        // Después, por dónde seguir: sus creadores y sus canales. Sus
        // creadores son los que figuran en el medio y, además, los dueños de
        // sus canales y quienes aparecen en ellos, aunque nadie los haya
        // marcado como parte de la casa.
        val enlazados = productora.canales.flatMap { listOfNotNull(it.creadorId) + it.tambien }.toSet()
        val figuran = creadores.filter {
            !it.esProductora && (it.id in productora.creadores || it.id in enlazados)
        }
        if (figuran.isNotEmpty()) {
            Text(
                "Sus creadores",
                style = MaterialTheme.typography.headlineMedium,
                color = esquema.onBackground,
                modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.sm)
            )
            figuran.forEach { creador ->
                FilaCreador(
                    creador = creador,
                    siguiendo = creador.id in favoritos,
                    onAbrir = { onAbrirCreador(creador.id) },
                    onSeguir = { onSeguirCreador(creador.id) },
                    suscritoEnYouTube = youtube.suscritoA(creador.id)
                )
            }
        }

        Text(
            "Sus canales",
            style = MaterialTheme.typography.headlineMedium,
            color = esquema.onBackground,
            modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.md)
        )

        if (productora.canales.isEmpty()) {
            Text(
                "Todavía no hemos agregado sus canales.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant
            )
        }

        productora.canales.forEach { canal ->
            val suscrito = youtube.suscritoAlCanal(canal)
            val dueno = creadores.firstOrNull { it.id == canal.creadorId }

            // Aquí solo la frase cuando ya se sabe: el botón para dar el
            // permiso de YouTube vive en el perfil de cada creador y en Ajustes.
            if (BuildConfig.SUSCRIPCIONES_YOUTUBE && canal.esDeYouTube) {
                SuscripcionEnYouTube(
                    suscrito = suscrito,
                    youtube = youtube,
                    esAnonimo = false,
                    onConectar = {},
                    etiqueta = canal.nombre ?: dueno?.name,
                    soloSiSeSabe = true
                )
            }

            // De quién es, o con quién sale si es un canal propio de la casa.
            val con = creadores.filter { it.id in canal.tambien }.map { it.name }

            BotonCanal(
                canal = canal,
                dueno = dueno?.let { "Canal de ${it.name}" }
                    ?: con.takeIf { it.isNotEmpty() }?.let { "Con ${enumerar(it)}" },
                suscrito = suscrito,
                campana = "ficha_productora",
                onVerFicha = canal.id.takeIf { it.isNotBlank() && canal.esDeYouTube }
                    ?.let { id -> { onAbrirCanal(id) } }
            )
        }

        Text(
            "Los videos se ven en la app oficial de cada plataforma. Esta app solo te avisa y te lleva hasta allá.",
            style = MaterialTheme.typography.bodySmall,
            color = esquema.onSurfaceVariant,
            modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.xxl)
        )
    }
}

/** "Ana", "Ana y Luis", "Ana, Luis y Juan". */
internal fun enumerar(nombres: List<String>): String = when (nombres.size) {
    0 -> ""
    1 -> nombres[0]
    else -> nombres.dropLast(1).joinToString(", ") + " y " + nombres.last()
}
