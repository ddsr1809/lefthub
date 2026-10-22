package com.vocesdeizquierda.lefthub.stickers

import android.content.Context
import com.vocesdeizquierda.lefthub.data.PaqueteDeStickers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Los stickers bajados, en la carpeta privada de la app.
 *
 * No van dentro del APK: se bajan del servidor la primera vez que la persona
 * abre los de un creador y se quedan aquí, en files/stickers/<paquete>/,
 * mientras la app esté instalada. Android borra esta carpeta al
 * desinstalarla, y con ella los stickers.
 *
 * Cada paquete lleva su ficha (paquete.json) con lo que necesita WhatsApp
 * para leerlo sin preguntar al servidor: ver [ProveedorDeStickers]. Un
 * paquete que el equipo despublica no se borra de aquí: quien ya lo agregó a
 * WhatsApp lo sigue teniendo.
 */
object AlmacenDeStickers {

    private const val CARPETA = "stickers"
    private const val FICHA = "paquete.json"
    private const val BAJANDO = ".bajando"

    /**
     * Los nombres que vienen del servidor (ids) o de WhatsApp (nombres de
     * archivo) se usan como nombres de archivo: solo letras, números, punto,
     * guion y guion bajo, para que nadie pueda salirse de la carpeta.
     */
    private val NOMBRE_SEGURO = Regex("[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}")

    /** Que dos pantallas no bajen el mismo paquete a la vez. */
    private val cerrojo = Mutex()

    data class StickerGuardado(val archivo: String, val emojis: List<String>)

    data class PaqueteGuardado(
        val id: String,
        val creador: String,
        val nombre: String,
        val animado: Boolean,
        val version: Int,
        val icono: String,
        val stickers: List<StickerGuardado>
    )

    fun seguro(nombre: String?): Boolean = nombre != null && NOMBRE_SEGURO.matches(nombre) && nombre != "." && nombre != ".."

    private fun raiz(c: Context) = File(c.filesDir, CARPETA)

    private fun carpetaDe(c: Context, id: String): File {
        require(seguro(id)) { "Id de paquete no válido" }
        return File(raiz(c), id)
    }

    /** Los paquetes bajados, completos. Lo que lee WhatsApp. */
    fun guardados(c: Context): List<PaqueteGuardado> =
        raiz(c).listFiles()
            ?.filter { it.isDirectory && !it.name.endsWith(BAJANDO) && seguro(it.name) }
            ?.mapNotNull { leer(it) }
            ?.sortedBy { it.creador.lowercase() }
            .orEmpty()

    fun guardado(c: Context, id: String): PaqueteGuardado? =
        if (seguro(id)) leer(carpetaDe(c, id)) else null

    /** Un archivo de un paquete bajado, o null si no está o el nombre no es válido. */
    fun archivo(c: Context, paqueteId: String?, nombre: String?): File? {
        if (!seguro(paqueteId) || !seguro(nombre) || nombre == FICHA) return null
        return File(carpetaDe(c, paqueteId!!), nombre!!).takeIf { it.isFile }
    }

    /**
     * Dónde queda la imagen de un sticker, sin mirar el disco: la pantalla
     * solo la enseña cuando el paquete ya está bajado. Null si el id no vale.
     */
    fun imagen(c: Context, paqueteId: String, stickerId: String): File? =
        if (seguro(paqueteId) && seguro(stickerId)) File(carpetaDe(c, paqueteId), nombreDeSticker(stickerId)) else null

    /** Está bajado y es de la misma versión que el del servidor. */
    fun alDia(c: Context, p: PaqueteDeStickers): Boolean =
        guardado(c, p.id)?.let { it.version == p.version && completo(c, it) } == true

    /**
     * Baja el paquete si no está, o si el servidor tiene una versión más
     * nueva. Se baja aparte y solo al terminar sustituye al anterior: si se
     * corta a medias, el que había sigue sirviendo.
     *
     * [bajar] trae los bytes de una dirección del servidor; [alAvanzar] dice
     * cuántos archivos van de cuántos.
     */
    suspend fun asegurar(
        c: Context,
        p: PaqueteDeStickers,
        bajar: suspend (String) -> ByteArray,
        alAvanzar: (Int, Int) -> Unit = { _, _ -> }
    ) = cerrojo.withLock {
        withContext(Dispatchers.IO) {
            if (alDia(c, p)) return@withContext
            if (!seguro(p.id) || p.stickers.any { !seguro(it.id) }) throw IOException("El servidor mandó un paquete no válido.")

            val temporal = File(raiz(c), p.id + BAJANDO)
            temporal.deleteRecursively()
            if (!temporal.mkdirs()) throw IOException("No hay espacio para guardar los stickers.")

            try {
                val total = p.stickers.size + 1
                val icono = bajar(p.icono)
                val nombreIcono = if (esPng(icono)) "icono.png" else "icono.webp"
                File(temporal, nombreIcono).writeBytes(icono)
                alAvanzar(1, total)

                p.stickers.forEachIndexed { i, s ->
                    File(temporal, nombreDeSticker(s.id)).writeBytes(bajar(s.imagen))
                    alAvanzar(i + 2, total)
                }

                File(temporal, FICHA).writeText(ficha(p, nombreIcono).toString())

                val destino = carpetaDe(c, p.id)
                destino.deleteRecursively()
                if (!temporal.renameTo(destino)) throw IOException("No se pudieron guardar los stickers.")
            } catch (e: Exception) {
                temporal.deleteRecursively()
                throw e
            }
        }
    }

    private fun nombreDeSticker(id: String) = "$id.webp"

    private fun completo(c: Context, p: PaqueteGuardado): Boolean =
        archivo(c, p.id, p.icono) != null && p.stickers.all { archivo(c, p.id, it.archivo) != null }

    private fun ficha(p: PaqueteDeStickers, icono: String) = JSONObject()
        .put("id", p.id)
        .put("creador", p.creador)
        .put("nombre", p.nombre)
        .put("animado", p.animado)
        .put("version", p.version)
        .put("icono", icono)
        .put("stickers", JSONArray().apply {
            p.stickers.forEach { s ->
                put(JSONObject().put("archivo", nombreDeSticker(s.id)).put("emojis", JSONArray(s.emojis)))
            }
        })

    private fun leer(carpeta: File): PaqueteGuardado? = runCatching {
        val json = JSONObject(File(carpeta, FICHA).readText())
        val stickers = json.getJSONArray("stickers")
        PaqueteGuardado(
            id = json.getString("id"),
            creador = json.optString("creador", ""),
            nombre = json.getString("nombre"),
            animado = json.optBoolean("animado", false),
            version = json.optInt("version", 1),
            icono = json.getString("icono"),
            stickers = (0 until stickers.length()).map { i ->
                val s = stickers.getJSONObject(i)
                val emojis = s.optJSONArray("emojis")
                StickerGuardado(
                    archivo = s.getString("archivo"),
                    emojis = if (emojis == null) emptyList() else (0 until emojis.length()).map { emojis.getString(it) }
                )
            }
        )
    }.getOrNull()?.takeIf { it.id == carpeta.name }

    private fun esPng(b: ByteArray) = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() &&
        b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
}
