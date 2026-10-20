package com.vocesdeizquierda.lefthub.chat

import android.util.Base64
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vocesdeizquierda.lefthub.data.ApiRelay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.KeyPair
import java.time.Instant
import javax.crypto.SecretKey
import kotlin.coroutines.coroutineContext

/** Una línea del chat. Las notas son avisos de la app, no mensajes de nadie. */
data class MensajeChat(
    val id: Long,
    val texto: String,
    val hora: Instant,
    val mio: Boolean = false,
    val nota: Boolean = false
)

enum class FaseChat { ENTRANDO, ESPERANDO, CONECTADO, TERMINADO }

data class EstadoChat(
    val fase: FaseChat = FaseChat.ENTRANDO,
    val mensajes: List<MensajeChat> = emptyList(),
    /** Las 8 cifras para comprobar con el equipo. Null hasta que el equipo está dentro. */
    val codigo: String? = null,
    val enviando: Boolean = false,
    /** Por qué no se pudo enviar el último mensaje. Null si salió bien. */
    val errorAlEnviar: String? = null
)

/**
 * Una conversación con el equipo, de principio a fin.
 *
 * Vive lo que vive la pantalla del chat (el ViewModel es de esa entrada de la
 * navegación): girar el teléfono no la corta, salir de la pantalla sí. Al
 * salir se avisa al servidor y se olvida todo: las llaves, la clave y los
 * mensajes. Nada de esto se escribe en disco en ningún momento.
 */
class ChatViewModel : ViewModel() {

    private val _estado = MutableStateFlow(EstadoChat())
    val estado: StateFlow<EstadoChat> = _estado.asStateFlow()

    private var par: KeyPair? = null
    private var miPublica: ByteArray? = null
    private var conexion: String? = null
    private var clave: SecretKey? = null
    private var enviados = 0L
    private var ultimoRecibido = 0L
    private var siguienteId = 0L
    private var bucle: Job? = null

    init {
        entrar()
    }

    /** Entra (o vuelve a entrar) con un par de llaves nuevo. */
    fun entrar() {
        bucle?.cancel()
        despedirse()
        olvidar()
        _estado.value = EstadoChat()

        bucle = viewModelScope.launch {
            val nuevo = withContext(Dispatchers.Default) { CifradoChat.nuevoPar() }
            val publica = CifradoChat.publicaCruda(nuevo)
            par = nuevo
            miPublica = publica

            val entrada = try {
                ApiRelay.entrarAlChat(b64(publica))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                terminar(e.message ?: "No se pudo abrir el chat. Revisa tu conexión.")
                return@launch
            }
            conexion = entrada.conexion

            val delEquipo = entrada.claveDelEquipo
            if (delEquipo != null) {
                llaveDelEquipo(delEquipo)
            } else {
                _estado.update { it.copy(fase = FaseChat.ESPERANDO) }
            }
            escuchar(entrada.conexion)
        }
    }

    fun enviar(texto: String, alEnviar: () -> Unit) {
        val limpio = texto.trim()
        val k = clave
        val c = conexion
        if (limpio.isEmpty() || k == null || c == null || _estado.value.enviando) return
        if (limpio.length > MAX_TEXTO) {
            _estado.update { it.copy(errorAlEnviar = "El mensaje es muy largo. Pártelo en dos.") }
            return
        }

        // De uno en uno: el contador de cada mensaje tiene que llegar en orden.
        _estado.update { it.copy(enviando = true, errorAlEnviar = null) }
        viewModelScope.launch {
            try {
                val claro = JSONObject().put("n", ++enviados).put("t", limpio).toString()
                val (iv, cifrado) = CifradoChat.cifrar(k, CifradoChat.LADO_USUARIO, claro.toByteArray(Charsets.UTF_8))
                ApiRelay.enviarAlChat(c, b64(iv), b64(cifrado))
                agregar(MensajeChat(siguienteId++, limpio, Instant.now(), mio = true))
                alEnviar()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _estado.update { it.copy(errorAlEnviar = e.message ?: "No se pudo enviar. Inténtalo otra vez.") }
            } finally {
                _estado.update { it.copy(enviando = false) }
            }
        }
    }

    override fun onCleared() {
        despedirse()
        olvidar()
        _estado.value = EstadoChat()
    }

    // -------------------------------------------------------------------------

    private suspend fun escuchar(miConexion: String) {
        var fallos = 0
        while (coroutineContext.isActive && conexion == miConexion) {
            val eventos = try {
                ApiRelay.eventosDelChat(miConexion).also { fallos = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiRelay.ErrorHttp) {
                // 410: la conversación terminó o se abrió en otro teléfono.
                // 401/403: sin sesión, o el equipo quitó el chat.
                if (e.codigo in listOf(401, 403, 404, 410)) {
                    terminar(e.message ?: "La conversación terminó.")
                    return
                }
                null
            } catch (e: Exception) {
                null
            }

            if (eventos == null) {
                // Sin red, o el servidor reiniciándose: cada vez más despacio.
                fallos++
                if (fallos == 3) nota("Se perdió la conexión. Reintentando…")
                delay(minOf(30_000L, 2_000L * fallos))
                continue
            }

            for (i in 0 until eventos.length()) {
                if (!procesar(eventos.getJSONObject(i))) return
            }
        }
    }

    /** False si la conversación terminó y hay que dejar de escuchar. */
    private fun procesar(evento: JSONObject): Boolean {
        when (evento.optString("tipo")) {
            "llave" -> evento.optString("clave").takeIf { it.isNotBlank() }?.let { llaveDelEquipo(it) }

            "mensaje" -> {
                val k = clave ?: return true
                val claro = runCatching {
                    CifradoChat.descifrar(
                        k, CifradoChat.LADO_ADMIN,
                        deB64(evento.getString("iv")), deB64(evento.getString("cifrado"))
                    )
                }.getOrNull()
                if (claro == null) {
                    nota("Llegó un mensaje que no se pudo leer y se descartó.")
                    return true
                }
                val m = runCatching { JSONObject(String(claro, Charsets.UTF_8)) }.getOrNull() ?: return true
                val n = m.optLong("n", -1)
                val texto = m.optString("t")
                // Un contador que no sube es un mensaje repetido: alguien lo reenvió.
                if (n <= ultimoRecibido || texto.isEmpty()) return true
                ultimoRecibido = n
                agregar(MensajeChat(siguienteId++, texto, Instant.now()))
            }

            "cerrada" -> {
                terminar(evento.optString("motivo").ifBlank { "La conversación terminó." })
                return false
            }
        }
        return true
    }

    private fun llaveDelEquipo(claveBase64: String) {
        val propio = par ?: return
        val mia = miPublica ?: return
        try {
            val delEquipo = deB64(claveBase64)
            clave = CifradoChat.clave(propio, delEquipo, delEquipo, mia)
            val codigo = CifradoChat.codigo(delEquipo, mia)
            val antes = _estado.value.codigo
            ultimoRecibido = 0
            nota(
                if (antes != null && antes != codigo) "El equipo volvió a entrar. El código de seguridad cambió."
                else "El equipo está en el chat."
            )
            _estado.update { it.copy(fase = FaseChat.CONECTADO, codigo = codigo) }
        } catch (e: Exception) {
            Log.w(TAG, "Llave del equipo no válida", e)
            clave = null
            nota("No se pudo preparar el cifrado. Sal del chat y vuelve a entrar.")
        }
    }

    private fun terminar(motivo: String) {
        conexion = null
        olvidar()
        nota(motivo)
        _estado.update { it.copy(fase = FaseChat.TERMINADO, codigo = null, enviando = false) }
    }

    private fun nota(texto: String) = agregar(MensajeChat(siguienteId++, texto, Instant.now(), nota = true))

    private fun agregar(m: MensajeChat) = _estado.update { it.copy(mensajes = it.mensajes + m) }

    /** Las llaves y la clave fuera de la memoria. */
    private fun olvidar() {
        par = null
        miPublica = null
        clave = null
        enviados = 0
        ultimoRecibido = 0
    }

    /**
     * Le dice al servidor que se va, para que el equipo lo sepa en el acto y
     * no a los dos minutos. Va aparte del ViewModel, que puede estar cerrándose.
     */
    private fun despedirse() {
        val c = conexion ?: return
        conexion = null
        aparte.launch { runCatching { ApiRelay.salirDelChat(c) } }
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun deB64(texto: String) = Base64.decode(texto, Base64.NO_WRAP)

    companion object {
        private const val TAG = "Chat"
        const val MAX_TEXTO = 2000
        private val aparte = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
