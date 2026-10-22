package com.vocesdeizquierda.lefthub.stickers

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.vocesdeizquierda.lefthub.BuildConfig
import java.io.File

/**
 * Mandar los stickers a otras apps.
 *
 * WhatsApp es la única que deja agregar un paquete entero desde otra app: se
 * le pasa el id del paquete y él lo lee por [ProveedorDeStickers]. Telegram,
 * Signal, Messenger y las demás no tienen nada así para apps de terceros, así
 * que a esas se les manda el sticker suelto, como imagen WebP; Telegram y
 * Signal lo enseñan como sticker.
 */
object Compartir {

    const val WHATSAPP = "com.whatsapp"
    const val WHATSAPP_NEGOCIOS = "com.whatsapp.w4b"

    private const val AGREGAR_PAQUETE = "com.whatsapp.intent.action.ENABLE_STICKER_PACK"

    /** Lo que dice WhatsApp cuando no acepta el paquete. */
    const val ERROR_DE_WHATSAPP = "validation_error"

    /** Los WhatsApp instalados: el normal, el de negocios o los dos. */
    fun whatsappsInstalados(c: Context): List<String> =
        listOf(WHATSAPP, WHATSAPP_NEGOCIOS).filter { instalado(c, it) }

    private fun instalado(c: Context, paquete: String): Boolean = try {
        @Suppress("DEPRECATION")
        c.packageManager.getPackageInfo(paquete, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * Lo que abre la pantalla de WhatsApp de "Agregar a WhatsApp". Con los
     * dos WhatsApp instalados y sin decir cuál, Android pregunta.
     */
    fun agregarAWhatsApp(paqueteId: String, nombre: String, whatsapp: String?): Intent =
        Intent(AGREGAR_PAQUETE).apply {
            putExtra("sticker_pack_id", paqueteId)
            putExtra("sticker_pack_authority", ProveedorDeStickers.autoridad())
            putExtra("sticker_pack_name", nombre)
            if (whatsapp != null) setPackage(whatsapp)
        }

    /**
     * Si el paquete ya está en ese WhatsApp. Lo contesta el propio WhatsApp;
     * si no contesta (versión vieja, o no deja), se da por que no.
     */
    fun yaEstaEnWhatsApp(c: Context, paqueteId: String, whatsapp: String): Boolean = runCatching {
        val consulta = Uri.Builder().scheme("content")
            .authority("$whatsapp.provider.sticker_whitelist_check")
            .appendPath("is_whitelisted")
            .appendQueryParameter("authority", ProveedorDeStickers.autoridad())
            .appendQueryParameter("identifier", paqueteId)
            .build()
        c.contentResolver.query(consulta, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow("result")) == 1
        } ?: false
    }.getOrDefault(false)

    /**
     * El selector de Android para mandar un sticker suelto: ahí salen
     * Telegram, Signal, Messenger, WhatsApp y las demás que acepten imágenes.
     */
    fun mandarSticker(c: Context, archivo: File): Intent {
        val uri = FileProvider.getUriForFile(c, autoridadDeArchivos(), archivo)
        val envio = Intent(Intent.ACTION_SEND).apply {
            type = "image/webp"
            putExtra(Intent.EXTRA_STREAM, uri)
            // Sin esto, algunas apps no ven la vista previa en el selector.
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(envio, "Mandar el sticker con…")
    }

    /** Tiene que coincidir con el FileProvider del manifiesto. */
    private fun autoridadDeArchivos() = BuildConfig.APPLICATION_ID + ".archivos"
}
