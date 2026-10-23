package com.vocesdeizquierda.lefthub.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Los videos que la persona ya abrió desde la app, y los que marcó a mano
 * como vistos con "Ya lo vi" en la ficha del video.
 *
 * YouTube no le dice a ninguna app qué vio alguien, así que "abierto" solo
 * quiere decir que tocó el video aquí y la app la mandó a verlo: las tarjetas
 * dicen "Ya lo abriste". "Visto" lo dice la propia persona: entonces dicen
 * "Ya lo viste".
 *
 * Van con la cuenta, en el servidor (GET/POST /api/vistos): al cerrar sesión
 * y volver a entrar, o en otro teléfono, reaparecen. El teléfono guarda una
 * copia para pintarlas en el acto y para no perder lo que se marca sin
 * conexión: eso queda "pendiente" y se manda en cuanto se puede.
 *
 * Con un servidor anterior a /api/vistos todo sigue como antes: las marcas
 * viven solo en el teléfono.
 */
object Abiertos {

    private const val TAG = "Abiertos"
    private const val PREFS = "relay_abiertos"
    private const val CLAVE = "videos"
    private const val CLAVE_A_MANO = "a_mano"
    private const val CLAVE_PENDIENTES = "pendientes"
    private const val CLAVE_PENDIENTES_A_MANO = "pendientes_a_mano"

    // Las marcas de antes de que se guardaran en la cuenta. La primera vez
    // que arranca esta versión se suben todas a la cuenta de este teléfono.
    private const val CLAVE_SUBIDAS = "subidas_a_la_cuenta"

    // Lo mismo que guarda el servidor: las más recientes.
    private const val TOPE = 500

    /** Los IDs de los videos abiertos o vistos, del más viejo al más nuevo. */
    var videos: Set<String> by mutableStateOf(emptySet())
        private set

    /** Los que la persona marcó con "Ya lo vi". Están también en [videos]. */
    var aMano: Set<String> by mutableStateOf(emptySet())
        private set

    // Lo que se marcó aquí y el servidor todavía no sabe.
    private var pendientes: Set<String> = emptySet()
    private var pendientesAMano: Set<String> = emptySet()

    private var cargado = false
    private var app: Context? = null

    // Un servidor anterior a /api/vistos contesta 404: no se insiste.
    @Volatile
    private var servidorSinVistos = false

    // Una sola sincronización a la vez, para que una respuesta vieja no pise
    // una marca recién hecha.
    private val turno = Mutex()
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Lee lo guardado. Se llama al arrancar; repetirlo no hace nada. */
    fun cargar(contexto: Context) {
        if (cargado) return
        app = contexto.applicationContext
        val prefs = prefs(contexto)
        videos = lista(prefs.getString(CLAVE, null))
        aMano = lista(prefs.getString(CLAVE_A_MANO, null)).intersect(videos)
        pendientes = lista(prefs.getString(CLAVE_PENDIENTES, null))
        pendientesAMano = lista(prefs.getString(CLAVE_PENDIENTES_A_MANO, null))

        if (!prefs.getBoolean(CLAVE_SUBIDAS, false)) {
            // Lo que se abrió con la versión anterior, a la cuenta.
            pendientes = pendientes + videos
            prefs.edit().putBoolean(CLAVE_SUBIDAS, true).apply()
            guardar()
        }
        cargado = true
    }

    /**
     * Apunta que este video ya se abrió o, con [aMano], que la persona dijo
     * que ya lo vio. Se pinta en el acto y se manda a la cuenta por detrás.
     * Sin ID no hay nada que apuntar.
     */
    fun marcar(contexto: Context, videoId: String?, aMano: Boolean = false) {
        if (videoId.isNullOrBlank()) return
        cargar(contexto)
        if (videoId in videos && (!aMano || videoId in this.aMano)) return

        videos = (videos + videoId).toList().takeLast(TOPE).toSet()
        if (aMano) {
            this.aMano = (this.aMano + videoId).intersect(videos)
            pendientesAMano = pendientesAMano + videoId
        } else {
            pendientes = pendientes + videoId
        }
        guardar()
        alcance.launch { enviarPendientes() }
    }

    /**
     * Manda a la cuenta lo que se marcó aquí y no ha llegado. Si no se puede
     * (sin conexión, sin sesión todavía), queda para la próxima.
     */
    suspend fun enviarPendientes() {
        turno.withLock { enviar() }
    }

    /**
     * Trae de la cuenta sus marcas y las pone en pantalla, junto con lo que
     * todavía no se pudo mandar. Va al arrancar y al cambiar de cuenta.
     *
     * @param cambioDeCuenta la cuenta es otra (se cerró sesión, se entró con
     *        Google, se borró la anterior): lo que había en pantalla era de la
     *        otra y se reemplaza por completo. Antes del cambio hay que llamar
     *        a [enviarPendientes] para que lo marcado llegue a la anterior.
     *
     * Si el servidor no contesta, o es anterior a /api/vistos, se queda lo
     * que hay en el teléfono.
     */
    suspend fun sincronizar(cambioDeCuenta: Boolean = false) {
        turno.withLock {
            if (!cambioDeCuenta) enviar()
            if (servidorSinVistos || !ApiRelay.haySesion) return@withLock

            val leidos = try {
                ApiRelay.vistos()
            } catch (e: ApiRelay.ErrorHttp) {
                if (e.codigo == 404) servidorSinVistos = true
                null
            } catch (e: Exception) {
                Log.w(TAG, "No se pudieron leer los vistos: ${e.message}")
                null
            } ?: return@withLock

            if (cambioDeCuenta) {
                pendientes = emptySet()
                pendientesAMano = emptySet()
            }
            // El servidor los da del más nuevo al más viejo; aquí van al revés.
            val todos = (leidos.videos.reversed() + pendientes + pendientesAMano)
                .distinct().takeLast(TOPE).toSet()
            videos = todos
            aMano = (leidos.aMano.toSet() + pendientesAMano).intersect(todos)
            guardar()
        }
    }

    /** Borra todas las marcas del teléfono. Va con el borrado de la cuenta. */
    fun olvidar(contexto: Context) {
        app = contexto.applicationContext
        videos = emptySet()
        aMano = emptySet()
        pendientes = emptySet()
        pendientesAMano = emptySet()
        cargado = true
        prefs(contexto).edit()
            .remove(CLAVE).remove(CLAVE_A_MANO)
            .remove(CLAVE_PENDIENTES).remove(CLAVE_PENDIENTES_A_MANO)
            .putBoolean(CLAVE_SUBIDAS, true)
            .apply()
    }

    // Llamar con el turno tomado.
    private suspend fun enviar() {
        if (servidorSinVistos || !ApiRelay.haySesion) return
        val abiertos = pendientes
        val vistos = pendientesAMano
        if (abiertos.isEmpty() && vistos.isEmpty()) return

        try {
            for (tanda in abiertos.chunked(TOPE)) ApiRelay.marcarVistos(tanda, aMano = false)
            pendientes = pendientes - abiertos
            for (tanda in vistos.chunked(TOPE)) ApiRelay.marcarVistos(tanda, aMano = true)
            pendientesAMano = pendientesAMano - vistos
        } catch (e: ApiRelay.ErrorHttp) {
            if (e.codigo == 404) servidorSinVistos = true
            Log.w(TAG, "El servidor no apuntó los vistos: ${e.codigo}")
        } catch (e: Exception) {
            Log.w(TAG, "Vistos sin mandar, para la próxima: ${e.message}")
        } finally {
            guardar()
        }
    }

    private fun guardar() {
        val contexto = app ?: return
        prefs(contexto).edit()
            .putString(CLAVE, videos.joinToString("\n"))
            .putString(CLAVE_A_MANO, aMano.joinToString("\n"))
            .putString(CLAVE_PENDIENTES, pendientes.joinToString("\n"))
            .putString(CLAVE_PENDIENTES_A_MANO, pendientesAMano.joinToString("\n"))
            .apply()
    }

    private fun lista(texto: String?): Set<String> =
        texto.orEmpty().split('\n').filter { it.isNotBlank() }.toSet()

    private fun prefs(contexto: Context) =
        contexto.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Las marcas que la cuenta tiene en el servidor.
 *
 * @param videos todas, de la más reciente a la más vieja
 * @param aMano  las que la persona marcó con "Ya lo vi"
 */
data class VistosDeLaCuenta(val videos: List<String>, val aMano: List<String>)
