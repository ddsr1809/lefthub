# Relé — Directorio de Creadores

Una app que avisa cuando publican los creadores que sigues y te lleva a la app oficial donde está el video. **No reproduce contenido, no aloja nada y no hace scraping.**

El nombre "Relé" es un marcador de posición: cámbialo por el que prefieras, pero que no contenga "YouTube", "Tube" ni nada parecido. Es un requisito de las guías de marca de Google.

---

## Qué hace cada carpeta

| Carpeta | Qué es | Dónde corre |
|---|---|---|
| `server/` | El cerebro. Recibe los avisos de YouTube, los enriquece y manda las notificaciones. Spring Boot + Kotlin. | Cloud Run, VPS o Docker |
| `admin/` | El panel donde tú das de alta a los creadores. | Firebase Hosting (web) |
| `android/` | La app de Android, en Kotlin y Jetpack Compose. | Play Store |
| `ios/` | La app de iPhone, en Swift y SwiftUI. | App Store |
| `scripts/` | Utilidades de terminal. | Tu computadora |
| `firestore.rules` | Quién puede leer y escribir qué. | Firebase |

---

## Cómo funciona, en una frase por paso

1. Un creador publica en YouTube.
2. Google avisa a `POST /websub` del servidor en menos de un segundo (protocolo WebSub, **0 unidades de cuota**).
3. El servidor verifica la firma criptográfica, comprueba que no sea un aviso repetido y pide los datos completos del video (**1 unidad de cuota**).
4. Manda una notificación push a todos los que siguen a ese creador.
5. El usuario toca el aviso y la app abre el video en YouTube con un enlace profundo.

Firestore, Auth y FCM siguen en el nivel gratuito de Firebase con unos cientos de usuarios. El servidor sí tiene un coste fijo: unos 10-15 USD al mes en Cloud Run con una instancia mínima, o 5-6 en un VPS pequeño. Lo caro —ancho de banda, transcodificación y almacenamiento de video— lo siguen pagando YouTube, TikTok y compañía.

---

## Instalación paso a paso

### 1. Crear el proyecto de Firebase

1. Entra a [console.firebase.google.com](https://console.firebase.google.com) y crea un proyecto.
2. Activa **Firestore Database** (modo producción).
3. Activa **Authentication** y habilita tres proveedores: Anónimo, Google y Apple.
4. Sube al plan **Blaze**. Es obligatorio: las Cloud Functions no salen a internet en el plan gratuito. Con este volumen de uso la factura seguirá siendo prácticamente cero, pero pon un presupuesto de alerta en Google Cloud de todos modos.

### 2. Conseguir la clave de la YouTube Data API

1. Ve a [console.cloud.google.com](https://console.cloud.google.com), selecciona el mismo proyecto.
2. **APIs y servicios → Biblioteca →** activa *YouTube Data API v3*.
3. **Credenciales → Crear credenciales → Clave de API**. Guárdala; la necesitas en el paso 4.

### 3. Instalar las herramientas

```bash
npm install -g firebase-tools     # solo para reglas y hosting del panel
firebase login
cd creator-hub
firebase use --add                # elige tu proyecto
firebase deploy --only firestore  # sube reglas e índices
```

El servidor se compila con Gradle y Java 21. Su guía completa está en
**`server/COMO-EJECUTAR.md`**.

### 4. Configurar y arrancar el servidor

```bash
cd server
gradle wrapper          # solo la primera vez
cp .env.example .env    # y rellena los valores
```

Genera las tres cadenas aleatorias que pide el archivo:

```bash
openssl rand -hex 32
```

Descarga la clave de servicio de Firebase (Configuración del proyecto →
Cuentas de servicio → Generar clave privada) y apunta
`GOOGLE_APPLICATION_CREDENTIALS` a ella.

```bash
export $(grep -v '^#' .env | xargs)
./gradlew test          # 11 pruebas, deben pasar todas
./gradlew bootRun
```

### 5. Publicar el servidor y conectar el webhook

Hay un baile de dos pasos: el servidor necesita saber su propia URL pública, y
esa URL solo existe después del primer despliegue.

```bash
gcloud run deploy relay-server --source . --region us-central1 \
  --allow-unauthenticated --min-instances 1
```

Copia la URL que imprime, ponla en `RELAY_URL_PUBLICA` y vuelve a desplegar.

**`--min-instances 1` importa.** Con escalado a cero, las tareas programadas
nunca se ejecutan y los arrendamientos de WebSub caducan a los diez días en
silencio. Está explicado en `server/COMO-EJECUTAR.md`.

### 6. Abrir el panel y darte permiso de administrador

```bash
firebase deploy --only hosting
```

Antes de entrar, edita `admin/firebase-config.js`: los datos de tu app web
(Consola de Firebase → Configuración del proyecto → Tus apps → Web) y la
`API_BASE` con la URL de tu servidor. Añade además ese origen del panel a
`CORS_ORIGENES` en el servidor, o el navegador bloqueará las peticiones.

Ahora date el rol de administrador:

1. Entra al panel una vez con tu cuenta de Google. Te va a rechazar, es normal: solo queríamos que existiera el usuario.
2. Descarga la clave de servicio (Configuración del proyecto → Cuentas de servicio → Generar clave privada) y guárdala como `scripts/service-account.json`.
3. Ejecuta:

```bash
cd scripts && npm install firebase-admin
node set-admin.js tu-correo@gmail.com
```

(Una vez que ya haya un administrador, los siguientes se nombran desde el
propio servidor con `POST /api/admin/administradores?correo=`.)

4. Cierra sesión, vuelve a entrar. Ya tienes acceso.

### 7. Agregar tu primer creador

En el panel, **Agregar creador**. Pega el `@handle` o la URL del canal en el campo de YouTube y toca **Buscar canal**: eso resuelve el ID canónico `UC...`, que es lo único que WebSub acepta como tema.

Al guardar, el backend manda el handshake al hub de Google. El testigo de la fila pasa a ámbar (verificando) y luego a verde (recibiendo avisos) en unos segundos. Si se queda en ámbar más de un minuto, revisa que `PUBLIC_BASE_URL` esté bien.

### 8. Compilar la app de Android

Lee primero **`android/COMO-ABRIR.md`**: el repositorio no incluye el
`gradle-wrapper.jar` (es binario), así que hay que generarlo una vez con
`gradle wrapper` dentro de `android/`. Sin ese paso, Android Studio usa el
Gradle que tenga instalado y choca con el AGP.

```bash
cd android
gradle wrapper        # solo la primera vez
```

Descarga `google-services.json` de la consola de Firebase (Configuración → Tus apps → Android) y ponlo en `android/app/`.

Después edita `android/app/build.gradle.kts`:
- `applicationId` con tu identificador.
- `WEB_CLIENT_ID` con el `client_id` de tipo 3 que viene dentro de `google-services.json`. Es el error más común: si pones ahí el ID de Android, el login con Google falla sin decir por qué.

Abre **la carpeta `android/`** en Android Studio (no la raíz del proyecto) y dale a Run. En `Settings → Build Tools → Gradle`, comprueba que *Distribution* diga `Wrapper` y que *Gradle JDK* sea un 17.

Para ver las pantallas sin instalar nada en el teléfono, abre `ui/Previews.kt` y pulsa **Split** en la esquina superior derecha del editor. Cada pantalla se renderiza en claro y en oscuro, y varias también con la letra del sistema al 200%: ahí es donde se detecta si un botón se corta antes de que lo sufra un usuario.

Las versiones están fijadas a Gradle 8.11.1 + AGP 8.7.3 + Kotlin 2.1.0, una combinación conservadora y conocida. Para subirlas, usa el *AGP Upgrade Assistant* de Android Studio, que cambia el plugin y la versión de Gradle a la vez: son dos cosas que hay que mover juntas o el proyecto deja de sincronizar.

Para el AAB de Play Store:

```bash
./gradlew bundleRelease
```

### 9. Compilar la app de iPhone

El proyecto se genera desde `ios/project.yml`, así no hay un `.xcodeproj` en el repositorio dando conflictos de merge en cada cambio.

```bash
brew install xcodegen
cd ios && xcodegen generate && open Relay.xcodeproj
```

Antes de compilar, lee `ios/Relay/README-config.md`: hacen falta el `GoogleService-Info.plist` y la clave de APNs.

Si prefieres no usar XcodeGen, crea un proyecto de app SwiftUI en Xcode, arrastra la carpeta `ios/Relay` dentro, y añade los paquetes de Firebase y GoogleSignIn con File → Add Package Dependencies. Los valores de `Info.plist` y los entitlements que necesitas están en `project.yml`.

**Las notificaciones no funcionan en el simulador.** Hace falta un iPhone real y una cuenta de desarrollador de pago. Es lo que más sorprende a quien viene de Android.

---

## Probar sin desplegar nada

```bash
cd server && ./gradlew test
```

Once pruebas que cubren lo único que, si se rompe, rompe el producto entero:
que la validación HMAC rechace cuerpos manipulados y firmas de otro secreto, y
que el parseo del Atom saque bien el `videoId` pese a los prefijos de
namespace. No levantan el contexto de Spring ni tocan Firebase.

Para Firestore y Auth en local:

```bash
firebase emulators:start
```

El emulador de Firestore está en el puerto **8085**, no en el 8080: ese lo
ocupa Spring Boot.

---

## Antes de publicar en las tiendas

Estos cuatro puntos son los que más rechazos causan. El código ya los cubre; lo que falta es la configuración de tu cuenta.

**Sign in with Apple (Guideline 4.8).** Si ofreces Google en iOS, Apple te obliga a ofrecer también su propio inicio de sesión, con la misma visibilidad. `AjustesVista.swift` ya lo pone primero y con la variante primaria. Habilita la capacidad en el portal de desarrollador.

**Borrado de cuenta (Guideline 5.1.1 v).** Ya está en Ajustes y borra de verdad. Para que además revoque el vínculo con Apple, carga estas credenciales:

En `server/.env` (o como variables de entorno del servicio):
`APPLE_TEAM_ID`, `APPLE_KEY_ID`, `APPLE_BUNDLE_ID` y `APPLE_CLAVE_PRIVADA`
con el contenido del archivo `.p8` en una sola línea, con `\n` literales en
los saltos.

**Identidad de marca.** Nada de "Tube" en el nombre, nada de logos parecidos, nada de degradados rojos. El logo de YouTube solo puede aparecer dentro de un botón que diga "Ver en YouTube".

**Curaduría real (Guideline 3.2.2).** La app no puede parecer un índice automático. Como tú apruebas cada creador a mano y no hay buscador abierto, ya estás del lado correcto. Explícalo en las notas para el revisor.

---

## Accesibilidad

`android/.../ui/Tema.kt` y `ios/Relay/Vistas/Tema.swift` codifican los criterios de WCAG 2.2 AA que exige la ley AB 1757 de California ($4,000 USD por barrera detectada, sin periodo de gracia):

- Objetivos táctiles de 48 dp/pt mínimo (56 en acciones principales), con 12 de separación entre botones adyacentes. El mínimo legal es 24; Apple recomienda 44.
- Contraste de 8:1 o más en todo el texto. El mínimo legal es 4.5:1.
- Tres tamaños de letra dentro de la app, que se **suman** al del sistema, con tope en 200%. Sin ese tope, alguien con el teléfono al 200% que además eligiera "Muy grande" llegaría al 290% y rompería el layout.
- Sin contraseñas: la sesión anónima cubre el criterio 3.3.8 (Accessible Authentication).
- Navegación abajo, en la zona del pulgar.
- Pestañas con etiquetas escritas en lugar de iconos que haya que interpretar.

Antes de publicar, recorre las dos apps con VoiceOver y TalkBack encendidos, y con el tamaño de letra del sistema al máximo. Es la única prueba que importa.

---

## Cuota de la YouTube Data API

Tienes 10,000 unidades al día. El diseño gasta ~1 por video publicado.

| Método | Coste | Uso aquí |
|---|---|---|
| WebSub | 0 | Detección de publicaciones |
| `videos.list` | 1 | Rellenar título, descripción y miniatura |
| `channels.list` | 1 | Solo al dar de alta un creador |
| `subscriptions.list` | 1 por cada 50 creadores | Saber si el usuario está suscrito a los creadores del directorio, al abrir la app |
| `search.list` | **100** | **Prohibido.** Agotaría el día en 100 llamadas |

Si alguna vez agregas búsqueda, hazlo con `playlistItems.list` sobre la playlist de subidas del canal (1 unidad), nunca con `search.list`.

---

## Canales y productoras

Tres cosas distintas, cada una con su ficha en el panel:

- **Creador**: una persona del directorio. Puede figurar en una o varias productoras, y tener uno o varios canales de YouTube, varias redes sociales, o solo redes y ningún canal de YouTube: se le puede dar de alta con un canal de YouTube o solo con una cuenta de X, Instagram, TikTok, Facebook, Threads, Telegram, Twitch, Spotify, Patreon o su página. Solo YouTube genera avisos de videos; las redes son enlaces de su perfil. En su ficha van primero sus datos y sus redes, que bastan para crearlo; los canales de YouTube son opcionales y se agregan cuando se quiera, ahí mismo o en la sección Canales, donde además se dice de qué productora es cada uno.
- **Canal de YouTube**: de donde salen los videos. Es de un creador (su dueño) o, si no tiene, de una productora: su canal oficial. Además puede ser de una productora aunque tenga creador, y **aparecer con otros creadores**.
- **Productora**: la casa detrás de varios creadores. Tiene sus canales propios y puede **aparecer en el directorio como un creador más**.

Lo que publica un canal le llega a quien sigue a cualquiera de estos: su dueño, su productora, o los demás creadores con los que aparece. Ejemplo: el canal de la productora *Gobierno de México* aparece con dos creadores; quien sigue a cualquiera de los dos, o a la productora, recibe sus videos, firmados por *Gobierno de México*.

Figurar en una productora no basta para recibir los videos de sus canales: son ligas separadas. Con qué creadores aparece un canal se marca en la ficha de ese canal.

| Tabla | Qué guarda |
|---|---|
| `canales` | Cada canal o red, con su creador dueño (`creador_id`), su productora (`productora_id`) o los dos. Un `channel_id` de YouTube es de un solo dueño. |
| `canales_creadores` | Con qué otros creadores aparece cada canal, sin contar al dueño. |
| `productoras` | Nombre, descripción, logo, y si aparece en el directorio (`en_directorio`) y en qué tema (`categoria`). |
| `creadores_productoras` | Qué creadores figuran en qué productoras. |
| `favoritos_productoras` | Qué productoras sigue cada persona. |

**Reglas**

- El dueño de un canal es su creador; si no tiene, la productora. Los avisos salen a su nombre. Con el dueño oculto, el canal no se vigila ni avisa a nadie, tampoco a través de los demás creadores.
- Seguir a una productora avisa de lo que se publica en los canales que le pertenecen, no de todo lo de sus creadores.
- Un video manda **un solo** aviso aunque tenga varias audiencias, con una condición de FCM ("sigue a este o a aquel"): quien sigue a varios lo recibe una vez. Los topics son `creator_<id>` por cada creador y, por la productora, `productora_<id>` y `creator_<id de la productora>`. FCM admite cinco topics por condición; con más, el aviso se parte y lleva la misma etiqueta para que la copia sustituya a la primera.
- Una productora que aparece en el directorio sale también en `GET /api/creadores`, como una fila más con `esProductora: true` y su mismo id. Las apps que no conocen las productoras la siguen con `PUT /api/favoritos/{id}`, que el servidor guarda como productora seguida; por eso `GET /api/perfil` repite en `favoritos` las productoras que se siguen.
- Al retirar una productora se van sus canales propios y lo que publicaron. Los canales de sus creadores se quedan, sin la liga.
- Cuando un canal sin creador pasa a tener dueño, lo que había publicado pasa a ser de ese creador.

**Rutas**

| Ruta | Para qué |
|---|---|
| `GET /api/creadores` | Cada creador trae `canales` (los suyos y después los de otros en los que aparece) y `productoras`. `conexiones` sigue viniendo, con el primer canal de cada plataforma. Incluye las productoras que aparecen en el directorio. |
| `GET /api/productoras`, `GET /api/productoras/{id}` | Productoras visibles, con sus canales y los ids de sus creadores. |
| `GET /api/canales/{id}/publicaciones` | Lo último que publicó un canal, para su ficha en la app. No depende de a quién se siga. |
| `PUT` / `DELETE /api/favoritos/productoras/{id}` | Seguir y dejar de seguir. `GET /api/perfil` las devuelve en `productoras`. |
| `GET` / `POST /api/admin/canales`, `DELETE /api/admin/canales/{id}` | Panel: la ficha de cada canal de YouTube. Dueño (`creadorId`), `productoraId` y `creadores` (con quién más aparece). Es el único sitio donde un canal cambia de dueño. |
| `GET` / `POST /api/admin/productoras`, `DELETE /api/admin/productoras/{id}` | Panel: listar, guardar (con sus canales propios, sus creadores, `enDirectorio` y `categoria`) y retirar. |
| `POST /api/admin/creadores` | Acepta `canales` (la lista completa, cada uno con su `productoraId` opcional) y `productoras`. No toca con quién aparece cada canal. |

**En el panel**

- **Creadores.** La ficha tiene dos listas: *Canales de YouTube* (con el buscador) y *Redes sociales* (un enlace por red; basta escribir el usuario, `@claudia`, y el panel arma el enlace). Cualquiera de las dos puede quedar vacía, no las dos. Más abajo, las productoras en las que figura y, solo para verlos, los canales de otros en los que aparece.
- **Canales.** Una fila por canal de YouTube, con su suscripción al hub (antes era la sección Suscripciones) y su ficha: etiqueta, creador dueño, productora y *También aparece con estos creadores*.
- **Productoras.** Sus canales propios, sus redes, los creadores que figuran en ella y la casilla *Aparece en el directorio como un creador más*, con el tema en el que sale.

El panel son archivos estáticos que Apache sirve desde el clon del VPS, el mismo para testing y producción: se actualiza con `git -C /opt/vocesleft pull`, no con el pipeline. Si el servidor al que apunta es anterior a esta versión, el panel no ofrece las fichas de canal ni la casilla del directorio, y lo demás funciona igual.

**En la app de Android.** Las mismas tres fichas que en el panel:

- **Creador.** *Canales de YouTube* y *Redes sociales* van en apartados distintos, y después sus productoras. De un canal que no es suyo dice de quién es. Si no tiene canal de YouTube lo dice, porque entonces no hay avisos de videos suyos.
- **Canal de YouTube.** Se llega con "Ver los últimos videos de este canal", debajo del botón del canal (que sigue abriendo YouTube de un toque). Dice de quién es, de qué productora y con qué otros creadores aparece, cada uno con el camino a su ficha para seguirlo, y lista sus últimos videos.
- **Productora.** Sus canales, con quién sale cada uno, sus creadores y el botón para seguirla. Está en la pestaña **Productoras** del directorio (solo si hay alguna) y, si aparece en el directorio, también entre los creadores.

En Novedades, un video de un canal de productora se firma "Creador · Productora". "¿Estoy suscrito en YouTube?" se contesta canal por canal. La app nueva también funciona contra un servidor anterior: sin `canales` usa `conexiones`, sin la ruta de productoras no muestra la pestaña y sin la de videos la ficha del canal sale sin lista.

**Compatibilidad.** Las versiones de la app y del panel anteriores a esto siguen funcionando: leen y mandan `conexiones`, un enlace por plataforma, que el servidor entiende como "el canal principal de cada plataforma" y deja los demás canales como están. Para ellas, un canal compartido es un video más en Novedades y un aviso más, y una productora en el directorio es un creador más. Las redes que no conocen (X, Facebook, Threads, Telegram) no las enseñan: un creador que solo tenga esas les sale sin enlaces. Las tablas `conexiones` y `youtube_suscripciones` ya no se usan, pero no se borran todavía: si un despliegue se revierte, la versión anterior arranca sobre el esquema nuevo. Se retiran en una migración posterior.

---

## Foto de perfil

En la ficha de un creador (y en la de una productora, para su logo) la sección
**Foto de perfil** ofrece un botón por cada cuenta que tenga en el formulario:
*YouTube · @canal*, *X · usuario*, *Instagram · usuario*… y **Manual**. Al
pulsar uno, el panel trae la foto de esa cuenta, la enseña y la deja lista
para guardarse con lo demás. Con *Manual* se pega la dirección de una imagen.

- Al dar de alta a alguien buscando su canal de YouTube, el formulario se
  rellena solo con el nombre, la descripción y la foto del canal (lo que esté
  vacío). Después se puede cambiar la foto por la de otra red.
- **De YouTube** se usa la dirección que da la Data API, que no caduca (1
  unidad de cuota).
- **De las demás redes** (X, Instagram, TikTok, Facebook, Threads, Telegram,
  Twitch, Spotify, Patreon) el servidor se la pide a
  [unavatar.io](https://unavatar.io) y **guarda una copia** (tabla `fotos`, V9)
  que sirve él mismo en `/api/fotos/{id}`. Se guarda la copia y no la
  dirección porque las de Instagram o TikTok caducan a los pocos días.
- Sin clave, ese servicio da **25 fotos al día** por servidor, que sobra para
  dar de alta creadores. Si hiciera falta más, se contrata un plan y se pone
  la clave en `FOTOS_API_KEY` del `.env`.
- La foto **no se actualiza sola**: si la persona la cambia en su red, se
  vuelve a pulsar el botón y se guarda.
- Si la red no entrega la foto (cuenta privada, usuario mal escrito, la red
  bloquea la consulta), el panel lo dice y se puede probar con otra red o
  pegarla a mano.

## Videos cortos (Shorts)

Los videos cortos van aparte de los demás, y son opcionales dos veces: para el
equipo y para cada persona.

- **El equipo decide si existen.** En el panel, sección **Publicaciones**, está
  el interruptor *Mostrar los videos cortos en la app*. Viene **apagado**. Así,
  el servidor guarda los Shorts que detecta pero no avisa de ellos ni los
  enseña en ningún sitio, y la app no ofrece la opción. Al encenderlo, la app
  muestra un apartado *Videos cortos* dentro de Novedades, con los que ya
  estaban guardados y los que lleguen.
- **Cada persona decide si los ve.** Con el interruptor encendido aparece en
  los Ajustes de la app la sección *Videos cortos*, con *Verlos* / *No verlos*.
  Si el equipo lo apaga, esa sección desaparece.

Reglas que conviene saber:

- Un corto **nunca** sale mezclado en Novedades ni en la ficha de un canal.
- Sus avisos van por topics aparte (`creator_<id>_cortos`,
  `productora_<id>_cortos`), a los que solo se suscribe el teléfono de quien
  los ve. Las versiones de la app anteriores a esto no los conocen: dejan de
  ver y de recibir Shorts del todo.
- **Cómo se sabe que un video es un Short.** La Data API no lo dice. Si dura
  más de tres minutos o es un directo, no lo es. Si dura menos, el servidor
  pide `youtube.com/shorts/ID` (solo la cabecera, sin cuota): YouTube contesta
  200 si es un Short y redirige a `/watch` si es un video normal. Si no
  contesta con claridad, se queda como corto.
- **Corregir a mano.** En la lista de Publicaciones cada video tiene *Es corto*
  / *No es corto*. Y *Repasar los cortos guardados* vuelve a preguntarle a
  YouTube por los últimos: sirve sobre todo la primera vez, porque lo guardado
  antes de esta versión se clasificó solo por la duración.

En la API: `GET /api/publicaciones` no trae cortos; `GET
/api/publicaciones?tipo=cortos` trae solo cortos (vacío si están apagados o la
persona no los quiere). `GET /api/perfil` dice `cortos` (lo que eligió la
persona) y `cortosDisponibles` (lo que decidió el equipo). El interruptor es
`GET`/`PUT /api/admin/ajustes`, y se guarda en la tabla `ajustes` (V8).

## "¿Estoy suscrito en YouTube?"

Seguir a un creador en la app y estar suscrito a su canal son cosas distintas. Con la cuenta guardada con Google, la app puede decirle a cada persona en cuáles sí lo está.

**Cómo funciona**

1. En el perfil de un creador o en Ajustes, la persona toca **Conectar con YouTube**. Google muestra su pantalla de permiso (`youtube.readonly`, solo lectura).
2. A partir de ahí, cada vez que la app pasa a primer plano le pide a Google un token de acceso sin mostrar nada y lo manda a `POST /api/youtube/suscripciones`.
3. El servidor comprueba con Google que el token es de esta app y de esa cuenta, pregunta a YouTube solo por los canales del directorio y guarda el resultado, canal por canal, en `youtube_suscripciones_canales`.
4. La app lo pinta en el directorio y en el perfil de cada creador.

El servidor no guarda tokens de Google ni necesita `client secret`: el token dura una hora y se usa en el momento. No hay variables de entorno nuevas.

**Lo que hay que hacer en Google Cloud antes de probarlo**

- **APIs y servicios → Pantalla de consentimiento de OAuth → Acceso a los datos:** agrega el permiso `https://www.googleapis.com/auth/youtube.readonly`.
- Mientras el proyecto esté en modo **Prueba**, solo pueden dar el permiso las cuentas que agregues como usuarios de prueba.
- `GOOGLE_CLIENT_ID` del servidor y el cliente OAuth de Android tienen que ser del **mismo proyecto**. El servidor rechaza tokens de cualquier otro.

**Antes de encenderlo en producción**

`youtube.readonly` es un permiso sensible. Sin la verificación de OAuth aprobada, Google muestra la pantalla de "app no verificada" y solo deja que 100 personas den el permiso en toda la vida del proyecto; al agotarse el cupo se desactiva también el inicio de sesión con Google. Por eso el sabor `prod` de Android trae `SUSCRIPCIONES_YOUTUBE` en `false` (`android/app/build.gradle.kts`). Pide la verificación, espera la aprobación y cámbialo entonces.

Para la verificación y para cumplir las políticas de la API de YouTube, la política de privacidad tiene que decir qué se lee (a qué canales del directorio está suscrita la persona), para qué, y enlazar a la política de privacidad de Google y a los términos de YouTube.

**Lo que el código ya cumple**

- El permiso se pide aparte del inicio de sesión y solo cuando la persona toca el botón.
- Se puede desconectar desde Ajustes: borra lo guardado y retira el permiso en Google.
- Si la persona retira el permiso desde su cuenta de Google, la app lo detecta al abrirse y borra lo guardado.
- Lo guardado se borra con la cuenta, y una tarea diaria elimina lo que lleve 30 días sin refrescarse.
- Cada cuenta puede comprobar seis veces seguidas y luego una cada dos minutos, para que nadie agote la cuota del proyecto.

La app de iOS todavía no tiene esta función.

---

## Cosas que se rompen y cómo notarlo

**Las notificaciones dejan de llegar a los 10 días.** El arrendamiento de WebSub caducó. La renovación corre cada 4 días; en los registros del servidor debe aparecer "Ciclo de renovación terminado". Si no aparece, casi siempre es que el servicio escala a cero y nunca llega a ejecutar la tarea programada.

**El testigo se queda en ámbar.** El hub no pudo verificar tu webhook. Casi siempre es `RELAY_URL_PUBLICA` mal puesta, o que el servidor no responde el `hub.challenge` como texto plano.

**Llegan avisos duplicados.** No deberían: la transacción de idempotencia en `WebSubService.procesarEntrada` solo deja pasar el primero. Si pasa, revisa que no tengas dos suscripciones al mismo canal.

**Se envían avisos de videos viejos.** Al suscribirte, el hub reenvía entradas recientes del feed. `relay.websub.antiguedad-maxima-horas` (6) las descarta. Cámbialo en `server/src/main/resources/application.yml`.

**`Task 'prepareKotlinBuildScriptModel' not found in project ':app'`.** Gradle y el Android Gradle Plugin no son compatibles entre sí. Casi siempre significa que el IDE no está usando el wrapper del proyecto. Está explicado en `android/COMO-ABRIR.md`.

---

## Qué falta por hacer

El proyecto está completo de punta a punta, pero estas piezas quedaron fuera a propósito:

- **Detección automática en TikTok, Twitch e Instagram.** Ahora mismo esas plataformas solo tienen enlace en el perfil, no notificación. TikTok no da webhooks públicos; Twitch sí tiene EventSub y sería el siguiente en agregar.
- **Subida de fotos de creador.** El panel pide una URL. Conectar Firebase Storage tomaría poco.
- **Notificaciones programadas** ("tu creador transmite en una hora").
- **Pruebas automatizadas** más allá de las que ya hay: falta cubrir el enriquecimiento, el envío de push y los controladores con MockMvc.
- **Sign in with Apple en Android.** Se puede, vía flujo web, pero añade complejidad y en Android casi nadie lo usa.
- **Tests de interfaz** en ambas apps.

---

## Migración de las apps

Las apps de Android y iOS del paquete anterior llamaban a Cloud Functions.
Con este servidor pasan a hablar REST. Son cinco puntos de llamada en total y
están detallados uno a uno en **`MIGRACION-APPS.md`**, con el código exacto
que hay que poner.

---

## Notas legales que no son código

Necesitas política de privacidad y términos de uso publicados en una URL antes de subir a cualquier tienda. Ambas tiendas los piden y ambas los revisan. Están en la carpeta `web/`, en español y en inglés:

| | Español | Inglés |
|---|---|---|
| Política de privacidad | `https://vocesdeizquierda.com/privacidad/` | `https://vocesdeizquierda.com/en/privacy/` |
| Condiciones de servicio | `https://vocesdeizquierda.com/condiciones/` | `https://vocesdeizquierda.com/en/terms/` |
| Cómo borrar la cuenta (lo pide Play) | `https://vocesdeizquierda.com/privacidad/#borrar-cuenta` | `https://vocesdeizquierda.com/en/privacy/#delete-account` |

Las páginas en español mandan solas a la versión en inglés cuando el navegador no está en español (`web/idioma.js`). Si cambias lo que la app o el servidor guardan de las personas, actualiza la política en los dos idiomas.

Este documento describe requisitos normativos de forma general; no es asesoría legal. Para AB 1757, la App Store Review y el manejo de datos personales, vale la pena una consulta con un abogado antes de publicar.
