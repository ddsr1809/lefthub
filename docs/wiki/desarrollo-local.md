[← Inicio](README.md)

# Desarrollo local

Todo se trabaja en la rama `development`, en `~/Documents/code/voces/lefthub` de la laptop (Linux Mint). `./local.sh` levanta Postgres, el servidor y el panel, y mantiene el puente USB para la app `developer`.

1. Traer lo último: `git checkout development && git pull`.
2. Primera vez: `./local.sh` crea `relay-server/.env.local` con secretos generados (incluida `CHAT_LLAVES`) y pide tres valores: `YOUTUBE_API_KEY`, `FCM_PROYECTO_ID` y `FCM_CREDENCIALES_HOST`. Se pueden copiar de `.env.test` del VPS.
3. Volver a correr `./local.sh`. `Ctrl+C` lo apaga y conserva los datos.
4. Darse administrador en local: `./local.sh admin tu@correo` (después de entrar una vez al panel).
5. Instalar en el teléfono la variante `developerDebug`. Habla con `http://localhost:8080` a través de `adb reverse`.

| Comando | Qué hace |
| --- | --- |
| `make test` | Pruebas Java del servidor |
| `make build` | JAR del servidor |
| `make ci` | Pruebas e imagen Docker local |
| `make local-up` / `local-down` / `local-restart` | Mismo ambiente que `./local.sh`, sin el puente USB |
| `make local-logs` / `local-status` | Registros y estado de los contenedores |
| `make datos-guardar` / `datos-restaurar` | Base local ↔ `datos/development/` |
| `make datos-test` | Pruebas de la herramienta de datos |
| `make panel-test` | Pruebas de versiones y migración del panel |
| `./local.sh down` | Apaga todo si quedó algo encendido |

Google no puede entregar WebSub a `localhost`. Para probar avisos de punta a punta hace falta una URL HTTPS pública temporal en `RELAY_URL_PUBLICA`.

## Convenciones del repositorio

- Los cambios se entregan como parches de git con autoría única `ddsr1809`; se aplican con `git am` sobre `development`.
- Las descargas llegan a `/home/ddsr/Downloads`.
- Cada versión sube el número en dos sitios: `versionCode`/`versionName` en `android/app/build.gradle.kts` y `version` en `relay-server/build.gradle.kts`.
- Una migración nueva toma el siguiente número libre (hoy `V26`) y es solo aditiva.
- Si un cambio altera lo que describe esta wiki, la página correspondiente se actualiza en el mismo commit.
