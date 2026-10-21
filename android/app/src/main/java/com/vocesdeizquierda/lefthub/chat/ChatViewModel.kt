package com.vocesdeizquierda.lefthub.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocesdeizquierda.lefthub.data.ApiRelay
import com.vocesdeizquierda.lefthub.data.MensajeDelChat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

data class EstadoChat(
    val cargando: Boolean = true,
    val mensajes: List<MensajeDelChat> = emptyList(),
    val enviando: Boolean = false,
    /** Por qué no se pudo enviar el último mensaje. Null si salió bien. */
    val errorAlEnviar: String? = null,
    /** Se perdió la conexión y se está reintentando. */
    val sinConexion: Boolean = false,
    /** El chat ya no está disponible (lo quitaron, sin permiso...): el motivo. */
    val terminado: String? = null
)

/**
 * Una conversación del chat: la de la persona con el equipo, o, para un
 * administrador, la de una cuenta.
 *
 * Los mensajes están guardados (cifrados) en el servidor; aquí solo están en
 * memoria mientras la pantalla está abierta. Con la pantalla a la vista se
 * esperan los nuevos con consultas de hasta 25 segundos; al pasar la app a
 * segundo plano se deja de esperar y se le dice al servidor, para que lo que
 * llegue mientras tanto traiga aviso.
 *
 * @param cuenta null para la conversación propia; el id de la cuenta si quien
 *               mira es un administrador
 */
class ChatViewModel(private val cuenta: String? = null) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoChat())
    val estado: StateFlow<EstadoChat> = _estado.asStateFlow()

    /** Quien mira es del equipo. */
    val comoEquipo: Boolean get() = cuenta != null

    private var escucha: Job? = null
    private var aLaVista = true
    private var despedido = false

    init {
        cargar()
    }

    /** Trae la conversación y se queda esperando lo nuevo. También para "Volver a intentar". */
    fun cargar() {
        escucha?.cancel()
        _estado.update { it.copy(cargando = it.mensajes.isEmpty(), terminado = null, sinConexion = false) }
        escucha = viewModelScope.launch {
            try {
                agregar(ApiRelay.mensajesDelChat(cuenta, ultimoId(), esperar = false))
                _estado.update { it.copy(cargando = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (definitivo(e)) {
                    terminar(e)
                    return@launch
                }
                _estado.update { it.copy(cargando = false, sinConexion = true) }
            }
            despedido = false
            escuchar()
        }
    }

    /** La pantalla volvió a verse: lo que haya llegado, y a esperar otra vez. */
    fun alVerse() {
        if (aLaVista) return
        aLaVista = true
        if (_estado.value.terminado == null) cargar()
    }

    /** La app pasó a segundo plano: se deja de esperar, para que lo nuevo traiga aviso. */
    fun alOcultarse() {
        if (!aLaVista) return
        aLaVista = false
        escucha?.cancel()
        despedirse()
    }

    /** Manda el mensaje. [alEnviar] se llama solo si salió bien, para vaciar la caja. */
    fun enviar(texto: String, alEnviar: () -> Unit) {
        val limpio = texto.trim()
        if (limpio.isEmpty() || _estado.value.enviando || _estado.value.terminado != null) return
        if (limpio.length > MAX_TEXTO) {
            _estado.update { it.copy(errorAlEnviar = "El mensaje es muy largo. Pártelo en dos.") }
            return
        }

        _estado.update { it.copy(enviando = true, errorAlEnviar = null) }
        viewModelScope.launch {
            try {
                agregar(listOf(ApiRelay.escribirAlChat(cuenta, limpio)))
                alEnviar()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (definitivo(e)) terminar(e)
                else _estado.update { it.copy(errorAlEnviar = e.message ?: "No se pudo enviar. Inténtalo otra vez.") }
            } finally {
                _estado.update { it.copy(enviando = false) }
            }
        }
    }

    override fun onCleared() {
        despedirse()
    }

    // -------------------------------------------------------------------------

    private suspend fun escuchar() {
        var fallos = 0
        while (coroutineContext.isActive) {
            try {
                agregar(ApiRelay.mensajesDelChat(cuenta, ultimoId(), esperar = true))
                fallos = 0
                if (_estado.value.sinConexion) _estado.update { it.copy(sinConexion = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (definitivo(e)) {
                    terminar(e)
                    return
                }
                // Sin red, o el servidor reiniciándose: cada vez más despacio.
                fallos++
                if (fallos >= 3) _estado.update { it.copy(sinConexion = true) }
                delay(minOf(30_000L, 2_000L * fallos))
            }
        }
    }

    private fun agregar(nuevos: List<MensajeDelChat>) {
        if (nuevos.isEmpty()) return
        _estado.update { e ->
            // Por id: el que se acaba de mandar puede llegar también por la espera.
            val porId = LinkedHashMap<Long, MensajeDelChat>()
            (e.mensajes + nuevos).forEach { porId[it.id] = it }
            e.copy(mensajes = porId.values.sortedBy { it.id })
        }
    }

    private fun ultimoId(): Long = _estado.value.mensajes.lastOrNull()?.id ?: 0

    /**
     * El servidor dijo que no y no tiene caso reintentar: sin sesión, el
     * chat quitado (403), la cuenta borrada (404), sin chat activado (409), la
     * app anterior (410) o el chat apagado en el servidor (503).
     */
    private fun definitivo(e: Exception) =
        e is ApiRelay.ErrorHttp && e.codigo in listOf(401, 403, 404, 409, 410, 503)

    private fun terminar(e: Exception) {
        escucha?.cancel()
        _estado.update {
            it.copy(cargando = false, enviando = false,
                terminado = e.message ?: "El chat no está disponible en este momento.")
        }
    }

    /**
     * Le dice al servidor que ya no mira, para que lo que llegue traiga aviso
     * al momento y no dentro de un rato. Va aparte del ViewModel, que puede
     * estar cerrándose.
     */
    private fun despedirse() {
        if (despedido) return
        despedido = true
        val hasta = ultimoId()
        aparte.launch { runCatching { ApiRelay.salirDelChat(cuenta, hasta) } }
    }

    companion object {
        const val MAX_TEXTO = 2000
        private val aparte = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
