package com.vocesdeizquierda.lefthub.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Los videos que la persona ya abrió desde la app.
 *
 * YouTube no le dice a ninguna app qué vio alguien ni en qué minuto se quedó,
 * así que lo único que se puede saber es esto: que tocó el video aquí y la
 * app la mandó a verlo. Por eso las tarjetas dicen "Ya lo abriste" y no "Ya
 * lo viste".
 *
 * Se guarda en el teléfono y no en el servidor a propósito: qué videos abre
 * cada quien es justo el historial que no queremos tener. La contra es que
 * las marcas no viajan con la cuenta: en otro teléfono solo reaparecen si la
 * persona restaura su copia de seguridad de Android.
 */
object Abiertos {

    private const val PREFS = "relay_abiertos"
    private const val CLAVE = "videos"

    // El feed solo enseña lo reciente; pasado este número se olvidan los más
    // viejos para que la lista no crezca sin fin.
    private const val TOPE = 500

    /** Los IDs de los videos abiertos, del más viejo al más nuevo. */
    var videos: Set<String> by mutableStateOf(emptySet())
        private set

    private var cargado = false

    /** Lee lo guardado. Se llama al arrancar; repetirlo no hace nada. */
    fun cargar(contexto: Context) {
        if (cargado) return
        videos = prefs(contexto).getString(CLAVE, null).orEmpty()
            .split('\n')
            .filter { it.isNotBlank() }
            .toSet()
        cargado = true
    }

    /** Apunta que este video ya se abrió. Sin ID no hay nada que apuntar. */
    fun marcar(contexto: Context, videoId: String?) {
        if (videoId.isNullOrBlank()) return
        cargar(contexto)
        if (videoId in videos) return

        videos = (videos + videoId).toList().takeLast(TOPE).toSet()
        guardar(contexto)
    }

    /** Borra todas las marcas. Va con el borrado de la cuenta. */
    fun olvidar(contexto: Context) {
        videos = emptySet()
        cargado = true
        prefs(contexto).edit().remove(CLAVE).apply()
    }

    private fun guardar(contexto: Context) {
        prefs(contexto).edit().putString(CLAVE, videos.joinToString("\n")).apply()
    }

    private fun prefs(contexto: Context) =
        contexto.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
