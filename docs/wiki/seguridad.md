[← Inicio](README.md)

# Seguridad

Qué protege a las personas que usan la app y al dueño del sistema, dónde están las llaves y qué hacer en el VPS. Desde el servidor 1.0.15 y la app Android 1.0.18.

## En resumen

| Capa | Qué la protege |
| --- | --- |
| Datos de las personas en la base | Correo, IP, token de Apple, comprobante y orden de compra, y autor de altas desde la app, cifrados con AES-256-GCM. El chat ya iba cifrado. El identificador del teléfono se guarda solo como huella |
| Llaves | En el `.env` de cada ambiente, nunca en la base. Van también dentro del respaldo cifrado |
| Sesiones | JWT de 30 días que se comprueban contra la base en cada petición: se pueden cerrar, y quitar el rol de administrador surte efecto al momento |
| Panel y chat del equipo | Solo con una sesión abierta con Google o Apple. El identificador del teléfono nunca da acceso de administrador |
| Entrar | Límite de entradas por IP en `/api/auth`; fail2ban saca del firewall a quien insiste |
| Quién habla con la API | Solo la app original: firma con la llave de la app y App Check (Play Integrity). Se elige en el panel si solo se avisa o se exige |
| VPS | Firewall (solo SSH, 80 y 443), fail2ban, parches automáticos, SSH endurecido, Apache sin versión y con HSTS y CSP en el panel |
| Respaldos | Diarios, cifrados para la llave pública del dueño: el VPS puede cifrarlos pero no abrirlos |
| Retención | La IP, la compañía y el agente se borran tras 90 días sin abrir la app |

## Los datos cifrados

`seguridad/CifradorDeDatos` cifra cada valor por separado: `c1:<llave>:<base64>`. Cada valor lleva su propio IV al azar y va ligado a su columna: un correo cifrado copiado a la columna de la IP no se descifra. Hibernate cifra al guardar y descifra al leer, así que el resto del código y el panel ven el texto normal.

| Columna | Cifrada | Para buscarla |
| --- | --- | --- |
| `usuarios.email` | Sí | `email_huella` (correo exacto) |
| `usuarios.ip` | Sí | `ip_huella` (IP exacta) |
| `usuarios.apple_refresh` | Sí | No se busca |
| `usuarios.device_id` | Huella SHA-256 (`h1:…`) | Es la propia huella |
| `compras.token`, `compras.orden` | Sí | `token_huella` (única) |
| `altas_desde_app.autor` | Sí | No se busca |
| `mensajes_chat` | Sí (desde V23, `CHAT_LLAVES`) | No se busca |

Las huellas son HMAC-SHA256 con `DATOS_INDICE`. Sirven para encontrar «este correo exacto» sin descifrar todas las cuentas, y no dejan recuperar el dato. La consecuencia en el panel es que **un correo o una IP se buscan escritos completos**; la compañía de internet se sigue buscando por trozos. La columna «Cuenta» ya no ordena alfabéticamente por correo, sino por tipo de cuenta.

**Por qué el identificador del teléfono va como huella.** Es lo que la app presenta en `/api/auth/anonimo` para recuperar su cuenta sin pedir nada: en la práctica, una contraseña. Guardado tal cual, quien tuviera una copia de la base podría entrar en cualquier cuenta.

**Qué no se cifra.** El país, el ASN, la compañía de internet y el agente (no identifican a nadie por sí solos y el panel los agrupa), los favoritos, las preferencias y el directorio. El `sub` de Google o Apple tampoco: es un identificador, no un secreto.

**La primera vez.** V26 solo agrega columnas. Al arrancar, `CifradoDeDatosExistentes` cifra lo que siga legible y convierte los `device_id` en huella. Las siguientes veces no encuentra nada y termina enseguida.

## Las llaves

| Variable | Qué cifra | Si se pierde | ¿Se puede cambiar? |
| --- | --- | --- | --- |
| `DATOS_LLAVES` | Correo, IP, token de Apple, compras | Esos datos no se leen (siguen guardados, cifrados) | Sí: `1:vieja,2:nueva`. Se cifra con la de número más alto |
| `DATOS_INDICE` | Las huellas | Las búsquedas por correo/IP y el reconocimiento de compras dejan de encontrar lo guardado | **No** |
| `CHAT_LLAVES` | Mensajes del chat | Los mensajes no se leen | Sí, igual que `DATOS_LLAVES` |
| `JWT_SECRETO` | Firma de las sesiones | Nada; cambiarla cierra todas las sesiones | Sí |

- **Dónde viven:** `/opt/vocesleft/relay-server/.env.prod` y `.env.test`, con permisos 600. Cada ambiente tiene las suyas.
- **Quién las crea:** `scripts/deploy-vps.sh` en el primer despliegue que no las encuentra (no se imprimen), `preparar-vps.sh` en un VPS nuevo y `./local.sh` en tu equipo.
- **Copia fuera del VPS:** el respaldo cifrado ya las incluye. Además, guarda una copia en tu gestor de contraseñas: `grep -E '^(DATOS_|CHAT_)' /opt/vocesleft/relay-server/.env.prod`.
- **Sin ellas el servidor no arranca.** Es a propósito: es peor guardar datos sin cifrar por descuido.
- **Si la llave está mal** (alguien la cambió en vez de agregar una nueva), el servidor arranca, los datos afectados se quedan cifrados tal cual (nunca se borran) y el registro dice `no se pueden descifrar con DATOS_LLAVES`. En el panel se ven correos que empiezan por `c1:`. Se arregla reponiendo la llave anterior.

## Sesiones

- El token dura 30 días y la app lo renueva al abrirse. En cada petición, el servidor comprueba en la base (con 30 s de memoria) que la cuenta existe y que nadie cerró sus sesiones después de emitirlo.
- **Administrador = la base y el token de acuerdo.** El rol solo viaja en tokens emitidos al entrar con Google o Apple (o al renovar uno que ya lo tenía). Quitar el rol en el panel corta el acceso al momento, aunque esa persona tenga el panel abierto.
- **El teléfono nunca da acceso de administrador.** Si la sesión del dueño caduca tras un mes sin abrir la app, la app recupera la cuenta con el identificador del teléfono, pero sin rol: para el chat del equipo hay que «Cerrar sesión» y volver a «Guardar con Google».
- **Cerrar todas las sesiones de una cuenta:** Panel → Usuarios → la cuenta → «Cerrar todas sus sesiones» (`POST /api/admin/usuarios/{id}/cerrar-sesiones`), o la propia persona con `POST /api/cuenta/cerrar-sesiones`. Además desliga su teléfono: vuelve a entrar con Google o Apple y no pierde nada. A un invitado no se le puede hacer (sin teléfono, su cuenta quedaría perdida).
- **«Cerrar sesión» en Android ahora sí cierra.** Antes, la app olvidaba su token y la sesión invisible siguiente devolvía la misma cuenta de Google. Ahora llama a `/api/auth/salir`, que desliga el teléfono. Las versiones anteriores de la app siguen con el comportamiento viejo.
- **Límite de entradas:** 60 por IP de margen y una más cada 2 s en `/api/auth/*`. Es generoso a propósito: muchos teléfonos comparten IP en las redes móviles (CGNAT).

## Llave de la app

Que solo la app oficial hable con `leftapp.vocesdeizquierda.com`. Cada petición de la app trae dos pruebas, y el servidor (`seguridad/FiltroDeApp`) las comprueba:

| Prueba | Qué demuestra | Límite |
| --- | --- | --- |
| **Firma con la llave de la app** (`X-App-Llave`, `X-App-Hora`, `X-App-Nonce`, `X-App-Firma`) | Que quien llama tiene la llave que va dentro de la app. HMAC-SHA256 del método, la ruta, la hora y un nonce de un solo uso: una petición copiada no se puede repetir, y una de hace más de 5 minutos no vale | La llave va dentro del APK: quien descompile la app con paciencia puede sacarla. Frena a scripts, bots y escáneres, no a alguien decidido |
| **App Check con Play Integrity** (`X-Firebase-AppCheck`) | Google confirma que es la app firmada por Play, instalada desde Play, en un teléfono Android real. El token lo firma Google y dura una hora | Se quedan sin token los teléfonos sin servicios de Google (algunos Huawei), con root, o con la app instalada fuera de Play |

**No las necesitan:** el panel (va con su sesión de administrador), entrar con Google o Apple (exige un token real de esa cuenta), `/api/servidor`, las fotos públicas (`/api/fotos`), el webhook de YouTube y las tareas internas.

**Qué se hace con las que fallan** se elige en el panel, **Apps → Llave de la app**, para cada prueba por separado, y vale en medio minuto:

- **Solo avisar** (como empieza): se cuentan y pasan todas. La tarjeta enseña, de las últimas 24 horas, cuántas venían bien.
- **Exigir**: la que no la trae buena no pasa. Una app sin ninguna de las dos (versiones anteriores a 1.0.18) recibe 426 y enseña la pantalla de «actualiza la app». Una con la prueba mala recibe 403.
- Si algo sale mal, se vuelve a «Solo avisar» y en medio minuto todo pasa otra vez.

Cada hora el registro del servidor también resume: `Llave de la app, última hora: N petición(es)…`.

**Quién trae la llave y quién no.** El servidor anota en cada cuenta cómo llegó su última petición (`seguridad/RegistroDeLlaves`, columnas `llave_firma`, `llave_app_check` y `llave_vista_en` de V27). Se escribe una vez por minuto, no en cada petición. En el panel:

- **Apps → Llave de la app → Por cuenta**: cuántas cuentas de las que abrieron la app en 7 días traen cada cosa, con un botón **Ver** que lleva a esas cuentas.
- **Usuarios**: la fila «Llave» de filtros (Firma bien, Sin firma, Firma con error, App Check bien, Sin App Check, App Check con error, Sin datos de llave), insignias en cada cuenta y el detalle en su ficha, junto con su versión de la app.

**Versión de la app, número de llave y app de App Check.** Con la misma anotación de cada minuto se guarda también, de la última petición de cada cuenta (V30):

- `app_version` / `app_plataforma` / `app_version_orden`: la versión de la app (cabecera `X-App-Version`). Antes solo cambiaba al entrar o renovar la sesión; ahora cambia en cuanto la cuenta abre la app con una versión nueva. `app_version_orden` es `mayor·1.000.000 + menor·1.000 + parche`, para ordenar 1.1.10 después de 1.1.8.
- `llave_numero`: el número de `APP_LLAVES` con que firmó (`X-App-Llave`: 1, 2…).
- `llave_app_check_app`: de qué app de Firebase venía su App Check (el `sub` del token, solo si era bueno). El panel lo traduce: Producción, Pruebas, Developer; una que no conoce sale como «Otra app (…últimos 6)» (`VerificadorAppCheck.NOMBRES_DE_APPS`).

En **Usuarios**:

- La tarjeta **Versiones y llaves** (`GET /api/admin/usuarios/versiones`): cada versión con sus cuentas, las activas en 7 días, con qué número de llave firman y de qué app viene su App Check, y botones **Ver**. Debajo, el total por número de llave (de qué versión a qué versión) y por app de App Check. Se pone al día sola cada minuto mientras está a la vista (sin ventana abierta y con la pestaña visible); la casilla «Actualizar cada minuto» lo apaga.
- Las columnas **Versión** y **Llave** de la lista ordenan con un clic; la versión anterior a la más nueva lleva la insignia «Anterior».
- Las filas de filtros **Versión** (`versiones=android:1.2.2,sin`) y **N.º llave** (`llaves=1,2,sin`): vale cualquiera de las marcadas, y se combinan con los demás filtros.

Ejemplo: con 1.1.8 (llave 1) y 1.2.2 (llave 2) publicadas, quien instala la 1.2.2 deja de contar en la fila de la 1.1.8 y suma en la de la 1.2.2 en cuanto abre la app. Cuando la tarjeta dice que la llave 1 ya no tiene cuentas activas, se puede quitar `1:` de `APP_LLAVES`.

«Sin firma» con una versión anterior a 1.0.18 es lo normal. «Firma con error» casi siempre es una app compilada con otra llave: comparar `llave_app_*` de `firma.properties` con `APP_LLAVES` del servidor.

### Poner la firma

1. El despliegue crea `APP_LLAVES` en cada `.env` (`.env.test` y `.env.prod`, distintas).
2. Cópialas a `android/firma.properties` (fuera de git):
   ```bash
   ssh root@216.238.70.157 "grep ^APP_LLAVES /opt/vocesleft/relay-server/.env.test /opt/vocesleft/relay-server/.env.prod"
   ```
   ```properties
   llave_app_pruebas=1:…      # la de .env.test
   llave_app_produccion=1:…   # la de .env.prod
   ```
3. Compila la app como siempre. Si falta `llave_app_produccion`, Gradle lo avisa y la app sale sin firma.

**Cambiar de llave** (si sospechas que alguien la sacó): agrega una nueva sin quitar la vieja, `APP_LLAVES=1:vieja,2:nueva`, y recrea el servidor. Pon la línea completa en `firma.properties` (la app firma con la de número más alto) y publica la app. Cuando en **Usuarios → Versiones y llaves** la llave 1 diga «Nadie activo firma con ella», quita la `1:` del servidor.

### Poner App Check (Play Integrity)

Una sola vez, en las consolas de Google (unos 15 minutos):

1. **Huella de la llave de Play.** Play Console → la app → *Probar y publicar → Configuración de la app → Integridad de la app → Firma de apps*: copia el **SHA-256 del certificado de la clave de firma de apps**.
2. **Firebase.** [console.firebase.google.com](https://console.firebase.google.com) → el proyecto (número `1025901070678`) → ⚙ *Configuración del proyecto* → la app Android `com.vocesdeizquierda.lefthub` → *Agregar huella digital*: pega ese SHA-256.
3. **App Check.** Firebase → *App Check* → *Apps* → `com.vocesdeizquierda.lefthub` → **Play Integrity** → pega el mismo SHA-256 → Guardar. Si te pide activar la API de Play Integrity en Google Cloud, actívala.
4. **Vincular Play con Google Cloud.** Play Console → *Integridad de la app* → *API de Play Integrity* → *Vincular un proyecto de Cloud* → el del proyecto de Firebase.
5. **No** pulses «Aplicar» en las tarjetas de servicios de Firebase de App Check: eso es para Firestore y compañía, que no usamos. Lo comprueba nuestro servidor.
6. Para `com.vocesdeizquierda.lefthub.pruebas` haz lo mismo si la instalas desde Play (prueba interna). Si la instalas con un APK, regístrala con el proveedor **Debug**, como developer:
7. **developer:** App Check → `…lefthub.developer` → *Debug* → al abrir la app, Logcat escribe `Enter this debug secret into the allow list…`; pega ese secreto en *Administrar tokens de depuración*.

El servidor ya trae `APP_CHECK_PROYECTO=1025901070678`. Para aceptar solo la app de cada ambiente, pon en el `.env` su `APP_CHECK_APPS` (el `mobilesdk_app_id` de `google-services.json`): producción `1:1025901070678:android:bdaed1e83362e0074b6ef7`, pruebas `1:1025901070678:android:1b22a606c86d110b4b6ef7`.

Play Integrity tiene una cuota gratuita de 10 000 consultas al día por app. App Check renueva el token una vez por hora y por teléfono mientras la app está abierta, así que alcanza para varios miles de personas al día; si se acerca al límite, Play Console deja pedir más.

### Cuándo exigir

1. Publica la app 1.0.18 (la primera con las dos pruebas) y deja todo en «Solo avisar» unos días.
2. Cuando en la tarjeta casi todas salgan «Bien» en la firma, exige la firma. Para que nadie con una versión anterior se quede con errores raros, puedes además dar de baja esas versiones en **Apps** (verán «actualiza»).
3. App Check, igual, pero mira con calma «Sin token» y «Token falso»: son teléfonos reales que no pasan Play Integrity. Exigirlo los deja fuera.

## El VPS

Una vez, y cada vez que quieras volver a aplicarlo (es repetible):

```bash
cd /opt/vocesleft && git pull
bash scripts/vps/endurecer-vps.sh
```

| Hace | Detalle |
| --- | --- |
| Parches automáticos | `unattended-upgrades` diario. Registro en `/var/log/unattended-upgrades/` |
| Firewall | `ufw`: entran SSH, 80 y 443. **Se detiene sin encender nada** si encuentra otro servicio escuchando hacia fuera (otro sistema en el VPS). Entonces: `PUERTOS_EXTRA="3000" bash …` para dejarlo abierto, o `FORZAR_FIREWALL=si bash …` para cerrarlo |
| fail2ban | SSH: 5 fallos en 10 min = 1 h fuera; reincidir = 1 semana. `/api/auth`: 30 respuestas 429 en 10 min = 15 min fuera. `fail2ban-client status sshd` para ver a quién |
| SSH | Menos intentos por conexión, sin X11. Contraseñas: siguen, salvo que lo pidas (abajo) |
| Apache | Sin versión, sin TRACE, HSTS de un año, nosniff, Referrer-Policy, Permissions-Policy. En `/admin`, una Content-Security-Policy: el panel solo ejecuta su propio código y el de Google para entrar. Con `CSP_PANEL=reporte` solo avisa en la consola |
| Permisos | `.env.*` en 600 y `/root/.config/vocesleft` en 700 |

**SSH solo con llaves (recomendado).** Las contraseñas son lo primero que prueban los bots.

1. En tu computadora: `ssh-keygen -t ed25519` (si no tienes llave) y `ssh-copy-id root@216.238.70.157`.
2. Comprueba que entras sin contraseña: `ssh root@216.238.70.157`.
3. En el VPS: `SSH_SOLO_LLAVES=si bash /opt/vocesleft/scripts/vps/endurecer-vps.sh`.
4. **Sin cerrar esa sesión**, abre otra terminal y vuelve a entrar con la llave. Si no entras, en la sesión abierta borra `/etc/ssh/sshd_config.d/10-vocesleft.conf` y `systemctl reload ssh`. Si te quedas fuera del todo, la consola web de Vultr sigue funcionando.

La llave con la que despliega GitHub (`VPS_SSH_KEY`) no se ve afectada.

## Respaldos cifrados

`scripts/vps/respaldo-base.sh` guarda cada día, por ambiente, la base completa y su `.env` con las llaves (más la cuenta de Firebase), cifrados con la llave **pública** del dueño. Nada se escribe sin cifrar en el disco. El VPS no puede abrirlos: solo tu llave privada.

**Una vez, en tu computadora** (no en el VPS):

```bash
brew install age            # o: sudo apt install age
age-keygen -o ~/vocesleft-respaldos.key
```

Guarda `~/vocesleft-respaldos.key` en un lugar seguro y con copia (gestor de contraseñas, una USB guardada). Sin ella los respaldos no se abren. Copia la línea `public key: age1…`.

**En el VPS:**

```bash
echo 'age1…' > /root/.config/vocesleft/respaldo.pub
bash /opt/vocesleft/scripts/vps/respaldo-base.sh instalar   # instala age, el temporizador y hace el primero
bash /opt/vocesleft/scripts/vps/respaldo-base.sh estado
```

Quedan en `/var/backups/vocesleft/<fecha>/` (14 días). **Bájalos a tu equipo** con regularidad, porque un respaldo que solo vive en el VPS se pierde con el VPS:

```bash
rsync -av root@216.238.70.157:/var/backups/vocesleft/ ~/respaldos-vocesleft/
```

Para copiarlos solos a otro lugar (Backblaze B2, Google Drive…), configura `rclone` en el VPS y pon `RESPALDO_RCLONE=remoto:carpeta` al instalar.

### Restaurar un respaldo

En tu computadora, o en un VPS nuevo copiando allí la llave privada solo mientras dura:

```bash
cd ~/respaldos-vocesleft/<fecha>
age -d -i ~/vocesleft-respaldos.key prod-secretos.tar.age | tar x   # el .env y fcm.json, con sus rutas
age -d -i ~/vocesleft-respaldos.key prod-base.dump.age > prod.dump
```

Con el `.env` en su sitio (`/opt/vocesleft/relay-server/.env.prod`) y el servidor detenido:

```bash
cd /opt/vocesleft/runtime/prod
docker compose --env-file ../../relay-server/.env.prod --env-file .env.imagen -p vocesleft-prod -f docker-compose.yml stop servidor
docker compose --env-file ../../relay-server/.env.prod --env-file .env.imagen -p vocesleft-prod -f docker-compose.yml \
  exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists' < prod.dump
docker compose --env-file ../../relay-server/.env.prod --env-file .env.imagen -p vocesleft-prod -f docker-compose.yml start servidor
rm prod.dump
```

El `.env` restaurado trae las mismas `DATOS_LLAVES`, `DATOS_INDICE` y `CHAT_LLAVES`: sin ellas, la base restaurada tendría los correos y el chat ilegibles.

## Al desplegar 1.0.15 por primera vez

1. Pasa por `development` → `testing` como siempre y revisa testing: entrar con Google en la app, buscar una cuenta por correo completo en el panel, abrir una ficha, el chat.
2. El despliegue crea `DATOS_LLAVES` y `DATOS_INDICE` en cada `.env`. Guarda una copia: `grep -E '^DATOS_' /opt/vocesleft/relay-server/.env.prod`.
3. En el registro del arranque busca `Datos personales protegidos al arrancar: N teléfono(s), N cuenta(s)…`.
4. Corre `endurecer-vps.sh` y `respaldo-base.sh instalar` (arriba).
5. Los tokens de administrador emitidos antes con el identificador del teléfono siguen valiendo hasta caducar. Para cortarlos ya, cierra las sesiones de tu propia cuenta en el panel y vuelve a entrar con Google.

**No vuelvas a una versión anterior a 1.0.15 una vez que esta arrancó bien.** La versión anterior no sabe leer los identificadores de teléfono convertidos en huella: cada persona que abriera la app recibiría una cuenta vacía (sus datos siguen ahí, y al volver a 1.0.15 la recupera). Si el servidor nuevo falla *al arrancar*, el pipeline vuelve solo a la versión anterior sin problema: la conversión ocurre solo cuando arranca bien.

## Pendiente o recomendado

- **Verificación en dos pasos** en las cuentas de Google de todos los administradores y en GitHub (quien entra a GitHub puede desplegar).
- **Secretos de GitHub:** revisar quién tiene acceso al repositorio y a `VPS_SSH_KEY`; rotarla si alguien dejó el equipo.
- **App:** un botón «Cerrar sesión en todos los teléfonos» que llame a `POST /api/cuenta/cerrar-sesiones` (hoy se hace desde el panel).
- **iOS:** al migrarla a las sesiones del servidor, guardar el token y el identificador del teléfono en el Llavero (Keychain), no en `UserDefaults`, llamar a `/api/auth/salir` al cerrar sesión, y agregar la firma y App Check (con App Attest). Mientras no los tenga, no exijas ninguna de las dos si hay iPhones usando la app.
- **Política de privacidad:** se actualizó (cifrado y 90 días de IP). Revísala antes de publicarla.
