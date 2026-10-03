import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

// -----------------------------------------------------------------------------
// Llave de firma de producción
// -----------------------------------------------------------------------------
// Vive fuera de git, en android/firma.properties (ver firma.properties.example
// y la sección "Firmar para Google Play" de COMO-ABRIR.md). Sin ese archivo la
// variante de producción se firma con la llave de depuración, como antes: sirve
// para probar en tu teléfono, pero Google Play la rechaza.
val firma = Properties().apply {
    val archivo = rootProject.file("firma.properties")
    if (archivo.exists()) archivo.inputStream().use { load(it) }
}
val hayFirma = !firma.getProperty("archivo").isNullOrBlank()

android {
    namespace = "com.vocesdeizquierda.lefthub"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vocesdeizquierda.lefthub"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // El client_id de tipo 3 ("web") dentro de google-services.json.
        // Credential Manager lo necesita para que el servidor pueda validar el
        // token; con el ID de Android puesto aqui, el login falla en silencio.
        //
        // OJO: si developer o pruebas viven en OTRO proyecto de Firebase, este valor
        // cambia por sabor. En ese caso borra esta linea y pon un
        // buildConfigField("String", "WEB_CLIENT_ID", ...) dentro de cada uno.
        buildConfigField(
            "String",
            "WEB_CLIENT_ID",
            "\"445243795956-jso91c84gsepuukis0h5dr91r9a0t4eu.apps.googleusercontent.com\""
        )

        // API_BASE NO se define aqui a proposito. Vive solo en los sabores, de
        // modo que sea imposible compilar una variante sin decidir contra que
        // servidor habla. Un valor por defecto aqui seria justo el que se
        // cuela en produccion sin que nadie lo note.
    }

    // -------------------------------------------------------------------------
    // Ambientes
    // -------------------------------------------------------------------------
    // Tres sabores, uno por ambiente:
    //
    //   developer  -> servidor local en tu equipo          (...lefthub.developer)
    //   pruebas    -> https://testapp.vocesdeizquierda.com  (...lefthub.pruebas)
    //   produccion -> https://leftapp.vocesdeizquierda.com  (com.vocesdeizquierda.lefthub)
    //
    // "pruebas" es el ambiente de testing. No puede llamarse "testing": el AGP
    // prohibe los sabores cuyo nombre empieza por "test", porque reserva ese
    // prefijo para los conjuntos de fuentes de pruebas unitarias.
    //
    // Cada sabor instala una app distinta en el telefono, asi que puedes tener
    // las tres a la vez. Los tres applicationId tienen que estar registrados en
    // Firebase, y cada carpeta src/<sabor>/ necesita su google-services.json.
    //
    // Al final de este archivo, androidComponents deja una sola variante por
    // sabor: developerDebug, pruebasDebug y produccionRelease.
    flavorDimensions += "ambiente"

    productFlavors {
        create("developer") {
            dimension = "ambiente"
            applicationIdSuffix = ".developer"
            versionNameSuffix = "-developer"

            // En el telefono (o el emulador) localhost es el propio aparato.
            // ./local.sh mantiene un puente por USB (adb reverse) que lleva
            // ese puerto 8080 al servidor de tu computadora, asi que la misma
            // direccion sirve en un telefono fisico y en el emulador.
            // Es el unico sabor que permite HTTP sin cifrar (ver
            // src/developer/AndroidManifest.xml).
            buildConfigField("String", "API_BASE", "\"http://localhost:8080\"")
            buildConfigField("boolean", "SUSCRIPCIONES_YOUTUBE", "true")
        }

        create("pruebas") {
            dimension = "ambiente"
            applicationIdSuffix = ".pruebas"
            versionNameSuffix = "-pruebas"

            // Tiene que coincidir con RELAY_URL_PUBLICA de .env.test en el VPS.
            buildConfigField("String", "API_BASE", "\"https://testapp.vocesdeizquierda.com\"")
            buildConfigField("boolean", "SUSCRIPCIONES_YOUTUBE", "true")
        }

        create("produccion") {
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

    signingConfigs {
        if (hayFirma) {
            create("produccion") {
                // Ruta absoluta, o relativa a la carpeta android/.
                storeFile = rootProject.file(firma.getProperty("archivo"))
                storePassword = firma.getProperty("clave")
                keyAlias = firma.getProperty("alias")
                // keytool crea almacenes PKCS12, donde la llave y el almacén
                // comparten clave.
                keyPassword = firma.getProperty("clave")
            }
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
            signingConfig = signingConfigs.getByName(if (hayFirma) "produccion" else "debug")
        }
        debug {
            // Ya NO lleva applicationIdSuffix = ".debug".
            // El sufijo lo pone el sabor. Si ambos pusieran el suyo saldrian
            // mas paquetes distintos (...pruebas.debug, etc.) y habria que
            // registrarlos todos en Firebase.
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

// Una sola variante por sabor. Sin este filtro saldrian seis (cada sabor en
// debug y en release). developer y pruebas se compilan en debug, para poder
// depurarlas; produccion solo en release, que es lo que se publica.
androidComponents {
    beforeVariants { variante ->
        val tipoEsperado = if (variante.flavorName == "produccion") "release" else "debug"
        variante.enable = variante.buildType == tipoEsperado

        // No hay pruebas instrumentadas (src/androidTest). Sin esta linea,
        // cada sabor en debug arrastra ademas una variante "...AndroidTest".
        variante.enableAndroidTest = false
    }
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