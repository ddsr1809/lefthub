package com.vocesdeizquierda.lefthub.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.vocesdeizquierda.lefthub.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Cliente del servidor Relé.
 *
 * Sustituye por completo a Firestore y Firebase Auth. La sesión es un JWT que
 * emite nuestro servidor y que guardamos aquí; no tiene nada que ver con
 * Firebase, que ahora solo interviene en las notificaciones push.
 *
 * El identificador de dispositivo se genera una vez y sobrevive a los cierres
 * de sesión: es lo que permite recuperar la cuenta anónima al reabrir la app
 * sin pedirle nada al usuario.
 */
object ApiRelay {

    private const val TAG = "ApiRelay"
    private const val PREFS = "relay_sesion"
    private const val CLAVE_TOKEN = "token"
    private const val CLAVE_DISPOSITIVO = "device_id"

    private val cliente = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private lateinit var prefs: SharedPreferences

    /** Se llama una vez desde LeftVocesApp.onCreate(). */
    fun inicializar(contexto: Context) {
        prefs = contexto.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /**
     * Identificador estable de esta instalación.
     *
     * No usamos ANDROID_ID ni nada ligado al hardware: un UUID nuestro es
     * suficiente para reconocer la instalación y no identifica a la persona
     * fuera de esta app.
     */
    val deviceId: String
        get() = prefs.getString(CLAVE_DISPOSITIVO, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(CLAVE_DISPOSITIVO, it).apply()
        }

    var token: String?
        get() = prefs.getString(CLAVE_TOKEN, null)
        private set(valor) {
            prefs.edit().apply {
                if (valor == null) remove(CLAVE_TOKEN) else putString(CLAVE_TOKEN, valor)
            }.apply()
        }

    val haySesion: Boolean get() = !token.isNullOrBlank()

    data class Sesion(
        val usuarioId: String,
        val proveedor: String,
        val email: String?,
        val esAdmin: Boolean,
        val favoritosFusionados: Boolean
    )

    /**
     * Respuesta de error del servidor, con su código.
     *
     * Sigue siendo una IOException, así que todo lo que ya atrapaba fallos de
     * red la atrapa igual. El código lo mira solo quien necesita distinguir
     * un caso de otro, como YouTubeRepo con el 412.
     */
    class ErrorHttp(val codigo: Int, mensaje: String) : IOException(mensaje)

    /** Datos de la sesión actual, sin tocar la red. */
    var sesion: Sesion? = null
        private set

    // -------------------------------------------------------------------------
    // Sesión
    // -------------------------------------------------------------------------

    /** Sesión invisible al abrir la app. Sin formularios ni contraseñas. */
    suspend fun entrarAnonimo(): Sesion =
        guardarSesion(post("/api/auth/anonimo", JSONObject().put("deviceId", deviceId), conToken = false))

    /**
     * Enlaza con Google.
     *
     * Mandamos el idToken que devuelve Credential Manager; el servidor lo
     * verifica contra las claves públicas de Google y decide si es una cuenta
     * nueva o una que ya existía en otro teléfono. La fusión de favoritos la
     * resuelve él, por eso aquí no hay lógica de colisiones.
     */
    suspend fun entrarConGoogle(idToken: String): Sesion =
        guardarSesion(post("/api/auth/google",
            JSONObject().put("token", idToken).put("deviceId", deviceId), conToken = false))

    /** Token nuevo antes de que caduque. La app lo llama al arrancar. */
    suspend fun renovar(): Sesion? = runCatching {
        guardarSesion(post("/api/auth/renovar", null))
    }.getOrNull()

    fun cerrarSesion() {
        token = null
        sesion = null
    }

    private fun guardarSesion(json: JSONObject): Sesion {
        token = json.getString("token")
        return Sesion(
            usuarioId = json.getString("usuarioId"),
            proveedor = json.optString("proveedor", "anonimo"),
            email = json.optStringONull("email"),
            esAdmin = json.optBoolean("esAdmin", false),
            favoritosFusionados = json.optBoolean("favoritosFusionados", false)
        ).also { sesion = it }
    }

    // -------------------------------------------------------------------------
    // Directorio
    // -------------------------------------------------------------------------

    suspend fun creadores(categoria: String? = null): List<Creador> {
        val ruta = if (categoria.isNullOrBlank() || categoria == "todos") "/api/creadores"
                   else "/api/creadores?categoria=$categoria"

        return getArray(ruta).mapJson { creadorDe(it) }
    }

    /**
     * Las productoras visibles, con sus canales y los ids de sus creadores.
     *
     * Un servidor anterior a las productoras no tiene la ruta y contesta 404:
     * para la app eso es "no hay ninguna", no un error.
     */
    suspend fun productoras(): List<Productora> = try {
        getArray("/api/productoras").mapJson { productoraDe(it) }
    } catch (e: ErrorHttp) {
        if (e.codigo == 404) emptyList() else throw e
    }

    /**
     * Las novedades de la persona: los videos normales o, con `cortos`, los
     * videos cortos. Son dos listas distintas y nunca se mezclan.
     *
     * El filtro de después es por si el servidor es anterior a esto: ese no
     * entiende `tipo` y lo manda todo junto.
     */
    suspend fun publicaciones(limite: Int = 50, cortos: Boolean = false): List<Publicacion> =
        getArray("/api/publicaciones?limite=$limite" + if (cortos) "&tipo=cortos" else "")
            .mapJson { publicacionDe(it) }
            .filter { it.esCorto == cortos }

    /**
     * Lo último que publicó un canal. Un servidor anterior a las fichas de
     * canal no tiene la ruta: para la app eso es "nada que mostrar".
     */
    suspend fun publicacionesDeCanal(canalId: String, limite: Int = 20): List<Publicacion> = try {
        getArray("/api/canales/$canalId/publicaciones?limite=$limite").mapJson { publicacionDe(it) }
    } catch (e: ErrorHttp) {
        if (e.codigo == 404) emptyList() else throw e
    }

    /**
     * Los últimos videos de un creador, para el mini feed de su ficha: los
     * de sus canales y los de canales de otros en los que aparece. Un
     * servidor anterior no tiene la ruta: para la app eso es "nada que mostrar".
     */
    suspend fun publicacionesDeCreador(creadorId: String, limite: Int = 5): List<Publicacion> = try {
        getArray("/api/creadores/$creadorId/publicaciones?limite=$limite").mapJson { publicacionDe(it) }
    } catch (e: ErrorHttp) {
        if (e.codigo == 404) emptyList() else throw e
    }

    suspend fun perfil(): Perfil = perfilDe(getObject("/api/perfil"))

    suspend fun seguir(creadorId: String) {
        ejecutar(Request.Builder()
            .url(BuildConfig.API_BASE + "/api/favoritos/$creadorId")
            .put(vacio()))
    }

    suspend fun dejarDeSeguir(creadorId: String) {
        ejecutar(Request.Builder()
            .url(BuildConfig.API_BASE + "/api/favoritos/$creadorId")
            .delete())
    }

    suspend fun seguirProductora(productoraId: String) {
        ejecutar(Request.Builder()
            .url(BuildConfig.API_BASE + "/api/favoritos/productoras/$productoraId")
            .put(vacio()))
    }

    suspend fun dejarDeSeguirProductora(productoraId: String) {
        ejecutar(Request.Builder()
            .url(BuildConfig.API_BASE + "/api/favoritos/productoras/$productoraId")
            .delete())
    }

    suspend fun guardarPreferencia(clave: String, valor: Any) {
        ejecutar(Request.Builder()
            .url(BuildConfig.API_BASE + "/api/preferencias")
            .put(JSONObject().put(clave, valor).toString().toRequestBody(JSON)))
    }

    suspend fun reportarEnlace(videoId: String?, creatorId: String?, motivo: String) {
        post("/api/reportes", JSONObject()
            .put("videoId", videoId ?: JSONObject.NULL)
            .put("creadorId", creatorId ?: JSONObject.NULL)
            .put("motivo", motivo))
    }

    suspend fun borrarCuenta() {
        ejecutar(Request.Builder()
            .url(BuildConfig.API_BASE + "/api/cuenta")
            .delete())
        cerrarSesion()
    }

    // -------------------------------------------------------------------------
    // Anuncios: quitarlos con un folio de regalo o con una compra
    // -------------------------------------------------------------------------
    // Las dos devuelven la frase que el servidor escribió para la pantalla. Si
    // no se puede, lanzan ErrorHttp con el motivo, también escrito para leerse.

    /** Canjea un folio de regalo. Vale una sola vez: al usarlo se borra. */
    suspend fun canjearFolio(codigo: String): String =
        post("/api/anuncios/folio", JSONObject().put("codigo", codigo))
            .optStringONull("mensaje") ?: "Listo. Ya no verás anuncios en esta cuenta."

    /**
     * Le pasa al servidor el comprobante de una compra de Google Play para
     * que la confirme con Google y la apunte en la cuenta.
     */
    suspend fun registrarCompra(producto: String, token: String): String =
        post("/api/anuncios/compra", JSONObject().put("producto", producto).put("token", token))
            .optStringONull("mensaje") ?: "Gracias por tu compra. Ya no verás anuncios."

    // -------------------------------------------------------------------------
    // Suscripciones de YouTube
    // -------------------------------------------------------------------------

    /** Lo último que el servidor comprobó. No llama a YouTube. */
    suspend fun suscripcionesYouTube(): SuscripcionesYouTube =
        suscripcionesDe(getObject("/api/youtube/suscripciones"))

    /**
     * Le pasa al servidor el token de acceso de Google para que pregunte a
     * YouTube. El token dura una hora y el servidor no lo guarda.
     */
    suspend fun verificarSuscripcionesYouTube(tokenDeAcceso: String): SuscripcionesYouTube =
        suscripcionesDe(post("/api/youtube/suscripciones", JSONObject().put("accessToken", tokenDeAcceso)))

    suspend fun olvidarSuscripcionesYouTube() {
        ejecutar(Request.Builder()
            .url(BuildConfig.API_BASE + "/api/youtube/suscripciones")
            .delete())
    }

    // El paso del JSON del servidor a los modelos de la app está en Mapeo.kt.

    // -------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------

    private suspend fun getObject(ruta: String): JSONObject =
        JSONObject(ejecutar(Request.Builder().url(BuildConfig.API_BASE + ruta).get()))

    private suspend fun getArray(ruta: String): JSONArray =
        JSONArray(ejecutar(Request.Builder().url(BuildConfig.API_BASE + ruta).get()))

    private suspend fun post(ruta: String, cuerpo: JSONObject?, conToken: Boolean = true): JSONObject {
        val peticion = Request.Builder()
            .url(BuildConfig.API_BASE + ruta)
            .post(cuerpo?.toString()?.toRequestBody(JSON) ?: vacio())

        val respuesta = ejecutar(peticion, conToken)
        return if (respuesta.isBlank()) JSONObject() else JSONObject(respuesta)
    }

    private fun vacio(): RequestBody = ByteArray(0).toRequestBody(JSON)

    private suspend fun ejecutar(peticion: Request.Builder, conToken: Boolean = true): String =
        withContext(Dispatchers.IO) {
            if (conToken) {
                val actual = token ?: throw IOException("No hay sesión activa.")
                peticion.header("Authorization", "Bearer $actual")
            }

            cliente.newCall(peticion.build()).execute().use { respuesta ->
                val cuerpo = respuesta.body?.string().orEmpty()

                if (!respuesta.isSuccessful) {
                    // Un 401 significa que el token caducó o que la cuenta ya
                    // no existe. Lo borramos para que el siguiente arranque
                    // cree una sesión limpia en vez de reintentar en bucle.
                    //
                    // Solo si la petición llevaba NUESTRO token. Al entrar con
                    // Google el 401 habla del token de Google, no del nuestro:
                    // borrar la sesión ahí dejaba la app sin poder guardar
                    // nada hasta reabrirla, por un inicio de sesión fallido.
                    if (respuesta.code == 401 && conToken) cerrarSesion()

                    val motivo = runCatching {
                        JSONObject(cuerpo).optString("message").takeIf { it.isNotBlank() }
                    }.getOrNull()

                    Log.w(TAG, "Respuesta ${respuesta.code} de ${respuesta.request.url}")
                    throw ErrorHttp(respuesta.code, motivo ?: "No se pudo completar la operación.")
                }

                cuerpo
            }
        }
}
