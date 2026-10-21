package com.vocesdeizquierda.lefthub.agregar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocesdeizquierda.lefthub.data.Agregado
import com.vocesdeizquierda.lefthub.data.ApiRelay
import com.vocesdeizquierda.lefthub.data.CanalEncontrado
import com.vocesdeizquierda.lefthub.data.LoQueAgregue
import com.vocesdeizquierda.lefthub.data.TipoDeAlta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** De quién es el canal que se agrega a alguien que ya está. */
data class Dueno(val id: String, val nombre: String, val esMedio: Boolean, val fotoUrl: String? = null)

data class EstadoAgregar(
    val cargando: Boolean = true,
    val historial: LoQueAgregue = LoQueAgregue(),
    /** Null mientras se elige qué agregar. */
    val tipo: TipoDeAlta? = null,
    val buscando: Boolean = false,
    val encontrado: CanalEncontrado? = null,
    /** Por qué no se encontró el canal. Escrito para leerse tal cual. */
    val errorBusqueda: String? = null,
    val enviando: Boolean = false,
    /** Por qué no se pudo agregar. Escrito para leerse tal cual. */
    val error: String? = null,
    /** Lo que se acaba de agregar. Mientras no es null se enseña el "Listo". */
    val hecho: Agregado? = null,
    /** El equipo quitó el permiso con la pantalla abierta. */
    val noDisponible: Boolean = false
)

/**
 * "Agregar al directorio", para las cuentas que el equipo autorizó.
 *
 * Lo agregado sale en el acto, sin aprobación: el servidor apunta quién lo
 * hizo y el equipo lo revisa después en el panel. El canal siempre se busca
 * en YouTube antes de guardar, para ver que es el bueno y que no está ya.
 */
class AgregarViewModel : ViewModel() {

    private val _estado = MutableStateFlow(EstadoAgregar())
    val estado: StateFlow<EstadoAgregar> = _estado.asStateFlow()

    private var busqueda: Job? = null

    init {
        cargarHistorial()
    }

    fun cargarHistorial() {
        viewModelScope.launch {
            try {
                val h = ApiRelay.loQueAgregue()
                _estado.update { it.copy(cargando = false, historial = h) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update { it.copy(cargando = false, noDisponible = esNoDisponible(e), error = mensajeDe(e)) }
            }
        }
    }

    fun elegir(tipo: TipoDeAlta?) {
        busqueda?.cancel()
        _estado.update {
            it.copy(tipo = tipo, buscando = false, encontrado = null, errorBusqueda = null, error = null, hecho = null)
        }
    }

    fun buscar(texto: String) {
        if (texto.isBlank()) return
        busqueda?.cancel()
        _estado.update { it.copy(buscando = true, encontrado = null, errorBusqueda = null, error = null) }
        busqueda = viewModelScope.launch {
            try {
                val k = ApiRelay.buscarCanalParaAgregar(texto)
                _estado.update { it.copy(buscando = false, encontrado = k) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update {
                    it.copy(buscando = false, noDisponible = esNoDisponible(e), errorBusqueda = mensajeDe(e))
                }
            }
        }
    }

    fun olvidarCanal() {
        busqueda?.cancel()
        _estado.update { it.copy(buscando = false, encontrado = null, errorBusqueda = null) }
    }

    /**
     * Agrega lo que está en pantalla. [alAgregar] se llama solo si salió bien,
     * para que la app vuelva a pedir el directorio.
     */
    fun agregar(nombre: String, descripcion: String, dueno: Dueno?, etiquetas: List<String>, alAgregar: () -> Unit) {
        val e = _estado.value
        val tipo = e.tipo ?: return
        if (e.enviando) return
        val canal = e.encontrado?.takeIf { it.libre }?.channelId

        _estado.update { it.copy(enviando = true, error = null) }
        viewModelScope.launch {
            try {
                val desc = descripcion.trim().ifEmpty { null }
                val r = when (tipo) {
                    TipoDeAlta.CREADOR -> ApiRelay.agregarCreador(nombre.trim(), desc, canal ?: return@launch sinCanal(), etiquetas)
                    TipoDeAlta.MEDIO -> ApiRelay.agregarMedio(nombre.trim(), desc, canal, etiquetas)
                    TipoDeAlta.CANAL -> {
                        val d = dueno ?: return@launch sinDueno()
                        ApiRelay.agregarCanal(canal ?: return@launch sinCanal(),
                            creadorId = d.id.takeUnless { d.esMedio },
                            productoraId = d.id.takeIf { d.esMedio },
                            etiquetas = etiquetas)
                    }
                }
                _estado.update {
                    it.copy(
                        enviando = false, hecho = r,
                        historial = it.historial.copy(quedanHoy = (it.historial.quedanHoy - 1).coerceAtLeast(0))
                    )
                }
                alAgregar()
                // La lista de abajo, con lo nuevo arriba. Si falla, no importa.
                runCatching { ApiRelay.loQueAgregue() }.getOrNull()?.let { h -> _estado.update { it.copy(historial = h) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update { it.copy(enviando = false, noDisponible = esNoDisponible(e), error = mensajeDe(e)) }
            }
        }
    }

    private fun sinCanal() = _estado.update { it.copy(enviando = false, error = "Busca primero el canal de YouTube.") }

    private fun sinDueno() = _estado.update { it.copy(enviando = false, error = "Elige de quién es el canal.") }

    private fun esNoDisponible(e: Exception) = (e as? ApiRelay.ErrorHttp)?.codigo == 403

    private fun mensajeDe(e: Exception): String =
        if (e is ApiRelay.ErrorHttp) e.message ?: "No se pudo completar. Inténtalo otra vez."
        else "No hay conexión. Revisa tu internet e inténtalo otra vez."

    companion object {
        /** Los mismos topes que el servidor. */
        const val MAX_NOMBRE = 60
        const val MAX_DESCRIPCION = 600
    }
}
