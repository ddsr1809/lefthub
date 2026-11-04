package com.vocesdeizquierda.lefthub.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.sync.Mutex

/**
 * Qué hay que hacer para pasar de los topics que tiene el teléfono a los que
 * le tocan.
 *
 * @param actuales a los que está suscrito; null si todavía no se sabe
 * @param queridos a los que debe estar suscrito
 * @param dudosos  los que pudo dejar una versión anterior de la app, de la que
 *                 no quedó nota: solo cuentan mientras no se sabe nada
 * @param repaso   volver a pedir todos los queridos, aunque ya consten
 */
internal fun planDeTopics(
    actuales: Set<String>?,
    queridos: Set<String>,
    dudosos: Set<String> = emptySet(),
    repaso: Boolean = false
): PlanDeTopics =
    if (actuales == null) PlanDeTopics(altas = queridos, bajas = dudosos - queridos)
    else PlanDeTopics(
        altas = if (repaso) queridos else queridos - actuales,
        bajas = actuales - queridos
    )

internal data class PlanDeTopics(val altas: Set<String>, val bajas: Set<String>)

/**
 * A qué topics de FCM está suscrito este teléfono.
 *
 * Los topics son del aparato y no de la cuenta, y lo que se sigue es de la
 * cuenta. Con esta nota solo se habla con FCM cuando algo cambió, y se sabe
 * también de cuáles darse de baja: lo que se dejó de seguir en otro teléfono,
 * o lo de la cuenta anterior.
 *
 * Es del teléfono y no del mapa: sobrevive al cambio de cuenta, porque
 * justamente entonces hay que saber qué quitar. Lo que falla no se anota al
 * revés: un alta sin confirmar no consta y una baja sin confirmar sigue
 * constando, así que las dos se reintentan solas.
 */
internal object TopicosDelTelefono {

    private const val PREFS = "relay_topics"
    private const val CLAVE_SUSCRITOS = "suscritos"
    private const val CLAVE_REPASO = "repasado_en"

    /**
     * Cada cuánto se vuelve a pedir todo, por si FCM perdió algo sin avisar.
     * Las notificaciones son lo que sostiene a la app: una vez al día no
     * cuesta nada y nadie se queda sin avisos por una nota equivocada.
     */
    private const val REPASO_MS = 24 * 60 * 60 * 1000L

    private var prefs: SharedPreferences? = null

    /** Un cambio de topics a la vez. */
    val turno = Mutex()

    /** Se llama una vez desde LeftVocesApp.onCreate(). */
    fun inicializar(contexto: Context) {
        prefs = contexto.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Lo que hay que hacer ahora. Llamar con el turno tomado. */
    fun planPara(queridos: Set<String>, dudosos: Set<String>, ahoraMs: Long): PlanDeTopics {
        val actuales = suscritos()
        val ultimo = prefs?.getLong(CLAVE_REPASO, 0L) ?: 0L
        val repaso = ahoraMs < ultimo || ahoraMs - ultimo >= REPASO_MS
        val plan = planDeTopics(actuales, queridos, dudosos, repaso)

        // La primera vez, lo dudoso consta como suscrito hasta que se confirme
        // la baja: si falla (sin red al abrir), se reintenta la próxima.
        if (actuales == null) guardar(plan.bajas)
        if (repaso) prefs?.edit()?.putLong(CLAVE_REPASO, ahoraMs)?.apply()
        return plan
    }

    fun anotar(topic: String) {
        suscritos()?.let { guardar(it + topic) }
    }

    fun quitar(topic: String) {
        suscritos()?.let { guardar(it - topic) }
    }

    /** FCM cambió el token: las suscripciones se perdieron todas. */
    fun olvidar() {
        prefs?.edit()?.remove(CLAVE_SUSCRITOS)?.apply()
    }

    private fun suscritos(): Set<String>? =
        prefs?.getString(CLAVE_SUSCRITOS, null)
            ?.split('\n')?.filter { it.isNotBlank() }?.toSet()

    private fun guardar(topics: Set<String>) {
        prefs?.edit()?.putString(CLAVE_SUSCRITOS, topics.joinToString("\n"))?.apply()
    }
}
