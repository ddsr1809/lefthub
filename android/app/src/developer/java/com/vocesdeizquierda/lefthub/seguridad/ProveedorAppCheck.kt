package com.vocesdeizquierda.lefthub.seguridad

import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * App Check en el sabor developer: el proveedor de depuración.
 *
 * Play Integrity solo responde por una app instalada desde Google Play. En tu
 * teléfono o en el emulador se usa este otro: al arrancar escribe en Logcat
 * una línea "Enter this debug secret into the allow list in the Firebase
 * Console". Ese secreto se registra en Firebase (App Check > Apps > la app
 * .developer > Administrar tokens de depuración) y desde entonces este
 * teléfono saca tokens válidos. Sin registrarlo, el servidor los ve malos;
 * en avisar no pasa nada.
 *
 * pruebas y produccion usan Play Integrity: src/playIntegrity/.
 */
object ProveedorAppCheck {
    fun instalar() {
        runCatching {
            FirebaseAppCheck.getInstance().apply {
                installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
                setTokenAutoRefreshEnabled(true)
                getAppCheckToken(false)   // el primero, ya mismo: la primera petición no espera
            }
        }.onFailure { Log.w("ProveedorAppCheck", "App Check no arrancó: ${it.message}") }
    }
}
