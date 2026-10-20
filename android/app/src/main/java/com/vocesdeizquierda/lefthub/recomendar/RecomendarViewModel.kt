package com.vocesdeizquierda.lefthub.recomendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocesdeizquierda.lefthub.data.ApiRelay
import com.vocesdeizquierda.lefthub.data.Recomendacion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EstadoRecomendar(
    val cargando: Boolean = true,
    val recomendaciones: List<Recomendacion> = emptyList(),
    /** Cuántas puede mandar todavía hoy. */
    val quedanHoy: Int = 0,
    val enviando: Boolean = false,
    /** La frase del servidor tras mandar una: "Gracias. Le pasamos…". */
    val gracias: String? = null,
    /** Por qué no se pudo mandar o cargar. Escrito para leerse tal cual. */
    val error: String? = null,
    /** El equipo lo apagó con la pantalla abierta: ya no hay nada que hacer aquí. */
    val noDisponible: Boolean = false
)

/**
 * La pantalla de "Recomendar un canal". Vive lo que vive esa pantalla.
 *
 * Solo se llega aquí si el perfil trae `recomendar` en true. Si el equipo lo
 * apaga con la pantalla abierta, el servidor contesta 403 y la pantalla lo
 * dice en vez de dejar escribir para nada.
 */
class RecomendarViewModel : ViewModel() {

    private val _estado = MutableStateFlow(EstadoRecomendar())
    val estado: StateFlow<EstadoRecomendar> = _estado.asStateFlow()

    init {
        cargar()
    }

    fun cargar() {
        _estado.update { it.copy(cargando = true, error = null) }
        viewModelScope.launch {
            try {
                val mias = ApiRelay.recomendaciones()
                _estado.update {
                    it.copy(cargando = false, recomendaciones = mias.recomendaciones, quedanHoy = mias.quedanHoy)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update { it.copy(cargando = false, noDisponible = esNoDisponible(e), error = mensajeDe(e)) }
            }
        }
    }

    /**
     * Manda la recomendación. [alMandar] se llama solo si salió bien, para
     * que la pantalla vacíe el formulario.
     */
    fun recomendar(texto: String, motivo: String, alMandar: () -> Unit) {
        val limpio = texto.trim()
        if (limpio.isEmpty() || _estado.value.enviando) return

        _estado.update { it.copy(enviando = true, error = null, gracias = null) }
        viewModelScope.launch {
            try {
                val (mensaje, nueva) = ApiRelay.recomendar(limpio, motivo.trim().ifEmpty { null })
                _estado.update {
                    it.copy(
                        enviando = false,
                        gracias = mensaje,
                        recomendaciones = listOfNotNull(nueva) + it.recomendaciones,
                        quedanHoy = (it.quedanHoy - 1).coerceAtLeast(0)
                    )
                }
                alMandar()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update { it.copy(enviando = false, noDisponible = esNoDisponible(e), error = mensajeDe(e)) }
            }
        }
    }

    private fun esNoDisponible(e: Exception) = (e as? ApiRelay.ErrorHttp)?.codigo == 403

    private fun mensajeDe(e: Exception): String =
        if (e is ApiRelay.ErrorHttp) e.message ?: "No se pudo completar. Inténtalo otra vez."
        else "No hay conexión. Revisa tu internet e inténtalo otra vez."

    companion object {
        /** Los mismos topes que el servidor: más largo, lo recorta. */
        const val MAX_TEXTO = 300
        const val MAX_MOTIVO = 500
    }
}
