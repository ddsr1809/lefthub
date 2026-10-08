package com.vocesdeizquierda.lefthub.anuncios

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.vocesdeizquierda.lefthub.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Los anuncios de Novedades (AdMob).
 *
 * Tres reglas que explican todo lo de este archivo:
 *
 *  1. La biblioteca de anuncios de Google no se toca hasta que hace falta.
 *     Quien quitó los anuncios, o usa la app con los anuncios apagados desde
 *     el panel, nunca la arranca: no se pide ni se manda nada por él.
 *  2. Antes de pedir el primer anuncio se pregunta a Google si a esta persona
 *     hay que pedirle consentimiento (Europa, Reino Unido, algunos estados de
 *     EE. UU.). Si hace falta, Google enseña su propio formulario; si no, no
 *     se ve nada. Sin esa respuesta no se pide ningún anuncio.
 *  3. A Google no se le dice nada de lo que la persona hace aquí: ni a quién
 *     sigue ni qué videos abre. La petición de un anuncio va sin palabras
 *     clave ni contenido.
 */
object Anuncios {

    private const val TAG = "Anuncios"

    /** Alto máximo de un anuncio, en dp. Más que esto tapa la lista. */
    private const val ALTO_MAXIMO_DP = 250

    /**
     * Esta compilación tiene un bloque de anuncios. La de producción no lo
     * tiene hasta que se rellenan los identificadores en build.gradle.kts.
     */
    val configurados: Boolean get() = BuildConfig.ADMOB_BANNER.isNotBlank()

    /** Ya se pueden pedir anuncios: el consentimiento está resuelto y la biblioteca, lista. */
    var listos by mutableStateOf(false)
        private set

    /**
     * A esta persona hay que ofrecerle cambiar su elección de privacidad. Solo
     * donde la ley lo pide; entonces Ajustes enseña el botón.
     */
    var hayOpcionesDePrivacidad by mutableStateOf(false)
        private set

    private val preguntando = AtomicBoolean(false)
    private val iniciada = AtomicBoolean(false)

    /**
     * Resuelve el consentimiento y arranca la biblioteca. Se llama cuando se
     * sabe que la persona ve anuncios; repetirlo no hace nada.
     */
    fun preparar(actividad: Activity) {
        if (!configurados || listos) return
        if (preguntando.getAndSet(true)) return

        val consentimiento = UserMessagingPlatform.getConsentInformation(actividad)
        val parametros = ConsentRequestParameters.Builder().build()

        consentimiento.requestConsentInfoUpdate(
            actividad,
            parametros,
            {
                // Enseña el formulario de Google solo si hace falta. En México
                // no hace falta y esto termina al momento sin pintar nada.
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(actividad) { fallo ->
                    if (fallo != null) Log.w(TAG, "Formulario de consentimiento: ${fallo.message}")
                    alResolver(actividad, consentimiento)
                }
            },
            { fallo ->
                // Sin conexión, por ejemplo. Vale lo que la persona eligió la
                // última vez, si eligió algo; si no, no hay anuncios y se
                // vuelve a preguntar la próxima vez.
                Log.w(TAG, "No se pudo consultar el consentimiento: ${fallo.message}")
                alResolver(actividad, consentimiento)
            }
        )

        // Lo que ya se sabía de una vez anterior sirve desde ya, sin esperar
        // a la respuesta de arriba.
        if (consentimiento.canRequestAds()) iniciar(actividad.applicationContext)
    }

    private fun alResolver(actividad: Activity, consentimiento: ConsentInformation) {
        preguntando.set(false)
        hayOpcionesDePrivacidad = consentimiento.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED

        if (consentimiento.canRequestAds()) iniciar(actividad.applicationContext)
    }

    private fun iniciar(contexto: Context) {
        if (iniciada.getAndSet(true)) return

        // Fuera del hilo principal, como pide Google: arrancar la biblioteca
        // lee de disco y puede tardar.
        thread(name = "anuncios") {
            runCatching { MobileAds.initialize(contexto) {} }
                .onFailure { Log.w(TAG, "No se pudo iniciar la biblioteca de anuncios", it) }
                .onSuccess { Handler(Looper.getMainLooper()).post { listos = true } }
        }
    }

    /** Abre el formulario de Google para cambiar la elección de privacidad. */
    fun abrirOpcionesDePrivacidad(actividad: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(actividad) { fallo ->
            if (fallo != null) Log.w(TAG, "Opciones de privacidad: ${fallo.message}")
        }
    }

    /**
     * Crea un anuncio y lo pide. `anchoDp` es el ancho que va a ocupar en la
     * lista; el alto lo decide Google según el anuncio, hasta el máximo.
     *
     * Hay que llamarlo con el contexto de la pantalla (la Activity), y
     * destruir lo que devuelve cuando ya no se use.
     */
    fun crearBanner(contexto: Context, anchoDp: Int): Banner {
        val vista = AdView(contexto)
        val banner = Banner(vista)

        vista.adUnitId = BuildConfig.ADMOB_BANNER
        vista.setAdSize(AdSize.getInlineAdaptiveBannerAdSize(anchoDp, ALTO_MAXIMO_DP))
        vista.adListener = object : AdListener() {
            override fun onAdLoaded() {
                banner.cargado = true
            }

            override fun onAdFailedToLoad(fallo: LoadAdError) {
                // Google no siempre tiene un anuncio que dar. No pasa nada:
                // ese hueco se queda sin tarjeta.
                Log.d(TAG, "Sin anuncio para este hueco: ${fallo.message}")
            }
        }
        // Sin palabras clave ni contenido: ver la regla 3 de arriba.
        vista.loadAd(AdRequest.Builder().build())

        return banner
    }
}

/**
 * Un anuncio de la lista. `cargado` pasa a true cuando Google lo entrega; hasta
 * entonces no se pinta su tarjeta, para no dejar un recuadro vacío.
 */
class Banner(val vista: AdView) {
    var cargado by mutableStateOf(false)
        internal set
}

/**
 * Los anuncios de Novedades, guardados mientras la pantalla principal esté
 * abierta. Así no se pide uno nuevo cada vez que la persona cambia de pestaña
 * y vuelve, ni cada vez que uno sale de la vista al deslizar.
 */
class BannersDelFeed {

    /** En orden: el del primer hueco, el del segundo... */
    val banners = mutableStateListOf<Banner>()

    /** Pide los que falten hasta tener `cuantos`. Los que ya hay se quedan. */
    fun asegurar(contexto: Context, cuantos: Int, anchoDp: Int) {
        if (anchoDp <= 0) return
        while (banners.size < minOf(cuantos, Huecos.MAXIMO)) {
            banners += Anuncios.crearBanner(contexto, anchoDp)
        }
    }

    /** Suelta todos: la persona quitó los anuncios, o se cierra la pantalla. */
    fun destruir() {
        banners.forEach { it.vista.destroy() }
        banners.clear()
    }
}
