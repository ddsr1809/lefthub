package com.vocesdeizquierda.lefthub.seguridad

import android.util.Base64
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * La firma con la llave de la app, igual que la comprueba el servidor
 * (seguridad/FirmaDeApp.java en relay-server).
 *
 * Cada petición lleva X-App-Llave, X-App-Hora, X-App-Nonce y X-App-Firma: un
 * HMAC-SHA256 de
 *
 *     lefthub-app-v1 \n MÉTODO \n /ruta \n hora \n nonce
 *
 * con la llave que se compila dentro de la app desde android/firma.properties
 * (llave_app_pruebas, llave_app_produccion). Es la misma que APP_LLAVES en el
 * .env de cada servidor.
 *
 * La llave va dentro del APK un poco disfrazada (mezclada con un valor que
 * sale del applicationId), para que no aparezca tal cual al buscar textos en
 * la app. No es un secreto de verdad: quien descompile la app con paciencia
 * puede sacarla. Lo que no puede falsificar es el token de App Check, que la
 * app manda además (ver InterceptorDeFirma).
 */
object FirmaDeApp {

    private const val VERSION = "lefthub-app-v1"
    private const val SIN_RELLENO = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    /** El texto que se firma. Si cambia aquí, tiene que cambiar en el servidor. */
    fun texto(metodo: String, ruta: String, hora: String, nonce: String): String =
        "$VERSION\n${metodo.uppercase(Locale.ROOT)}\n$ruta\n$hora\n$nonce"

    /** HMAC-SHA256 en base64url sin relleno. */
    fun firmar(llave: ByteArray, texto: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(llave, "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(texto.toByteArray(Charsets.UTF_8)), SIN_RELLENO)
    }

    /** Un nonce nuevo: 16 bytes al azar en base64url (22 caracteres). */
    fun nonce(bytes: ByteArray): String = Base64.encodeToString(bytes, SIN_RELLENO)

    /**
     * La llave de verdad a partir de la que se compiló (BuildConfig.APP_LLAVE),
     * o null si esta compilación no trae llave. El disfraz lo pone
     * android/app/build.gradle.kts con la misma fórmula.
     */
    fun descubrir(oculta: String, idDeApp: String): ByteArray? {
        if (oculta.isBlank()) return null
        val bytes = runCatching { Base64.decode(oculta, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.size != 32) return null
        val mascara = mascara(idDeApp)
        return ByteArray(32) { (bytes[it].toInt() xor mascara[it].toInt()).toByte() }
    }

    fun mascara(idDeApp: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest("lefthub-app|$idDeApp".toByteArray(Charsets.UTF_8))
}
