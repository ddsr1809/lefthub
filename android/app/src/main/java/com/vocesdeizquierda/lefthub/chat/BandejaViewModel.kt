package com.vocesdeizquierda.lefthub.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocesdeizquierda.lefthub.data.ApiRelay
import com.vocesdeizquierda.lefthub.data.ConversacionDelChat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class EstadoBandeja(
    val cargando: Boolean = true,
    val conversaciones: List<ConversacionDelChat> = emptyList(),
    /** Por qué no se pudo cargar. Escrito para leerse tal cual. */
    val error: String? = null
)

/**
 * La bandeja del chat de un administrador: las cuentas con chat, la que
 * espera respuesta primero. Se pone al día sola mientras está a la vista.
 */
class BandejaViewModel : ViewModel() {

    private val _estado = MutableStateFlow(EstadoBandeja())
    val estado: StateFlow<EstadoBandeja> = _estado.asStateFlow()

    private var refresco: Job? = null

    /** Se llama al verse la pantalla: carga y vuelve a cargar cada 15 segundos. */
    fun alVerse() {
        refresco?.cancel()
        refresco = viewModelScope.launch {
            while (isActive) {
                cargar()
                delay(INTERVALO_MS)
            }
        }
    }

    fun alOcultarse() {
        refresco?.cancel()
    }

    private suspend fun cargar() {
        try {
            val lista = ApiRelay.bandejaDelChat()
            _estado.update { EstadoBandeja(cargando = false, conversaciones = lista) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val motivo = if (e is ApiRelay.ErrorHttp && e.codigo == 403)
                "Tu sesión no tiene el rol de administrador. Cierra sesión y vuelve a entrar con Google."
            else e.message ?: "No se pudo cargar la bandeja."
            _estado.update { it.copy(cargando = false, error = motivo) }
        }
    }

    companion object {
        const val INTERVALO_MS = 15_000L
    }
}
