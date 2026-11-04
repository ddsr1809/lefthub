package com.vocesdeizquierda.lefthub.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.time.Instant

/**
 * La copia del mapa de selección que se queda en el teléfono.
 *
 * Sirve para dos cosas: pintar lo que la persona sigue sin esperar a la red, y
 * que al abrir la app baste con preguntar "¿mi mapa sigue siendo este?". Es
 * una copia: el que manda es el del servidor, y si no coinciden se reemplaza
 * entero.
 *
 * Lo de YouTube es un dato que la plataforma nos presta. Se borra de aquí
 * cuando la persona desconecta YouTube y cuando pasan 30 días sin volver a
 * comprobarlo, igual que en el servidor.
 */
object AlmacenDeMapa {

    private const val PREFS = "relay_mapa"
    private const val CLAVE_MAPA = "mapa"
    private const val CLAVE_YOUTUBE = "youtube_comprobado_en"

    /**
     * Cada cuánto se le vuelve a preguntar a YouTube, aunque el mapa no
     * cambie: lo que la persona hace allá el servidor no lo puede saber.
     */
    private const val REFRESCO_YOUTUBE_MS = 6 * 60 * 60 * 1000L

    private var prefs: SharedPreferences? = null

    /** Se llama una vez desde LeftVocesApp.onCreate(). */
    fun inicializar(contexto: Context) {
        prefs = contexto.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** El mapa guardado, o null si no hay (o si lo guardado no se entiende). */
    fun leer(): MapaDeSeleccion? {
        val texto = prefs?.getString(CLAVE_MAPA, null) ?: return null
        val mapa = runCatching { mapaDe(JSONObject(texto)) }.getOrNull() ?: return null
        if (!mapa.youtubeCaducado(Instant.now())) return mapa
        return mapa.sinYouTube().also { guardar(it) }
    }

    fun guardar(mapa: MapaDeSeleccion) {
        prefs?.edit()?.putString(CLAVE_MAPA, mapa.aJson().toString())?.apply()
    }

    /** La persona desconectó YouTube o le retiró el permiso a la app. */
    fun quitarYouTube() {
        leer()?.let { guardar(it.sinYouTube()) }
        prefs?.edit()?.remove(CLAVE_YOUTUBE)?.apply()
    }

    /**
     * YouTube acaba de contestar. Si contestó lo mismo el mapa no cambia, pero
     * la fecha sí: de ella depende que no caduque a los 30 días.
     */
    fun youtubeComprobado(ahoraMs: Long, verificadoEn: Instant?) {
        prefs?.edit()?.putLong(CLAVE_YOUTUBE, ahoraMs)?.apply()
        leer()?.takeIf { it.sabeDeYouTube }?.let { guardar(it.copy(youtubeVerificadoEn = verificadoEn)) }
    }

    /** Se le preguntó a YouTube hace poco: lo guardado todavía vale. */
    fun youtubeAlDia(ahoraMs: Long): Boolean {
        val ultima = prefs?.getLong(CLAVE_YOUTUBE, 0L) ?: 0L
        // Un reloj movido hacia atrás no debe dejarla sin comprobar.
        return ahoraMs >= ultima && ahoraMs - ultima < REFRESCO_YOUTUBE_MS
    }

    /** Al cerrar sesión o borrar la cuenta: el mapa era de quien se fue. */
    fun olvidar() {
        prefs?.edit()?.clear()?.apply()
    }
}
