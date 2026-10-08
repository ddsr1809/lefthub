package com.vocesdeizquierda.lefthub

import android.app.Activity
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
    /** Los videos cortos, que van en su propio apartado de Novedades. */
    val cortos: List<Publicacion> = emptyList(),
    val perfil: Perfil = Perfil(),
    val esAnonimo: Boolean = true,
    val correo: String? = null,
    val ocupado: Boolean = false,
    val mensaje: String? = null,
    val conflicto: AuthRepo.Resultado.Conflicto? = null,
    val youtube: EstadoYouTube = EstadoYouTube(),
    val videos: VideosDeCanal = VideosDeCanal(),
    /**
     * Lo que cuesta quitar los anuncios, ya escrito con su moneda. Null
     * mientras no se sepa o si en este teléfono no se puede comprar: entonces
     * Ajustes no enseña el botón de comprar.
     */
    val precioSinAnuncios: String? = null,
    val folio: EstadoFolio = EstadoFolio()
) {
    /** Un canal por su id, sea de un creador o propio de una productora. */
    fun canal(id: String): Canal? =
        (creadores.flatMap { it.canales } + productoras.flatMap { it.canales })
            .firstOrNull { it.id == id }
}

/** El canje de un folio de regalo, para la ventanita de Ajustes. */
data class EstadoFolio(
    val enviando: Boolean = false,
    /** Por qué no se pudo, escrito para leerse. Null si no ha fallado. */
    val error: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(
    private val directorio: DirectorioRepo = DirectorioRepo(),
    private val autenticacion: AuthRepo = AuthRepo(),
    private val youtube: YouTubeRepo = YouTubeRepo(),
    private val compras: ComprasRepo = ComprasRepo()
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

    // Y una sola consulta a Google Play a la vez.
    private val turnoTienda = Mutex()
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

        viewModelScope.launch {
            perfilFlow
                // Los cortos dependen además de si la persona los ve: al
                // apagarlos, o si el equipo los apaga, la lista se vacía.
                .map { Triple(it.favoritos, it.productoras, it.veCortos) }
                .distinctUntilChanged()
                .flatMapLatest { (favoritos, productoras, losVe) ->
                    directorio.cortos(favoritos, productoras, losVe)
                }
                .collect { lista -> _estado.update { it.copy(cortos = lista) } }
        }

        viewModelScope.launch {
            // Lo que cuenta Google Play cuando se cierra su pantalla de pago.
            compras.novedades.collect { alComprar(it) }
        }
    }

    fun creador(id: String) = _estado.value.creadores.firstOrNull { it.id == id }

    fun productora(id: String) = _estado.value.productoras.firstOrNull { it.id == id }

    /**
     * Trae los últimos videos de un canal para su ficha.
     *
     * Al volver a la misma ficha se ve lo que ya había mientras llega lo
     * nuevo; al abrir la de otro canal, la lista empieza vacía.
     */
    fun cargarVideosDeCanal(canalId: String) = viewModelScope.launch {
        if (canalId.isBlank()) return@launch

        _estado.update { e ->
            val previos = if (e.videos.canalId == canalId) e.videos.lista else emptyList()
            e.copy(videos = VideosDeCanal(canalId, cargando = true, lista = previos))
        }

        val resultado = runCatching { directorio.videosDeCanal(canalId) }

        _estado.update { e ->
            // Si mientras tanto se abrió la ficha de otro canal, esta
            // respuesta ya no es la que hay que pintar.
            if (e.videos.canalId != canalId) e
            else e.copy(videos = VideosDeCanal(
                canalId = canalId,
                lista = resultado.getOrDefault(e.videos.lista),
                fallo = resultado.isFailure
            ))
        }
    }

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
        val leido = runCatching { directorio.perfil() }.getOrNull() ?: return
        if (version != versionPerfil) return

        // Una compilación sin bloque de anuncios (la de producción, mientras
        // no se rellenen los identificadores de AdMob) no puede mostrar
        // ninguno, diga lo que diga el panel. Para ella es como si estuvieran
        // apagados: ni anuncios ni la oferta de pagar por quitarlos.
        val p = if (BuildConfig.ADMOB_BANNER.isBlank()) leido.copy(anuncios = false) else leido

        perfilFlow.value = p
        _estado.update { it.copy(perfil = p) }
        // Los favoritos viven en la cuenta, pero los topics son por aparato.
        // Un teléfono nuevo no está suscrito a nada.
        directorio.sincronizarTopics(p.favoritos, p.productoras, p.veCortos)
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
            runCatching {
                directorio.alternarFavorito(id, siguiendo, _estado.value.perfil.veCortos)
            }
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
            runCatching {
                directorio.alternarProductora(id, siguiendo, _estado.value.perfil.veCortos)
            }
        }
        versionPerfil++

        resultado
            .onSuccess { perfilFlow.update { p -> p.conProductora(id, !siguiendo) } }
            .onFailure {
                _estado.update { e -> e.copy(perfil = e.perfil.conProductora(id, siguiendo)) }
                avisar("No se pudo guardar el cambio. Revisa tu conexión.")
            }
    }

    /** Tema, tamaño de letra, avisos y videos cortos: mismo trato que los favoritos. */
    fun guardarPreferencia(clave: String, valor: Any) = viewModelScope.launch {
        val anterior = _estado.value.perfil.preferencia(clave)

        versionPerfil++
        _estado.update { it.copy(perfil = it.perfil.conPreferencia(clave, valor)) }

        val resultado = escrituras.withLock {
            runCatching { directorio.guardarPreferencia(clave, valor) }
        }
        versionPerfil++

        resultado.onSuccess {
            if (clave == "cortos") {
                // Ver o no los cortos cambia dos cosas más que la pantalla:
                // la lista que se pide al servidor y los avisos que llegan a
                // este aparato.
                perfilFlow.update { p -> p.conPreferencia(clave, valor) }
                val p = _estado.value.perfil
                directorio.sincronizarTopics(p.favoritos, p.productoras, p.veCortos)
            }
        }

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
    // Anuncios: quitarlos con una compra o con un folio de regalo
    // -------------------------------------------------------------------------

    /**
     * Pregunta a Google Play cuánto cuesta quitar los anuncios y, de paso, si
     * esta cuenta de Google Play ya lo compró. La llama MainActivity cuando el
     * perfil dice que la persona ve anuncios y que se pueden comprar.
     *
     * Lo segundo es lo que recupera una compra sin que nadie haga nada: tras
     * reinstalar la app, al cambiar de teléfono, o si se pagó y la conexión
     * se cortó antes de avisar al servidor.
     *
     * @param soloSiFalta para cuando la app vuelve a primer plano: no repite
     *                    nada si ya se sabe el precio, solo reintenta si la
     *                    vez anterior no se pudo hablar con Google Play
     */
    fun prepararTienda(contexto: Context, soloSiFalta: Boolean = false) = viewModelScope.launch {
        val app = contexto.applicationContext

        // onResume llega antes de que exista la sesión; esperamos a que esté.
        estado.first { it.listo }

        turnoTienda.withLock {
            val perfil = _estado.value.perfil
            if (!perfil.veAnuncios || !perfil.compraDisponible) return@launch
            if (soloSiFalta && _estado.value.precioSinAnuncios != null) return@launch

            val precio = compras.precio(app)
            _estado.update { it.copy(precioSinAnuncios = precio) }
            if (precio == null) return@launch

            compras.pagadas(app).forEach {
                registrarCompra(it, recuperada = true, avisarSiFalla = false)
            }
        }
    }

    /** El botón "Quitar los anuncios". La pantalla de pago es de Google Play. */
    fun comprarSinAnuncios(actividad: Activity) {
        if (!compras.comprar(actividad)) {
            avisar("Google Play no está disponible ahora mismo. Inténtalo más tarde.")
        }
    }

    private suspend fun alComprar(novedad: ComprasRepo.Novedad) {
        when (novedad) {
            is ComprasRepo.Novedad.Pagada -> registrarCompra(novedad, recuperada = false)

            is ComprasRepo.Novedad.Pendiente -> avisar(
                "Tu pago está en proceso. En cuanto Google Play lo confirme, los anuncios se quitan solos."
            )

            // Google Play dice que ya era suya: se busca y se registra, sin
            // cobrar otra vez.
            is ComprasRepo.Novedad.YaLaTenia -> {
                val suyas = compras.pagadas()
                if (suyas.isEmpty()) {
                    avisar("Google Play dice que ya lo habías comprado. Cierra la app y vuelve a abrirla para recuperarlo.")
                }
                suyas.forEach { registrarCompra(it, recuperada = true) }
            }

            // Cerró la pantalla de pago sin comprar. No hay nada que decir.
            is ComprasRepo.Novedad.Cancelada -> Unit

            is ComprasRepo.Novedad.Fallo -> avisar(novedad.mensaje)
        }
    }

    /**
     * Manda el comprobante al servidor, que es quien decide. Hasta que él no
     * contesta que sí, la persona sigue viendo anuncios.
     */
    private suspend fun registrarCompra(
        compra: ComprasRepo.Novedad.Pagada,
        recuperada: Boolean,
        avisarSiFalla: Boolean = true
    ) {
        runCatching { ApiRelay.registrarCompra(compra.producto, compra.token) }
            .onSuccess {
                yaNoVeAnuncios()
                avisar(
                    if (recuperada) "Recuperamos tu compra. Ya no verás anuncios."
                    else "Gracias por tu compra. Ya no verás anuncios."
                )
            }
            .onFailure { fallo ->
                // Una compra que se recupera sola al abrir la app no avisa si
                // falla: nadie pidió nada, y se reintenta la próxima vez.
                if (!avisarSiFalla) return@onFailure
                avisar(
                    if (fallo is ApiRelay.ErrorHttp) fallo.message.orEmpty()
                    else "Tu compra se hizo, pero no pudimos registrarla por la conexión. " +
                        "No se pierde: se registra sola la próxima vez que abras la app."
                )
            }
    }

    /** Canjea un folio de regalo. El resultado se pinta en la ventanita de Ajustes. */
    fun canjearFolio(codigo: String) = viewModelScope.launch {
        if (_estado.value.folio.enviando) return@launch
        _estado.update { it.copy(folio = EstadoFolio(enviando = true)) }

        runCatching { ApiRelay.canjearFolio(codigo.trim()) }
            .onSuccess { mensaje ->
                yaNoVeAnuncios()
                _estado.update { it.copy(folio = EstadoFolio(), mensaje = mensaje) }
            }
            .onFailure { fallo ->
                val motivo = if (fallo is ApiRelay.ErrorHttp) fallo.message.orEmpty()
                else "No se pudo comprobar el folio. Revisa tu conexión."
                _estado.update { it.copy(folio = EstadoFolio(error = motivo)) }
            }
    }

    /** Se cerró la ventanita del folio: el error que hubiera ya no se enseña. */
    fun folioCerrado() = _estado.update { it.copy(folio = EstadoFolio()) }

    /**
     * El servidor acaba de confirmar que esta cuenta ya no ve anuncios. Se
     * pinta en el momento, sin esperar a la siguiente lectura del perfil, y
     * después se lee para quedarse con lo que diga el servidor.
     */
    private suspend fun yaNoVeAnuncios() {
        versionPerfil++
        _estado.update { it.copy(perfil = it.perfil.copy(sinAnuncios = true)) }
        perfilFlow.update { it.copy(sinAnuncios = true) }
        refrescarPerfil()
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
                // Borrar la cuenta es empezar de cero también en el teléfono.
                Abiertos.olvidar(contexto)
                avisar("Cuenta borrada. Puedes seguir usando la app como invitado.")
            }
            .onFailure { avisar("No se pudo borrar la cuenta: ${it.localizedMessage}") }
        _estado.update { it.copy(ocupado = false, esAnonimo = true, correo = null) }
        recargarPerfil()
    }

    private fun avisar(texto: String) = _estado.update { it.copy(mensaje = texto) }

    fun mensajeVisto() = _estado.update { it.copy(mensaje = null) }

    override fun onCleared() {
        compras.cerrar()
        super.onCleared()
    }

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
    "cortos" -> cortos
    else -> null
}

private fun Perfil.conPreferencia(clave: String, valor: Any?): Perfil = when (clave) {
    "tema" -> copy(tema = valor as? String ?: tema)
    "escalaTexto" -> copy(escalaTexto = valor as? String ?: escalaTexto)
    "avisos" -> copy(avisos = valor as? Boolean ?: avisos)
    "cortos" -> copy(cortos = valor as? Boolean ?: cortos)
    else -> this
}
