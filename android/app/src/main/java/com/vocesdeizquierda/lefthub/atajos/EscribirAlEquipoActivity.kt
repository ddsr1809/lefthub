package com.vocesdeizquierda.lefthub.atajos

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocesdeizquierda.lefthub.MainActivity
import com.vocesdeizquierda.lefthub.chat.ChatViewModel
import com.vocesdeizquierda.lefthub.data.Aceptacion
import com.vocesdeizquierda.lefthub.data.ApiRelay
import com.vocesdeizquierda.lefthub.data.MensajeDelChat
import com.vocesdeizquierda.lefthub.ui.BotonGrande
import com.vocesdeizquierda.lefthub.ui.EscalaTexto
import com.vocesdeizquierda.lefthub.ui.Espacio
import com.vocesdeizquierda.lefthub.ui.TemaRelay
import com.vocesdeizquierda.lefthub.ui.VarianteBoton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * La ventanita de «Escribir al equipo», la del menú del ícono: se escribe y
 * se manda sin entrar a la app. Enseña los últimos mensajes de la
 * conversación, para saber a qué se contesta.
 *
 * Es una Activity aparte, con su propia tarea, para que al cerrarse no se
 * lleve la app si estaba abierta detrás. Igual que la pantalla del chat, no
 * deja hacer capturas.
 */
class EscribirAlEquipoActivity : ComponentActivity() {

    private val modelo: EscribirViewModel by viewModels()

    // El ViewModel habla con el servidor al nacer: solo se pide si la
    // ventanita de verdad se abre.
    private var abierta = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Antes de aceptar las condiciones nada habla con el servidor, y sin
        // sesión no hay chat: en los dos casos, a la app, que sabe qué hacer.
        if (!Aceptacion.vigente(this) || !ApiRelay.haySesion) {
            abrirApp(conChat = false)
            return
        }

        abierta = true
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // Para que la caja de texto suba con el teclado (imePadding).
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val estado by modelo.estado.collectAsState()
            TemaRelay(preferencia = estado.tema, escala = EscalaTexto.desde(estado.escala)) {
                Ventana(
                    estado = estado,
                    onEnviar = modelo::enviar,
                    onAbrirChat = { abrirApp(conChat = true) },
                    onCerrar = { finish() }
                )
            }
            // Mandado: un momento para leer que salió, y se cierra sola.
            LaunchedEffect(estado.paso) {
                if (estado.paso == Paso.ENVIADO) {
                    delay(2_500)
                    finish()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Lo que se vio queda leído, y lo que el equipo conteste desde ahora
        // trae aviso (mientras se ve la ventanita, el servidor no avisa).
        if (abierta) modelo.despedirse()
    }

    private fun abrirApp(conChat: Boolean) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { if (conChat) putExtra("tipo", "chat") }
        )
        finish()
    }
}

enum class Paso { CARGANDO, ESCRIBIR, ENVIADO, NO_DISPONIBLE }

data class EstadoEscribir(
    val paso: Paso = Paso.CARGANDO,
    /** Los últimos de la conversación, el más viejo primero. */
    val ultimos: List<MensajeDelChat> = emptyList(),
    val enviando: Boolean = false,
    /** Por qué no salió el mensaje, para leerse tal cual. */
    val error: String? = null,
    /** Por qué no hay chat (NO_DISPONIBLE). */
    val motivo: String? = null,
    val tema: String = "sistema",
    val escala: String = "normal"
)

class EscribirViewModel : ViewModel() {

    private val _estado = MutableStateFlow(EstadoEscribir())
    val estado: StateFlow<EstadoEscribir> = _estado.asStateFlow()

    private var ultimoVisto = 0L
    private var vio = false

    init {
        viewModelScope.launch {
            try {
                // El perfil dice si tiene chat y cómo le gusta ver la app.
                val p = ApiRelay.perfil()
                _estado.update { it.copy(tema = p.tema, escala = p.escalaTexto) }
                if (!p.chat) {
                    _estado.update {
                        it.copy(paso = Paso.NO_DISPONIBLE,
                            motivo = "El chat con el equipo no está activado para tu cuenta.")
                    }
                    return@launch
                }
                val lista = ApiRelay.mensajesDelChat(null, 0, esperar = false)
                vio = true
                ultimoVisto = lista.lastOrNull()?.id ?: 0
                _estado.update { it.copy(paso = Paso.ESCRIBIR, ultimos = lista.takeLast(ULTIMOS)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update {
                    if (definitivo(e)) it.copy(paso = Paso.NO_DISPONIBLE,
                        motivo = e.message ?: "El chat no está disponible en este momento.")
                    // Sin conexión: se puede escribir igual; si al mandar
                    // sigue sin haber, lo dice ahí.
                    else it.copy(paso = Paso.ESCRIBIR)
                }
            }
        }
    }

    fun enviar(texto: String) {
        val limpio = texto.trim()
        if (limpio.isEmpty() || _estado.value.enviando) return
        if (limpio.length > ChatViewModel.MAX_TEXTO) {
            _estado.update { it.copy(error = "El mensaje es muy largo. Pártelo en dos.") }
            return
        }
        _estado.update { it.copy(enviando = true, error = null) }
        viewModelScope.launch {
            try {
                val enviado = ApiRelay.escribirAlChat(null, limpio)
                ultimoVisto = maxOf(ultimoVisto, enviado.id)
                _estado.update { it.copy(enviando = false, paso = Paso.ENVIADO) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update {
                    if (definitivo(e)) it.copy(enviando = false, paso = Paso.NO_DISPONIBLE,
                        motivo = e.message ?: "El chat no está disponible en este momento.")
                    else it.copy(enviando = false,
                        error = e.message ?: "No se pudo enviar. Revisa tu conexión e inténtalo otra vez.")
                }
            }
        }
    }

    /** Ver ChatViewModel.despedirse: lo mismo, desde la ventanita. */
    fun despedirse() {
        if (!vio) return
        vio = false
        val hasta = ultimoVisto
        aparte.launch { runCatching { ApiRelay.salirDelChat(null, hasta) } }
    }

    override fun onCleared() {
        despedirse()
    }

    /** Los mismos códigos que en ChatViewModel: no tiene caso reintentar. */
    private fun definitivo(e: Exception) =
        e is ApiRelay.ErrorHttp && e.codigo in listOf(401, 403, 404, 409, 410, 503)

    private companion object {
        const val ULTIMOS = 3
        val aparte = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

@Composable
private fun Ventana(
    estado: EstadoEscribir,
    onEnviar: (String) -> Unit,
    onAbrirChat: () -> Unit,
    onCerrar: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme
    // Tocar fuera de la tarjeta la cierra, como cualquier ventanita.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onCerrar
            )
            .systemBarsPadding()
            .imePadding()
            .padding(Espacio.md),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = esquema.background,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                // Que tocar dentro no cuente como tocar fuera.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(Espacio.lg)
            ) {
                Text(
                    "Escribir al equipo de Voces",
                    style = MaterialTheme.typography.titleLarge,
                    color = esquema.onBackground,
                    modifier = Modifier
                        .padding(bottom = Espacio.md)
                        .semantics { heading() }
                )
                when (estado.paso) {
                    Paso.CARGANDO -> Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(Espacio.lg),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    Paso.NO_DISPONIBLE -> {
                        Text(
                            estado.motivo ?: "El chat no está disponible en este momento.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = esquema.onBackground,
                            modifier = Modifier.padding(bottom = Espacio.lg)
                        )
                        BotonGrande("Abrir la app", onClick = onAbrirChat)
                        BotonGrande("Cerrar", variante = VarianteBoton.SECUNDARIO, onClick = onCerrar)
                    }

                    Paso.ENVIADO -> {
                        Text(
                            "Mensaje enviado. Te avisamos cuando el equipo conteste.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = esquema.onBackground,
                            modifier = Modifier
                                .padding(bottom = Espacio.lg)
                                .semantics { liveRegion = LiveRegionMode.Polite }
                        )
                        BotonGrande("Ver la conversación", onClick = onAbrirChat)
                    }

                    Paso.ESCRIBIR -> Escribir(estado, onEnviar, onAbrirChat)
                }
            }
        }
    }
}

@Composable
private fun Escribir(
    estado: EstadoEscribir,
    onEnviar: (String) -> Unit,
    onAbrirChat: () -> Unit
) {
    val esquema = MaterialTheme.colorScheme
    var texto by rememberSaveable { mutableStateOf("") }
    val foco = remember { FocusRequester() }
    val teclado = LocalSoftwareKeyboardController.current
    // Se abrió para escribir: el teclado ya listo.
    LaunchedEffect(Unit) {
        runCatching { foco.requestFocus() }
        teclado?.show()
    }

    estado.ultimos.forEach { m ->
        val quien = if (m.deAdmin) (m.autor ?: "Equipo de Voces") else "Tú"
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = Espacio.sm)
                .background(
                    if (m.deAdmin) esquema.surface else esquema.surfaceVariant,
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = Espacio.md, vertical = Espacio.sm)
                .semantics(mergeDescendants = true) { contentDescription = "$quien: ${m.texto}" }
        ) {
            Text(quien, style = MaterialTheme.typography.bodySmall, color = esquema.onSurfaceVariant)
            Text(
                m.texto,
                style = MaterialTheme.typography.bodyLarge,
                color = esquema.onSurface,
                maxLines = 4
            )
        }
    }

    OutlinedTextField(
        value = texto,
        onValueChange = { texto = it },
        label = { Text("Tu mensaje") },
        minLines = 2,
        maxLines = 6,
        enabled = !estado.enviando,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Espacio.sm, bottom = Espacio.sm)
            .focusRequester(foco)
    )

    estado.error?.let {
        Text(
            it,
            color = esquema.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(bottom = Espacio.sm)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
    }

    BotonGrande(
        titulo = if (estado.enviando) "Enviando…" else "Enviar",
        habilitado = !estado.enviando && texto.isNotBlank(),
        onClick = { onEnviar(texto) }
    )
    BotonGrande("Abrir la conversación", variante = VarianteBoton.SECUNDARIO, onClick = onAbrirChat)
}
