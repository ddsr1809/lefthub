package com.vocesdeizquierda.lefthub

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.vocesdeizquierda.lefthub.anuncios.Anuncios
import com.vocesdeizquierda.lefthub.anuncios.BannersDelFeed
import com.vocesdeizquierda.lefthub.data.Abiertos
import com.vocesdeizquierda.lefthub.data.Aceptacion
import com.vocesdeizquierda.lefthub.data.ApiRelay
import com.vocesdeizquierda.lefthub.data.AvisoTocado
import com.vocesdeizquierda.lefthub.data.AyudaTele
import com.vocesdeizquierda.lefthub.data.VideoDeAviso
import com.vocesdeizquierda.lefthub.data.EstadoServidor
import com.vocesdeizquierda.lefthub.data.FichaDeVideo
import com.vocesdeizquierda.lefthub.data.VideosDeCanal
import com.vocesdeizquierda.lefthub.data.canalesDelDirectorio
import com.vocesdeizquierda.lefthub.enlaces.Enrutador
import com.vocesdeizquierda.lefthub.ui.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // El permiso se pide en cuanto la persona acepta las condiciones (o al
    // arrancar, si ya las aceptó) porque sin él el producto no hace nada útil.
    // Si dice que no, la app sigue funcionando como directorio; simplemente
    // no avisa.
    private val pedirPermiso = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* concedido o no, seguimos igual */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Aceptacion.vigente(this)) solicitarPermisoDeAvisos()
        Abiertos.cargar(this)
        AyudaTele.cargar(this)

        setContent {
            var aceptado by remember { mutableStateOf(Aceptacion.vigente(this@MainActivity)) }

            if (!aceptado) {
                // Primera vez: nada sale hacia el servidor hasta que la persona
                // acepta. Por eso el ViewModel, que crea la cuenta anónima al
                // nacer, solo se pide en la otra rama.
                TemaRelay {
                    BienvenidaPantalla(
                        onAceptar = {
                            Aceptacion.registrar(this@MainActivity)
                            aceptado = true
                            solicitarPermisoDeAvisos()
                        }
                    )
                }
            } else {
                val modelo: AppViewModel = viewModel()
                val estado by modelo.estado.collectAsState()

                // El equipo dio de baja esta versión de la app: el servidor ya
                // no la atiende. No tiene sentido enseñar pantallas vacías.
                val bloqueo by ApiRelay.bloqueo.collectAsState()

                // El servidor está en mantenimiento o no responde. Se le
                // pregunta cada vez que la app pasa a primer plano.
                val servidor by ApiRelay.servidor.collectAsState()

                TemaRelay(
                    preferencia = estado.perfil.tema,
                    escala = EscalaTexto.desde(estado.perfil.escalaTexto)
                ) {
                    val aviso = bloqueo
                    val estadoDelServidor = servidor
                    when {
                        aviso != null -> ActualizarPantalla(mensaje = aviso)

                        estadoDelServidor is EstadoServidor.Mantenimiento -> ServidorPantalla(
                            titulo = "Estamos en mantenimiento",
                            mensaje = estadoDelServidor.mensaje,
                            onReintentar = { comprobarServidor() }
                        )

                        estadoDelServidor is EstadoServidor.Caido -> ServidorPantalla(
                            titulo = "No podemos conectar con el servidor",
                            mensaje = "El servidor no está respondiendo en este momento. " +
                                "Suele durar poco.",
                            onReintentar = { comprobarServidor() }
                        )

                        !estado.listo -> PantallaDeCarga()

                        else -> Navegacion(modelo, estado)
                    }
                }

                // Anuncios. Todo cuelga de lo que diga el perfil, que es lo
                // que decide el servidor: mientras la persona no vea anuncios
                // (el equipo los tiene apagados, o ella los quitó) no se
                // arranca la biblioteca de Google ni se le pregunta nada.
                val veAnuncios = estado.perfil.veAnuncios
                LaunchedEffect(veAnuncios) {
                    if (veAnuncios) Anuncios.preparar(this@MainActivity)
                }
                // Y a Google Play se le pregunta el precio, y si esta persona
                // ya lo había comprado, solo cuando hay algo que vender.
                LaunchedEffect(veAnuncios, estado.perfil.compraDisponible) {
                    modelo.prepararTienda(applicationContext)
                }

                // La notificación que abrió la app trae el destino en los extras.
                // Lo procesamos una vez y lo limpiamos, o al girar la pantalla
                // volvería a abrirse el video.
                LaunchedEffect(Unit) { procesarIntent(intent) }
            }
        }
    }

    /**
     * Cada vez que la app pasa a primer plano: al abrirla y al volver de
     * YouTube. Es el momento de comprobar si la persona está suscrita a los
     * creadores, porque es cuando pudo haber cambiado.
     *
     * El ViewModel es el mismo que usa la interfaz (ambos salen del almacén de
     * esta Activity), y él decide si toca: espera a que haya sesión, ignora a
     * los invitados y no repite si acaba de comprobar.
     */
    override fun onResume() {
        super.onResume()
        // Pedir el ViewModel lo crea, y al crearse abre la cuenta anónima:
        // antes de la aceptación no se toca.
        if (!Aceptacion.vigente(this)) return
        // Cada vez que se entra: ¿está vivo el servidor? ¿en mantenimiento?
        comprobarServidor()
        val modelo = ViewModelProvider(this)[AppViewModel::class.java]
        modelo.verificarYouTube(applicationContext)
        // Si al abrir no se pudo hablar con Google Play (sin conexión, por
        // ejemplo), se reintenta al volver. Si ya se pudo, esto no hace nada.
        modelo.prepararTienda(applicationContext, soloSiFalta = true)
    }

    /**
     * Pregunta al servidor si atiende a la app. El resultado queda en
     * ApiRelay.servidor, que es lo que pinta la interfaz.
     *
     * Si no atendía y ahora sí (terminó el mantenimiento, o volvió), la app
     * arranca de nuevo: lo que se pidió mientras tanto falló, y empezar de
     * cero es la forma segura de que nada se quede a medias o vacío.
     */
    private fun comprobarServidor() {
        lifecycleScope.launch {
            val antes = ApiRelay.servidor.value
            val ahora = ApiRelay.comprobarServidor()
            if (antes !is EstadoServidor.Vivo && ahora is EstadoServidor.Vivo) reiniciar()
        }
    }

    private fun reiniciar() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        procesarIntent(intent)
    }

    /**
     * FCM mete los pares de `data` como extras del Intent cuando el usuario
     * toca la notificación. Si trae un video, se abre su ficha en la app,
     * con el botón grande para verlo en YouTube.
     */
    private fun procesarIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        val plataforma = extras.getString("platform") ?: return

        val videoId = extras.getString("videoId")
        val url = extras.getString("url")
        val tipo = extras.getString("tipo")

        // Con un video, a su ficha dentro de la app: ahí se ve de qué se trata
        // antes de ir a YouTube, y cabe un anuncio. La navegación lo recoge.
        if (!videoId.isNullOrBlank()) {
            AvisoTocado.pendiente.value = VideoDeAviso(videoId, plataforma, url, tipo)
            intent.replaceExtras(Bundle())
            return
        }

        // Sin video (un aviso de otro tipo con un enlace), directo a él.
        Enrutador.abrirVideo(
            contexto = this,
            plataforma = plataforma,
            videoId = videoId,
            url = url,
            campana = if (tipo == "movido") "contenido_movido" else "aviso_publicacion"
        )
        // Tocar el aviso también cuenta: al volver, su tarjeta ya no es nueva.
        Abiertos.marcar(this, videoId)

        // Consumido: que no se repita al recrear la Activity.
        intent.replaceExtras(Bundle())
    }

    private fun solicitarPermisoDeAvisos() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val concedido = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!concedido) pedirPermiso.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun PantallaDeCarga() {
    Box(
        contentAlignment = androidx.compose.ui.Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
                "Un momento…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Espacio.md)
            )
        }
    }
}

/**
 * Navegación anclada abajo.
 *
 * Los teléfonos actuales son demasiado altos para alcanzar la parte superior
 * con el pulgar sin recolocar la mano, y recolocar la mano es justo lo que
 * cuesta a quien tiene menos destreza. Todo lo que se toca vive en el tercio
 * inferior de la pantalla.
 */
/** Las pantallas a las que se entra desde el directorio. */
private val FICHAS = listOf("creador/", "productora/", "canal/", "elegir/")

// La ficha de un video se abre desde una notificación: es parte de Novedades.
private const val FICHA_DE_VIDEO = "video/"

@Composable
private fun Navegacion(modelo: AppViewModel, estado: EstadoApp) {
    val nav = rememberNavController()
    val contexto = LocalContext.current

    // Qué lista del Directorio se ve: Canales de YouTube, Creadores o Medios.
    // Vive aquí y no en el Directorio para que no se pierda al abrir una
    // ficha: con el botón de atrás, o al volver a la pestaña desde Novedades,
    // se vuelve a la lista de la última ficha que se vio (la de un creador
    // lleva a Creadores; la de un canal, a Canales). Al abrir la app, Canales.
    var seccionDirectorio by rememberSaveable { mutableStateOf(SeccionDirectorio.CANALES) }

    // Tocar una notificación abre la ficha de su video. El toque puede llegar
    // antes que esta pantalla (la app estaba cerrada): queda guardado en
    // AvisoTocado hasta aquí.
    val avisoPendiente by AvisoTocado.pendiente.collectAsState()
    LaunchedEffect(avisoPendiente) {
        val aviso = avisoPendiente ?: return@LaunchedEffect
        AvisoTocado.pendiente.value = null
        modelo.abrirFichaDeVideo(aviso)
        nav.navigate(FICHA_DE_VIDEO + aviso.videoId) { launchSingleTop = true }
    }

    // Todos los canales de YouTube del directorio, con lo que pinta su fila.
    val todosLosCanales = remember(estado.creadores, estado.productoras) {
        canalesDelDirectorio(estado.creadores, estado.productoras)
    }

    // Seguir a alguien es seguir sus canales de YouTube. Antes una ventana
    // dice cuántos y cuáles, y deja quitar los que no quiera o ir a verlos.
    // Sin canales de YouTube se sigue de un toque, y dejar de seguir nunca
    // pregunta.
    var porSeguirAhora by remember { mutableStateOf<PorSeguir?>(null) }
    // Si ya sigue algunos de sus canales sueltos, la misma ventana sirve para
    // cambiar cuáles; con uno solo, se deja de seguir ese canal y ya.
    val preguntarOSeguir: (PorSeguir?, () -> Unit) -> Unit = { preguntar, seguirDeUnToque ->
        val unico = preguntar?.unicoSeguido(estado.perfil)
        when {
            unico != null -> modelo.alternarCanal(unico)
            preguntar != null -> porSeguirAhora = preguntar
            else -> seguirDeUnToque()
        }
    }
    val seguirCreador: (String) -> Unit = { id ->
        preguntarOSeguir(
            porSeguir(modelo.creador(id), todosLosCanales).takeIf { id !in estado.perfil.favoritos }
        ) { modelo.alternarFavorito(id) }
    }
    val seguirProductora: (String) -> Unit = { id ->
        preguntarOSeguir(
            porSeguir(estado.productoras.firstOrNull { it.id == id }, todosLosCanales)
                .takeIf { id !in estado.perfil.productoras }
        ) { modelo.alternarProductora(id) }
    }
    val snackbar = remember { SnackbarHostState() }

    val destinos = listOf(
        "novedades" to "Novedades",
        "directorio" to "Directorio",
        "ajustes" to "Ajustes"
    )

    LaunchedEffect(estado.mensaje) {
        estado.mensaje?.let {
            snackbar.showSnackbar(it)
            modelo.mensajeVisto()
        }
    }

    // La pantalla de permiso de YouTube es de Google, no nuestra: el ViewModel
    // avisa de que hay que abrirla y aquí se abre y se recoge la respuesta.
    val pantallaDePermiso = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { respuesta ->
        modelo.permisoDeYouTubeRespondido(contexto, respuesta.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(Unit) {
        modelo.permisosDeYouTube.collect { pedir ->
            pantallaDePermiso.launch(IntentSenderRequest.Builder(pedir).build())
        }
    }

    // Los anuncios de Novedades viven aquí, por encima de las pestañas, para
    // que cambiar de pestaña y volver no pida anuncios nuevos. Se sueltan al
    // cerrarse la pantalla, y en el momento en que la persona deja de verlos:
    // acaba de comprar o de canjear un folio y no debe quedar ni uno a la vista.
    val conAnuncios = estado.perfil.veAnuncios && Anuncios.listos
    val banners = remember { BannersDelFeed() }
    DisposableEffect(Unit) { onDispose { banners.destruir() } }
    LaunchedEffect(conAnuncios) { if (!conAnuncios) banners.destruir() }

    // Ajustes, entrando siempre por su principio, como al tocar su pestaña.
    val irAAjustes = {
        nav.navigate("ajustes") {
            popUpTo(nav.graph.startDestinationId)
            launchSingleTop = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            val actual by nav.currentBackStackEntryAsState()
            val ruta = actual?.destination?.route

            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.heightIn(min = Tactil.principal + 28.dp)
            ) {
                destinos.forEach { (destino, etiqueta) ->
                    NavigationBarItem(
                        // La ficha de un creador, de una productora o de un
                        // canal es parte del directorio: su pestaña sigue marcada.
                        selected = ruta == destino ||
                            (destino == "directorio" && ruta != null && FICHAS.any { ruta.startsWith(it) }) ||
                            (destino == "novedades" && ruta != null && ruta.startsWith(FICHA_DE_VIDEO)),
                        onClick = {
                            // Cada pestaña abre siempre su pantalla principal.
                            // Antes se guardaba dónde se había quedado cada
                            // una, y al volver al Directorio aparecía la ficha
                            // del último creador en vez de la lista: quien no
                            // recordaba haberla abierto se quedaba sin saber
                            // cómo volver.
                            nav.navigate(destino) {
                                popUpTo(nav.graph.startDestinationId)
                                launchSingleTop = true
                            }
                        },
                        // Sin icono a propósito: una etiqueta escrita no hay
                        // que adivinarla, y a este público los pictogramas
                        // abstractos le cuestan más que una palabra.
                        icon = { },
                        label = { Text(etiqueta, style = MaterialTheme.typography.bodySmall) },
                        alwaysShowLabel = true
                    )
                }
            }
        }
    ) { relleno ->
        NavHost(
            navController = nav,
            startDestination = "novedades",
            modifier = Modifier.padding(relleno)
        ) {
            composable("novedades") {
                NovedadesPantalla(
                    publicaciones = estado.publicaciones,
                    hayFavoritos = estado.perfil.sigueAAlguien,
                    cuantosFavoritos = estado.perfil.favoritos.size,
                    cuantasProductoras = estado.perfil.productoras.size,
                    cuantosCanales = estado.perfil.canales.size,
                    cortos = estado.cortos,
                    verCortos = estado.perfil.veCortos,
                    abiertos = Abiertos.videos,
                    anuncios = banners.takeIf { conAnuncios },
                    onQuitarAnuncios = irAAjustes,
                    ayudaTele = AyudaTele.pendiente,
                    onAyudaTeleEntendida = { AyudaTele.entendida(contexto) },
                    onIrAlDirectorio = { nav.navigate("directorio") },
                    // El canal propio de una productora no tiene creador.
                    onReportar = { modelo.reportarEnlace(it.videoId, it.creatorId.ifBlank { null }) }
                )
            }

            composable("directorio") {
                DirectorioPantalla(
                    creadores = estado.creadores,
                    favoritos = estado.perfil.favoritos,
                    youtube = estado.youtube,
                    onSeguir = seguirCreador,
                    onAbrirCreador = { nav.navigate("creador/$it") },
                    productoras = estado.productoras,
                    productorasSeguidas = estado.perfil.productoras,
                    onSeguirProductora = seguirProductora,
                    onAbrirProductora = { nav.navigate("productora/$it") },
                    onAbrirCanal = { nav.navigate("canal/$it") },
                    etiquetas = estado.etiquetas,
                    perfil = estado.perfil,
                    onSeguirCanal = { modelo.alternarCanal(it) },
                    perfilCargado = estado.perfilCargado,
                    seccionElegida = seccionDirectorio,
                    onElegirSeccion = { seccionDirectorio = it }
                )
            }

            composable(
                "creador/{creatorId}",
                arguments = listOf(navArgument("creatorId") { type = NavType.StringType })
            ) { entrada ->
                val id = entrada.arguments?.getString("creatorId").orEmpty()

                // Sus últimos videos se piden al abrir su ficha.
                LaunchedEffect(id) {
                    modelo.cargarVideosDeCreador(id)
                    // Al volver al Directorio, a la lista de creadores.
                    seccionDirectorio = SeccionDirectorio.CREADORES
                }

                CreadorPantalla(
                    creador = modelo.creador(id),
                    siguiendo = id in estado.perfil.favoritos,
                    youtube = estado.youtube,
                    esAnonimo = estado.esAnonimo,
                    onSeguir = { seguirCreador(id) },
                    onConectarYouTube = { modelo.conectarYouTube(contexto) },
                    onVolver = { nav.popBackStack() },
                    productoras = estado.productoras,
                    onAbrirProductora = { nav.navigate("productora/$it") },
                    creadores = estado.creadores,
                    onAbrirCanal = { nav.navigate("canal/$it") },
                    // Mientras llegan los de este creador, no se pintan los del anterior.
                    videos = estado.videosDeCreador.takeIf { it.canalId == id } ?: VideosDeCanal(id, cargando = true),
                    abiertos = Abiertos.videos,
                    perfil = estado.perfil,
                    onSeguirCanal = { modelo.alternarCanal(it) }
                )
            }

            composable(
                "canal/{canalId}",
                arguments = listOf(navArgument("canalId") { type = NavType.StringType })
            ) { entrada ->
                val id = entrada.arguments?.getString("canalId").orEmpty()

                // Los videos del canal se piden al abrir su ficha.
                LaunchedEffect(id) {
                    modelo.cargarVideosDeCanal(id)
                    seccionDirectorio = SeccionDirectorio.CANALES
                }

                CanalPantalla(
                    canal = estado.canal(id),
                    creadores = estado.creadores,
                    productoras = estado.productoras,
                    // Mientras llegan los de este canal, no se pintan los del anterior.
                    videos = estado.videos.takeIf { it.canalId == id } ?: VideosDeCanal(id, cargando = true),
                    youtube = estado.youtube,
                    onAbrirCreador = { nav.navigate("creador/$it") },
                    onAbrirProductora = { nav.navigate("productora/$it") },
                    onReportar = { modelo.reportarEnlace(it.videoId, it.creatorId.ifBlank { null }) },
                    onReintentar = { modelo.cargarVideosDeCanal(id) },
                    abiertos = Abiertos.videos,
                    perfil = estado.perfil,
                    onSeguirCanal = { modelo.alternarCanal(it) },
                    onVolver = { nav.popBackStack() }
                )
            }

            composable(
                "productora/{productoraId}",
                arguments = listOf(navArgument("productoraId") { type = NavType.StringType })
            ) { entrada ->
                val id = entrada.arguments?.getString("productoraId").orEmpty()

                // Sus últimos videos se piden al abrir su ficha.
                LaunchedEffect(id) {
                    modelo.cargarVideosDeProductora(id)
                    seccionDirectorio = SeccionDirectorio.PRODUCTORAS
                }

                ProductoraPantalla(
                    // De la lista del estado y no del modelo: así la ficha se
                    // repinta cuando llegan las productoras del servidor.
                    productora = estado.productoras.firstOrNull { it.id == id },
                    siguiendo = id in estado.perfil.productoras,
                    creadores = estado.creadores,
                    favoritos = estado.perfil.favoritos,
                    youtube = estado.youtube,
                    onSeguir = { seguirProductora(id) },
                    onSeguirCreador = seguirCreador,
                    onAbrirCreador = { nav.navigate("creador/$it") },
                    onAbrirCanal = { nav.navigate("canal/$it") },
                    // Mientras llegan los de este medio, no se pintan los del anterior.
                    videos = estado.videosDeProductora.takeIf { it.canalId == id } ?: VideosDeCanal(id, cargando = true),
                    abiertos = Abiertos.videos,
                    perfil = estado.perfil,
                    onSeguirCanal = { modelo.alternarCanal(it) },
                    onVolver = { nav.popBackStack() }
                )
            }

            // Los canales de un creador o de un medio, para seguir solo algunos.
            composable(
                "elegir/{tipo}/{id}",
                arguments = listOf(
                    navArgument("tipo") { type = NavType.StringType },
                    navArgument("id") { type = NavType.StringType }
                )
            ) { entrada ->
                val esMedio = entrada.arguments?.getString("tipo") == "productora"
                val id = entrada.arguments?.getString("id").orEmpty()
                LaunchedEffect(esMedio) {
                    seccionDirectorio = if (esMedio) SeccionDirectorio.PRODUCTORAS else SeccionDirectorio.CREADORES
                }
                val creador = if (esMedio) null else modelo.creador(id)
                val productora = if (esMedio) estado.productoras.firstOrNull { it.id == id } else null
                ElegirCanalesPantalla(
                    nombre = creador?.name ?: productora?.nombre,
                    listados = canalesParaElegir(creador, productora, todosLosCanales),
                    sigueATodos = if (esMedio) id in estado.perfil.productoras else id in estado.perfil.favoritos,
                    perfil = estado.perfil,
                    youtube = estado.youtube,
                    onSeguirCanal = { modelo.alternarCanal(it) },
                    onSeguirTodos = {
                        if (esMedio) modelo.alternarProductora(id) else modelo.alternarFavorito(id)
                    },
                    onAbrirCanal = { nav.navigate("canal/$it") },
                    onListo = { nav.popBackStack() }
                )
            }

            // La ficha de un video: a donde lleva tocar una notificación.
            composable(
                FICHA_DE_VIDEO + "{videoId}",
                arguments = listOf(navArgument("videoId") { type = NavType.StringType })
            ) { entrada ->
                val id = entrada.arguments?.getString("videoId").orEmpty()
                // Si Android cerró la app y la rehizo, el estado se perdió:
                // se vuelve a pedir con lo que hay, el id.
                LaunchedEffect(id) {
                    if (estado.fichaDeVideo.videoId != id) modelo.abrirFichaDeVideo(VideoDeAviso(id))
                }

                // Su propio anuncio, apartado de los de Novedades. Solo se
                // pide si el equipo tiene los anuncios encendidos y la persona
                // no los quitó, y se suelta al salir de la ficha.
                val bannerDelVideo = remember { BannersDelFeed() }
                DisposableEffect(Unit) { onDispose { bannerDelVideo.destruir() } }
                val anchoDeAnuncio = LocalConfiguration.current.screenWidthDp - 2 * Espacio.md.value.toInt() - 2
                LaunchedEffect(conAnuncios, anchoDeAnuncio) {
                    if (conAnuncios) bannerDelVideo.asegurar(contexto, 1, anchoDeAnuncio)
                    else bannerDelVideo.destruir()
                }
                val banner = bannerDelVideo.banners.firstOrNull()?.takeIf { conAnuncios && it.cargado }
                val anuncio: (@Composable () -> Unit)? = banner?.let { b ->
                    @Composable { TarjetaAnuncio(banner = b, onQuitar = irAAjustes) }
                }

                val ficha = estado.fichaDeVideo.takeIf { it.videoId == id }
                    ?: FichaDeVideo(VideoDeAviso(id), cargando = true)
                VideoPantalla(
                    ficha = ficha,
                    abierto = id in Abiertos.videos,
                    onVer = { destino ->
                        Enrutador.abrirVideo(
                            contexto,
                            destino.plataforma,
                            destino.videoId,
                            destino.url,
                            campana = if (ficha.esMovido) "contenido_movido" else "aviso_publicacion"
                        )
                        Abiertos.marcar(contexto, id)
                    },
                    onVolver = {
                        nav.navigate("novedades") {
                            popUpTo(nav.graph.startDestinationId)
                            launchSingleTop = true
                        }
                    },
                    onAbrirCreador = { nav.navigate("creador/$it") },
                    onAbrirCanal = { nav.navigate("canal/$it") },
                    onReportar = {
                        ficha.publicacion?.let { modelo.reportarEnlace(it.videoId, it.creatorId.ifBlank { null }) }
                    },
                    anuncio = anuncio
                )
            }

            composable("ajustes") {
                AjustesPantalla(
                    estado = estado,
                    onGuardarPreferencia = modelo::guardarPreferencia,
                    onVincularGoogle = { modelo.vincularConGoogle(contexto) },
                    onCerrarSesion = { modelo.cerrarSesion(contexto) },
                    onBorrarCuenta = { modelo.borrarCuenta(contexto) },
                    onConectarYouTube = { modelo.conectarYouTube(contexto) },
                    onDesconectarYouTube = { modelo.desconectarYouTube(contexto) },
                    // La pantalla de pago y el formulario de privacidad son de
                    // Google y se abren encima de esta pantalla: necesitan la
                    // Activity, que aquí es el propio contexto.
                    onComprarSinAnuncios = {
                        (contexto as? Activity)?.let { modelo.comprarSinAnuncios(it) }
                    },
                    onCanjearFolio = { modelo.canjearFolio(it) },
                    onFolioCerrado = { modelo.folioCerrado() },
                    onPrivacidadDeAnuncios = {
                        (contexto as? Activity)?.let { Anuncios.abrirOpcionesDePrivacidad(it) }
                    }
                )
            }
        }
    }

    porSeguirAhora?.let { por ->
        VentanaAlSeguir(
            por = por,
            perfil = estado.perfil,
            onSeguir = { marcados ->
                porSeguirAhora = null
                val plan = por.queSeguir(marcados, estado.perfil)
                // Primero lo que se deja: con todos marcados, los sueltos
                // sobran porque ya llegan por seguirlo entero.
                plan.dejar.forEach { modelo.alternarCanal(it) }
                plan.seguir.forEach { modelo.alternarCanal(it) }
                if (plan.todo) {
                    if (por.esMedio) modelo.alternarProductora(por.id) else modelo.alternarFavorito(por.id)
                }
            },
            onVerCanales = {
                porSeguirAhora = null
                nav.navigate(por.rutaDeCanales)
            },
            onCancelar = { porSeguirAhora = null }
        )
    }

    // Dejó de estar suscrito en YouTube a alguien del directorio. Es una
    // ventana y no el aviso de abajo, que se va solo a los pocos segundos:
    // esto hay que poder leerlo con calma, y a lo mejor fue sin querer.
    estado.bajasDeYouTube?.let { bajas ->
        AlertDialog(
            onDismissRequest = { modelo.bajasDeYouTubeVistas() },
            title = { Text("Ya no estás suscrito en YouTube") },
            text = { Text(bajas.texto) },
            confirmButton = {
                TextButton(
                    onClick = { modelo.bajasDeYouTubeVistas() },
                    modifier = Modifier.heightIn(min = Tactil.minimo)
                ) { Text("Entendido") }
            },
            dismissButton = {
                // Con un solo canal, a su ficha, que tiene el botón para
                // abrirlo en YouTube; con varios, al Directorio.
                TextButton(
                    onClick = {
                        modelo.bajasDeYouTubeVistas()
                        nav.navigate(bajas.canalId?.let { "canal/$it" } ?: "directorio")
                    },
                    modifier = Modifier.heightIn(min = Tactil.minimo)
                ) { Text(if (bajas.canalId != null) "Ver el canal" else "Ir al Directorio") }
            }
        )
    }

    // Colisión de cuentas: en vez de un código de error, explicamos qué botón
    // tocar. Este diálogo es el que evita que alguien se quede fuera de su
    // propia cuenta al cambiar de teléfono.
    estado.conflicto?.let { conflicto ->
        AlertDialog(
            onDismissRequest = { modelo.descartarConflicto() },
            title = { Text("Ese correo ya tiene cuenta") },
            text = {
                Text(
                    "Tu correo ${conflicto.correo ?: ""} ya se registró antes. " +
                        "Entra otra vez con Google y juntamos las dos cuentas en una sola."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { modelo.resolverConflicto(contexto) },
                    modifier = Modifier.heightIn(min = Tactil.minimo)
                ) { Text("Entrar con Google") }
            },
            dismissButton = {
                TextButton(
                    onClick = { modelo.descartarConflicto() },
                    modifier = Modifier.heightIn(min = Tactil.minimo)
                ) { Text("Ahora no") }
            }
        )
    }
}
