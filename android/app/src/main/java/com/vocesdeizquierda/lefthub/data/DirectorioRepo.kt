package com.vocesdeizquierda.lefthub.data

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Acceso al directorio, ahora contra nuestro servidor.
 *
 * Las firmas públicas son las mismas que cuando esto hablaba con Firestore, de
 * modo que AppViewModel no cambia. Lo que sí cambió es el mecanismo: antes
 * Firestore empujaba los cambios en vivo; ahora preguntamos.
 *
 * En la práctica se nota poco. La novedad importante llega por notificación
 * push, y el feed no es una pantalla que la gente mire fijamente esperando que
 * cambie. El sondeo es de cortesía, con intervalos amplios.
 */
class DirectorioRepo(
    private val mensajeria: FirebaseMessaging = FirebaseMessaging.getInstance()
) {

    /**
     * El directorio completo.
     *
     * Cambia muy poco —lo edita una persona a mano—, así que refrescar cada
     * cinco minutos sobra. El Flow emite de inmediato al suscribirse, que es
     * lo que importa para que la pantalla pinte rápido.
     */
    fun creadores(): Flow<List<Creador>> = sondear(intervaloMs = 5 * 60_000L) {
        ApiRelay.creadores()
    }

    /** Las productoras. Mismo ritmo que el directorio: también se editan a mano. */
    fun productoras(): Flow<List<Productora>> = sondear(intervaloMs = 5 * 60_000L) {
        ApiRelay.productoras()
    }

    /** Las etiquetas encendidas. Mismo ritmo: también las maneja una persona a mano. */
    fun etiquetas(): Flow<List<Etiqueta>> = sondear(intervaloMs = 5 * 60_000L) {
        ApiRelay.etiquetas()
    }

    /**
     * El perfil tal como está en el servidor, una sola lectura.
     *
     * No es un Flow como los otros: el perfil es lo único que el usuario
     * cambia desde la app, y AppViewModel necesita decidir cuándo una lectura
     * llegó tarde y pisaría un cambio que acaba de hacerse en pantalla.
     */
    suspend fun perfil(): Perfil = ApiRelay.perfil()

    /**
     * ¿El mapa guardado en el teléfono sigue siendo el de la cuenta?
     *
     * Si sí, o si no se pudo saber, null: lo guardado se queda como está. Si
     * no, deja guardado el nuevo y lo devuelve. Un servidor anterior a esta
     * ruta contesta 404: no se insiste, y la app funciona con el perfil.
     */
    suspend fun mapaNuevo(): MapaDeSeleccion? {
        if (servidorSinMapa) return null
        val nuevo = try {
            ApiRelay.mapa(AlmacenDeMapa.leer()?.huella)
        } catch (e: ApiRelay.ErrorHttp) {
            if (e.codigo == 404) servidorSinMapa = true
            null
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo comprobar el mapa: ${e.message}")
            null
        }
        return nuevo?.also { AlmacenDeMapa.guardar(it) }
    }

    /**
     * Los parámetros ya no se usan para filtrar: el servidor sabe a quién
     * sigue el usuario por el token. Se mantienen en la firma porque
     * AppViewModel los usa como disparador para reemitir cuando cambian.
     */
    fun publicaciones(
        favoritos: List<String>,
        productoras: List<String> = emptyList(),
        canales: List<String> = emptyList()
    ): Flow<List<Publicacion>> =
        if (favoritos.isEmpty() && productoras.isEmpty() && canales.isEmpty()) flow { emit(emptyList()) }
        else sondear(intervaloMs = 2 * 60_000L) { ApiRelay.publicaciones() }

    /**
     * Los videos cortos de quienes sigue, que van en su propio apartado.
     * Si la persona no los ve (el equipo los tiene apagados, o los apagó
     * ella), ni se preguntan.
     */
    fun cortos(
        favoritos: List<String>,
        productoras: List<String>,
        losVe: Boolean,
        canales: List<String> = emptyList()
    ): Flow<List<Publicacion>> =
        if (!losVe || (favoritos.isEmpty() && productoras.isEmpty() && canales.isEmpty())) flow { emit(emptyList()) }
        else sondear(intervaloMs = 2 * 60_000L) { ApiRelay.publicaciones(cortos = true) }

    /** Un video, para su ficha: la que abre tocar una notificación. */
    suspend fun video(videoId: String): Publicacion? = ApiRelay.video(videoId)

    /** Los últimos videos de un canal, una sola lectura al abrir su ficha. */
    suspend fun videosDeCanal(canalId: String): List<Publicacion> =
        ApiRelay.publicacionesDeCanal(canalId)

    /** Los últimos videos de un creador, una sola lectura al abrir su ficha. */
    suspend fun videosDeCreador(creadorId: String): List<Publicacion> =
        ApiRelay.publicacionesDeCreador(creadorId, parte = "propios")

    /** Lo más visto de todo el directorio: "hoy" o "semana". Se pide al entrar al apartado. */
    suspend fun masVistas(periodo: String): List<Publicacion> =
        ApiRelay.masVistas(periodo)

    /** Quién está al aire ahora. Se pide cada minuto mientras se ve su pestaña. */
    suspend fun enVivo(): List<DirectoEnVivo> =
        ApiRelay.enVivo()

    /** Los videos de canales de otros en los que aparece el creador. */
    suspend fun videosDondeAparece(creadorId: String): List<Publicacion> =
        ApiRelay.publicacionesDeCreador(creadorId, parte = "compartidos")

    /** Cuándo publicó por última vez cada canal; se refresca con el directorio. */
    fun ultimosVideos(): Flow<Map<String, java.time.Instant>> = sondear(intervaloMs = 5 * 60_000L) {
        ApiRelay.ultimosVideos()
    }

    /** Los últimos videos de un medio, una sola lectura al abrir su ficha. */
    suspend fun videosDeProductora(productoraId: String): List<Publicacion> =
        ApiRelay.publicacionesDeProductora(productoraId)

    /**
     * Seguir o dejar de seguir.
     *
     * Dos cosas a la vez: el favorito en el servidor, que sobrevive al cambio
     * de teléfono, y el topic de FCM, que es lo que hace llegar el aviso a
     * ESTE aparato. Con solo lo primero, el usuario vería el creador marcado
     * y no recibiría nada.
     */
    suspend fun alternarFavorito(
        creatorId: String,
        siguiendoAhora: Boolean,
        conCortos: Boolean = false
    ) {
        val topic = topicDe(creatorId)
        if (siguiendoAhora) {
            ApiRelay.dejarDeSeguir(creatorId)
            darDeBaja(topic)
        } else {
            ApiRelay.seguir(creatorId)
            darDeAlta(topic, conCortos)
        }
    }

    /**
     * Seguir o dejar de seguir a una productora. Igual que con un creador:
     * el servidor guarda a quién se sigue y el topic hace llegar los avisos
     * de sus canales a este aparato.
     */
    suspend fun alternarProductora(
        productoraId: String,
        siguiendoAhora: Boolean,
        conCortos: Boolean = false
    ) {
        val topic = topicDeProductora(productoraId)
        if (siguiendoAhora) {
            ApiRelay.dejarDeSeguirProductora(productoraId)
            darDeBaja(topic)
        } else {
            ApiRelay.seguirProductora(productoraId)
            darDeAlta(topic, conCortos)
        }
    }

    /**
     * Seguir o dejar de seguir un canal de YouTube por sí solo. Igual que con
     * un creador o un medio: el servidor guarda que se sigue y el topic
     * (canal_<id>) hace llegar sus avisos a este aparato.
     */
    suspend fun alternarCanal(
        canalId: String,
        siguiendoAhora: Boolean,
        conCortos: Boolean = false
    ) {
        val topic = topicDeCanal(canalId)
        if (siguiendoAhora) {
            ApiRelay.dejarDeSeguirCanal(canalId)
            darDeBaja(topic)
        } else {
            ApiRelay.seguirCanal(canalId)
            darDeAlta(topic, conCortos)
        }
    }

    /**
     * Deja el teléfono suscrito a lo que le toca, y solo toca FCM si algo
     * cambió: se puede llamar en cada lectura del perfil. Los favoritos viven
     * en la cuenta y los topics son por dispositivo, así que esto es lo que
     * alinea un teléfono nuevo, lo que se dejó de seguir en otro y un cambio
     * de cuenta.
     *
     * Las listas vacías dan de baja de todo: llamar solo con lo que dijo el
     * servidor, nunca con un perfil que todavía no ha llegado.
     */
    suspend fun sincronizarTopics(
        favoritos: List<String>,
        productoras: List<String> = emptyList(),
        conCortos: Boolean = false,
        canales: List<String> = emptyList()
    ) = TopicosDelTelefono.turno.withLock {
        val seguidos = favoritos.map { topicDe(it) } + productoras.map { topicDeProductora(it) } +
            canales.map { topicDeCanal(it) }
        val cortos = seguidos.map { it + SUFIJO_CORTOS }

        // El de toda la app, siga a quien siga: por ahí llegan los avisos del
        // equipo para todos (un creador, medio o canal nuevo, un mensaje).
        // Va aunque no siga a nadie todavía: justo a esa persona le sirve
        // enterarse de quién llega.
        val queridos = (listOf(TOPIC_TODOS) + seguidos + if (conCortos) cortos else emptyList()).toSet()

        // Hasta la 1.0.6 los avisos de cortos iban por topics que terminaban
        // en "_cortos", y la app se suscribía a ellos con solo ver los cortos.
        // El servidor ya no manda nada ahí; darse de baja es solo para no
        // dejar suscripciones muertas en el aparato.
        val dudosos = (cortos + seguidos.map { it + SUFIJO_CORTOS_VIEJO }).toSet()

        val plan = TopicosDelTelefono.planPara(queridos, dudosos, System.currentTimeMillis())
        plan.bajas.forEach { dejar(it) }
        plan.altas.forEach { recibir(it) }
    }

    private suspend fun darDeAlta(topic: String, conCortos: Boolean) =
        TopicosDelTelefono.turno.withLock {
            recibir(topic)
            if (conCortos) recibir(topic + SUFIJO_CORTOS)
        }

    private suspend fun darDeBaja(topic: String) = TopicosDelTelefono.turno.withLock {
        dejar(topic)
        dejar(topic + SUFIJO_CORTOS)
    }

    // Sin estos registros, un teléfono que no logra suscribirse (sin Play
    // Services, google-services.json de otro proyecto) es indistinguible de
    // uno que sí.
    private suspend fun recibir(topic: String) {
        runCatching { mensajeria.subscribeToTopic(topic).await() }
            .onSuccess {
                TopicosDelTelefono.anotar(topic)
                Log.d(TAG, "Suscrito a $topic")
            }
            .onFailure { Log.w(TAG, "No se pudo suscribir a $topic", it) }
    }

    private suspend fun dejar(topic: String) {
        runCatching { mensajeria.unsubscribeFromTopic(topic).await() }
            .onSuccess { TopicosDelTelefono.quitar(topic) }
            .onFailure { Log.w(TAG, "No se pudo dar de baja de $topic", it) }
    }

    suspend fun guardarPreferencia(clave: String, valor: Any) {
        ApiRelay.guardarPreferencia(clave, valor)
    }

    /** Reporta un enlace roto. Alimenta la redirección de emergencia. */
    suspend fun reportarEnlaceRoto(
        videoId: String?,
        creatorId: String?,
        motivo: String = "enlace_roto"
    ) {
        ApiRelay.reportarEnlace(videoId, creatorId, motivo)
    }

    /**
     * Emite una vez al suscribirse y después cada `intervaloMs`.
     *
     * Un fallo de red no corta el Flow: se registra y se reintenta en el
     * siguiente ciclo. Si cortáramos, la pantalla se quedaría congelada hasta
     * que el usuario saliera y volviera a entrar.
     */
    private fun <T> sondear(intervaloMs: Long, traer: suspend () -> T): Flow<T> = flow {
        while (true) {
            runCatching { traer() }
                .onSuccess { emit(it) }
                .onFailure { Log.w(TAG, "Fallo al consultar el servidor: ${it.message}") }
            delay(intervaloMs)
        }
    }

    companion object {
        private const val TAG = "DirectorioRepo"
        fun topicDe(creatorId: String) = "creator_$creatorId"

        /** El mismo nombre que arma el servidor en PushService. */
        fun topicDeProductora(productoraId: String) = "productora_$productoraId"

        /** El de quien sigue un canal por sí solo. Lo mismo que PushService.topicDeCanal. */
        fun topicDeCanal(canalId: String) = "canal_$canalId"

        /** El de toda la app. Lo mismo que PushService.TOPIC_TODOS en el servidor. */
        const val TOPIC_TODOS = "todos"

        /**
         * Lo mismo que PushService.SUFIJO_CORTOS en el servidor. Cambió para
         * que los avisos de cortos empiecen apagados para todos.
         */
        const val SUFIJO_CORTOS = "_avisos_cortos"
        private const val SUFIJO_CORTOS_VIEJO = "_cortos"

        @Volatile
        private var servidorSinMapa = false
    }
}
