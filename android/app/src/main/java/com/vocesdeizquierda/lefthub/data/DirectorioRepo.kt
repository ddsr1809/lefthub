package com.vocesdeizquierda.lefthub.data

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

    /** Los últimos videos de un canal, una sola lectura al abrir su ficha. */
    suspend fun videosDeCanal(canalId: String): List<Publicacion> =
        ApiRelay.publicacionesDeCanal(canalId)

    /** Los últimos videos de un creador, una sola lectura al abrir su ficha. */
    suspend fun videosDeCreador(creadorId: String): List<Publicacion> =
        ApiRelay.publicacionesDeCreador(creadorId)

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
        if (siguiendoAhora) {
            ApiRelay.dejarDeSeguir(creatorId)
            runCatching { mensajeria.unsubscribeFromTopic(topicDe(creatorId)).await() }
            dejarCortos(topicDe(creatorId) + SUFIJO_CORTOS)
        } else {
            ApiRelay.seguir(creatorId)
            runCatching { mensajeria.subscribeToTopic(topicDe(creatorId)).await() }
            if (conCortos) recibirCortos(topicDe(creatorId) + SUFIJO_CORTOS)
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
            runCatching { mensajeria.unsubscribeFromTopic(topic).await() }
            dejarCortos(topic + SUFIJO_CORTOS)
        } else {
            ApiRelay.seguirProductora(productoraId)
            runCatching { mensajeria.subscribeToTopic(topic).await() }
            if (conCortos) recibirCortos(topic + SUFIJO_CORTOS)
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
            runCatching { mensajeria.unsubscribeFromTopic(topic).await() }
            dejarCortos(topic + SUFIJO_CORTOS)
        } else {
            ApiRelay.seguirCanal(canalId)
            runCatching { mensajeria.subscribeToTopic(topic).await() }
            if (conCortos) recibirCortos(topic + SUFIJO_CORTOS)
        }
    }

    /**
     * Vuelve a alinear los topics tras iniciar sesión en otro teléfono.
     * Los favoritos viven en la cuenta, pero los topics son por dispositivo:
     * un teléfono nuevo no está suscrito a nada aunque la cuenta sí lo esté.
     */
    suspend fun sincronizarTopics(
        favoritos: List<String>,
        productoras: List<String> = emptyList(),
        conCortos: Boolean = false,
        canales: List<String> = emptyList()
    ) {
        val topics = favoritos.map { topicDe(it) } + productoras.map { topicDeProductora(it) } +
            canales.map { topicDeCanal(it) }

        topics.forEach { topic ->
            runCatching { mensajeria.subscribeToTopic(topic).await() }
                // Sin este registro, un teléfono que no logra suscribirse
                // (sin Play Services, google-services.json de otro proyecto)
                // es indistinguible de uno que sí.
                .onSuccess { Log.d(TAG, "Suscrito a $topic") }
                .onFailure { Log.w(TAG, "No se pudo suscribir a $topic", it) }
        }

        sincronizarCortos(topics.map { it + SUFIJO_CORTOS }, conCortos)
    }

    /**
     * Los avisos de videos cortos llegan por topics aparte: los mismos de
     * siempre con otro final. Quien los ve está suscrito a ellos; quien no,
     * no, y así no le llega ninguno.
     *
     * A diferencia de los de arriba, aquí también hay que darse de baja (la
     * persona los apagó, o el equipo). Para no repetirlo en cada lectura del
     * perfil, se recuerda a cuáles está suscrito este aparato y solo se toca
     * lo que cambió.
     */
    private suspend fun sincronizarCortos(deQuienesSigue: List<String>, conCortos: Boolean) {
        val queridos = if (conCortos) deQuienesSigue.toSet() else emptySet()
        // La primera vez no se sabe a qué quedó suscrito el aparato en un uso
        // anterior: se da de baja de todo lo que no toque.
        val sobran = (cortosSuscritos ?: deQuienesSigue.toSet()) - queridos
        val faltan = queridos - (cortosSuscritos ?: emptySet())

        if (cortosSuscritos == null) cortosSuscritos = emptySet()
        sobran.forEach { dejarCortos(it) }
        faltan.forEach { recibirCortos(it) }
    }

    private suspend fun recibirCortos(topic: String) {
        runCatching { mensajeria.subscribeToTopic(topic).await() }
            .onSuccess { cortosSuscritos = (cortosSuscritos ?: emptySet()) + topic }
            .onFailure { Log.w(TAG, "No se pudo suscribir a $topic", it) }
    }

    private suspend fun dejarCortos(topic: String) {
        runCatching { mensajeria.unsubscribeFromTopic(topic).await() }
            .onSuccess { cortosSuscritos = cortosSuscritos?.minus(topic) }
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

        /** Lo mismo que PushService.SUFIJO_CORTOS en el servidor. */
        const val SUFIJO_CORTOS = "_cortos"

        // A qué topics de cortos está suscrito este aparato, hasta donde se
        // sabe. Es del proceso y no de cada DirectorioRepo, porque los topics
        // son del aparato. null: todavía no se ha comprobado.
        @Volatile
        private var cortosSuscritos: Set<String>? = null

        /**
         * FCM cambió el token: las suscripciones se perdieron todas y lo que
         * se recordaba ya no vale.
         */
        fun olvidarCortos() {
            cortosSuscritos = null
        }
    }
}
