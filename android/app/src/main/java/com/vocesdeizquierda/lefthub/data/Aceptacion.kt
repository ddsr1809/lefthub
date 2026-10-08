package com.vocesdeizquierda.lefthub.data

import android.content.Context

/**
 * Si la persona ya aceptó la política de privacidad y las condiciones.
 *
 * Se guarda en el teléfono y no en el servidor a propósito: la pregunta se
 * hace antes de crear la cuenta, cuando todavía no hay a quién apuntárselo.
 *
 * VERSION es la fecha de los documentos publicados en vocesdeizquierda.com.
 * Si cambian de forma importante, pon aquí la fecha nueva y la app volverá a
 * pedir la aceptación a todo el mundo.
 */
object Aceptacion {

    const val VERSION = "2026-10-08"

    private const val PREFS = "relay_legal"
    private const val CLAVE_VERSION = "version_aceptada"

    fun vigente(contexto: Context): Boolean =
        prefs(contexto).getString(CLAVE_VERSION, null) == VERSION

    fun registrar(contexto: Context) {
        prefs(contexto).edit().putString(CLAVE_VERSION, VERSION).apply()
    }

    private fun prefs(contexto: Context) =
        contexto.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
