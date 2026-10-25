package com.vocesdeizquierda.lefthub.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vocesdeizquierda.lefthub.data.Abiertos
import com.vocesdeizquierda.lefthub.data.Canal
import com.vocesdeizquierda.lefthub.data.Creador
import com.vocesdeizquierda.lefthub.data.EstadoYouTube
import com.vocesdeizquierda.lefthub.data.Etiqueta
import com.vocesdeizquierda.lefthub.data.Perfil
import com.vocesdeizquierda.lefthub.data.PermisoYouTube
import com.vocesdeizquierda.lefthub.data.Productora
import com.vocesdeizquierda.lefthub.data.VideosDeCanal
import com.vocesdeizquierda.lefthub.data.canalesDelDirectorio
import com.vocesdeizquierda.lefthub.data.ClaseDeCuenta
import com.vocesdeizquierda.lefthub.data.enGruposDelDirectorio
import com.vocesdeizquierda.lefthub.data.ultimoVideoDe
import java.time.Instant
import com.vocesdeizquierda.lefthub.enlaces.Enrutador

// El directorio es cerrado: solo aparecen los creadores que el equipo aprobó.
// Por eso no hay buscador abierto hacia todo YouTube, y por eso la app no cae
// en la categoría de "directorio genérico" que la Guideline 3.2.2 de Apple
// rechaza y que Play Store también penaliza.

// Los temas fijos de antes (comida, cine, política…) ya no se enseñan. El
// directorio se filtra con las etiquetas que el equipo crea y enciende en el
// panel; mientras no haya ninguna encendida que alguien lleve, no hay filtro.

// Las tres listas del directorio. Se elige arriba cuál se ve; las etiquetas
// de debajo filtran dentro de la que esté elegida. Van en este orden:
// Creadores, Canales de YouTube y Medios. Dentro de cada una, primero lo
// general, después la televisión pública y al final la política y el
// gobierno (ver ClaseDeCuenta); y en cada grupo, arriba lo que sigue y quien
// publicó más reciente.
//
// Cuál se ve no lo guarda esta pantalla sino la navegación (Navegacion en
// MainActivity): así, al volver de una ficha con el botón de atrás, o al
// volver a la pestaña del Directorio, se ve la lista de esa ficha y no la
// primera. Ver SeccionDirectorio.
object SeccionDirectorio {
    const val CANALES = "canales"
    const val CREADORES = "creadores"
    const val PRODUCTORAS = "productoras"
}
private const val CREADORES = SeccionDirectorio.CREADORES
private const val CANALES = SeccionDirectorio.CANALES
private const val PRODUCTORAS = SeccionDirectorio.PRODUCTORAS

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
    onAbrirCanal: (String) -> Unit = {},
    /** Las etiquetas encendidas, con lo que lleva cada una. */
    etiquetas: List<Etiqueta> = emptyList(),
    /** A quién sigue: de aquí sale si un canal ya le manda sus avisos. */
    perfil: Perfil = Perfil(),
    onSeguirCanal: (Canal) -> Unit = {},
    /**
     * Ya llegó el perfil del servidor. Hasta entonces no se sabe qué sigue, y
     * las listas se vuelven a ordenar cuando llega.
     */
    perfilCargado: Boolean = true,
    /** Cuándo salió el último video de cada canal, por id: ordena las listas. */
    ultimosVideos: Map<String, Instant> = emptyMap(),
    /** La lista que se ve: una de SeccionDirectorio. */
    seccionElegida: String = CREADORES,
    /** La persona tocó otra de las listas de arriba. */
    onElegirSeccion: (String) -> Unit = {}
) {
    // El id de la etiqueta elegida; null es "Todos". Se guarda también al ir
    // a una ficha y volver.
    var etiquetaElegida by rememberSaveable { mutableStateOf<String?>(null) }
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
    // productora, por ejemplo), su pestaña desaparece y se vuelve a la primera.
    val seccion = if (secciones.any { it.first == seccionElegida }) seccionElegida else secciones.first().first

    // Lo que sigue va arriba en cada lista. Se decide al abrir el Directorio,
    // al cambiar de lista y cada vez que se refresca, pero no en cada toque:
    // si la fila saltara arriba al tocar "Seguir", la persona perdería de
    // vista dónde estaba. Un creador o un medio del que sigue algún canal
    // suelto cuenta como seguido.
    val seguidosAlAbrir = remember(creadores, productoras, seccion, perfilCargado) {
        SeguidosAlAbrir(
            creadores = personas.filter {
                it.id in favoritos || perfil.sueltosDe(it.canalesDeYouTube) > 0
            }.map { it.id }.toSet(),
            productoras = productoras.filter {
                it.id in productorasSeguidas || perfil.sueltosDe(it.canales) > 0
            }.map { it.id }.toSet(),
            canales = canales.filter { perfil.recibe(it.canal) }.map { it.canal.id }.toSet()
        )
    }

    // Las etiquetas que de verdad llevan a alguien de la lista elegida. Una
    // etiqueta sin nadie no se enseña; y sin ninguna, no hay fila de filtros.
    val etiquetasConGente = remember(etiquetas, seccion, personas, canales, productoras) {
        etiquetas.filter { e ->
            when (seccion) {
                CANALES -> canales.any { it.canal.id in e.canales }
                PRODUCTORAS -> productoras.any { it.id in e.productoras }
                else -> personas.any { it.id in e.creadores }
            }
        }
    }
    // Al cambiar de lista, o si el equipo apaga la etiqueta mientras estaba
    // elegida, se vuelve a "Todos".
    val etiqueta = etiquetasConGente.firstOrNull { it.id == etiquetaElegida }

    val creadoresVisibles = remember(personas, etiqueta) {
        if (etiqueta == null) personas else personas.filter { it.id in etiqueta.creadores }
    }
    val canalesVisibles = remember(canales, etiqueta) {
        if (etiqueta == null) canales else canales.filter { it.canal.id in etiqueta.canales }
    }
    val productorasVisibles = remember(productoras, etiqueta) {
        if (etiqueta == null) productoras else productoras.filter { it.id in etiqueta.productoras }
    }

    // El anuncio que explica la etiqueta verde solo sale cuando hay etiquetas
    // que explicar: con YouTube conectado y ya comprobado.
    val verLeyendaYouTube = perfil.youtubeActivo && youtube.haySuscripciones

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
                        onClick = { onElegirSeccion(clave) },
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

        // Las etiquetas filtran la lista que se esté viendo. Solo sale la
        // fila si hay alguna que enseñar; si no, un poco de aire y la lista.
        if (etiquetasConGente.isEmpty()) {
            Spacer(Modifier.height(Espacio.md))
        } else Row(
            horizontalArrangement = Arrangement.spacedBy(Espacio.sm),
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Espacio.md, vertical = Espacio.md)
        ) {
            // "Todos" primero, y después las etiquetas en el orden del panel.
            (listOf<Pair<String?, String>>(null to "Todos") +
                etiquetasConGente.map { it.id to it.nombre }).forEach { (clave, nombre) ->
                val activo = clave == etiqueta?.id
                OutlinedButton(
                    onClick = { etiquetaElegida = clave },
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
                        .semantics {
                            role = Role.Tab
                            selected = activo
                        }
                ) {
                    Text(nombre, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (seccion == PRODUCTORAS) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = Espacio.md),
                modifier = Modifier.fillMaxSize()
            ) {
                // Por tipo de cuenta (lo general, la televisión pública, la
                // política); dentro, arriba lo que sigue y lo más nuevo.
                enGrupos(enGruposDelDirectorio(
                    productorasVisibles,
                    clase = { it.clase },
                    sigue = { it.id in seguidosAlAbrir.productoras },
                    cuando = { ultimoVideoDe(it.canales, ultimosVideos) },
                    titulos = titulosDeClase("Medios"),
                    tituloSigues = "Medios que sigues",
                    tituloDemas = "Los demás medios"
                ), { it.id }) { productora, franja ->
                    FilaProductora(
                        productora = productora,
                        siguiendo = productora.id in productorasSeguidas,
                        onAbrir = { onAbrirProductora(productora.id) },
                        onSeguir = { onSeguirProductora(productora.id) },
                        conFranja = franja,
                        sueltos = perfil.sueltosDe(productora.canales),
                        deCuantos = productora.canales.count { it.sePuedeSeguir },
                        ultimoVideo = ultimoVideoDe(productora.canales, ultimosVideos)
                    )
                }
            }
        } else if (seccion == CANALES) {
            // No puede quedar vacía: solo se ofrecen etiquetas que lleva algún canal.
            LazyColumn(
                contentPadding = PaddingValues(horizontal = Espacio.md),
                modifier = Modifier.fillMaxSize()
            ) {
                if (verLeyendaYouTube) {
                    item(key = "leyenda-youtube") {
                        LeyendaYouTube(Modifier.padding(bottom = Espacio.sm))
                    }
                }
                enGrupos(enGruposDelDirectorio(
                    canalesVisibles,
                    clase = { it.clase },
                    sigue = { it.canal.id in seguidosAlAbrir.canales },
                    cuando = { ultimosVideos[it.canal.id] },
                    titulos = titulosDeClase("Canales"),
                    tituloSigues = "Canales que sigues",
                    tituloDemas = "Los demás canales"
                ), { it.canal.id }) { listado, franja ->
                    FilaCanal(
                        listado = listado,
                        onAbrir = { onAbrirCanal(listado.canal.id) },
                        suscritoEnYouTube = youtube.suscritoAlCanal(listado.canal),
                        siguiendo = perfil.recibe(listado.canal),
                        onSeguir = { onSeguirCanal(listado.canal) },
                        conFranja = franja,
                        ultimoVideo = ultimosVideos[listado.canal.id]
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
                enGrupos(enGruposDelDirectorio(
                    creadoresVisibles,
                    clase = { it.clase },
                    sigue = { it.id in seguidosAlAbrir.creadores },
                    cuando = { ultimoVideoDe(it.canalesDeYouTube, ultimosVideos) },
                    titulos = titulosDeClase("Creadores"),
                    tituloSigues = "Creadores que sigues",
                    tituloDemas = "Los demás creadores"
                ), { it.id }) { creador, franja ->
                    FilaCreador(
                        creador = creador,
                        siguiendo = creador.id in favoritos,
                        onAbrir = { onAbrirCreador(creador.id) },
                        onSeguir = { onSeguir(creador.id) },
                        suscritoEnYouTube = youtube.suscritoA(creador.id),
                        conFranja = franja,
                        sueltos = perfil.sueltosDe(creador.canalesDeYouTube),
                        deCuantos = creador.canalesDeYouTube.count { it.sePuedeSeguir },
                        ultimoVideo = ultimoVideoDe(creador.canalesDeYouTube, ultimosVideos)
                    )
                }
            }
        }
    }
}

/** Lo que seguía la persona al abrir la lista, por id. */
private data class SeguidosAlAbrir(
    val creadores: Set<String>,
    val productoras: Set<String>,
    val canales: Set<String>
)

/** Los títulos de los grupos por tipo de cuenta; el de lo general es el de la lista. */
private fun titulosDeClase(general: String) = mapOf(
    ClaseDeCuenta.GENERAL to general,
    ClaseDeCuenta.TELEVISION_PUBLICA to "Televisión pública",
    ClaseDeCuenta.POLITICA to "Política y gobierno"
)

/**
 * Una lista en grupos, cada uno con su título si lo tiene. Las franjas
 * alternas empiezan de nuevo en cada grupo. Ver enGruposDelDirectorio.
 */
private fun <T> LazyListScope.enGrupos(
    grupos: List<Pair<String?, List<T>>>,
    clave: (T) -> String,
    fila: @Composable (T, Boolean) -> Unit
) {
    grupos.filter { it.second.isNotEmpty() }.forEachIndexed { i, (titulo, lista) ->
        if (titulo != null) item(key = "titulo-$i-$titulo") { TituloDeGrupo(titulo) }
        itemsIndexed(lista, key = { _, x -> clave(x) }) { j, x -> fila(x, j % 2 == 0) }
    }
}

@Composable
private fun TituloDeGrupo(texto: String) {
    Text(
        texto,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .padding(top = Espacio.md, bottom = Espacio.sm)
            .semantics { heading() }
    )
}

/**
 * Perfil del creador.
 *
 * Aquí se materializa la idea del "Creador" como entidad, no del canal. Una
 * persona publica en varios lugares; la app los junta bajo un solo perfil y
 * cada botón dice en palabras qué va a pasar al tocarlo.
 *
 * De arriba abajo: quién es y el botón de seguir; su canal principal; sus
 * últimos videos; un anuncio, si toca; los videos de canales de otros en los
 * que también aparece; sus otros canales, sus redes y sus medios. Puede
 * faltar cualquiera: hay quien solo tiene una cuenta de X o de Instagram.
 *
 * @param anuncio el espacio de publicidad, ya decidido fuera: null si no toca.
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
    /** Sus últimos videos, de sus propios canales. */
    videos: VideosDeCanal = VideosDeCanal(),
    /** Los videos que ya abrió desde la app: su fila se ve distinta. */
    abiertos: Set<String> = emptySet(),
    /** A quién sigue: para saber qué canales sigue sueltos. */
    perfil: Perfil = Perfil(),
    onSeguirCanal: (Canal) -> Unit = {},
    /** Los videos de canales de otros (un medio, otro creador) en los que aparece. */
    videosDondeAparece: VideosDeCanal = VideosDeCanal(),
    anuncio: (@Composable () -> Unit)? = null
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

        // Seguir al creador es seguir todos sus canales. Con uno solo no hay
        // diferencia y el botón lo dice como siempre; con varios, dice que
        // son todos, porque más abajo cada canal se puede seguir por separado.
        val cuantosCanales = creador.canalesDeYouTube.count { it.sePuedeSeguir }
        val variosCanales = cuantosCanales >= 2
        // Sigue algunos de sus canales sueltos, sin seguirlo entero.
        val sueltos = if (siguiendo) 0 else perfil.sueltosDe(creador.canalesDeYouTube)
        BotonGrande(
            titulo = when {
                sueltos > 0 && !variosCanales -> "Sigues su canal"
                sueltos > 0 -> "Sigues ${deCuales(sueltos, cuantosCanales)}"
                !variosCanales -> if (siguiendo) "Ya recibes sus avisos" else "Avísame cuando publique"
                siguiendo -> "Sigues todos sus canales"
                else -> "Seguir todos sus canales"
            },
            subtitulo = when {
                sueltos > 0 && !variosCanales -> "Toca para dejar de seguirlo"
                sueltos > 0 -> "Toca para elegir cuáles"
                siguiendo -> "Toca para dejar de recibir sus avisos"
                variosCanales -> "Te avisamos de lo que publique en cualquiera de ellos"
                else -> "Te llegará una notificación a este teléfono"
            },
            variante = if (siguiendo || sueltos > 0) VarianteBoton.SECUNDARIO else VarianteBoton.PRIMARIO,
            onClick = onSeguir
        )

        // La misma división que en su ficha del panel: de un lado sus
        // canales de YouTube, que es de donde salen los videos y los avisos;
        // del otro sus redes, que son enlaces.
        val canales = creador.canalesVisibles
        val deYouTube = creador.canalesDeYouTube
        val redes = creador.redes

        // Su canal principal: el primero de los suyos. Si solo aparece en
        // canales de otros, el primero de esos.
        val principal = deYouTube.firstOrNull { it.creadorId == creador.id } ?: deYouTube.firstOrNull()
        val otrosCanales = deYouTube.filter { it !== principal }

        // Con varios canales de YouTube, el botón para dar el permiso y las
        // frases de "todavía no se sabe" salen una sola vez, en el primero.
        val primeroDeYouTube = principal?.takeIf { it.esDeYouTube } ?: deYouTube.firstOrNull { it.esDeYouTube }

        val pintarCanal: @Composable (Canal) -> Unit = { canal ->
            val suscrito = youtube.suscritoAlCanal(canal)
            val productora = productoras.firstOrNull { it.id == canal.productoraId }

            if (perfil.youtubeActivo && canal.esDeYouTube) {
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

            // Con varios canales, cada uno se puede seguir por separado. Con
            // uno solo sobra: es lo mismo que el botón de arriba.
            if (variosCanales && canal.sePuedeSeguir) {
                SeguirCanal(
                    siguiendo = perfil.sigueCanal(canal),
                    incluido = perfil.loRecibePorOtros(canal),
                    onSeguir = { onSeguirCanal(canal) }
                )
            }
        }

        val pintarVideos: @Composable (String, VideosDeCanal) -> Unit = { titulo, lista ->
            // Si no hay ninguno, el apartado no sale.
            if (lista.lista.isNotEmpty()) {
                Text(
                    titulo,
                    style = MaterialTheme.typography.headlineMedium,
                    color = esquema.onBackground,
                    modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.md)
                )
                lista.lista.forEach { publicacion ->
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
        }

        if (canales.isEmpty()) {
            Text(
                "Todavía no hemos agregado sus enlaces.",
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurfaceVariant,
                modifier = Modifier.padding(top = Espacio.lg)
            )
        }

        // 1. Su canal principal, lo primero después del botón de seguir.
        if (principal != null) {
            Text(
                "Su canal principal",
                style = MaterialTheme.typography.headlineMedium,
                color = esquema.onBackground,
                modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.md)
            )
            pintarCanal(principal)
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

        // 2. Lo último que subió a sus canales.
        pintarVideos("Sus últimos videos", videos)

        // El anuncio, después de lo suyo y nunca pegado al botón de seguir:
        // con un hueco por encima, para que un toque con el pulso inseguro no
        // caiga en él. La tarjeta dice «Publicidad» y no se parece a un video.
        if (anuncio != null) {
            Spacer(Modifier.height(Espacio.lg))
            anuncio()
        }

        // 3. Los videos de canales de otros (un medio, otro creador) en los
        //    que también aparece.
        pintarVideos("Videos donde también aparece", videosDondeAparece)

        // 4. Sus demás canales de YouTube, y los de otros en los que sale.
        if (otrosCanales.isNotEmpty()) {
            Text(
                if (otrosCanales.size == 1) "Otro canal de YouTube" else "Otros canales de YouTube",
                style = MaterialTheme.typography.headlineMedium,
                color = esquema.onBackground,
                modifier = Modifier.padding(top = Espacio.lg, bottom = Espacio.md)
            )
            otrosCanales.forEach { pintarCanal(it) }
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
 * Seguir un canal de YouTube por sí solo, sin seguir a su creador ni a su
 * medio. Lo comparten el perfil del creador, la ficha del medio y la del canal.
 *
 * @param siguiendo sigue este canal suelto.
 * @param incluido  sus avisos ya le llegan por seguir a su creador o a su
 *                  medio. Entonces no hay nada que tocar: se dice y ya. Quien
 *                  quiera quedarse solo con este canal deja de seguir al
 *                  creador o al medio y lo sigue aquí.
 * @param destacado en la ficha del canal es el botón principal de la pantalla.
 */
@Composable
internal fun SeguirCanal(
    siguiendo: Boolean,
    incluido: Boolean,
    onSeguir: () -> Unit,
    destacado: Boolean = false
) {
    val esquema = MaterialTheme.colorScheme

    if (incluido && !siguiendo) {
        Text(
            "✓ Ya recibes los avisos de este canal: sigues a su creador o a su medio.",
            style = MaterialTheme.typography.bodyLarge,
            color = esquema.onSurfaceVariant,
            modifier = Modifier.padding(bottom = Espacio.md)
        )
        return
    }

    BotonGrande(
        titulo = if (siguiendo) "Sigues este canal" else "Seguir solo este canal",
        subtitulo = if (siguiendo)
            "Toca para dejar de recibir sus avisos"
        else
            "Te avisamos solo de lo que salga en este canal",
        variante = if (destacado && !siguiendo) VarianteBoton.PRIMARIO else VarianteBoton.SECUNDARIO,
        onClick = onSeguir
    )
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
        // Con dos canales en la misma plataforma, su nombre es lo único que
        // los distingue: "Ver videos largos · Claudia Sheinbaum Clips". El de
        // YouTube si ya se sabe; si no, la etiqueta del panel.
        titulo = Enrutador.accionDe(canal.plataforma) +
            ((if (canal.esDeYouTube) canal.nombreEnPantalla else canal.nombre)
                ?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
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
