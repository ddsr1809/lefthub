package com.vocesdeizquierda.lefthub.atajos

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.vocesdeizquierda.lefthub.EstadoApp
import com.vocesdeizquierda.lefthub.MainActivity
import com.vocesdeizquierda.lefthub.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * El menú que sale al dejar el dedo sobre el ícono de la app, como el de
 * WhatsApp: a quién sigue la persona, el que publicó más reciente primero, y
 * «Escribir al equipo» si tiene el chat. Tocar a alguien abre su ficha; tocar
 * el chat abre una ventanita para escribir sin entrar a la app.
 *
 * Cada uno se puede arrastrar a la pantalla de inicio y queda ahí como un
 * ícono más (eso lo hace el teléfono, no la app).
 *
 * Se arman con lo que la app ya tiene en memoria: el perfil (a quién sigue),
 * el directorio (nombres y fotos) y cuándo salió el último video de cada
 * canal. MainActivity los vuelve a publicar cuando algo de eso cambia; si lo
 * que saldría es lo mismo que ya está, no se toca nada.
 */
object Atajos {

    private const val TAG = "Atajos"

    /**
     * Cuántos caben. Android admite más, pero la mayoría de los teléfonos
     * enseña cuatro y corta el resto sin avisar.
     */
    const val MAXIMO = 4

    /** La ruta de la ficha que abre el atajo: "creador/<id>", "productora/<id>" o "canal/<id>". */
    const val EXTRA_RUTA = "atajo_ruta"

    /** El id del atajo que se tocó, para contárselo al sistema. */
    const val EXTRA_ID = "atajo_id"

    const val ID_CHAT = "chat"

    private val RUTA_VALIDA = Regex("^(creador|productora|canal)/[A-Za-z0-9_-]{1,64}$")

    /**
     * MainActivity está abierta al resto de apps (es la del lanzador), así
     * que cualquiera podría mandarle una ruta. Solo se acepta la de una ficha.
     */
    fun rutaValida(ruta: String?): Boolean = ruta != null && RUTA_VALIDA.matches(ruta)

    /** Alguien que la persona sigue, como sale en el menú. */
    data class Seguido(
        /** El id del atajo: único por tipo y por id. */
        val id: String,
        val ruta: String,
        val nombre: String,
        val foto: String?,
        /** Cuándo publicó por última vez. Null si no se sabe. */
        val ultimo: Instant?
    )

    /**
     * A quién sigue, el que publicó más reciente primero, hasta [cupo].
     *
     * Entran los creadores, los medios y los canales sueltos. Un canal suelto
     * cuyo dueño ya está en la lista (porque también lo sigue) no se repite.
     */
    fun elegir(estado: EstadoApp, cupo: Int): List<Seguido> {
        if (cupo <= 0) return emptyList()
        val perfil = estado.perfil
        val ultimos = estado.ultimosVideos

        fun masReciente(vararg fechas: Instant?): Instant? = fechas.filterNotNull().maxOrNull()

        val lista = mutableListOf<Seguido>()

        for (id in perfil.favoritos) {
            val creador = estado.creadores.firstOrNull { it.id == id } ?: continue
            val porCanal = creador.canales.mapNotNull { ultimos[it.id] }.maxOrNull()
            val porNovedades = estado.publicaciones
                .filter { it.creatorId == id }
                .mapNotNull { it.publishedAt }
                .maxOrNull()
            lista += Seguido(
                id = "seguido_creador_$id",
                ruta = "creador/$id",
                nombre = creador.name,
                foto = creador.photoUrl,
                ultimo = masReciente(porCanal, porNovedades)
            )
        }

        for (id in perfil.productoras) {
            val medio = estado.productoras.firstOrNull { it.id == id } ?: continue
            val porCanal = medio.canales.mapNotNull { ultimos[it.id] }.maxOrNull()
            val porNovedades = estado.publicaciones
                .filter { it.productoraId == id }
                .mapNotNull { it.publishedAt }
                .maxOrNull()
            lista += Seguido(
                id = "seguido_productora_$id",
                ruta = "productora/$id",
                nombre = medio.nombre,
                foto = medio.logoUrl,
                ultimo = masReciente(porCanal, porNovedades)
            )
        }

        for (id in perfil.canales) {
            val canal = estado.canal(id) ?: continue
            // Ya sale su dueño: con ese basta.
            if (perfil.loRecibePorOtros(canal)) continue
            val dueno = canal.creadorId?.let { c -> estado.creadores.firstOrNull { it.id == c } }
            val medio = canal.productoraId?.let { p -> estado.productoras.firstOrNull { it.id == p } }
            val nombre = canal.nombreVisible ?: dueno?.name ?: medio?.nombre ?: continue
            lista += Seguido(
                id = "seguido_canal_$id",
                ruta = "canal/$id",
                nombre = nombre,
                foto = dueno?.photoUrl ?: medio?.logoUrl,
                ultimo = masReciente(
                    ultimos[id],
                    estado.publicaciones.filter { it.canalId == id }.mapNotNull { it.publishedAt }.maxOrNull()
                )
            )
        }

        return lista
            .filter { it.nombre.isNotBlank() }
            .sortedWith(
                compareByDescending<Seguido, Instant?>(nullsFirst<Instant>()) { it.ultimo }
                    .thenBy { it.nombre.lowercase() }
            )
            .take(cupo)
    }

    // Lo último que se publicó, para no volver a bajar fotos y molestar al
    // sistema si no cambió nada. Solo vive mientras vive el proceso.
    @Volatile
    private var publicado: String? = null

    /**
     * Deja el menú del ícono con [seguidos] y, si [conChat], «Escribir al
     * equipo» arriba de todo. Con la lista vacía y sin chat, el menú queda
     * vacío (por ejemplo, al cerrar sesión).
     */
    suspend fun publicar(contexto: Context, seguidos: List<Seguido>, conChat: Boolean): Unit =
        withContext(Dispatchers.IO) {
            val firma = buildString {
                append(conChat)
                seguidos.forEach { append('|').append(it.id).append(it.nombre).append(it.foto) }
            }
            if (firma == publicado) return@withContext

            try {
                val atajos = mutableListOf<ShortcutInfoCompat>()
                if (conChat) atajos += atajoDelChat(contexto)
                seguidos.forEachIndexed { i, s ->
                    atajos += ShortcutInfoCompat.Builder(contexto, s.id)
                        .setShortLabel(s.nombre)
                        .setLongLabel(s.nombre)
                        .setIcon(icono(contexto, s))
                        .setIntent(
                            Intent(contexto, MainActivity::class.java)
                                .setAction(Intent.ACTION_VIEW)
                                .putExtra(EXTRA_RUTA, s.ruta)
                                .putExtra(EXTRA_ID, s.id)
                        )
                        // El chat va primero; luego, el que publicó más reciente.
                        .setRank(i + 1)
                        .build()
                }
                // Cambia los del menú y, si alguno estaba en la pantalla de
                // inicio, también su nombre y su foto.
                ShortcutManagerCompat.setDynamicShortcuts(contexto, atajos)
                publicado = firma
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // El sistema limita cuántas veces por minuto se cambian; si
                // dice que no, queda como estaba y se intenta a la siguiente.
                Log.w(TAG, "No se pudo poner el menú del ícono", e)
            }
        }

    /** Se tocó un atajo: el sistema lo pone más arriba la próxima vez. */
    fun usado(contexto: Context, id: String) {
        runCatching { ShortcutManagerCompat.reportShortcutUsed(contexto, id) }
    }

    private fun atajoDelChat(contexto: Context): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(contexto, ID_CHAT)
            .setShortLabel("Escribir al equipo")
            .setLongLabel("Escribir al equipo de Voces")
            .setIcon(IconCompat.createWithResource(contexto, R.drawable.ic_atajo_chat_icono))
            .setIntent(
                Intent(contexto, EscribirAlEquipoActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
            )
            .setRank(0)
            .build()

    // -------------------------------------------------------------------------
    // Íconos
    // -------------------------------------------------------------------------

    /**
     * La foto del creador (o el logo del medio) como ícono adaptable: el
     * teléfono le pone la forma que use en todos sus íconos, casi siempre un
     * círculo. Sin foto, o si no se pudo bajar, sus iniciales.
     */
    private suspend fun icono(contexto: Context, s: Seguido): IconCompat {
        val lado = ladoEnPixeles(contexto)
        val foto = s.foto?.let { bajarFoto(contexto, it, lado) }
        return IconCompat.createWithAdaptiveBitmap(
            if (foto != null) enAdaptable(foto, lado) else iniciales(s.nombre, lado)
        )
    }

    /** 108dp: el lienzo completo de un ícono adaptable. */
    private fun ladoEnPixeles(contexto: Context): Int =
        (108 * contexto.resources.displayMetrics.density).toInt().coerceIn(108, 432)

    private suspend fun bajarFoto(contexto: Context, url: String, lado: Int): Bitmap? = try {
        val resultado = contexto.imageLoader.execute(
            ImageRequest.Builder(contexto)
                .data(url)
                .size(lado)
                // Hace falta un Bitmap normal para dibujarlo encima de otro.
                .allowHardware(false)
                .build()
        )
        (resultado as? SuccessResult)?.drawable?.toBitmap()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * De un ícono adaptable solo se ve el centro (unos 72 de sus 108dp). La
     * foto se dibuja entera en ese centro, para que no se le corte la cara, y
     * la misma foto, más grande, rellena lo de alrededor.
     */
    private fun enAdaptable(foto: Bitmap, lado: Int): Bitmap {
        val salida = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val lienzo = Canvas(salida)
        val pincel = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val origen = cuadradoCentral(foto)
        lienzo.drawBitmap(foto, origen, Rect(0, 0, lado, lado), pincel)
        val centro = lado * 76f / 108f
        val margen = (lado - centro) / 2f
        lienzo.drawBitmap(foto, origen, RectF(margen, margen, margen + centro, margen + centro), pincel)
        return salida
    }

    private fun cuadradoCentral(foto: Bitmap): Rect {
        val lado = minOf(foto.width, foto.height)
        val x = (foto.width - lado) / 2
        val y = (foto.height - lado) / 2
        return Rect(x, y, x + lado, y + lado)
    }

    /** Las iniciales en ámbar, el color de la app. */
    private fun iniciales(nombre: String, lado: Int): Bitmap {
        val salida = Bitmap.createBitmap(lado, lado, Bitmap.Config.ARGB_8888)
        val lienzo = Canvas(salida)
        lienzo.drawColor(Color.parseColor("#FFB020"))
        val letras = nombre.trim().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .take(2)
            .joinToString("") { it.first().uppercase() }
            .ifEmpty { "?" }
        val pincel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#241A05")
            textAlign = Paint.Align.CENTER
            textSize = lado * 0.30f
            isFakeBoldText = true
        }
        val y = lado / 2f - (pincel.descent() + pincel.ascent()) / 2f
        lienzo.drawText(letras, lado / 2f, y, pincel)
        return salida
    }
}
