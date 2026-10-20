package com.vocesdeizquierda.lefthub.chat

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * El aviso de "el equipo quiere hablar contigo".
 *
 * Llega por un topic de FCM propio de la cuenta, que el servidor saca de un
 * HMAC y manda en el perfil solo si el chat está activado. Aquí se anota a
 * cuál está suscrito este teléfono, para darse de baja cuando el equipo quita
 * el chat o cuando entra otra cuenta: si no, el teléfono seguiría recibiendo
 * los avisos de la cuenta anterior.
 *
 * El aviso no lleva nada de la conversación. Los mensajes no pasan por aquí.
 */
object ChatAvisos {

    private const val TAG = "ChatAvisos"
    private const val PREFS = "chat_avisos"
    private const val CLAVE_TOPIC = "topic"

    private var prefs: SharedPreferences? = null
    private val turno = Mutex()

    /** Se llama una vez desde LeftVocesApp.onCreate(). */
    fun inicializar(contexto: Context) {
        prefs = contexto.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /**
     * Deja el teléfono suscrito a [topic], o a ninguno si es null. Solo toca
     * FCM cuando cambia, así que se puede llamar en cada lectura del perfil.
     */
    suspend fun sincronizar(topic: String?) = turno.withLock {
        val p = prefs ?: return@withLock
        val actual = p.getString(CLAVE_TOPIC, null)
        if (actual == topic) return@withLock

        val mensajeria = FirebaseMessaging.getInstance()
        if (actual != null) {
            val baja = runCatching { mensajeria.unsubscribeFromTopic(actual).await() }
            // Si no se pudo dar de baja, se queda anotado y se reintenta en
            // la siguiente lectura del perfil.
            if (baja.isFailure) {
                Log.w(TAG, "No se pudo dar de baja del aviso del chat", baja.exceptionOrNull())
                return@withLock
            }
            p.edit().remove(CLAVE_TOPIC).apply()
        }
        if (topic != null) {
            runCatching { mensajeria.subscribeToTopic(topic).await() }
                .onSuccess { p.edit().putString(CLAVE_TOPIC, topic).apply() }
                .onFailure { Log.w(TAG, "No se pudo suscribir al aviso del chat", it) }
        }
    }
}
