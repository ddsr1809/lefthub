plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.tuempresa.vocesleft"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tuempresa.vocesleft"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // El client_id de tipo 3 ("web") dentro de google-services.json.
        // Credential Manager lo necesita para que el servidor pueda validar el
        // token; con el ID de Android puesto aqui, el login falla en silencio.
        //
        // OJO: si pruebas vive en OTRO proyecto de Firebase, este valor
        // cambia por sabor. En ese caso borra esta linea y pon un
        // buildConfigField("String", "WEB_CLIENT_ID", ...) dentro de cada uno.
        buildConfigField(
            "String",
            "WEB_CLIENT_ID",
            "\"389825726990-b6ubrv9f9fv2n2rn2r9dcbho9dnmdv8c.apps.googleusercontent.com\""
        )

        // API_BASE NO se define aqui a proposito. Vive solo en los sabores, de
        // modo que sea imposible compilar una variante sin decidir contra que
        // servidor habla. Un valor por defecto aqui seria justo el que se
        // cuela en produccion sin que nadie lo note.
    }

    // -------------------------------------------------------------------------
    // Ambientes
    // -------------------------------------------------------------------------
    // Solo hay dos sabores, uno por cada servidor del VPS:
    //
    //   pruebas -> https://testapp.vocesdeizquierda.com   (com.tuempresa.vocesleft.pruebas)
    //   prod    -> https://leftapp.vocesdeizquierda.com   (com.tuempresa.vocesleft)
    //
    // Cada sabor instala una app distinta en el telefono, asi que puedes tener
    // las dos a la vez. Ambos hablan con el servidor por HTTPS: ya no existe
    // un sabor que permita trafico en claro hacia un servidor local.
    //
    // Los dos applicationId tienen que estar registrados en Firebase, y cada
    // carpeta src/<sabor>/ necesita su google-services.json con ese paquete.
    //
    // No se puede llamar "test" a un sabor: el AGP reserva ese prefijo para los
    // conjuntos de fuentes de pruebas unitarias y la sincronizacion falla.
    flavorDimensions += "ambiente"

    productFlavors {
        create("pruebas") {
            dimension = "ambiente"
            applicationIdSuffix = ".pruebas"
            versionNameSuffix = "-pruebas"

            // Tiene que coincidir con RELAY_URL_PUBLICA de .env.test en el VPS.
            buildConfigField("String", "API_BASE", "\"https://testapp.vocesdeizquierda.com\"")
            buildConfigField("boolean", "SUSCRIPCIONES_YOUTUBE", "true")
        }

        create("prod") {
            dimension = "ambiente"
            // Sin sufijo: este es el applicationId de verdad, el que va a Play.
            // Tiene que coincidir con RELAY_URL_PUBLICA de .env.prod en el VPS.
            buildConfigField("String", "API_BASE", "\"https://leftapp.vocesdeizquierda.com\"")

            // "¿Estoy suscrito en YouTube?" pide el permiso youtube.readonly,
            // que Google trata como sensible. Mientras la verificación de
            // OAuth del proyecto no esté aprobada, solo 100 personas pueden
            // darlo EN TODA LA VIDA del proyecto, y al agotarse Google
            // desactiva también el inicio de sesión. Ese cupo no se recupera.
            // Por eso en producción sale apagado: cámbialo a "true" el día que
            // Google apruebe la verificación, no antes.
            buildConfigField("boolean", "SUSCRIPCIONES_YOUTUBE", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            // Ya NO lleva applicationIdSuffix = ".debug".
            // El sufijo lo pone el sabor. Si ambos pusieran el suyo saldrian
            // cuatro paquetes distintos (...pruebas.debug, ...debug, etc.) y
            // habria que registrar los cuatro en Firebase.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    buildToolsVersion = "34.0.0"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    // Solo mensajeria. El directorio, las sesiones y los favoritos viven
    // ahora en el servidor propio.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.play.services)
}
kotlin {
    jvmToolchain(17)
}

tasks.register("prepareKotlinBuildScriptModel") {
    // Dummy task to satisfy Android Studio
}