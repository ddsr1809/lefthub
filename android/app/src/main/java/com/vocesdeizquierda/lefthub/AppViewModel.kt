package com.vocesdeizquierda.lefthub

import android.content.Context
import android.content.IntentSender
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocesdeizquierda.lefthub.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EstadoApp(
    val listo: Boolean = false,
    val creadores: List<Creador> = emptyList(),
    val productoras: List<Productora> = emptyList(),
    val publicaciones: List<Publicacion> = emptyList(),
    val perfil: Perfil = Perfil(),
    val esAnonimo: Boolean = true,
    val correo: String? = null,
    val ocupado: Boolean = false,
    val mensaje: String? = null,
    val conflicto: AuthRepo.Resultado.Conflicto? = null,
    val youtube: EstadoYouTube = EstadoYouTube()
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(
    private val directorio: DirectorioRepo = DirectorioRepo(),
    private val autenticacion: AuthRepo = AuthRepo(),
    private val youtube: YouTubeRepo = YouTubeRepo()
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

    // Peticiones de "abre la pantalla de permiso de Google". Es un canal y no
    // un campo del estado porque es un suceso, no algo que se pinta: si
    // viviera en el estado, girar el teléfono volvería a abrir la pantalla.
    private val _permisosDeYouTube = Channel<IntentSender>(Channel.BUFFERED)
    val permisosDeYouTube: Flow<IntentSender> = _permisosDeYouTube.receiveAsFlow()

    // Una sola comprobación de YouTube a la vez.
    private val turnoYouTube = Mutex()
    private var ultimaComprobacionYouTube = 0L

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
            directorio.productoras().collect { lista ->
                _estado.update { it.copy(productoras = lista) }
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
                // Seguir a una productora también cambia lo que sale en Novedades.
                .map { it.favoritos to it.productoras }
                .distinctUntilChanged()
                .flatMapLatest { (favoritos, productoras) -> directorio.publicaciones(favoritos, productoras) }
                .collect { lista -> _estado.update { it.copy(publicaciones = lista) } }
        }
    }

    fun creador(id: String) = _estado.value.creadores.firstOrNull { it.id == id }

    fun productora(id: String) = _estado.value.productoras.firstOrNull { it.id == id }

    fun sigue(id: String) = _estado.value.perfil.favoritos.contains(id)

    fun sigueProductora(id: String) = _estado.value.perfil.productoras.contains(id)

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
        directorio.sincronizarTopics(p.favoritos, p.productoras)
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

    /** Seguir o dejar de seguir a una productora: mismo trato que un creador. */
    fun alternarProductora(id: String) = viewModelScope.launch {
        val siguiendo = sigueProductora(id)

        versionPerfil++
        _estado.update { it.copy(perfil = it.perfil.conProductora(id, !siguiendo)) }

        val resultado = escrituras.withLock {
            runCatching { directorio.alternarProductora(id, siguiendo) }
        }
        versionPerfil++

        resultado
            .onSuccess { perfilFlow.update { p -> p.conProductora(id, !siguiendo) } }
            .onFailure {
                _estado.update { e -> e.copy(perfil = e.perfil.conProductora(id, siguiendo)) }
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
    // Suscripciones de YouTube
    // -------------------------------------------------------------------------

    /**
     * La comprobación "en cuanto entra". La llama MainActivity cada vez que la
     * app pasa a primer plano: al abrirla y al volver de YouTube, que es justo
     * cuando alguien acaba de suscribirse y espera verlo reflejado.
     *
     * Es silenciosa de principio a fin. Si falta el permiso no lo pide; solo
     * deja el estado en SIN_PERMISO para que la pantalla ofrezca el botón. Y
     * si algo falla no avisa: nadie pidió nada, así que no hay nada que
     * explicar.
     */
    fun verificarYouTube(contexto: Context) = viewModelScope.launch {
        if (!BuildConfig.SUSCRIPCIONES_YOUTUBE) return@launch

        // onResume llega antes de que exista la sesión; esperamos a que esté.
        estado.first { it.listo }
        if (autenticacion.esAnonimo) return@launch

        val ahora = SystemClock.elapsedRealtime()
        if (ahora - ultimaComprobacionYouTube < PAUSA_YOUTUBE_MS) return@launch

        comprobarYouTube(contexto.applicationContext, interactivo = false)
    }

    /** El botón "Conectar con YouTube". Aquí sí se muestra la pantalla de Google. */
    fun conectarYouTube(contexto: Context) = viewModelScope.launch {
        if (!BuildConfig.SUSCRIPCIONES_YOUTUBE) return@launch
        if (autenticacion.esAnonimo) {
            avisar("Primero guarda tu cuenta con Google, aquí en Ajustes.")
            return@launch
        }
        youtube.encender(contexto)
        comprobarYouTube(contexto.applicationContext, interactivo = true)
    }

    /** La pantalla de permiso de Google se cerró. */
    fun permisoDeYouTubeRespondido(contexto: Context, concedido: Boolean) = viewModelScope.launch {
        if (!concedido) {
            // Dijo que no, o cerró la pantalla. Se respeta sin insistir.
            _estado.update { it.copy(youtube = EstadoYouTube(PermisoYouTube.SIN_PERMISO)) }
            return@launch
        }
        // Con el permiso ya dado, el mismo camino silencioso trae el token.
        comprobarYouTube(contexto.applicationContext, interactivo = false, avisarSiFalla = true)
    }

    fun desconectarYouTube(contexto: Context) = viewModelScope.launch {
        turnoYouTube.withLock {
            youtube.desconectar(contexto.applicationContext, autenticacion.usuario?.email)
            _estado.update { it.copy(youtube = EstadoYouTube(PermisoYouTube.SIN_PERMISO)) }
        }
        avisar("Listo. Ya no consultamos tus suscripciones de YouTube.")
    }

    private suspend fun comprobarYouTube(
        contexto: Context,
        interactivo: Boolean,
        avisarSiFalla: Boolean = interactivo
    ) {
        turnoYouTube.withLock { comprobarYouTubeConTurno(contexto, interactivo, avisarSiFalla) }
    }

    private suspend fun comprobarYouTubeConTurno(
        contexto: Context,
        interactivo: Boolean,
        avisarSiFalla: Boolean
    ) {
        ultimaComprobacionYouTube = SystemClock.elapsedRealtime()
        val usuario = autenticacion.usuario?.uid
        val primeraVez = _estado.value.youtube.permiso == PermisoYouTube.DESCONOCIDO

        _estado.update { it.copy(youtube = it.youtube.copy(verificando = true)) }

        // Lo que el servidor ya sabía, para pintar sin esperar a Google. Solo
        // la primera vez: después, lo que hay en pantalla es igual de reciente.
        if (primeraVez) {
            youtube.guardadas()?.let { guardadas ->
                _estado.update { it.copy(youtube = it.youtube.copy(suscripciones = guardadas)) }
            }
        }

        val resultado = youtube.verificar(contexto)

        // Si mientras tanto se cerró sesión o se cambió de cuenta, esta
        // respuesta es de otra persona y no se pinta.
        if (autenticacion.esAnonimo || autenticacion.usuario?.uid != usuario) {
            _estado.update { it.copy(youtube = EstadoYouTube()) }
            return
        }

        when (resultado) {
            is YouTubeRepo.Resultado.Verificado -> _estado.update {
                it.copy(youtube = EstadoYouTube(PermisoYouTube.CONCEDIDO, resultado.suscripciones))
            }

            is YouTubeRepo.Resultado.FaltaPermiso -> {
                val habiaDatos = _estado.value.youtube.suscripciones.verificadoEn != null
                _estado.update { it.copy(youtube = EstadoYouTube(PermisoYouTube.SIN_PERMISO)) }

                if (interactivo && resultado.pedir != null) {
                    _permisosDeYouTube.send(resultado.pedir)
                } else if (habiaDatos) {
                    // El servidor tenía datos pero el permiso ya no está: la
                    // persona lo retiró desde su cuenta de Google. Lo guardado
                    // ya no nos corresponde conservarlo.
                    youtube.olvidar()
                }
            }

            is YouTubeRepo.Resultado.Fallo -> {
                // Se queda lo que hubiera en pantalla; solo se apaga el aviso
                // de "comprobando".
                _estado.update { it.copy(youtube = it.youtube.copy(verificando = false)) }
                if (avisarSiFalla) avisar(resultado.mensaje)
            }
        }
    }

    private fun reiniciarYouTube() {
        ultimaComprobacionYouTube = 0L
        _estado.update { it.copy(youtube = EstadoYouTube()) }
    }

    // -------------------------------------------------------------------------
    // Cuenta
    // -------------------------------------------------------------------------

    fun vincularConGoogle(contexto: Context) = viewModelScope.launch {
        _estado.update { it.copy(ocupado = true) }
        val resultado = autenticacion.vincularConGoogle(contexto)
        procesar(resultado)
        recargarPerfil()

        // Cuenta recién guardada: si en otro teléfono ya había dado el permiso
        // de YouTube, aquí se entera sin tener que cerrar y abrir la app.
        reiniciarYouTube()
        verificarYouTube(contexto)
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
        youtube.alCerrarSesion(contexto)
        reiniciarYouTube()
        _estado.update { it.copy(esAnonimo = true, correo = null, mensaje = "Sesión cerrada.") }
        recargarPerfil()
    }

    fun borrarCuenta(contexto: Context) = viewModelScope.launch {
        _estado.update { it.copy(ocupado = true) }

        // Borrar la cuenta incluye retirarle a la app el permiso de YouTube.
        // Lo guardado en el servidor se va con la cuenta; falta el lado de
        // Google. El correo se anota ahora porque después del borrado ya no
        // sabemos a qué cuenta de Google hay que retirárselo.
        val teniaYouTube = _estado.value.youtube.permiso == PermisoYouTube.CONCEDIDO
        val correo = autenticacion.usuario?.email

        runCatching { autenticacion.borrarCuenta(contexto) }
            .onSuccess {
                if (teniaYouTube) youtube.desconectar(contexto.applicationContext, correo)
                youtube.alCerrarSesion(contexto)
                reiniciarYouTube()
                avisar("Cuenta borrada. Puedes seguir usando la app como invitado.")
            }
            .onFailure { avisar("No se pudo borrar la cuenta: ${it.localizedMessage}") }
        _estado.update { it.copy(ocupado = false, esAnonimo = true, correo = null) }
        recargarPerfil()
    }

    private fun avisar(texto: String) = _estado.update { it.copy(mensaje = texto) }

    fun mensajeVisto() = _estado.update { it.copy(mensaje = null) }

    private companion object {
        const val INTERVALO_PERFIL_MS = 60_000L

        // onResume se dispara varias veces seguidas al arrancar y al cerrarse
        // la pantalla de permiso; con esto solo cuenta la primera.
        const val PAUSA_YOUTUBE_MS = 3_000L
    }
}

// --- Cambios locales sobre el perfil -----------------------------------------

private fun Perfil.conFavorito(id: String, siguiendo: Boolean): Perfil = copy(
    favoritos = if (siguiendo) (favoritos + id).distinct() else favoritos - id
)

private fun Perfil.conProductora(id: String, siguiendo: Boolean): Perfil = copy(
    productoras = if (siguiendo) (productoras + id).distinct() else productoras - id
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
