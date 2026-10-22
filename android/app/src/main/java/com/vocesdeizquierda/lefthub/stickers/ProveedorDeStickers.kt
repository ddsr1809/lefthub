package com.vocesdeizquierda.lefthub.stickers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.vocesdeizquierda.lefthub.BuildConfig
import java.io.FileNotFoundException

/**
 * Por aquí lee WhatsApp los paquetes que se le agregan desde la app.
 *
 * Es el contrato que publica WhatsApp para las apps de stickers
 * (github.com/WhatsApp/stickers): los nombres de las rutas y de las columnas
 * tienen que ser letra por letra esos. WhatsApp pide la lista de paquetes,
 * los stickers de uno y cada imagen; todo sale de lo ya bajado en
 * [AlmacenDeStickers], sin red.
 *
 * Solo WhatsApp puede leerlo: el manifiesto pide el permiso
 * com.whatsapp.sticker.READ, que solo tiene WhatsApp.
 */
class ProveedorDeStickers : ContentProvider() {

    private lateinit var rutas: UriMatcher

    override fun onCreate(): Boolean {
        val autoridad = autoridad()
        rutas = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(autoridad, METADATA, TODOS)
            addURI(autoridad, "$METADATA/*", UNO)
            addURI(autoridad, "$STICKERS/*", DE_UN_PAQUETE)
            addURI(autoridad, "$STICKERS_ASSET/*/*", ARCHIVO)
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val c = context ?: throw IllegalStateException("Sin contexto")
        val cursor = when (rutas.match(uri)) {
            TODOS -> metadatos(c, AlmacenDeStickers.guardados(c))
            UNO -> metadatos(c, listOfNotNull(uri.lastPathSegment?.let { AlmacenDeStickers.guardado(c, it) }))
            DE_UN_PAQUETE -> stickers(uri.lastPathSegment?.let { AlmacenDeStickers.guardado(c, it) })
            else -> throw IllegalArgumentException("Dirección desconocida: $uri")
        }
        cursor.setNotificationUri(c.contentResolver, uri)
        return cursor
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor {
        if (rutas.match(uri) != ARCHIVO) throw FileNotFoundException("Dirección desconocida: $uri")
        val partes = uri.pathSegments
        val archivo = AlmacenDeStickers.archivo(context ?: throw FileNotFoundException("Sin contexto"),
            partes.getOrNull(1), partes.getOrNull(2))
            ?: throw FileNotFoundException("No está: $uri")
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(archivo, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            AssetFileDescriptor.UNKNOWN_LENGTH
        )
    }

    override fun getType(uri: Uri): String? {
        val autoridad = autoridad()
        return when (rutas.match(uri)) {
            TODOS -> "vnd.android.cursor.dir/vnd.$autoridad.$METADATA"
            UNO -> "vnd.android.cursor.item/vnd.$autoridad.$METADATA"
            DE_UN_PAQUETE -> "vnd.android.cursor.dir/vnd.$autoridad.$STICKERS"
            ARCHIVO -> if (uri.lastPathSegment?.endsWith(".png") == true) "image/png" else "image/webp"
            else -> null
        }
    }

    private fun metadatos(c: Context, paquetes: List<AlmacenDeStickers.PaqueteGuardado>): MatrixCursor {
        val cursor = MatrixCursor(
            arrayOf(
                PACK_ID, PACK_NAME, PACK_PUBLISHER, PACK_ICON, ANDROID_LINK, IOS_LINK,
                PUBLISHER_EMAIL, PUBLISHER_WEBSITE, PRIVACY_POLICY, LICENSE, IMAGE_DATA_VERSION,
                AVOID_CACHE, ANIMATED
            )
        )
        val tienda = "https://play.google.com/store/apps/details?id=" + c.packageName
        paquetes.forEach { p ->
            cursor.addRow(
                arrayOf<Any>(
                    p.id,
                    p.nombre.take(128),
                    p.creador.ifBlank { "Voces de Izquierda" }.take(128),
                    p.icono,
                    tienda,
                    "",
                    "",
                    "https://vocesdeizquierda.com",
                    "",
                    "",
                    p.version.toString(),
                    0,
                    if (p.animado) 1 else 0
                )
            )
        }
        return cursor
    }

    private fun stickers(p: AlmacenDeStickers.PaqueteGuardado?): MatrixCursor {
        val cursor = MatrixCursor(arrayOf(STICKER_FILE, STICKER_EMOJI, STICKER_TEXT))
        p?.stickers?.forEach { s ->
            cursor.addRow(arrayOf<Any>(s.archivo, s.emojis.joinToString(","), "Sticker de ${p.creador}"))
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        /** Tiene que coincidir con android:authorities del manifiesto. */
        fun autoridad() = BuildConfig.APPLICATION_ID + ".stickers"

        private const val METADATA = "metadata"
        private const val STICKERS = "stickers"
        private const val STICKERS_ASSET = "stickers_asset"

        private const val TODOS = 1
        private const val UNO = 2
        private const val DE_UN_PAQUETE = 3
        private const val ARCHIVO = 4

        // Las columnas, con los nombres que espera WhatsApp.
        private const val PACK_ID = "sticker_pack_identifier"
        private const val PACK_NAME = "sticker_pack_name"
        private const val PACK_PUBLISHER = "sticker_pack_publisher"
        private const val PACK_ICON = "sticker_pack_icon"
        private const val ANDROID_LINK = "android_play_store_link"
        private const val IOS_LINK = "ios_app_download_link"
        private const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        private const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        private const val PRIVACY_POLICY = "sticker_pack_privacy_policy"
        private const val LICENSE = "sticker_pack_license_agreement"
        private const val IMAGE_DATA_VERSION = "image_data_version"
        private const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        private const val ANIMATED = "animated_sticker_pack"

        private const val STICKER_FILE = "sticker_file_name"
        private const val STICKER_EMOJI = "sticker_emoji"
        private const val STICKER_TEXT = "sticker_accessibility_text"
    }
}
