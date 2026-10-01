# Pipeline de TubeHub

Este pipeline cubre el backend que corre en Docker. Android e iOS conservan
sus compilaciones independientes porque no se despliegan en el VPS.

## Flujo

| Evento | Resultado |
|---|---|
| Pull request a `development` o `master` | Pruebas Java y construccion de la imagen, sin desplegar |
| Push a `development` | Publica la imagen y despliega `relay-test` con `.env.test` |
| Push a `master` | Publica la imagen y despliega `relay-prod` con `.env.prod` |
| Ejecucion manual desde cualquier rama | Permite validar (`none`) o desplegar `test` |
| Ejecucion manual desde `master` | Tambien permite desplegar `prod` |

Cada imagen queda identificada por el SHA completo del commit. El VPS conserva
Postgres y sus volumenes; el pipeline solo reemplaza el contenedor `servidor`.
Si el healthcheck no queda saludable en dos minutos, intenta restaurar la
imagen anterior.

La imagen confirmada queda guardada de forma atomica en un archivo
`.env.imagen` separado para cada ambiente. Un rollback exitoso tambien restaura
ese archivo. El rollback cubre la imagen de la aplicacion, no revierte
migraciones de Flyway; las migraciones deben ser compatibles con la version
anterior durante al menos un despliegue.

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

Con `VPS_APP_DIR=/opt/tubehub`, los secretos actuales permanecen donde estan:

```text
/opt/tubehub/relay-server/.env.test
/opt/tubehub/relay-server/.env.prod
```

El pipeline crea y administra automaticamente directorios separados que no
forman parte del clon Git:

```text
/opt/tubehub/runtime/test/docker-compose.yml
/opt/tubehub/runtime/test/.env.imagen
/opt/tubehub/runtime/prod/docker-compose.yml
/opt/tubehub/runtime/prod/.env.imagen
```

No ejecutes `git pull`, `docker compose build` ni `docker compose up` dentro de
los directorios `runtime`. El pipeline copia el Compose exclusivo del VPS y
solo utiliza imagenes inmutables de GHCR. Los JSON de FCM permanecen en las
rutas absolutas declaradas mediante `FCM_CREDENCIALES_HOST`.

## Configurar SSH

La llave del pipeline debe ser exclusiva y no tener passphrase, porque GitHub
Actions no puede responder una solicitud interactiva:

```bash
ssh-keygen -t ed25519 -N "" \
  -C tubehub-github-actions-ci \
  -f "$HOME/.ssh/tubehub_actions_ci"

ssh-copy-id -i "$HOME/.ssh/tubehub_actions_ci.pub" \
  -p 22 root@TU_HOST_VPS
```

Prueba exactamente el modo no interactivo del pipeline:

```bash
SSH_AUTH_SOCK= ssh \
  -o BatchMode=yes \
  -o IdentitiesOnly=yes \
  -i "$HOME/.ssh/tubehub_actions_ci" \
  -p 22 root@TU_HOST_VPS whoami
```

## Configurar GitHub

En **Settings > Environments**, crea `test` y `prod`. En cada Environment agrega
estos secretos (pueden apuntar al mismo VPS):

| Secreto | Valor actual de TubeHub |
|---|---|
| `VPS_HOST` | `ythub.d2600.com` |
| `VPS_PORT` | `22` |
| `VPS_USER` | `root` |
| `VPS_SSH_KEY` | Contenido completo de la llave privada sin passphrase |
| `VPS_KNOWN_HOSTS` | Resultado de `ssh-keyscan -H -p 22 ythub.d2600.com` |
| `VPS_APP_DIR` | `/opt/tubehub` |

En `prod` es obligatorio permitir despliegues solamente desde `master`. Tambien
se recomienda activar **Required reviewers**. El workflow contiene una segunda
proteccion: un despliegue manual de `prod` se omite si la rama no es `master`.

## Operacion manual de emergencia

El pipeline es el metodo normal de despliegue. Para consultar `test` en el VPS:

```bash
cd /opt/tubehub/runtime/test
docker compose \
  --env-file /opt/tubehub/relay-server/.env.test \
  --env-file .env.imagen \
  -p relay-test \
  -f docker-compose.yml \
  ps
```

Para recrear solamente el servidor de `test` sin compilar nada:

```bash
docker compose \
  --env-file /opt/tubehub/relay-server/.env.test \
  --env-file .env.imagen \
  -p relay-test \
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
