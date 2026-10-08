package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vocesdeizquierda.lefthub.BuildConfig
import com.vocesdeizquierda.lefthub.data.Creador
import com.vocesdeizquierda.lefthub.data.EstadoYouTube
import com.vocesdeizquierda.lefthub.data.Productora

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
    onAbrirCreador: (String) -> Unit = {}
) {
    val esquema = MaterialTheme.colorScheme

    if (productora == null) {
        Vacio(
            titulo = "Esta productora ya no está",
            mensaje = "Puede que la hayamos retirado del directorio. Vuelve al listado para ver las demás.",
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
                "Te avisamos de lo que salga en los canales de esta productora",
            variante = if (siguiendo) VarianteBoton.SECUNDARIO else VarianteBoton.PRIMARIO,
            onClick = onSeguir
        )

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
                campana = "ficha_productora"
            )
        }

        val figuran = creadores.filter { it.id in productora.creadores }
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
            "Los videos se ven en la app oficial de cada plataforma. Esta app solo te avisa y te lleva hasta allá.",
            style = MaterialTheme.typography.bodySmall,
            color = esquema.onSurfaceVariant,
            modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.xxl)
        )
    }
}

/** "Ana", "Ana y Luis", "Ana, Luis y Juan". */
private fun enumerar(nombres: List<String>): String = when (nombres.size) {
    0 -> ""
    1 -> nombres[0]
    else -> nombres.dropLast(1).joinToString(", ") + " y " + nombres.last()
}
