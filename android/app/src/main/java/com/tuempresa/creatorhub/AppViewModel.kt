package com.tuempresa.creatorhub

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tuempresa.creatorhub.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EstadoApp(
    val listo: Boolean = false,
    val creadores: List<Creador> = emptyList(),
    val publicaciones: List<Publicacion> = emptyList(),
    val perfil: Perfil = Perfil(),
    val esAnonimo: Boolean = true,
    val correo: String? = null,
    val ocupado: Boolean = false,
    val mensaje: String? = null,
    val conflicto: AuthRepo.Resultado.Conflicto? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(
    private val directorio: DirectorioRepo = DirectorioRepo(),
    private val autenticacion: AuthRepo = AuthRepo()
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoApp())
    val estado: StateFlow<EstadoApp> = _estado.asStateFlow()

    // El perfil que el servidor ya confirmó. Es el que dispara la recarga de
    // Novedades: si la disparáramos con el cambio optimista, pediríamos el
    // feed antes de que el servidor supiera del favorito nuevo.
    private val perfilFlow = MutableStateFlow(Perfil())

    // Sube cada vez que el perfil cambia desde este teléfono. Una lectura del
    // servidor que empezó con una versión anterior se descarta: trae el estado
    // de antes del cambio y devolvería el botón a como estaba.
    private var versionPerfil = 0

    // Los cambios salen de uno en uno y en el orden en que se tocaron.
    private val escrituras = Mutex()

    init {
        viewModelScope.launch {
            // Sesión anónima antes que nada: sin UID no hay lecturas posibles.
            runCatching { autenticacion.iniciarSesionInvisible() }
            _estado.update {
                it.copy(
                    listo = true,
                    esAnonimo = autenticacion.esAnonimo,
                    correo = autenticacion.usuario?.email
                )
            }
            observar()
        }
    }

    private fun observar() {
        viewModelScope.launch {
            directorio.creadores().collect { lista ->
                _estado.update { it.copy(creadores = lista) }
            }
        }

        viewModelScope.launch {
            while (true) {
                refrescarPerfil()
                delay(INTERVALO_PERFIL_MS)
            }
        }

        viewModelScope.launch {
            perfilFlow
                .map { it.favoritos }
                .distinctUntilChanged()
                .flatMapLatest { directorio.publicaciones(it) }
                .collect { lista -> _estado.update { it.copy(publicaciones = lista) } }
        }
    }

    fun creador(id: String) = _estado.value.creadores.firstOrNull { it.id == id }

    fun sigue(id: String) = _estado.value.perfil.favoritos.contains(id)

    /**
     * Trae el perfil del servidor y lo pone en pantalla.
     *
     * Antes Firestore empujaba los cambios solo; ahora el servidor no avisa de
     * nada, así que esta lectura periódica solo sirve para enterarse de lo que
     * cambió en OTRO teléfono. Lo que el usuario toca aquí se pinta en el
     * momento, sin esperar a esta lectura.
     */
    private suspend fun refrescarPerfil() {
        val version = versionPerfil
        val p = runCatching { directorio.perfil() }.getOrNull() ?: return
        if (version != versionPerfil) return

        perfilFlow.value = p
        _estado.update { it.copy(perfil = p) }
        // Los favoritos viven en la cuenta, pero los topics son por aparato.
        // Un teléfono nuevo no está suscrito a nada.
        directorio.sincronizarTopics(p.favoritos)
    }

    /** Tras cambiar de cuenta, el perfil en pantalla es de otra persona. */
    private suspend fun recargarPerfil() {
        versionPerfil++
        refrescarPerfil()
    }

    /**
     * Seguir o dejar de seguir.
     *
     * El botón cambia en el mismo toque y el servidor se entera después. Si
     * el servidor falla, el botón vuelve a como estaba y se avisa.
     */
    fun alternarFavorito(id: String) = viewModelScope.launch {
        val siguiendo = sigue(id)

        versionPerfil++
        _estado.update { it.copy(perfil = it.perfil.conFavorito(id, !siguiendo)) }

        val resultado = escrituras.withLock {
            runCatching { directorio.alternarFavorito(id, siguiendo) }
        }
        versionPerfil++

        resultado
            .onSuccess { perfilFlow.update { p -> p.conFavorito(id, !siguiendo) } }
            .onFailure {
                _estado.update { e -> e.copy(perfil = e.perfil.conFavorito(id, siguiendo)) }
                avisar("No se pudo guardar el cambio. Revisa tu conexión.")
            }
    }

    /** Tema, tamaño de letra y avisos: mismo trato que los favoritos. */
    fun guardarPreferencia(clave: String, valor: Any) = viewModelScope.launch {
        val anterior = _estado.value.perfil.preferencia(clave)

        versionPerfil++
        _estado.update { it.copy(perfil = it.perfil.conPreferencia(clave, valor)) }

        val resultado = escrituras.withLock {
            runCatching { directorio.guardarPreferencia(clave, valor) }
        }
        versionPerfil++

        resultado.onFailure {
            // Solo se deshace si en pantalla sigue este cambio; si el usuario
            // ya eligió otra cosa mientras tanto, manda lo último que tocó.
            _estado.update { e ->
                if (e.perfil.preferencia(clave) == valor)
                    e.copy(perfil = e.perfil.conPreferencia(clave, anterior))
                else e
            }
            avisar("No se pudo guardar el cambio. Revisa tu conexión.")
        }
    }

    fun reportarEnlace(videoId: String?, creatorId: String?) = viewModelScope.launch {
        runCatching { directorio.reportarEnlaceRoto(videoId, creatorId) }
            .onSuccess { avisar("Gracias. Vamos a revisar ese enlace.") }
            .onFailure { avisar("No se pudo enviar el reporte. Inténtalo más tarde.") }
    }

    // -------------------------------------------------------------------------
    // Cuenta
    // -------------------------------------------------------------------------

    fun vincularConGoogle(contexto: Context) = viewModelScope.launch {
        _estado.update { it.copy(ocupado = true) }
        val resultado = autenticacion.vincularConGoogle(contexto)
        procesar(resultado)
        recargarPerfil()
    }

    fun resolverConflicto(contexto: Context) = viewModelScope.launch {
        val pendiente = _estado.value.conflicto ?: return@launch
        _estado.update { it.copy(ocupado = true, conflicto = null) }
        procesar(autenticacion.resolverConflicto(contexto, pendiente.credencialPendiente))
        recargarPerfil()
    }

    fun descartarConflicto() = _estado.update { it.copy(conflicto = null) }

    private fun procesar(resultado: AuthRepo.Resultado) {
        val nuevo = when (resultado) {
            is AuthRepo.Resultado.Vinculada ->
                _estado.value.copy(mensaje = "Tu cuenta quedó guardada. Si cambias de teléfono, tus creadores te siguen.")

            is AuthRepo.Resultado.Recuperada -> _estado.value.copy(
                mensaje = if (resultado.favoritosFusionados > 0)
                    "Ya tenías cuenta aquí. Juntamos los ${resultado.favoritosFusionados} creadores de este teléfono con los de antes."
                else
                    "Ya tenías cuenta aquí y volvimos a entrar con ella."
            )

            is AuthRepo.Resultado.Conflicto -> _estado.value.copy(conflicto = resultado)
            is AuthRepo.Resultado.Cancelada -> _estado.value
            is AuthRepo.Resultado.Fallo -> _estado.value.copy(mensaje = resultado.mensaje)
        }

        _estado.value = nuevo.copy(
            ocupado = false,
            esAnonimo = autenticacion.esAnonimo,
            correo = autenticacion.usuario?.email
        )
    }

    fun cerrarSesion(contexto: Context) = viewModelScope.launch {
        runCatching { autenticacion.cerrarSesion(contexto) }
        _estado.update { it.copy(esAnonimo = true, correo = null, mensaje = "Sesión cerrada.") }
        recargarPerfil()
    }

    fun borrarCuenta(contexto: Context) = viewModelScope.launch {
        _estado.update { it.copy(ocupado = true) }
        runCatching { autenticacion.borrarCuenta(contexto) }
            .onSuccess { avisar("Cuenta borrada. Puedes seguir usando la app como invitado.") }
            .onFailure { avisar("No se pudo borrar la cuenta: ${it.localizedMessage}") }
        _estado.update { it.copy(ocupado = false, esAnonimo = true, correo = null) }
        recargarPerfil()
    }

    private fun avisar(texto: String) = _estado.update { it.copy(mensaje = texto) }

    fun mensajeVisto() = _estado.update { it.copy(mensaje = null) }

    private companion object {
        const val INTERVALO_PERFIL_MS = 60_000L
    }
}

// --- Cambios locales sobre el perfil -----------------------------------------

private fun Perfil.conFavorito(id: String, siguiendo: Boolean): Perfil = copy(
    favoritos = if (siguiendo) (favoritos + id).distinct() else favoritos - id
)

private fun Perfil.preferencia(clave: String): Any? = when (clave) {
    "tema" -> tema
    "escalaTexto" -> escalaTexto
    "avisos" -> avisos
    else -> null
}

private fun Perfil.conPreferencia(clave: String, valor: Any?): Perfil = when (clave) {
    "tema" -> copy(tema = valor as? String ?: tema)
    "escalaTexto" -> copy(escalaTexto = valor as? String ?: escalaTexto)
    "avisos" -> copy(avisos = valor as? Boolean ?: avisos)
    else -> this
}
