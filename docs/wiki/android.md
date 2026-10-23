[← Inicio](README.md)

# App Android (1.0.15)

La app de Android es la versión completa del producto: Kotlin 2.1.0, Jetpack Compose (BOM 2024.12.01), Material 3, Gradle 8.11.1 + AGP 8.7.3, minSdk 24, compileSdk 35. Vive en `android/`, paquete `com.vocesdeizquierda.lefthub`, clase de aplicación `LeftVocesApp`.

## Sabores

| Sabor | Variante | applicationId | Servidor |
| --- | --- | --- | --- |
| `developer` | `developerDebug` | `…lefthub.developer` | `http://localhost:8080` (puente `adb reverse`) |
| `pruebas` | `pruebasDebug` | `…lefthub.pruebas` | `https://testapp.vocesdeizquierda.com` |
| `produccion` | `produccionRelease` | `com.vocesdeizquierda.lefthub` | `https://leftapp.vocesdeizquierda.com` |

Las tres apps se pueden instalar a la vez. `API_BASE` solo existe en los sabores, para que no se pueda compilar sin elegir servidor. Cada sabor tiene su `google-services.json`. El sabor `pruebas` no puede llamarse `testing` porque el AGP reserva el prefijo `test`.

## Pantallas

La barra inferior tiene **Directorio** (inicio), **Novedades**, **Stickers** (si la cuenta los ve) y **Ajustes**.

| Pantalla | Archivo | Qué muestra |
| --- | --- | --- |
| Bienvenida | `BienvenidaPantalla.kt` | Condiciones y privacidad; se vuelve a pedir si cambia `Aceptacion.VERSION` |
| Unirse con Google | `UnirseConGoogle.kt` | Invitación a guardar la cuenta, cada apertura mientras sea invitado |
| Directorio | `DirectorioPantalla.kt` | Listas Creadores, Canales de YouTube y Medios; lo seguido arriba; filtro de etiquetas |
| Ficha de creador | `DirectorioPantalla.kt` | Canal principal, sus últimos videos, videos donde aparece, redes y medios |
| Ficha de medio | `ProductoraPantalla.kt` | Sus últimos videos, creadores y canales |
| Ficha de canal | `CanalPantalla.kt` | De quién es, con quién aparece y últimos videos |
| Elegir canales | `ElegirCanales.kt` | Ventana al seguir: todos los canales o solo algunos |
| Novedades | `NovedadesPantalla.kt` | Videos, Videos cortos (si están encendidos) y Lo más visto |
| Ficha del video | `VideoPantalla.kt` | Se abre al tocar una notificación; botón Ver el video y espacio para anuncio |
| Stickers | `StickersPantalla.kt` | Paquetes por creador para WhatsApp, Telegram, Signal y otras |
| Ajustes | `AjustesPantalla.kt` | Cuenta, letra, cortos, YouTube, anuncios, chat, recomendar, agregar |
| Chat | `ChatPantalla.kt`, `BandejaPantalla.kt` | Chat con el equipo; bandeja para administradores |
| Recomendar | `RecomendarPantalla.kt` | Proponer un canal de YouTube |
| Agregar | `AgregarPantalla.kt` | Editores: alta de creadores, medios y canales |
| Servidor | `ServidorPantalla.kt` | Mantenimiento o servidor caído, reintenta cada 30 s |
| Actualizar | `ActualizarPantalla.kt` | Versión dada de baja (426): pide actualizar desde Play |

## Notificaciones y enlaces

- `notif/MensajeriaService.kt` recibe FCM. La app se suscribe a topics: `creator_<id>`, `productora_<id>`, `canal_<id>`, sus variantes `_avisos_cortos` y el topic general `todos`.
- Tocar el aviso de un video abre la ficha del video; tocar un video en Novedades va directo a YouTube.
- `enlaces/Enrutador.kt` abre YouTube con enlace profundo y cae al navegador si la app no está.
- Los avisos del chat van a topics derivados con HMAC, para que nadie se suscriba al de otra cuenta.

## Anuncios y compras

- AdMob solo en Novedades: uno tras el segundo video y luego cada seis, máximo tres (`anuncios/Huecos.kt`).
- En producción `build.gradle.kts` lleva los IDs reales desde la 1.0.16: app `ca-app-pub-6584298596563589~8770479750` y bloque Banner «novedades» `ca-app-pub-6584298596563589/6767132949`. `developer` y `pruebas` usan los IDs de prueba de Google. `web/app-ads.txt` declara al editor `pub-6584298596563589`.
- Compra única `sin_anuncios` con Google Play Billing 8.3.0; el servidor la verifica antes de quitar anuncios.

## Accesibilidad

`ui/Tema.kt` aplica WCAG 2.2 AA (ley AB 1757 de California): objetivos táctiles de 48 dp (56 en acciones principales), contraste 8:1, tres tamaños de letra que se suman al del sistema con tope de 200%, navegación abajo y pestañas con texto. `ui/Previews.kt` renderiza cada pantalla en claro, oscuro y letra al 200%.

## Compilar y firmar

- Abrir la carpeta `android/` en Android Studio, con Distribution *Wrapper* y Gradle JDK 17.
- AAB para Play: `./gradlew bundleProduccionRelease`. La llave vive fuera de git en `android/firma.properties`; sin ella se firma con la de depuración y Play la rechaza.
- `WEB_CLIENT_ID` es el client_id de tipo 3 (web) del proyecto 445243795956. Con el ID de Android, entrar con Google falla en silencio.
