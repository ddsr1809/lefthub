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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vocesdeizquierda.lefthub.BuildConfig
import com.vocesdeizquierda.lefthub.data.Canal
import com.vocesdeizquierda.lefthub.data.Creador
import com.vocesdeizquierda.lefthub.data.EstadoYouTube
import com.vocesdeizquierda.lefthub.data.PermisoYouTube
import com.vocesdeizquierda.lefthub.data.Productora
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

// No es un tema: es la otra mitad del directorio. Va como una pestaña más, al
// final, para no añadir una pantalla que haya que descubrir.
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
    onAbrirProductora: (String) -> Unit = {}
) {
    var elegido by remember { mutableStateOf("todos") }
    val esquema = MaterialTheme.colorScheme

    // Si la última productora se retira mientras su pestaña está abierta, la
    // pestaña desaparece y la lista vuelve a "Todos".
    val tema = if (elegido == PRODUCTORAS && productoras.isEmpty()) "todos" else elegido

    val visibles = remember(creadores, tema) {
        if (tema == "todos") creadores else creadores.filter { it.category == tema }
    }

    // Solo mostramos los temas que de verdad tienen a alguien dentro. Una
    // pestaña vacía es una promesa incumplida.
    val temasConGente = remember(creadores, productoras) {
        val usados = creadores.map { it.category }.toSet()
        TEMAS.filter { it.first == "todos" || it.first in usados } +
            (if (productoras.isNotEmpty()) listOf(PRODUCTORAS to "Productoras") else emptyList())
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Directorio",
            style = MaterialTheme.typography.displayLarge,
            color = esquema.onBackground,
            modifier = Modifier.padding(start = Espacio.md, end = Espacio.md, top = Espacio.sm)
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(Espacio.sm),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Espacio.md, vertical = Espacio.md)
        ) {
            temasConGente.forEach { (clave, nombre) ->
                val activo = clave == tema
                OutlinedButton(
                    onClick = { elegido = clave },
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

        if (tema == PRODUCTORAS) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = Espacio.md),
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
        } else if (visibles.isEmpty()) {
            Vacio(
                titulo = "Nada en este tema todavía",
                mensaje = "Estamos sumando creadores poco a poco. Prueba con otro tema."
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = Espacio.md),
                modifier = Modifier.fillMaxSize()
            ) {
                items(visibles, key = { it.id }) { creador ->
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
    onAbrirProductora: (String) -> Unit = {}
) {
    val esquema = MaterialTheme.colorScheme

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

        Text(
            "Dónde publica",
            style = MaterialTheme.typography.headlineMedium,
            color = esquema.onBackground,
            modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.md)
        )

        val canales = creador.canalesVisibles
        if (canales.isEmpty()) {
            Text(
                "Todavía no hemos agregado sus enlaces.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant
            )
        }

        // Con varios canales de YouTube, el botón para dar el permiso y las
        // frases de "todavía no se sabe" salen una sola vez, en el primero.
        val primeroDeYouTube = canales.firstOrNull { it.esDeYouTube }

        canales.forEach { canal ->
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

            BotonCanal(
                canal = canal,
                // En su propio perfil no hace falta decir de quién es el canal,
                // pero sí si además es de una productora.
                dueno = productora?.let { "De ${it.nombre}" },
                suscrito = suscrito,
                campana = "perfil_creador"
            )
        }

        // Las casas con las que trabaja: donde figura y las dueñas de alguno
        // de sus canales. Las que el servidor no manda (ocultas) no salen.
        val susProductoras = productoras.filter { p ->
            p.id in creador.productoras || canales.any { it.productoraId == p.id }
        }
        if (susProductoras.isNotEmpty()) {
            Text(
                if (susProductoras.size == 1) "Su productora" else "Sus productoras",
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
        Text(
            frase,
            style = MaterialTheme.typography.bodyLarge,
            color = if (suscrito == true) esquema.onBackground else esquema.onSurfaceVariant,
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
 */
@Composable
internal fun BotonCanal(
    canal: Canal,
    dueno: String?,
    suscrito: Boolean?,
    campana: String
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
}
