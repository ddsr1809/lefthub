# Cómo abrir este proyecto

## 1. Generar el wrapper (solo la primera vez)

El repositorio trae `gradle/wrapper/gradle-wrapper.properties`, que fija la
versión de Gradle, pero le falta el `.jar` y los scripts `gradlew` porque son
binarios. Se generan con un comando, desde esta carpeta:

```bash
gradle wrapper
```

Si `gradle` no está en tu PATH, usa el que trae Android Studio:

- **macOS / Linux**
  `/Applications/Android\ Studio.app/Contents/gradle/gradle-*/bin/gradle wrapper`
- **Windows**
  `"C:\Program Files\Android\Android Studio\gradle\gradle-*\bin\gradle.bat" wrapper`

Después de eso aparecen `gradlew`, `gradlew.bat` y
`gradle/wrapper/gradle-wrapper.jar`. Esos sí van al repositorio.

## 2. Abrir en Android Studio

`File → Open` y elige **esta carpeta** (`android/`), no la raíz del proyecto.
Si abres la raíz, Gradle no encuentra `settings.gradle.kts` y te dirá que no
es un proyecto Android.

## 3. Comprobar que usa el wrapper

`Settings → Build, Execution, Deployment → Build Tools → Gradle`

- **Distribution:** `Wrapper` (no "Specified location" ni "Local installation")
- **Gradle JDK:** cualquiera de la **17**

Con "Distribution: Wrapper", Android Studio respeta la versión fijada en
`gradle-wrapper.properties` y deja de usar la que tenga instalada.

## 4. Sincronizar

La primera sincronización descarga Gradle 8.11.1, el SDK de Android y las
dependencias de Compose y Firebase. Tarda un buen rato y parece colgada.
Déjala terminar; cancelar a mitad deja el caché a medias y produce errores
raros después.

## Si algo falla

**`Task 'prepareKotlinBuildScriptModel' not found`**
Gradle y AGP no se llevan. Casi siempre es que el IDE no está usando el
wrapper: revisa el paso 3.

**`Unsupported class file major version`** o errores de Java
El Gradle JDK no es 17. Paso 3, segunda línea.

**`SDK location not found`**
Falta `local.properties`. Android Studio lo crea solo; si compilas desde la
terminal, copia `local.properties.example` y pon la ruta de tu SDK.

**Limpiar y volver a empezar**
```bash
./gradlew --stop
rm -rf .gradle build app/build
```
Y en el IDE: `File → Invalidate Caches → Invalidate and Restart`.


---

## Firmar para Google Play

Google Play no acepta apps firmadas con la llave de depuración. La variante
`produccionRelease` usa tu llave propia cuando existe `android/firma.properties`;
sin ese archivo sigue firmando con la de depuración, que solo sirve para
probar en tu teléfono.

**1. Crear la llave** (una sola vez). Te pide una clave y unos datos de nombre;
guarda el archivo fuera del repositorio:

```bash
mkdir -p ~/llaves
keytool -genkeypair -v -keystore ~/llaves/voces-izquierda.jks \
  -alias voces -keyalg RSA -keysize 2048 -validity 10000
```

Haz una copia de seguridad del archivo `.jks` y de la clave. Es la llave con la
que subes cada versión a Play.

**2. Decirle a Gradle dónde está.**

```bash
cd android
cp firma.properties.example firma.properties
```

Edita `firma.properties` con la ruta del `.jks`, el alias (`voces`) y la clave.
Ese archivo no se sube a git.

**3. Registrar la huella en Firebase.** La variante de producción cambió de
llave, así que su huella SHA-1 también:

```bash
./gradlew signingReport
```

Busca `Variant: produccionRelease` y copia su `SHA1`. En la consola de Firebase,
en la app `com.vocesdeizquierda.lefthub`, agrégala como huella digital nueva.
Sin esto, entrar con Google falla en producción.

**4. Generar el archivo para Play.**

```bash
./gradlew bundleProduccionRelease
```

Sale en `app/build/outputs/bundle/produccionRelease/`. Ese `.aab` es el que se
sube a Play Console. Cada subida necesita un `versionCode` mayor que la anterior
(`app/build.gradle.kts`).

**5. Después de la primera subida.** Play vuelve a firmar la app con su propia
llave antes de entregarla a los teléfonos. Copia de Play Console la huella
SHA-1 del certificado de firma de la app y agrégala también en Firebase; si no,
entrar con Google falla en las copias instaladas desde Play.

Si ya tenías `produccionRelease` instalada en tu teléfono con la llave de
depuración, desinstálala antes de instalar la nueva: Android no deja actualizar
una app firmada con otra llave.
