package com.vocesdeizquierda.lefthub.chat

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Los avisos de "tienes un mensaje" del chat.
 *
 * Llegan por topics de FCM que el servidor saca de un HMAC y manda en el
 * perfil: el de la propia conversación (si el equipo le activó el chat) y, a
 * un administrador, el del equipo (cuando una cuenta le escribe). Aquí se
 * anota a cuáles está suscrito este teléfono, para darse de baja cuando el
 * equipo quita el chat o cuando entra otra cuenta: si no, el teléfono seguiría
 * recibiendo los avisos de la anterior.
 *
 * Los avisos no llevan el texto. Los mensajes no pasan por aquí.
 */
object ChatAvisos {

    private const val TAG = "ChatAvisos"
    private const val PREFS = "chat_avisos"
    /** Hasta la 1.0.13 había uno solo, con esta clave. */
    private const val CLAVE_ANTERIOR = "topic"
    private const val CLAVE_TOPICS = "topics"

    private var prefs: SharedPreferences? = null
    private val turno = Mutex()

    /** Se llama una vez desde LeftVocesApp.onCreate(). */
    fun inicializar(contexto: Context) {
        prefs = contexto.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /**
     * Deja el teléfono suscrito exactamente a [queridos]. Solo toca FCM en lo
     * que cambió, así que se puede llamar en cada lectura del perfil.
     */
    suspend fun sincronizar(queridos: Set<String>) = turno.withLock {
        val p = prefs ?: return@withLock
        val actuales = (p.getStringSet(CLAVE_TOPICS, null)?.toSet() ?: emptySet()) +
            listOfNotNull(p.getString(CLAVE_ANTERIOR, null))
        if (actuales == queridos) return@withLock

        val mensajeria = FirebaseMessaging.getInstance()
        val quedan = actuales.toMutableSet()
        for (topic in actuales - queridos) {
            // Si no se pudo dar de baja, se queda anotado y se reintenta en la
            // siguiente lectura del perfil.
            runCatching { mensajeria.unsubscribeFromTopic(topic).await() }
                .onSuccess { quedan -= topic }
                .onFailure { Log.w(TAG, "No se pudo dar de baja de un aviso del chat", it) }
        }
        for (topic in queridos - actuales) {
            runCatching { mensajeria.subscribeToTopic(topic).await() }
                .onSuccess { quedan += topic }
                .onFailure { Log.w(TAG, "No se pudo suscribir a un aviso del chat", it) }
        }
        p.edit().remove(CLAVE_ANTERIOR).putStringSet(CLAVE_TOPICS, quedan).apply()
    }

    /**
     * FCM renovó el token del teléfono: las suscripciones se perdieron con el
     * anterior. Se olvida lo anotado y se suscribe otra vez a [queridos].
     */
    suspend fun rehacer(queridos: Set<String>) {
        turno.withLock { prefs?.edit()?.remove(CLAVE_ANTERIOR)?.remove(CLAVE_TOPICS)?.apply() }
        sincronizar(queridos)
    }
}
