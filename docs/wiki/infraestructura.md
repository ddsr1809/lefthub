[← Inicio](README.md)

# Infraestructura y ambientes

Testing y producción corren en un solo VPS de Vultr (`216.238.70.157`), cada uno con su propio Postgres y su contenedor del servidor, detrás de Apache con certificados de Let's Encrypt.

| Ambiente | Rama | Dominio | Proyecto Compose | Puerto local | Base | Archivo de secretos |
| --- | --- | --- | --- | --- | --- | --- |
| developer | `development` | tu equipo | `vocesleft-local` | 8080 | `relay` | `relay-server/.env.local` |
| testing | `testing` | `testapp.vocesdeizquierda.com` | `vocesleft-test` | 8081 | `relay_test` | `/opt/vocesleft/relay-server/.env.test` |
| producción | `master` | `leftapp.vocesdeizquierda.com` | `vocesleft-prod` | 8080 | `relay_prod` | `/opt/vocesleft/relay-server/.env.prod` |

## Carpetas en el VPS

| Ruta | Qué es |
| --- | --- |
| `/opt/vocesleft` | Clon del repositorio; de aquí sale el panel `/admin` y los scripts |
| `/opt/vocesleft/runtime/{test,prod}/` | `docker-compose.yml` y `.env.imagen` que administra el pipeline. No hacer `git pull` ni `build` aquí |
| `/root/.config/vocesleft/fcm-{prod,test}.json` | Credenciales de FCM, `chown root:101`, `chmod 640` |
| `/opt/vocesleft-datos` | Clon aparte del respaldo `datos/` |

## Contenedores

- `db`: `postgres:17-alpine`, 384 MB, volumen `datos_postgres`.
- `servidor`: imagen `ghcr.io/ddsr1809/vocesleft-relay:<sha>`, 640 MB, expuesto solo en `127.0.0.1`, healthcheck en `/actuator/health`.

## Apache

Cada dominio tiene un vhost que pasa `/` al puerto del servidor, excepto `/admin` (alias a la carpeta del panel) y `/.well-known/acme-challenge/`. El sitio legal `vocesdeizquierda.com` se instala con `scripts/vps/instalar-web.sh` desde `web/`.

## Proyectos de Google

| Proyecto | Para qué |
| --- | --- |
| «Voces de Izquierda» (445243795956) | Entrar con Google: clientes OAuth web y Android, pantalla de consentimiento, YouTube Data API |
| `vocesdeizquierda-app` (Firebase) | FCM: `google-services.json` y `FCM_PROYECTO_ID` |

Los dos ambientes comparten proyecto de Firebase. Por eso la réplica a testing usa ids distintos: con el mismo id, un aviso de testing llegaría a la app de producción.

## Secretos de GitHub

Environments `test` y `prod` (este solo acepta `master`) con `VPS_HOST`, `VPS_PORT`, `VPS_USER`, `VPS_SSH_KEY`, `VPS_KNOWN_HOSTS` y `VPS_APP_DIR`. Opcionales en el repositorio: `DOCKERHUB_USUARIO` y `DOCKERHUB_TOKEN`. Todo lo crea `scripts/configurar-github.sh`.
