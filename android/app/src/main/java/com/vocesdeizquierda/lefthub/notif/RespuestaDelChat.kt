package com.vocesdeizquierda.lefthub.notif

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.vocesdeizquierda.lefthub.chat.ChatViewModel
import com.vocesdeizquierda.lefthub.data.ApiRelay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lo que se escribió en «Responder» de un aviso del chat, desde el teléfono
 * o desde el reloj. Se manda al servidor como cualquier mensaje del chat y
 * el aviso se cambia por uno que dice si salió.
 *
 * Solo lo puede llamar la propia app: no está abierto a otras (ver el
 * manifiesto).
 */
class RespuestaDelChat : BroadcastReceiver() {

    override fun onReceive(contexto: Context, intent: Intent) {
        val texto = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(AvisoDelChat.CLAVE_RESPUESTA)
            ?.toString()
            ?.trim()
        val etiqueta = intent.getStringExtra(AvisoDelChat.EXTRA_ETIQUETA) ?: "chat"
        val cuenta = intent.getStringExtra(AvisoDelChat.EXTRA_CUENTA)

        if (texto.isNullOrEmpty()) {
            AvisoDelChat.respondido(contexto, etiqueta, cuenta, salio = false,
                motivo = "La respuesta estaba vacía. Escribe algo antes de enviar.")
            return
        }
        if (texto.length > ChatViewModel.MAX_TEXTO) {
            AvisoDelChat.respondido(contexto, etiqueta, cuenta, salio = false,
                motivo = "El mensaje es muy largo. Ábrelo en la app y pártelo en dos.")
            return
        }

        // Android da unos segundos para terminar; el envío va aparte y avisa
        // al acabar. Si tarda demasiado (sin conexión), se da por fallido.
        val pendiente = goAsync()
        alcance.launch {
            val resultado = withTimeoutOrNull(LIMITE_MS) {
                runCatching { ApiRelay.escribirAlChat(cuenta, texto) }
            }
            // null si se pasó del tiempo.
            val error = resultado?.exceptionOrNull()
            val salio = resultado?.isSuccess == true
            if (!salio) Log.w(TAG, "No se pudo mandar la respuesta del aviso", error)
            AvisoDelChat.respondido(
                contexto, etiqueta, cuenta, salio,
                motivo = when {
                    salio -> null
                    !ApiRelay.haySesion -> "Abre la app para volver a entrar a tu cuenta."
                    error is ApiRelay.ErrorHttp -> error.message
                    else -> null
                }
            )
            pendiente.finish()
        }
    }

    private companion object {
        const val TAG = "RespuestaDelChat"
        // goAsync da unos 10 segundos antes de que Android lo dé por colgado.
        const val LIMITE_MS = 8_000L
        val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
