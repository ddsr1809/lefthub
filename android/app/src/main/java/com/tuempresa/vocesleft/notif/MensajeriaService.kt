package com.tuempresa.vocesleft.notif

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tuempresa.vocesleft.MainActivity
import com.tuempresa.vocesleft.R
import com.tuempresa.vocesleft.RelayApp
import com.tuempresa.vocesleft.data.ApiRelay
import com.tuempresa.vocesleft.data.DirectorioRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Servicio encargado de recibir mensajes de Firebase Cloud Messaging.
 *
 * La autenticación, el perfil y los favoritos ahora se consultan
 * directamente en el servidor mediante ApiRelay.
 */
class MensajeriaService : FirebaseMessagingService() {

    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Solo se ejecuta con la app ABIERTA. En segundo plano, los mensajes con
     * bloque `notification` los pinta el sistema sin pasar por aquí.
     *
     * Con la app en primer plano FCM no muestra nada por su cuenta: si este
     * método se limita a registrar, el aviso llega y se pierde en silencio.
     * Lo pintamos a mano con los mismos datos, para que tocarlo abra el video
     * igual que cuando la app estaba cerrada.
     */
    override fun onMessageReceived(mensaje: RemoteMessage) {
        Log.d(TAG, "Aviso en primer plano: ${mensaje.data["videoId"]}")

        val aviso = mensaje.notification ?: return
        val gestor = getSystemService(NotificationManager::class.java) ?: return
        if (!gestor.areNotificationsEnabled()) return

        // MainActivity.procesarIntent lee estos extras, los mismos que mete
        // FCM cuando la notificación la pinta el sistema.
        val abrir = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            mensaje.data.forEach { (clave, valor) -> putExtra(clave, valor) }
        }
        val alTocar = PendingIntent.getActivity(
            this,
            (mensaje.data["videoId"] ?: mensaje.messageId).hashCode(),
            abrir,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificacion = NotificationCompat.Builder(
            this, aviso.channelId ?: RelayApp.CANAL_PUBLICACIONES
        )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(aviso.title)
            .setContentText(aviso.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(aviso.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(alTocar)
            .build()

        // Mismo tag que usa el servidor: un aviso por creador, el último
        // reemplaza al anterior.
        gestor.notify(aviso.tag ?: mensaje.data["creatorId"], 0, notificacion)
    }

    /**
     * Cuando FCM renueva el token, se reconstruyen las suscripciones
     * a los creadores favoritos de la cuenta actual.
     */
    override fun onNewToken(token: String) {
        Log.d(TAG, "Token de FCM renovado")

        alcance.launch {
            runCatching {
                if (!ApiRelay.haySesion) {
                    Log.d(
                        TAG,
                        "Sin sesión todavía; las suscripciones se rehacen al abrir la app"
                    )
                    return@runCatching
                }

                val favoritos = ApiRelay.perfil().favoritos

                favoritos.forEach { creatorId ->
                    FirebaseMessaging.getInstance()
                        .subscribeToTopic(
                            DirectorioRepo.topicDe(creatorId)
                        )
                        .await()
                }

                Log.d(TAG, "Suscripciones rehechas: ${favoritos.size}")
            }.onFailure { error ->
                Log.w(
                    TAG,
                    "No se pudieron rehacer las suscripciones",
                    error
                )
            }
        }
    }

    companion object {
        private const val TAG = "MensajeriaService"
    }
}