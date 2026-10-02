package com.tuempresa.creatorhub.data

import android.accounts.Account
import android.content.Context
import android.content.IntentSender
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * "¿Estoy suscrito en YouTube a este creador?"
 *
 * Seguir a alguien en esta app y estar suscrito a su canal son cosas
 * distintas. Para contestar hace falta leer las suscripciones de la persona,
 * y eso solo se puede con su permiso expreso.
 *
 * Quién hace qué:
 *
 *   · Este archivo le pide a Google un token de acceso de solo lectura. La
 *     primera vez Google muestra su pantalla de permiso; después lo entrega
 *     sin interfaz cada vez que la app se abre.
 *   · El servidor recibe ese token, pregunta a YouTube y guarda la respuesta.
 *     El token dura una hora y nadie lo conserva.
 *
 * Va separado de AuthRepo a propósito. Entrar con Google (quién eres) y dar
 * acceso a tus datos de YouTube (qué puedo leer) son dos consentimientos, y
 * Google exige pedirlos por separado: nadie debe encontrarse la pantalla de
 * permisos de YouTube solo por querer guardar su cuenta.
 */
class YouTubeRepo {

    sealed interface Resultado {
        data class Verificado(val suscripciones: SuscripcionesYouTube) : Resultado

        /**
         * No hay permiso. `pedir` abre la pantalla de Google para darlo; es
         * null cuando la persona lo apagó en Ajustes y no hay que insistir.
         */
        data class FaltaPermiso(val pedir: IntentSender?) : Resultado

        data class Fallo(val mensaje: String) : Resultado
    }

    /** Lo último que el servidor comprobó, o null si no se pudo leer. */
    suspend fun guardadas(): SuscripcionesYouTube? =
        runCatching { ApiRelay.suscripcionesYouTube() }
            .onFailure { Log.w(TAG, "No se pudo leer lo guardado: ${it.message}") }
            .getOrNull()

    /**
     * Comprueba contra YouTube sin mostrar nada.
     *
     * Si el permiso ya está dado, Google entrega el token en silencio y el
     * servidor contesta. Si no, devuelve FaltaPermiso con lo necesario para
     * pedirlo, pero NO lo pide: eso lo decide quien llama, y solo cuando la
     * persona tocó un botón.
     */
    suspend fun verificar(contexto: Context): Resultado {
        if (apagado(contexto)) return Resultado.FaltaPermiso(pedir = null)

        val cliente = Identity.getAuthorizationClient(contexto)

        // Dos intentos como mucho. Google guarda los tokens en caché y a veces
        // entrega uno que ya no vale; el servidor lo detecta (412), lo tiramos
        // de la caché y el segundo intento trae uno recién emitido.
        for (intento in 0..1) {
            val autorizacion = try {
                cliente.authorize(peticion()).await()
            } catch (e: Exception) {
                Log.w(TAG, "Google no entregó la autorización", e)
                return Resultado.Fallo("No se pudo consultar YouTube. Inténtalo más tarde.")
            }

            if (autorizacion.hasResolution()) {
                return Resultado.FaltaPermiso(autorizacion.pendingIntent?.intentSender)
            }

            val token = autorizacion.accessToken
            if (token.isNullOrBlank()) {
                return Resultado.Fallo("No se pudo consultar YouTube. Inténtalo más tarde.")
            }

            try {
                return Resultado.Verificado(ApiRelay.verificarSuscripcionesYouTube(token))
            } catch (e: ApiRelay.ErrorHttp) {
                if (e.codigo == TOKEN_RECHAZADO && intento == 0) {
                    runCatching {
                        cliente.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
                    }
                    continue
                }
                Log.w(TAG, "El servidor respondió ${e.codigo} al verificar")
                return Resultado.Fallo(e.message ?: "No se pudo consultar YouTube.")
            } catch (e: Exception) {
                Log.w(TAG, "Verificación fallida: ${e.message}")
                return Resultado.Fallo("No se pudo consultar YouTube. Revisa tu conexión.")
            }
        }

        return Resultado.Fallo("El permiso de YouTube caducó. Vuelve a conectarlo.")
    }

    /**
     * Borra lo que el servidor tenía guardado. Para cuando se descubre que la
     * persona retiró el permiso desde su cuenta de Google, fuera de la app.
     */
    suspend fun olvidar() {
        runCatching { ApiRelay.olvidarSuscripcionesYouTube() }
            .onFailure { Log.w(TAG, "El servidor no confirmó el borrado: ${it.message}") }
    }

    /** La persona tocó "Conectar": a partir de aquí sí se comprueba al entrar. */
    fun encender(contexto: Context) = guardarApagado(contexto, false)

    /**
     * Desconecta YouTube: apaga la comprobación en este teléfono, borra lo que
     * el servidor tenía guardado y le retira el permiso a la app en Google.
     *
     * El interruptor local va primero y no depende de la red. Si la revocación
     * fallara y solo confiáramos en ella, la siguiente apertura volvería a
     * consultar YouTube en silencio justo después de que la persona dijo que
     * no quería.
     *
     * Ojo: revokeAccess retira TODOS los permisos de Google de la app, también
     * el del inicio de sesión; no existe la opción de retirar solo uno. La
     * sesión de Relé sigue viva (es un token nuestro), y lo único que cambia
     * es que Google volverá a preguntar la próxima vez que se entre con él.
     */
    suspend fun desconectar(contexto: Context, correo: String?) {
        guardarApagado(contexto, true)
        olvidar()

        if (correo.isNullOrBlank()) return
        runCatching {
            Identity.getAuthorizationClient(contexto).revokeAccess(
                RevokeAccessRequest.builder()
                    .setAccount(Account(correo, TIPO_DE_CUENTA_GOOGLE))
                    .setScopes(PERMISOS)
                    .build()
            ).await()
        }.onFailure { Log.w(TAG, "Google no confirmó la revocación: ${it.message}") }
    }

    /**
     * Al cerrar sesión. El interruptor era de la cuenta que se va: la
     * siguiente persona que entre en este teléfono empieza sin nada decidido.
     */
    fun alCerrarSesion(contexto: Context) {
        prefs(contexto).edit().remove(CLAVE_APAGADO).apply()
    }

    // -------------------------------------------------------------------------

    private fun peticion() = AuthorizationRequest.builder()
        .setRequestedScopes(PERMISOS)
        .build()

    private fun apagado(contexto: Context) = prefs(contexto).getBoolean(CLAVE_APAGADO, false)

    private fun guardarApagado(contexto: Context, valor: Boolean) {
        prefs(contexto).edit().putBoolean(CLAVE_APAGADO, valor).apply()
    }

    private fun prefs(contexto: Context) =
        contexto.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "YouTubeRepo"
        private const val PREFS = "relay_youtube"
        private const val CLAVE_APAGADO = "apagado"
        private const val TIPO_DE_CUENTA_GOOGLE = "com.google"

        /** El servidor contesta 412 cuando el token de Google no le sirve. */
        private const val TOKEN_RECHAZADO = 412

        /**
         * Solo lectura. Es el permiso más estrecho con el que YouTube deja ver
         * suscripciones; con él la app no puede suscribir, comentar ni subir
         * nada en nombre de nadie.
         */
        private val PERMISOS = listOf(Scope("https://www.googleapis.com/auth/youtube.readonly"))
    }
}
