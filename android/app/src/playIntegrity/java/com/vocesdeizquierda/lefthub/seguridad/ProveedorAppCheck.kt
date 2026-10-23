package com.vocesdeizquierda.lefthub.seguridad

import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/**
 * App Check con Play Integrity, para pruebas y produccion (ver los sourceSets
 * en build.gradle.kts).
 *
 * Google confirma que la app es la firmada para Google Play, instalada desde
 * Play, en un teléfono Android de verdad, y Firebase entrega un token que el
 * servidor comprueba (VerificadorAppCheck). Un teléfono sin servicios de
 * Google (algunos Huawei), con root, o una app instalada fuera de Play, no
 * consigue token: el servidor lo cuenta, y solo lo deja fuera si en el panel
 * se exige App Check.
 */
object ProveedorAppCheck {
    fun instalar() {
        runCatching {
            FirebaseAppCheck.getInstance().apply {
                installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
                setTokenAutoRefreshEnabled(true)
                getAppCheckToken(false)   // el primero, ya mismo: la primera petición no espera
            }
        }.onFailure { Log.w("ProveedorAppCheck", "App Check no arrancó: ${it.message}") }
    }
}
