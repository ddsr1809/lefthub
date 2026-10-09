package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vocesdeizquierda.lefthub.BuildConfig
import com.vocesdeizquierda.lefthub.data.Abiertos
import com.vocesdeizquierda.lefthub.data.Canal
import com.vocesdeizquierda.lefthub.data.Creador
import com.vocesdeizquierda.lefthub.data.EstadoYouTube
import com.vocesdeizquierda.lefthub.data.PermisoYouTube
import com.vocesdeizquierda.lefthub.data.Productora
import com.vocesdeizquierda.lefthub.data.VideosDeCanal
import com.vocesdeizquierda.lefthub.data.canalesDelDirectorio
import com.vocesdeizquierda.lefthub.enlaces.Enrutador

// El directorio es cerrado: solo aparecen los creadores que el equipo aprobó.
// Por eso no hay buscador abierto hacia todo YouTube, y por eso la app no cae
// en la categoría de "directorio genérico" que la Guideline 3.2.2 de Apple
// rechaza y que Play Store también penaliza.

private val TEMAS = listOf(
    "todos" to "Todos",
    "comida" to "Comida",
    "cine" to "Cine",
    "politica" to "Política",
    "musica" to "Música",
    "noticias" to "Noticias",
    "salud" to "Salud",
    "tecnologia" to "Tecnología",
    "otros" to "Otros"
)

// Las tres listas del directorio. Se elige arriba cuál se ve; los temas de
// debajo filtran dentro de la que esté elegida.
private const val CREADORES = "creadores"
private const val CANALES = "canales"
private const val PRODUCTORAS = "productoras"

@Composable
fun DirectorioPantalla(
    creadores: List<Creador>,
    // La lista y no una función que consulte: Compose compara los parámetros
    // para decidir si repinta, y una función es siempre "la misma" aunque su
    // respuesta haya cambiado. Con la lista, seguir a alguien repinta la fila.
    favoritos: List<String>,
    onSeguir: (String) -> Unit,
    onAbrirCreador: (String) -> Unit,
    youtube: EstadoYouTube = EstadoYouTube(),
    productoras: List<Productora> = emptyList(),
    productorasSeguidas: List<String> = emptyList(),
    onSeguirProductora: (String) -> Unit = {},
    onAbrirProductora: (String) -> Unit = {},
    onAbrirCanal: (String) -> Unit = {}
) {
    var seccionElegida by remember { mutableStateOf(CREADORES) }
    var temaElegido by remember { mutableStateOf("todos") }
    val esquema = MaterialTheme.colorScheme

    // Los creadores son las personas. Una productora que el servidor manda
    // entre ellos (para las versiones de la app que no las conocen) va aquí
    // en su propia lista, no mezclada.
    val personas = remember(creadores) { creadores.filter { !it.esProductora } }
    val canales = remember(creadores, productoras) { canalesDelDirectorio(creadores, productoras) }

    // Solo se ofrecen las listas que tienen algo. Una pestaña vacía es una
    // promesa incumplida.
    val secciones = listOfNotNull(
        CREADORES to "Creadores",
        (CANALES to "Canales de YouTube").takeIf { canales.isNotEmpty() },
        (PRODUCTORAS to "Medios").takeIf { productoras.isNotEmpty() }
    )
    // Si la lista que se estaba viendo se queda vacía (se retiró la última
    // productora, por ejemplo), su pestaña desaparece y se vuelve a los creadores.
    val seccion = if (secciones.any { it.first == seccionElegida }) seccionElegida else CREADORES

    // Los temas que de verdad tienen a alguien dentro de la lista elegida.
    val temasConGente = remember(personas, canales, seccion) {
        val usados = when (seccion) {
            CANALES -> canales.map { it.categoria }
            else -> personas.map { it.category }
        }.toSet()
        TEMAS.filter { it.first == "todos" || it.first in usados }
    }
    // Al cambiar de lista, un tema que en la nueva no tiene a nadie no se queda puesto.
    val tema = if (temasConGente.any { it.first == temaElegido }) temaElegido else "todos"

    val creadoresVisibles = remember(personas, tema) {
        if (tema == "todos") personas else personas.filter { it.category == tema }
    }
    val canalesVisibles = remember(canales, tema) {
        if (tema == "todos") canales else canales.filter { it.categoria == tema }
    }

    // El anuncio que explica la etiqueta verde solo sale cuando hay etiquetas
    // que explicar: con YouTube conectado y ya comprobado.
    val verLeyendaYouTube = BuildConfig.SUSCRIPCIONES_YOUTUBE && youtube.haySuscripciones

    Column(Modifier.fillMaxSize()) {
        Text(
            "Directorio",
            style = MaterialTheme.typography.displayLarge,
            color = esquema.onBackground,
            modifier = Modifier.padding(start = Espacio.md, end = Espacio.md, top = Espacio.sm)
        )

        // Qué lista se ve. Solo aparece si hay más de una entre las que elegir.
        if (secciones.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Espacio.sm),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Espacio.md)
                    .padding(top = Espacio.md)
            ) {
                secciones.forEach { (clave, nombre) ->
                    val activa = clave == seccion
                    Button(
                        onClick = { seccionElegida = clave },
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

        // Los temas filtran a los creadores y a los canales. Las productoras
        // no tienen tema: ahí la fila de temas no sale.
        if (seccion != PRODUCTORAS) Row(
            horizontalArrangement = Arrangement.spacedBy(Espacio.sm),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Espacio.md, vertical = Espacio.md)
        ) {
            temasConGente.forEach { (clave, nombre) ->
                val activo = clave == tema
                OutlinedButton(
                    onClick = { temaElegido = clave },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (activo) esquema.primary else Color.Transparent,
                        contentColor = if (activo) esquema.onPrimary else esquema.onBackground
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp, if (activo) esquema.primary else esquema.outline
                    ),
                    modifier = Modifier
                        .heightIn(min = Tactil.minimo)
                        .semantics { role = Role.Tab }
                ) {
                    Text(nombre, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (seccion == PRODUCTORAS) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = Espacio.md, vertical = Espacio.md),
                modifier = Modifier.fillMaxSize()
            ) {
                items(productoras, key = { it.id }) { productora ->
                    FilaProductora(
                        productora = productora,
                        siguiendo = productora.id in productorasSeguidas,
                        onAbrir = { onAbrirProductora(productora.id) },
                        onSeguir = { onSeguirProductora(productora.id) }
                    )
                }
            }
        } else if (seccion == CANALES) {
            // No puede quedar vacía: la lista de temas sale de estos mismos canales.
            LazyColumn(
                contentPadding = PaddingValues(horizontal = Espacio.md),
                modifier = Modifier.fillMaxSize()
            ) {
                if (verLeyendaYouTube) {
                    item(key = "leyenda-youtube") {
                        LeyendaYouTube(Modifier.padding(bottom = Espacio.sm))
                    }
                }
                items(canalesVisibles, key = { it.canal.id }) { listado ->
                    FilaCanal(
                        listado = listado,
                        onAbrir = { onAbrirCanal(listado.canal.id) },
                        suscritoEnYouTube = youtube.suscritoAlCanal(listado.canal)
                    )
                }
            }
        } else if (creadoresVisibles.isEmpty()) {
            Vacio(
                titulo = "Todavía no hay creadores",
                mensaje = "Estamos sumando creadores poco a poco. Vuelve pronto."
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = Espacio.md),
                modifier = Modifier.fillMaxSize()
            ) {
                if (verLeyendaYouTube) {
                    item(key = "leyenda-youtube") {
                        LeyendaYouTube(Modifier.padding(bottom = Espacio.sm))
                    }
                }
                items(creadoresVisibles, key = { it.id }) { creador ->
                    FilaCreador(
                        creador = creador,
                        siguiendo = creador.id in favoritos,
                        onAbrir = { onAbrirCreador(creador.id) },
                        onSeguir = { onSeguir(creador.id) },
                        suscritoEnYouTube = youtube.suscritoA(creador.id)
                    )
                }
            }
        }
    }
}

/**
 * Perfil del creador.
 *
 * Aquí se materializa la idea del "Creador" como entidad, no del canal. Una
 * persona publica en varios lugares; la app los junta bajo un solo perfil y
 * cada botón dice en palabras qué va a pasar al tocarlo.
 *
 * Tiene las mismas partes que su ficha del panel: sus canales de YouTube, sus
 * redes sociales y sus productoras. Puede faltar cualquiera: hay quien solo
 * tiene una cuenta de X o de Instagram.
 */
@Composable
fun CreadorPantalla(
    creador: Creador?,
    siguiendo: Boolean,
    onSeguir: () -> Unit,
    onVolver: () -> Unit,
    youtube: EstadoYouTube = EstadoYouTube(),
    esAnonimo: Boolean = true,
    onConectarYouTube: () -> Unit = {},
    productoras: List<Productora> = emptyList(),
    onAbrirProductora: (String) -> Unit = {},
    // El directorio: para decir de quién es un canal ajeno en el que aparece.
    creadores: List<Creador> = emptyList(),
    onAbrirCanal: (String) -> Unit = {},
    /** Sus últimos videos: el mini feed de arriba. */
    videos: VideosDeCanal = VideosDeCanal(),
    /** Los videos que ya abrió desde la app: su fila se ve distinta. */
    abiertos: Set<String> = emptySet()
) {
    val esquema = MaterialTheme.colorScheme
    val contexto = LocalContext.current

    if (creador == null) {
        Vacio(
            titulo = "Este creador ya no está",
            mensaje = "Puede que lo hayamos retirado del directorio. Vuelve al listado para ver a los demás.",
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
            Avatar(creador.photoUrl, creador.name, tamano = 88.dp)
            Text(
                creador.name,
                style = MaterialTheme.typography.displayLarge,
                color = esquema.onBackground,
                modifier = Modifier.padding(top = Espacio.md)
            )
            if (!creador.bio.isNullOrBlank()) {
                Text(
                    creador.bio,
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
                "Te llegará una notificación a este teléfono",
            variante = if (siguiendo) VarianteBoton.SECUNDARIO else VarianteBoton.PRIMARIO,
            onClick = onSeguir
        )

        // Lo último que publicó, lo primero que se ve: unos pocos videos en
        // filas pequeñas. Si no hay ninguno (no tiene YouTube, o todavía no
        // ha publicado desde que entró al directorio) el apartado no sale.
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
                    sinFirmarPor = creador.name,
                    onAbrir = {
                        val destino = publicacion.destino
                        Enrutador.abrirVideo(
                            contexto,
                            destino.plataforma,
                            destino.videoId,
                            destino.url,
                            campana = "perfil_creador"
                        )
                        Abiertos.marcar(contexto, publicacion.videoId)
                    }
                )
            }
        }

        // La misma división que en su ficha del panel: de un lado sus
        // canales de YouTube, que es de donde salen los videos y los avisos;
        // del otro sus redes, que son enlaces.
        val canales = creador.canalesVisibles
        val deYouTube = creador.canalesDeYouTube
        val redes = creador.redes

        if (canales.isEmpty()) {
            Text(
                "Todavía no hemos agregado sus enlaces.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant,
                modifier = Modifier.padding(top = Espacio.lg)
            )
        }

        if (deYouTube.isNotEmpty()) {
            Text(
                if (deYouTube.size == 1) "Canal de YouTube" else "Canales de YouTube",
                style = MaterialTheme.typography.headlineMedium,
                color = esquema.onBackground,
                modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.md)
            )
        } else if (redes.isNotEmpty()) {
            // Sin canal de YouTube no hay de dónde avisar: mejor decirlo que
            // dejar a alguien esperando una notificación que no va a llegar.
            Text(
                "No tiene canal de YouTube, así que no hay avisos de videos suyos. " +
                    "Puedes verlo en sus redes.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant,
                modifier = Modifier.padding(top = Espacio.md)
            )
        }

        // Con varios canales de YouTube, el botón para dar el permiso y las
        // frases de "todavía no se sabe" salen una sola vez, en el primero.
        val primeroDeYouTube = deYouTube.firstOrNull { it.esDeYouTube }

        deYouTube.forEach { canal ->
            val suscrito = youtube.suscritoAlCanal(canal)
            val productora = productoras.firstOrNull { it.id == canal.productoraId }

            if (BuildConfig.SUSCRIPCIONES_YOUTUBE && canal.esDeYouTube) {
                SuscripcionEnYouTube(
                    suscrito = suscrito,
                    youtube = youtube,
                    esAnonimo = esAnonimo,
                    onConectar = onConectarYouTube,
                    etiqueta = canal.nombre,
                    soloSiSeSabe = canal !== primeroDeYouTube
                )
            }

            // En su propio perfil no hace falta decir de quién es un canal
            // suyo, pero sí si es de una productora o de otro creador con el
            // que aparece.
            val deOtro = creadores.firstOrNull {
                it.id == canal.creadorId && it.id != creador.id
            }

            BotonCanal(
                canal = canal,
                dueno = deOtro?.let { "Canal de ${it.name}" } ?: productora?.let { "De ${it.nombre}" },
                suscrito = suscrito,
                campana = "perfil_creador",
                onVerFicha = canal.id.takeIf { it.isNotBlank() && canal.esDeYouTube }
                    ?.let { id -> { onAbrirCanal(id) } }
            )
        }

        if (redes.isNotEmpty()) {
            Text(
                "Redes sociales",
                style = MaterialTheme.typography.headlineMedium,
                color = esquema.onBackground,
                modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.md)
            )
            redes.forEach { red ->
                BotonCanal(canal = red, dueno = null, suscrito = null, campana = "perfil_creador")
            }
        }

        // Las casas con las que trabaja: donde figura y las dueñas de alguno
        // de sus canales. Las que el servidor no manda (ocultas) no salen.
        val susProductoras = productoras.filter { p ->
            p.id in creador.productoras || canales.any { it.productoraId == p.id }
        }
        if (susProductoras.isNotEmpty()) {
            Text(
                if (susProductoras.size == 1) "Su medio" else "Sus medios",
                style = MaterialTheme.typography.headlineMedium,
                color = esquema.onBackground,
                modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.md)
            )
            susProductoras.forEach { p ->
                BotonGrande(
                    titulo = p.nombre,
                    subtitulo = "Ver sus canales y creadores",
                    variante = VarianteBoton.SECUNDARIO,
                    onClick = { onAbrirProductora(p.id) }
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

/**
 * Dice si la persona está suscrita al canal de YouTube del creador, o qué
 * falta para saberlo.
 *
 * Seguir aquí y suscribirse allá son cosas distintas y se confunden con
 * facilidad. Cada caso lo dice con una frase entera, y solo aparece un botón
 * cuando tocarlo sirve de algo.
 */
@Composable
internal fun SuscripcionEnYouTube(
    suscrito: Boolean?,
    youtube: EstadoYouTube,
    esAnonimo: Boolean,
    onConectar: () -> Unit,
    // "Clips", "Directos"… para decir de cuál canal se habla cuando hay varios.
    etiqueta: String? = null,
    // En el segundo canal y siguientes: solo la frase cuando ya se sabe la
    // respuesta, sin repetir el botón ni las explicaciones.
    soloSiSeSabe: Boolean = false
) {
    val esquema = MaterialTheme.colorScheme
    if (soloSiSeSabe && suscrito == null) return

    val canal = if (etiqueta.isNullOrBlank()) "su canal de YouTube" else "su canal $etiqueta de YouTube"

    val frase = when {
        suscrito == true -> "Estás suscrito a $canal."
        suscrito == false -> "No estás suscrito a $canal."
        esAnonimo -> "Para ver aquí si estás suscrito a $canal, " +
            "guarda tu cuenta con Google en Ajustes."
        youtube.verificando -> "Comprobando si estás suscrito a $canal…"
        else -> null
    }

    if (frase != null) {
        // Suscrito: en el mismo verde que la etiqueta del Directorio, y con
        // su palomita, para que sea la misma señal en todas las pantallas.
        Text(
            if (suscrito == true) "✓ $frase" else frase,
            style = if (suscrito == true)
                MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
            else
                MaterialTheme.typography.bodyLarge,
            color = if (suscrito == true) esquema.tertiary else esquema.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Espacio.md)
        )
    } else if (youtube.permiso == PermisoYouTube.SIN_PERMISO) {
        BotonGrande(
            titulo = "Ver si estoy suscrito en YouTube",
            subtitulo = "Google te pedirá permiso para consultar tus suscripciones",
            variante = VarianteBoton.SECUNDARIO,
            onClick = onConectar
        )
    }
}

/**
 * El botón que lleva a un canal. Lo comparten el perfil del creador y la
 * ficha de la productora.
 *
 * @param dueno una frase corta sobre de quién es el canal ("De Estudio X",
 *              "Canal de Juan Pérez"), o null si no hace falta decirlo.
 * @param onVerFicha abre la ficha del canal, con sus últimos videos. El botón
 *              grande sigue llevando directo a YouTube, de un toque; la ficha
 *              es un enlace aparte, debajo, para quien quiera ver más.
 */
@Composable
internal fun BotonCanal(
    canal: Canal,
    dueno: String?,
    suscrito: Boolean?,
    campana: String,
    onVerFicha: (() -> Unit)? = null
) {
    val contexto = LocalContext.current

    val destino = if (canal.plataforma == "youtube" && suscrito == false)
        "Se abre YouTube; ahí puedes suscribirte"
    else
        "Se abre la app de ${Enrutador.nombreDe(canal.plataforma)}"

    BotonGrande(
        // Con dos canales en la misma plataforma, la etiqueta es lo único que
        // los distingue: "Ver videos largos · Clips".
        titulo = Enrutador.accionDe(canal.plataforma) +
            (canal.nombre?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
        subtitulo = if (dueno != null) "$dueno. $destino" else destino,
        variante = VarianteBoton.SECUNDARIO,
        onClick = {
            Enrutador.abrirCanal(contexto, canal.plataforma, canal.url, campana = campana)
        }
    )

    if (onVerFicha != null) {
        TextButton(
            onClick = onVerFicha,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Tactil.minimo)
                .padding(bottom = Tactil.separacion)
        ) {
            Text(
                "Ver los últimos videos de este canal",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
