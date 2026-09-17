# Pipeline de TubeHub

Este pipeline cubre el backend que corre en Docker. Android e iOS conservan
sus compilaciones independientes porque no se despliegan en el VPS.

## Flujo

| Evento | Resultado |
|---|---|
| Pull request a `development` o `master` | Pruebas Java y construccion de la imagen, sin desplegar |
| Push a `development` | Publica la imagen y despliega `relay-test` con `.env.test` |
| Push a `master` | Publica la imagen y despliega `relay-prod` con `.env.prod` |
| Ejecucion manual | Permite solo validar (`none`) o elegir `test`/`prod` |

Cada imagen queda identificada por el SHA completo del commit. El VPS conserva
Postgres y sus volumenes; el pipeline solo reemplaza el contenedor `servidor`.
Si el healthcheck no queda saludable en dos minutos, intenta restaurar la
imagen que estaba ejecutandose antes.

El rollback cubre la imagen de la aplicacion, no revierte migraciones de
Flyway. Las futuras migraciones de base de datos deben ser compatibles con la
version anterior durante al menos un despliegue.

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

Si necesitas otro archivo o nombre de proyecto:

```bash
make local-up ENV_FILE=relay-server/.env.test PROJECT=relay-test-local
```

`RELAY_URL_PUBLICA=http://localhost:8080` permite probar la API, pero Google no
puede entregar WebSub a localhost. Para probar notificaciones de extremo a
extremo, usa una URL HTTPS publica temporal y colocala en `.env.local`.

## Preparar el VPS una sola vez

El directorio indicado por `VPS_APP_DIR` debe existir y contener la carpeta
`relay-server`. Dentro deben vivir estos archivos, creados manualmente y fuera
de Git:

```text
relay-server/.env.test
relay-server/.env.prod
```

Tambien deben existir los JSON de FCM en las rutas declaradas por
`FCM_CREDENCIALES_HOST`. El usuario SSH necesita permiso para ejecutar Docker.

Genera una llave dedicada para el pipeline y agrega solamente su parte publica
a `~/.ssh/authorized_keys` del usuario del VPS:

```bash
ssh-keygen -t ed25519 -C tubehub-github-actions -f tubehub_deploy
ssh-keyscan -H TU_HOST_VPS > tubehub_known_hosts
```

## Configurar GitHub

En **Settings > Environments**, crea `test` y `prod`. En cada Environment agrega
estos secretos (pueden apuntar al mismo VPS):

| Secreto | Ejemplo / funcion |
|---|---|
| `VPS_HOST` | Dominio o IP del VPS |
| `VPS_PORT` | `22` si no cambiaste SSH |
| `VPS_USER` | Usuario con acceso a Docker |
| `VPS_SSH_KEY` | Contenido completo de `tubehub_deploy` (privada) |
| `VPS_KNOWN_HOSTS` | Contenido completo de `tubehub_known_hosts` |
| `VPS_APP_DIR` | `/home/ddsr/tubehub` (carpeta que contiene `relay-server`) |

En `prod` conviene activar **Required reviewers** y permitir despliegues solo
desde `master`. Asi una union a `master` compila inmediatamente, pero espera tu
aprobacion antes de tocar produccion.

La imagen se almacena en GitHub Container Registry. El token temporal de cada
ejecucion inicia sesion solo para descargar la imagen y despues ejecuta
`docker logout`; no se guarda como secreto permanente en el VPS.

## Primera ejecucion

1. Abre **Actions > TubeHub CI/CD > Run workflow**.
2. Elige la rama `development` y el ambiente `test`.
3. Verifica el job `Desplegar al VPS` y despues ejecuta en el VPS:

```bash
cd /RUTA/DE/TUBEHUB/relay-server
docker compose --env-file .env.test -p relay-test ps
curl -fsS http://127.0.0.1:8081/actuator/health
```

Para produccion, une `development` a `master` o ejecuta manualmente el workflow
seleccionando `prod`.
