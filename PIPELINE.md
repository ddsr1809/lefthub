# Pipeline de VocesLeft

Este pipeline cubre el backend que corre en Docker. Android e iOS conservan
sus compilaciones independientes porque no se despliegan en el VPS.

## Flujo

| Ambiente | Rama | Servidor | App Android |
|---|---|---|---|
| developer | `development` | Local, en tu equipo (`make local-up`) | `developerDebug` |
| testing | `testing` | `testapp.vocesdeizquierda.com` | `pruebasDebug` |
| produccion | `master` | `leftapp.vocesdeizquierda.com` | `produccionRelease` |

El codigo sube en ese orden: se trabaja en `development`, se fusiona a
`testing` para probar en el servidor y, cuando esta sano, se fusiona a `master`.

| Evento | Resultado |
|---|---|
| Pull request a `development`, `testing` o `master` | Pruebas Java y construccion de la imagen, sin desplegar |
| Push a `development` | Pruebas y publicacion de la imagen, sin desplegar |
| Push a `testing` | Publica la imagen y despliega `vocesleft-test` con `.env.test` |
| Push a `master` | Publica la imagen y despliega `vocesleft-prod` con `.env.prod`. Si la version es nueva, crea el tag `vX.Y.Z` (ver "Versiones") |
| Ejecucion manual desde cualquier rama | Permite validar (`none`) o desplegar `test` |
| Ejecucion manual desde `master` | Tambien permite desplegar `prod` |

En el VPS y en GitHub los ambientes desplegados conservan sus nombres cortos:
`test` y `prod` (Environments, archivos `.env` y carpetas `runtime`).

Cada imagen queda identificada por el SHA completo del commit. El VPS conserva
Postgres y sus volumenes; el pipeline solo reemplaza el contenedor `servidor`.
Si el healthcheck no queda saludable en dos minutos, intenta restaurar la
imagen anterior.

La imagen confirmada queda guardada de forma atomica en un archivo
`.env.imagen` separado para cada ambiente. Un rollback exitoso tambien restaura
ese archivo. El rollback cubre la imagen de la aplicacion, no revierte
migraciones de Flyway; las migraciones deben ser compatibles con la version
anterior durante al menos un despliegue.

## Versiones

La version del servidor es la linea `version = "X.Y.Z"` de
`relay-server/build.gradle.kts`. Cada servidor dice cual corre y de que commit
salio, sin token:

```bash
curl https://testapp.vocesdeizquierda.com/api/servidor
curl https://leftapp.vocesdeizquierda.com/api/servidor
```

El panel lo ensena en el Resumen. Un servidor compilado a mano (sin el
pipeline) no trae commit, y ejecutado desde el IDE dice `desarrollo`.

### Publicar una version

La primera vez que una version llega a `master` es un lanzamiento: la imagen
recibe tambien el numero (`ghcr.io/ddsr1809/vocesleft-relay:X.Y.Z`) y, cuando
produccion queda sana, el pipeline crea el tag `vX.Y.Z` en el repositorio. Si
el despliegue falla no hay tag y el siguiente intento vuelve a contar como
lanzamiento.

Los pushes siguientes a `master` con la misma version no mueven ese numero: su
imagen solo se identifica por el commit y el pipeline lo avisa. Para publicar
otra version hay que subir el numero en `build.gradle.kts`.

### Volver a una version exacta

En el VPS, con el numero de una version publicada:

```bash
cd /opt/vocesleft/runtime/prod
printf 'SERVIDOR_IMAGEN=%s\n' ghcr.io/ddsr1809/vocesleft-relay:X.Y.Z > .env.imagen
docker compose \
  --env-file /opt/vocesleft/relay-server/.env.prod \
  --env-file .env.imagen \
  -p vocesleft-prod \
  -f docker-compose.yml \
  up -d --no-deps --force-recreate servidor
```

Las migraciones no se deshacen: la version anterior arranca sobre el esquema
nuevo porque todas las migraciones son aditivas. Por eso **un numero de
migracion que ya llego a algun servidor no se reutiliza ni se borra**; lo que
haya que deshacer va en una migracion nueva. Reutilizar el numero deja al
servidor sin arrancar con `Migration checksum mismatch`.

### Dar de baja versiones de la app

Las apps mandan en cada peticion `X-App-Version` y `X-App-Plataforma`, y el
servidor anota con que version entra cada cuenta cada vez que abre la app. La
seccion **Apps** del panel ensena cuantas cuentas tiene cada version y deja
dar de baja las que se quieran, una a una, y volver a atenderlas.

- Una version dada de baja recibe un 426 en todas sus peticiones. La app de
  Android ensena entonces una pantalla que pide actualizar desde Google Play;
  las versiones anteriores a esa pantalla solo muestran el mensaje como error.
- **Sin identificar** son las apps que no mandan su version (las primeras que
  se publicaron) y las cuentas que no han vuelto a abrir la app. Darlas de
  baja deja fuera a toda app que no diga su version.
- **Antes de dar de baja una version, la nueva tiene que estar publicada**:
  quien se quede fuera solo puede arreglarlo actualizando.
- El cambio vale en medio minuto como mucho y no reinicia nada. La lista se
  guarda en la tabla `ajustes`, por ambiente.

### Mantenimiento y servidor caido

La seccion **Apps** del panel tiene un interruptor de mantenimiento, con el
mensaje que se le quiera dejar a la gente. Encendido, todas las peticiones de
las apps reciben un 503 con `"mantenimiento": true` y ese mensaje.

- El panel sigue funcionando, y el servidor sigue recibiendo las publicaciones
  de YouTube y mandando los avisos: lo unico que se corta es la app.
- La app de Android pregunta a `GET /api/servidor` cada vez que pasa a primer
  plano. En mantenimiento ensena el mensaje a pantalla completa; si el
  servidor no responde y el telefono si tiene internet, avisa de que no puede
  conectar. En los dos casos vuelve a preguntar sola cada medio minuto y,
  cuando el servidor contesta, arranca de nuevo.
- "No responde" incluye el minuto en que el pipeline recrea el contenedor en
  cada despliegue: Apache contesta 503 y quien abra la app justo entonces vera
  el aviso hasta que el servidor vuelva.
- Las versiones de la app anteriores a estas pantallas solo muestran el
  mensaje como un error.
- El cambio vale en medio minuto como mucho y se guarda por ambiente.

### Version minima de las apps

Ademas del panel, el `.env` del ambiente admite una version minima por
plataforma: todo lo anterior queda fuera, sin ir version por version:

```bash
APP_VERSION_MINIMA_ANDROID=1.0.1
APP_VERSION_MINIMA_IOS=
```

y se recrea el servidor (ver "Operacion manual de emergencia"). La app que no
llegue a la minima recibe un 426 con el aviso de que hay que actualizarla.

- **Vacio = se atiende a todas.** Es el valor por defecto.
- **Las apps anteriores a la 1.0.1 no dicen que version son**: cuentan como
  la mas vieja, asi que cualquier minima de Android las deja fuera.
- **Actualiza el panel antes** (`git pull` en `/opt/vocesleft`): el panel
  nuevo se identifica y nunca se queda fuera; el anterior no podria entrar.
- No se aplica al webhook, a las tareas internas, a las fotos ni a
  `/api/admin`.

## Uso local

Desde la raiz del repositorio:

```bash
cp relay-server/.env.local.example relay-server/.env.local
# Rellena secretos y la ruta de las credenciales FCM.

make test
make local-up
make local-status
make local-logs
```

Para detenerlo sin borrar la base de datos:

```bash
make local-down
```

`RELAY_URL_PUBLICA=http://localhost:8080` permite probar la API, pero Google no
puede entregar WebSub a localhost. Para probar notificaciones de extremo a
extremo, usa una URL HTTPS publica temporal en `.env.local`.

## Estructura del VPS

Con `VPS_APP_DIR=/opt/vocesleft`, los secretos actuales permanecen donde estan:

```text
/opt/vocesleft/relay-server/.env.test
/opt/vocesleft/relay-server/.env.prod
```

El pipeline crea y administra automaticamente directorios separados que no
forman parte del clon Git:

```text
/opt/vocesleft/runtime/test/docker-compose.yml
/opt/vocesleft/runtime/test/.env.imagen
/opt/vocesleft/runtime/prod/docker-compose.yml
/opt/vocesleft/runtime/prod/.env.imagen
```

No ejecutes `git pull`, `docker compose build` ni `docker compose up` dentro de
los directorios `runtime`. El pipeline copia el Compose exclusivo del VPS y
solo utiliza imagenes inmutables de GHCR. Los JSON de FCM permanecen en las
rutas absolutas declaradas mediante `FCM_CREDENCIALES_HOST`.

## Copia de creadores a testing

Cada creador que se guarda en el panel de produccion (alta o cambio) se copia
solo a testing. Produccion lo manda a `POST /internal/replica/creadores` de
testing, con un token compartido; testing lo guarda y se suscribe por su
cuenta al hub de YouTube.

Las productoras se copian igual, por `POST /internal/replica/productoras`. Un
creador solo queda ligado en testing a las productoras que testing ya conoce,
asi que el script de abajo las manda primero.

Con que otros creadores aparece un canal viaja dentro de su dueno (su creador
o, si no tiene, su productora), y testing solo puede ligarlo a los creadores
que ya conoce. Por eso la copia completa da una segunda vuelta a quien tiene
canales compartidos, y guardar la ficha de un canal vuelve a copiar a su dueno.

Se enciende una vez, en el **VPS**, cuando los dos ambientes ya tienen esta
version desplegada. El mismo comando copia los creadores que ya existian:

```bash
git -C /opt/vocesleft pull
bash /opt/vocesleft/scripts/vps/replicar-creadores.sh
```

La primera vez escribe `REPLICA_URL` y `REPLICA_TOKEN` en `.env.prod` y
`REPLICA_TOKEN` en `.env.test`, y reinicia los dos servidores. Se puede repetir
cuando se quiera: no duplica nada.

Lo que conviene saber:

- **La copia tiene otro id.** Los dos servidores comparten proyecto de Firebase
  y el topic de los avisos sale del id del creador; con el mismo id, un aviso
  de testing llegaria a la app de produccion. Testing guarda el id de
  produccion en `creadores.origen_id` para reconocer al creador cuando cambia.
- **Los borrados no se copian.** Retirar un creador en produccion lo deja como
  esta en testing; alli se borra desde su propio panel.
- **Produccion manda, salvo sobre lo que testing tiene sin migrar.** Un creador
  o una productora que se cambio en el panel de testing queda protegido: la
  copia de produccion no lo pisa, produccion guarda igual y su panel avisa de
  que no se copio. Se resuelve al migrar esa version a produccion, o
  descartando el cambio en el panel de testing (seccion Versiones). Lo que
  testing no ha tocado se sigue copiando como siempre. Ver "Versiones y
  migracion de pruebas a produccion" en `README.md`.
- **Si testing no responde, produccion guarda igual** y el panel avisa de que
  la copia fallo. Para ponerse al dia basta repetir el script.
- Si en testing ya existia un creador con ese canal de YouTube, la copia lo
  adopta en lugar de duplicarlo.

## Respaldo del directorio en datos/

Los creadores, las productoras y los canales de testing y de produccion se
guardan tambien en archivos, en `datos/testing/` y `datos/produccion/` de este
repositorio. Un temporizador del VPS lee las dos bases cada 5 minutos y, si
algo cambio, hace un commit en `master` y lo sube. Que hay en esos archivos y
como se recupera algo desde ellos esta en `datos/README.md`.

Se enciende una vez, en el **VPS**, cuando `master` ya trae esta version:

```bash
git -C /opt/vocesleft pull
bash /opt/vocesleft/scripts/vps/respaldo-datos.sh instalar
```

La primera vez se detiene y muestra una llave: hay que agregarla en GitHub
(Settings del repositorio, Deploy keys) marcando **Allow write access**, y
repetir el comando. Es la unica llave del VPS que puede escribir en el
repositorio, y solo vale para este.

Lo que conviene saber:

- **No redespliega nada.** Esos commits solo tocan `datos/`, que no esta entre
  las rutas que disparan el pipeline.
- **`master` recibe commits que `development` y `testing` no tienen.** No
  estorban al fusionar con pull requests, que es como sube el codigo. Lo que
  ya no funciona es empujar directo a `master` desde tu equipo sin traer antes
  lo ultimo.
- **Usa un clon aparte**, `/opt/vocesleft-datos`. El de `/opt/vocesleft`, de
  donde sale el panel, no se toca: sigue actualizandose solo cuando haces
  `git pull` en el.
- **Si `master` esta protegida** contra subidas directas, GitHub rechazara el
  commit y la pasada lo dira en su registro. Hay que permitir esa llave en la
  regla, o indicar otra rama con `RAMA=...` al instalar.
- Ver las ultimas pasadas: `journalctl -u vocesleft-datos -n 30 --no-pager`.
- El ambiente developer no pasa por aqui: `make datos-guardar` en tu equipo
  escribe `datos/development/`.

## Configurar SSH y GitHub

Todo lo de esta seccion y la siguiente lo hace un solo comando desde tu laptop:

```bash
bash scripts/configurar-github.sh
```

Crea la llave, la autoriza en el VPS, comprueba las huellas, crea los
Environments y guarda los secretos. Lo que sigue describe lo mismo paso a
paso, por si necesitas hacerlo o revisarlo a mano.


La llave del pipeline debe ser exclusiva y no tener passphrase, porque GitHub
Actions no puede responder una solicitud interactiva:

```bash
ssh-keygen -t ed25519 -N "" \
  -C vocesleft-github-actions-ci \
  -f "$HOME/.ssh/vocesleft_actions_ci"

ssh-copy-id -i "$HOME/.ssh/vocesleft_actions_ci.pub" \
  -p 22 root@216.238.70.157
```

Prueba exactamente el modo no interactivo del pipeline:

```bash
SSH_AUTH_SOCK= ssh \
  -o BatchMode=yes \
  -o IdentitiesOnly=yes \
  -i "$HOME/.ssh/vocesleft_actions_ci" \
  -p 22 root@216.238.70.157 whoami
```

### Environments y secretos

En **Settings > Environments**, crea `test` y `prod`. En cada Environment agrega
estos secretos (pueden apuntar al mismo VPS):

| Secreto | Valor de VocesLeft |
|---|---|
| `VPS_HOST` | `216.238.70.157` |
| `VPS_PORT` | `22` |
| `VPS_USER` | `root` |
| `VPS_SSH_KEY` | Contenido completo de la llave privada sin passphrase |
| `VPS_KNOWN_HOSTS` | Resultado de `ssh-keyscan -p 22 -t ed25519,ecdsa,rsa 216.238.70.157` (sin `-H`) |
| `VPS_APP_DIR` | `/opt/vocesleft` |

En `prod` es obligatorio permitir despliegues solamente desde `master`. Tambien
se recomienda activar **Required reviewers**. El workflow contiene una segunda
proteccion: un despliegue manual de `prod` se omite si la rama no es `master`.

## Operacion manual de emergencia

El pipeline es el metodo normal de despliegue. Para consultar `test` en el VPS:

```bash
cd /opt/vocesleft/runtime/test
docker compose \
  --env-file /opt/vocesleft/relay-server/.env.test \
  --env-file .env.imagen \
  -p vocesleft-test \
  -f docker-compose.yml \
  ps
```

Para recrear solamente el servidor de `test` sin compilar nada:

```bash
docker compose \
  --env-file /opt/vocesleft/relay-server/.env.test \
  --env-file .env.imagen \
  -p vocesleft-test \
  -f docker-compose.yml \
  up -d --no-deps --force-recreate servidor
```

Para `prod`, cambia `test` por `prod` en la ruta, el archivo `.env` y el nombre
del proyecto. Nunca uses `--build` en el VPS.

## Verificacion

Despues de un despliegue a `test`:

```bash
curl -fsS http://127.0.0.1:8081/actuator/health
```

Despues de un despliegue a `prod`:

```bash
curl -fsS http://127.0.0.1:8080/actuator/health
```
