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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.vocesdeizquierda.lefthub.data.Aceptacion
import com.vocesdeizquierda.lefthub.data.VideosDeCanal
import com.vocesdeizquierda.lefthub.enlaces.Enrutador
import com.vocesdeizquierda.lefthub.ui.*

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

                TemaRelay(
                    preferencia = estado.perfil.tema,
                    escala = EscalaTexto.desde(estado.perfil.escalaTexto)
                ) {
                    if (!estado.listo) {
                        PantallaDeCarga()
                    } else {
                        Navegacion(modelo, estado)
                    }
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
        ViewModelProvider(this)[AppViewModel::class.java].verificarYouTube(applicationContext)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        procesarIntent(intent)
    }

    /**
     * FCM mete los pares de `data` como extras del Intent cuando el usuario
     * toca la notificación. Un toque, y el video ya está abriéndose en su app
     * oficial: cero pantallas intermedias.
     */
    private fun procesarIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        val plataforma = extras.getString("platform") ?: return

        val videoId = extras.getString("videoId")
        val url = extras.getString("url")
        val tipo = extras.getString("tipo")

        Enrutador.abrirVideo(
            contexto = this,
            plataforma = plataforma,
            videoId = videoId,
            url = url,
            campana = if (tipo == "movido") "contenido_movido" else "aviso_publicacion"
        )

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
private val FICHAS = listOf("creador/", "productora/", "canal/")

@Composable
private fun Navegacion(modelo: AppViewModel, estado: EstadoApp) {
    val nav = rememberNavController()
    val contexto = LocalContext.current
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
                            (destino == "directorio" && ruta != null && FICHAS.any { ruta.startsWith(it) }),
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
                    cortos = estado.cortos,
                    verCortos = estado.perfil.veCortos,
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
                    onSeguir = { modelo.alternarFavorito(it) },
                    onAbrirCreador = { nav.navigate("creador/$it") },
                    productoras = estado.productoras,
                    productorasSeguidas = estado.perfil.productoras,
                    onSeguirProductora = { modelo.alternarProductora(it) },
                    onAbrirProductora = { nav.navigate("productora/$it") }
                )
            }

            composable(
                "creador/{creatorId}",
                arguments = listOf(navArgument("creatorId") { type = NavType.StringType })
            ) { entrada ->
                val id = entrada.arguments?.getString("creatorId").orEmpty()
                CreadorPantalla(
                    creador = modelo.creador(id),
                    siguiendo = id in estado.perfil.favoritos,
                    youtube = estado.youtube,
                    esAnonimo = estado.esAnonimo,
                    onSeguir = { modelo.alternarFavorito(id) },
                    onConectarYouTube = { modelo.conectarYouTube(contexto) },
                    onVolver = { nav.popBackStack() },
                    productoras = estado.productoras,
                    onAbrirProductora = { nav.navigate("productora/$it") },
                    creadores = estado.creadores,
                    onAbrirCanal = { nav.navigate("canal/$it") }
                )
            }

            composable(
                "canal/{canalId}",
                arguments = listOf(navArgument("canalId") { type = NavType.StringType })
            ) { entrada ->
                val id = entrada.arguments?.getString("canalId").orEmpty()

                // Los videos del canal se piden al abrir su ficha.
                LaunchedEffect(id) { modelo.cargarVideosDeCanal(id) }

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
                    onVolver = { nav.popBackStack() }
                )
            }

            composable(
                "productora/{productoraId}",
                arguments = listOf(navArgument("productoraId") { type = NavType.StringType })
            ) { entrada ->
                val id = entrada.arguments?.getString("productoraId").orEmpty()
                ProductoraPantalla(
                    // De la lista del estado y no del modelo: así la ficha se
                    // repinta cuando llegan las productoras del servidor.
                    productora = estado.productoras.firstOrNull { it.id == id },
                    siguiendo = id in estado.perfil.productoras,
                    creadores = estado.creadores,
                    favoritos = estado.perfil.favoritos,
                    youtube = estado.youtube,
                    onSeguir = { modelo.alternarProductora(id) },
                    onSeguirCreador = { modelo.alternarFavorito(it) },
                    onAbrirCreador = { nav.navigate("creador/$it") },
                    onAbrirCanal = { nav.navigate("canal/$it") },
                    onVolver = { nav.popBackStack() }
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
                    onDesconectarYouTube = { modelo.desconectarYouTube(contexto) }
                )
            }
        }
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
