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
import com.vocesdeizquierda.lefthub.data.Abiertos
import com.vocesdeizquierda.lefthub.data.Canal
import com.vocesdeizquierda.lefthub.data.Creador
import com.vocesdeizquierda.lefthub.data.EstadoYouTube
import com.vocesdeizquierda.lefthub.data.Perfil
import com.vocesdeizquierda.lefthub.data.Productora
import com.vocesdeizquierda.lefthub.data.Publicacion
import com.vocesdeizquierda.lefthub.data.VideosDeCanal
import com.vocesdeizquierda.lefthub.enlaces.Enrutador

/**
 * Ficha de un canal de YouTube.
 *
 * Dice lo mismo que su ficha del panel, con palabras: de quién es, de qué
 * productora y con qué otros creadores aparece. Debajo, lo último que publicó.
 *
 * Un canal no se sigue: se sigue a su creador o a su productora, y de ahí
 * llegan los avisos. Por eso aquí no hay botón de seguir, sino el camino a
 * las fichas de quienes sí lo tienen.
 */
@Composable
fun CanalPantalla(
    canal: Canal?,
    // El directorio: de aquí salen los nombres de su dueño, de su productora
    // y de los demás creadores con los que aparece.
    creadores: List<Creador>,
    productoras: List<Productora>,
    videos: VideosDeCanal,
    onVolver: () -> Unit,
    youtube: EstadoYouTube = EstadoYouTube(),
    onAbrirCreador: (String) -> Unit = {},
    onAbrirProductora: (String) -> Unit = {},
    onReportar: (Publicacion) -> Unit = {},
    onReintentar: () -> Unit = {},
    /** Los videos que ya abrió desde la app: su tarjeta se ve distinta. */
    abiertos: Set<String> = emptySet(),
    /** A quién sigue: para saber si sigue este canal, suelto o por su dueño. */
    perfil: Perfil = Perfil(),
    onSeguirCanal: (Canal) -> Unit = {}
) {
    val contexto = LocalContext.current
    val esquema = MaterialTheme.colorScheme

    if (canal == null) {
        Vacio(
            titulo = "Este canal ya no está",
            mensaje = "Puede que lo hayamos retirado del directorio. Vuelve atrás para ver los demás.",
            accion = { BotonGrande("Volver", onClick = onVolver) }
        )
        return
    }

    // Las filas que aparecen en el directorio como productoras no son
    // creadores: no pueden ser dueñas de un canal ni salir en él.
    val dueno = creadores.firstOrNull { it.id == canal.creadorId && !it.esProductora }
    val productora = productoras.firstOrNull { it.id == canal.productoraId }
    val con = creadores.filter { it.id in canal.tambien && !it.esProductora && it.id != dueno?.id }

    // "Clips" si tiene etiqueta; si no, el nombre de quien lo firma.
    val titulo = canal.nombre?.takeIf { it.isNotBlank() }
        ?: dueno?.name ?: productora?.nombre ?: "Canal de YouTube"
    val suscrito = youtube.suscritoAlCanal(canal)

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
            Avatar(dueno?.photoUrl ?: productora?.logoUrl, titulo, tamano = 88.dp)
            Text(
                titulo,
                style = MaterialTheme.typography.displayLarge,
                color = esquema.onBackground,
                modifier = Modifier.padding(top = Espacio.md)
            )
            Text(
                deQuienEs(dueno, productora),
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = Espacio.sm)
            )
        }

        // El canal se puede seguir por sí solo, sin seguir a su creador ni a
        // su medio: es el botón principal de su ficha.
        if (canal.sePuedeSeguir) {
            SeguirCanal(
                siguiendo = perfil.sigueCanal(canal),
                incluido = perfil.loRecibePorOtros(canal),
                onSeguir = { onSeguirCanal(canal) },
                destacado = true
            )
        }

        // Después, de quién es el canal. Su medio, si tiene, su creador y con
        // quién más aparece. Seguir a cualquiera de estos también trae los
        // avisos de este canal, junto con los de sus otros canales.
        Text(
            "De quién es este canal",
            style = MaterialTheme.typography.headlineMedium,
            color = esquema.onBackground,
            modifier = Modifier.padding(top = Espacio.sm, bottom = Espacio.sm)
        )
        Text(
            "Si sigues a cualquiera de estos, recibes los avisos de todos sus canales, " +
                "este incluido. Toca uno para ver su ficha:",
            style = MaterialTheme.typography.bodyLarge,
            color = esquema.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Espacio.md)
        )

        if (productora != null) {
            BotonGrande(
                titulo = productora.nombre,
                subtitulo = if (dueno == null) "Es su canal. Ver el medio"
                    else "El canal es de este medio. Ver su ficha",
                variante = VarianteBoton.SECUNDARIO,
                onClick = { onAbrirProductora(productora.id) }
            )
        }
        if (dueno != null) {
            BotonGrande(
                titulo = dueno.name,
                subtitulo = "Es su canal. Ver su perfil",
                variante = VarianteBoton.SECUNDARIO,
                onClick = { onAbrirCreador(dueno.id) }
            )
        }
        con.forEach { creador ->
            BotonGrande(
                titulo = creador.name,
                subtitulo = "También aparece en este canal. Ver su perfil",
                variante = VarianteBoton.SECUNDARIO,
                onClick = { onAbrirCreador(creador.id) }
            )
        }

        if (perfil.youtubeActivo && canal.esDeYouTube) {
            SuscripcionEnYouTube(
                suscrito = suscrito,
                youtube = youtube,
                esAnonimo = false,
                onConectar = {},
                etiqueta = null,
                soloSiSeSabe = true
            )
        }

        BotonGrande(
            titulo = "Abrir el canal en YouTube",
            subtitulo = if (suscrito == false) "Ahí puedes suscribirte" else "Se abre la app de YouTube",
            onClick = {
                Enrutador.abrirCanal(contexto, canal.plataforma, canal.url, campana = "ficha_canal")
            }
        )

        Text(
            "Últimos videos",
            style = MaterialTheme.typography.headlineMedium,
            color = esquema.onBackground,
            modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.md)
        )

        when {
            videos.lista.isNotEmpty() -> videos.lista.forEach { publicacion ->
                TarjetaPublicacion(
                    publicacion = publicacion,
                    abierto = publicacion.videoId in abiertos,
                    onAbrir = {
                        val destino = publicacion.destino
                        Enrutador.abrirVideo(
                            contexto,
                            destino.plataforma,
                            destino.videoId,
                            destino.url,
                            campana = "ficha_canal"
                        )
                        Abiertos.marcar(contexto, publicacion.videoId)
                    },
                    onReportar = { onReportar(publicacion) }
                )
            }

            videos.cargando -> Text(
                "Buscando sus videos…",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant
            )

            videos.fallo -> {
                Text(
                    "No se pudieron traer sus videos. Revisa tu conexión.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = esquema.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Espacio.md)
                )
                BotonGrande(
                    titulo = "Intentar de nuevo",
                    variante = VarianteBoton.SECUNDARIO,
                    onClick = onReintentar
                )
            }

            else -> Text(
                "Todavía no hemos visto videos nuevos en este canal. " +
                    "Aquí aparecen los que publique desde que entró al directorio.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant
            )
        }

        Text(
            "Los videos se ven en la app de YouTube. Esta app solo te avisa y te lleva hasta allá.",
            style = MaterialTheme.typography.bodySmall,
            color = esquema.onSurfaceVariant,
            modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.xxl)
        )
    }
}

/** "Canal de YouTube de Juan Pérez", "…de la productora Estudio X", o las dos cosas. */
private fun deQuienEs(dueno: Creador?, productora: Productora?): String = when {
    dueno != null && productora != null ->
        "Canal de YouTube de ${dueno.name}, del medio ${productora.nombre}"
    dueno != null -> "Canal de YouTube de ${dueno.name}"
    productora != null -> "Canal de YouTube del medio ${productora.nombre}"
    else -> "Canal de YouTube"
}
