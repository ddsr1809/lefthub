package com.vocesdeizquierda.lefthub.notif

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.vocesdeizquierda.lefthub.LeftVocesApp
import com.vocesdeizquierda.lefthub.MainActivity
import com.vocesdeizquierda.lefthub.R

/**
 * El aviso de un mensaje del chat, pintado por la app y no por el sistema,
 * para que lleve el botón «Responder». Ese botón sale también en el reloj
 * (Wear OS, Galaxy Watch...), que contesta con la voz, el teclado o una
 * respuesta rápida, sin sacar el teléfono.
 *
 * El aviso sigue sin decir nada de la conversación: ni el texto ni quién lo
 * escribió (ver PushService.avisarChat en el servidor).
 */
object AvisoDelChat {

    /** La clave de lo que se escribió en «Responder». */
    const val CLAVE_RESPUESTA = "respuesta"

    /** null para la conversación propia; el id de la cuenta para un administrador. */
    const val EXTRA_CUENTA = "cuenta"
    const val EXTRA_ETIQUETA = "etiqueta"

    /**
     * Siempre el mismo número, el que usa FCM cuando el aviso lo pinta el
     * sistema: con la misma etiqueta, uno reemplaza al otro. La etiqueta es la
     * que distingue un aviso de otro.
     */
    private const val ID = 0

    /**
     * Pinta el aviso con los datos que manda el servidor: "tipo" ("chat" o
     * "chat_equipo"), "usuarioId" (solo en el del equipo), "titulo", "cuerpo"
     * y "etiqueta". Para un aviso que trae bloque de notificación (el de una
     * app anterior que siga suscrita mientras cambia de topic), [titulo] y
     * [cuerpo] vienen de ahí.
     */
    fun mostrar(
        contexto: Context,
        datos: Map<String, String>,
        titulo: String? = null,
        cuerpo: String? = null
    ) {
        val gestor = contexto.getSystemService(NotificationManager::class.java) ?: return
        if (!gestor.areNotificationsEnabled()) return

        val tipo = datos["tipo"] ?: return
        // A un administrador se le dice qué cuenta escribió; a la persona, nada.
        val cuenta: String? = when (tipo) {
            "chat" -> null
            "chat_equipo" -> datos["usuarioId"]?.takeIf { it.isNotBlank() } ?: return
            else -> return
        }
        val etiqueta = datos["etiqueta"] ?: if (cuenta != null) "chat_$cuenta" else "chat"
        val elTitulo = titulo ?: datos["titulo"] ?: "Tienes un mensaje del equipo de Voces"
        val elCuerpo = cuerpo ?: datos["cuerpo"] ?: "Toca para leerlo."

        // Tocarlo abre la conversación: los mismos extras que pone FCM
        // cuando el aviso lo pinta el sistema (ver MainActivity.procesarIntent).
        val abrir = Intent(contexto, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("tipo", tipo)
            if (cuenta != null) putExtra("usuarioId", cuenta)
        }
        val alTocar = PendingIntent.getActivity(
            contexto, etiqueta.hashCode(), abrir,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificacion = base(contexto, elTitulo, elCuerpo)
            .setContentIntent(alTocar)
            .addAction(accionResponder(contexto, cuenta, etiqueta))
            .extend(
                // En el reloj: el botón «Responder» a la vista, y las
                // respuestas rápidas que propone el propio reloj.
                NotificationCompat.WearableExtender()
                    .setHintContentIntentLaunchesActivity(true)
            )
            .build()

        gestor.notify(etiqueta, ID, notificacion)
    }

    /**
     * Ya se mandó (o no) lo que se escribió en «Responder». El aviso se
     * cambia por otro que lo dice, sin botón; si no, Android deja el botón
     * dando vueltas como si siguiera enviando.
     */
    fun respondido(contexto: Context, etiqueta: String, cuenta: String?, salio: Boolean, motivo: String?) {
        val gestor = contexto.getSystemService(NotificationManager::class.java) ?: return
        val abrir = Intent(contexto, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("tipo", if (cuenta != null) "chat_equipo" else "chat")
            if (cuenta != null) putExtra("usuarioId", cuenta)
        }
        val alTocar = PendingIntent.getActivity(
            contexto, etiqueta.hashCode(), abrir,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val constructor = if (salio) {
            base(contexto, "Respuesta enviada", "Toca para ver la conversación.")
                // Que no vuelva a sonar ni a vibrar: ya se sabe.
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setTimeoutAfter(4_000)
        } else {
            base(contexto, "No se pudo enviar tu respuesta",
                motivo ?: "Revisa tu conexión y vuelve a intentarlo.")
                .setOnlyAlertOnce(true)
                .addAction(accionResponder(contexto, cuenta, etiqueta))
        }
        gestor.notify(etiqueta, ID, constructor.setContentIntent(alTocar).build())
    }

    private fun base(contexto: Context, titulo: String, cuerpo: String) =
        NotificationCompat.Builder(contexto, LeftVocesApp.CANAL_AVISOS)
            .setSmallIcon(R.drawable.ic_notificacion)
            .setContentTitle(titulo)
            .setContentText(cuerpo)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cuerpo))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

    private fun accionResponder(contexto: Context, cuenta: String?, etiqueta: String): NotificationCompat.Action {
        val entrada = RemoteInput.Builder(CLAVE_RESPUESTA)
            .setLabel("Escribe tu respuesta")
            .build()

        val responder = Intent(contexto, RespuestaDelChat::class.java).apply {
            putExtra(EXTRA_ETIQUETA, etiqueta)
            if (cuenta != null) putExtra(EXTRA_CUENTA, cuenta)
        }
        // Mutable a la fuerza: el sistema le agrega lo que se escribió.
        val bandera = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE else 0
        val alResponder = PendingIntent.getBroadcast(
            contexto, etiqueta.hashCode(), responder,
            PendingIntent.FLAG_UPDATE_CURRENT or bandera
        )

        return NotificationCompat.Action.Builder(R.drawable.ic_atajo_chat, "Responder", alResponder)
            .addRemoteInput(entrada)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            // El reloj y el teléfono pueden proponer respuestas cortas.
            .setAllowGeneratedReplies(true)
            .setShowsUserInterface(false)
            .build()
    }
}
