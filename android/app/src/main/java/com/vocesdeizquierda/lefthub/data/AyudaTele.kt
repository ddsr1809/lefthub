package com.vocesdeizquierda.lefthub.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Si todavía hay que enseñar en Novedades la explicación de cómo ver un video
 * en la tele.
 *
 * Esta app no transmite nada a la tele: quien lo hace es YouTube, con su
 * propio botón. Lo único que hacemos es contarlo una vez, arriba de la lista,
 * hasta que la persona toca "Entendido". Después la explicación se queda en
 * Ajustes, donde no estorba.
 *
 * Se guarda en el teléfono, igual que Abiertos: no es un dato de la cuenta.
 */
object AyudaTele {

    private const val PREFS = "relay_ayuda_tele"
    private const val CLAVE = "entendida"

    /** True mientras la persona no haya tocado "Entendido". */
    var pendiente: Boolean by mutableStateOf(false)
        private set

    private var cargado = false

    /** Lee lo guardado. Se llama al arrancar; repetirlo no hace nada. */
    fun cargar(contexto: Context) {
        if (cargado) return
        pendiente = !prefs(contexto).getBoolean(CLAVE, false)
        cargado = true
    }

    /** La persona ya la leyó: no vuelve a salir en Novedades. */
    fun entendida(contexto: Context) {
        pendiente = false
        cargado = true
        prefs(contexto).edit().putBoolean(CLAVE, true).apply()
    }

    private fun prefs(contexto: Context) =
        contexto.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
