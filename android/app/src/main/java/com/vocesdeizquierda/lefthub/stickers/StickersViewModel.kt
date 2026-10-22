package com.vocesdeizquierda.lefthub.stickers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vocesdeizquierda.lefthub.data.ApiRelay
import com.vocesdeizquierda.lefthub.data.PaqueteDeStickers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cómo va la bajada de un paquete al teléfono. */
data class Bajada(
    /** Ya está en el teléfono, al día con el servidor. */
    val lista: Boolean = false,
    val bajando: Boolean = false,
    /** Cuántos archivos van y de cuántos, mientras se baja. */
    val van: Int = 0,
    val de: Int = 0,
    /** Por qué no se pudo bajar. Escrito para leerse. */
    val error: String? = null
)

data class EstadoStickers(
    val cargando: Boolean = true,
    val paquetes: List<PaqueteDeStickers> = emptyList(),
    /** Por paquete. Uno que no está aquí todavía no se ha intentado bajar. */
    val bajadas: Map<String, Bajada> = emptyMap(),
    val error: String? = null,
    /** El equipo los apagó para esta cuenta: no hay nada que enseñar. */
    val noDisponible: Boolean = false
) {
    /** Los creadores con stickers, en el orden en que llegan (alfabético). */
    val creadores: List<String> get() = paquetes.map { it.creadorId }.distinct()

    fun deCreador(id: String) = paquetes.filter { it.creadorId == id }
}

/**
 * El apartado de Stickers. Vive lo que vive la pantalla principal, así que la
 * lista de creadores y la de cada uno comparten lo ya pedido y lo ya bajado.
 *
 * La lista se pide al servidor cada vez que se entra al apartado. Las
 * imágenes se bajan al abrir los de un creador, y solo si no están ya en el
 * teléfono con la misma versión.
 */
class StickersViewModel(app: Application) : AndroidViewModel(app) {

    private val _estado = MutableStateFlow(EstadoStickers())
    val estado: StateFlow<EstadoStickers> = _estado.asStateFlow()

    fun cargar() {
        _estado.update { it.copy(cargando = it.paquetes.isEmpty(), error = null) }
        viewModelScope.launch {
            try {
                val lista = ApiRelay.stickers()
                val contexto = getApplication<Application>()
                // Leer las fichas guardadas toca el disco: fuera del hilo principal.
                val alDia = withContext(Dispatchers.IO) {
                    lista.filter { AlmacenDeStickers.alDia(contexto, it) }.map { it.id }.toSet()
                }
                _estado.update { e ->
                    e.copy(
                        cargando = false,
                        noDisponible = false,
                        paquetes = lista,
                        // Lo que ya está en el teléfono, al día, no hay que bajarlo.
                        bajadas = lista.associate { p ->
                            p.id to (e.bajadas[p.id]?.takeIf { it.bajando }
                                ?: Bajada(lista = p.id in alDia))
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update { it.copy(cargando = false, noDisponible = esNoDisponible(e), error = mensajeDe(e)) }
            }
        }
    }

    /** Baja los paquetes de un creador que todavía no estén en el teléfono. */
    fun bajarDeCreador(creadorId: String) {
        _estado.value.deCreador(creadorId).forEach { bajar(it) }
    }

    fun bajar(p: PaqueteDeStickers) {
        val antes = _estado.value.bajadas[p.id]
        if (antes?.lista == true || antes?.bajando == true) return

        poner(p.id, Bajada(bajando = true, de = p.stickers.size + 1))
        viewModelScope.launch {
            try {
                AlmacenDeStickers.asegurar(getApplication<Application>(), p, { ruta -> ApiRelay.archivo(ruta) }) { van, de ->
                    poner(p.id, Bajada(bajando = true, van = van, de = de))
                }
                poner(p.id, Bajada(lista = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                poner(p.id, Bajada(error = if (e is ApiRelay.ErrorHttp) e.message ?: "No se pudieron bajar."
                    else "No se pudieron bajar. Revisa tu internet e inténtalo otra vez."))
            }
        }
    }

    private fun poner(id: String, b: Bajada) {
        _estado.update { it.copy(bajadas = it.bajadas + (id to b)) }
    }

    private fun esNoDisponible(e: Exception) = (e as? ApiRelay.ErrorHttp)?.codigo == 403

    private fun mensajeDe(e: Exception): String =
        if (e is ApiRelay.ErrorHttp) e.message ?: "No se pudo completar. Inténtalo otra vez."
        else "No hay conexión. Revisa tu internet e inténtalo otra vez."
}
