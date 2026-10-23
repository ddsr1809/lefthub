package com.vocesdeizquierda.lefthub.seguridad

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.appcheck.FirebaseAppCheck
import com.vocesdeizquierda.lefthub.BuildConfig
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Pone en cada petición a nuestro servidor las dos pruebas de que viene de la
 * app original:
 *
 *  1. La firma con la llave de la app (FirmaDeApp), con la hora y un nonce.
 *  2. El token de Firebase App Check (X-Firebase-AppCheck), que Firebase solo
 *     da a la app firmada por Google Play, instalada desde Play, en un
 *     teléfono Android de verdad (Play Integrity).
 *
 * Si algo de esto falla en el teléfono (sin servicios de Google, sin llave en
 * esta compilación), la petición sale igual sin esa parte: qué hace el
 * servidor con ella lo decide el panel (avisar o exigir).
 *
 * La hora del teléfono puede estar mal. El servidor manda la suya en cada
 * respuesta (X-Servidor-Hora) y se firma con la hora corregida. Si aun así el
 * servidor dice que la hora no cuadra (403 con X-App-Motivo: hora), se repite
 * una vez con la hora que acaba de mandar.
 */
object InterceptorDeFirma : Interceptor {

    private const val TAG = "InterceptorDeFirma"

    /** Segundos que hay que sumarle a la hora del teléfono para tener la del servidor. */
    private val desfase = AtomicLong(0)
    private val azar = SecureRandom()

    private val llave: ByteArray? by lazy {
        FirmaDeApp.descubrir(BuildConfig.APP_LLAVE, BuildConfig.APPLICATION_ID).also {
            if (it == null) Log.w(TAG, "Esta compilación no trae llave de la app: las peticiones van sin firma")
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (!original.url.toString().startsWith(BuildConfig.API_BASE)) return chain.proceed(original)

        var respuesta = chain.proceed(conPruebas(original))
        anotarHora(respuesta)
        if (respuesta.code == 403 && respuesta.header("X-App-Motivo") == "hora") {
            respuesta.close()
            respuesta = chain.proceed(conPruebas(original))
            anotarHora(respuesta)
        }
        return respuesta
    }

    private fun conPruebas(peticion: Request): Request {
        val nueva = peticion.newBuilder()

        llave?.let { k ->
            val hora = (System.currentTimeMillis() / 1000 + desfase.get()).toString()
            val nonce = FirmaDeApp.nonce(ByteArray(16).also { azar.nextBytes(it) })
            // La ruta ya decodificada y sin la consulta, como la ve el servidor.
            val ruta = "/" + peticion.url.pathSegments.joinToString("/")
            nueva.header("X-App-Llave", BuildConfig.APP_LLAVE_ID.toString())
                .header("X-App-Hora", hora)
                .header("X-App-Nonce", nonce)
                .header("X-App-Firma", FirmaDeApp.firmar(k, FirmaDeApp.texto(peticion.method, ruta, hora, nonce)))
        }

        tokenDeAppCheck()?.let { nueva.header("X-Firebase-AppCheck", it) }
        return nueva.build()
    }

    private fun anotarHora(respuesta: Response) {
        respuesta.header("X-Servidor-Hora")?.toLongOrNull()?.let {
            desfase.set(it - System.currentTimeMillis() / 1000)
        }
    }

    /**
     * El token de App Check. Firebase lo guarda y lo renueva solo (dura una
     * hora), así que casi siempre vuelve al instante; la primera vez puede
     * tardar un par de segundos. Las peticiones salen siempre en un hilo de
     * fondo (Dispatchers.IO), donde se puede esperar.
     */
    private fun tokenDeAppCheck(): String? = runCatching {
        Tasks.await(FirebaseAppCheck.getInstance().getAppCheckToken(false), 5, TimeUnit.SECONDS).token
    }.onFailure {
        Log.w(TAG, "Sin token de App Check: ${it.message}")
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
