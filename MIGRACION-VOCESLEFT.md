# Migración a vocesdeizquierda.com

Cómo levantar VocesLeft en el VPS nuevo y dejar el despliegue automático
funcionando desde este repositorio.

| | Antes (TubeHub) | Ahora (VocesLeft) |
|---|---|---|
| Repositorio | `ddsr1809/tubehub` | `ddsr1809/lefthub` |
| VPS | 216.238.90.94 | 216.238.70.157 |
| Producción | `ythub.d2600.com` | `leftapp.vocesdeizquierda.com` |
| Pruebas | `testhub.d2600.com` | `testapp.vocesdeizquierda.com` |
| Carpeta en el VPS | `/opt/tubehub` | `/opt/vocesleft` |
| Proyectos de Compose | `relay-prod`, `relay-test` | `vocesleft-prod`, `vocesleft-test` |
| Imagen | `ghcr.io/ddsr1809/tubehub-relay` | `ghcr.io/ddsr1809/vocesleft-relay` |
| App Android | `com.tuempresa.creatorhub` | `com.vocesdeizquierda.lefthub` |
| Sabores Android | `dev`, `pruebas`, `prod` | `developer`, `pruebas`, `produccion` |
| Rama que despliega pruebas | `development` | `testing` |

Los puertos (8080 producción, 8081 pruebas) y los nombres de las bases
(`relay_prod`, `relay_test`) no cambian.

Hay tres ambientes, y el código sube en este orden:

| Ambiente | Rama | Servidor | App Android |
|---|---|---|---|
| developer | `development` | Local, en tu equipo | `developerDebug` |
| testing | `testing` | `testapp.vocesdeizquierda.com` | `pruebasDebug` |
| producción | `master` | `leftapp.vocesdeizquierda.com` | `produccionRelease` |

El DNS ya está listo: `leftapp` y `testapp` resuelven a 216.238.70.157.

Cada bloque dice dónde se ejecuta. **Laptop** es tu computadora, en la carpeta
del repositorio. **VPS** es `ssh root@216.238.70.157`.

---

## 1. Preparar el VPS

**Laptop.** Copia los dos scripts al servidor:

```bash
scp scripts/vps/*.sh root@216.238.70.157:/root/
```

**VPS.**

```bash
bash /root/preparar-vps.sh
```

Los certificados se piden a nombre de `tcs.md.soto@gmail.com`; Let's Encrypt
usa ese correo para avisar si alguno está por vencer.

Instala Docker, Apache y Certbot, clona el repositorio en `/opt/vocesleft`,
crea `.env.prod` y `.env.test` con secretos nuevos, configura un vhost por
dominio y pide los certificados. Al final imprime lo que queda pendiente. Se
puede repetir: no sobrescribe nada que ya exista.

## 2. Traer las claves del servidor anterior

**VPS.** Te pide la clave de root del servidor anterior una vez:

```bash
bash /root/copiar-config-anterior.sh
```

Copia las claves de YouTube, Google y Apple, y los JSON de Firebase. No copia
los secretos propios del servidor (JWT, WebSub, base de datos): el nuevo tiene
los suyos.

Si el servidor anterior no acepta entrar con clave, copia a mano esos valores a
`/opt/vocesleft/relay-server/.env.prod` y `.env.test`, y los JSON a
`/root/.config/vocesleft/fcm-prod.json` y `fcm-test.json` con
`chown root:101` y `chmod 640`. El grupo 101 es el del usuario del contenedor;
con `600` el servidor no puede leer el archivo.

## 3. Configurar GitHub

**Laptop.** Necesitas la [CLI de GitHub](https://cli.github.com) con la sesión
iniciada (`gh auth login`):

```bash
bash scripts/configurar-github.sh
```

Crea la llave SSH del pipeline y la autoriza en el VPS, comprueba las huellas
del servidor, crea los Environments `test` y `prod` (este último solo acepta
`master`) y guarda los seis secretos en cada uno.

## 4. Autorizar los dominios nuevos en Google

En [Google Cloud Console](https://console.cloud.google.com/apis/credentials),
proyecto `hub-11979`:

- **Cliente OAuth web** (`389825726990-b6ubrv9f…`): en *Orígenes autorizados de
  JavaScript* agrega `https://leftapp.vocesdeizquierda.com` y
  `https://testapp.vocesdeizquierda.com`. Sin esto, el botón de Google del
  panel `/admin` falla con `origin_mismatch`.
- **Clave de la YouTube Data API**: si está restringida por IP, agrega
  216.238.70.157.

## 5. Primer despliegue

**Laptop.**

```bash
git push origin development
```

Eso solo prueba y construye; `development` no despliega. Para desplegar
pruebas, abre un pull request de `development` a `testing` y fusiónalo. Cuando
el pipeline termine:

```bash
curl https://testapp.vocesdeizquierda.com/actuator/health
```

Debe responder con `"status":"UP"`. Después abre un pull request de `testing`
a `master` y fusiónalo: eso despliega producción.

**VPS.** El panel `/admin` no viaja por el pipeline. Después de fusionar a
`master`:

```bash
git -C /opt/vocesleft pull
```

## 6. Primer administrador

Entra una vez con la cuenta de Google `tcs.md.soto@gmail.com` a
`https://leftapp.vocesdeizquierda.com/admin`. Luego, en el **VPS**:

```bash
cd /opt/vocesleft/runtime/prod
docker compose --env-file /opt/vocesleft/relay-server/.env.prod --env-file .env.imagen \
  -p vocesleft-prod -f docker-compose.yml \
  exec db psql -U relay -d relay_prod \
  -c "update usuarios set es_admin = true where email = 'tcs.md.soto@gmail.com';"
```

Cierra sesión en el panel y vuelve a entrar. Para pruebas es igual, cambiando
`prod` por `test` en la ruta, el `.env`, el proyecto y la base.

## 7. La app de Android

El identificador de la app cambió, así que Firebase todavía no la conoce. Hasta
hacer esto, la compilación falla con `No matching client found for package
name`.

1. En la [consola de Firebase](https://console.firebase.google.com), proyecto
   `hub-11979`, agrega tres apps de Android: `com.vocesdeizquierda.lefthub`,
   `com.vocesdeizquierda.lefthub.pruebas` y
   `com.vocesdeizquierda.lefthub.developer`. En cada una registra la huella
   SHA-1 de tu llave de firma (`./gradlew signingReport`, dentro de
   `android/`); sin ella no funciona entrar con Google.
2. Descarga el `google-services.json` nuevo (un solo archivo trae las tres
   apps) y cópialo a las tres carpetas de sabor:

   ```bash
   for sabor in developer pruebas produccion; do
     cp ~/Downloads/google-services.json android/app/src/$sabor/
   done
   ```
3. En Android Studio, *Build Variants* ofrece tres variantes:
   `developerDebug`, `pruebasDebug` y `produccionRelease`.

El sabor de testing se llama `pruebas` porque Android no permite sabores cuyo
nombre empiece por "test".

`developerDebug` habla con un servidor local. Levántalo en tu equipo con
`./local.sh`, que además mantiene el puente por USB para que la app lo
encuentre en `http://localhost:8080`, tanto en un teléfono físico como en el
emulador. Si lo levantas con `make local-up`, crea el puente a mano:
`adb reverse tcp:8080 tcp:8080`.

`WEB_CLIENT_ID` no cambia mientras sigas en el mismo proyecto de Firebase.

**Antes de publicar en Play:** el `applicationId` (`com.vocesdeizquierda.lefthub`)
no se puede cambiar una vez publicada la app.

## 8. La página web

`https://vocesdeizquierda.com` muestra la página de presentación de la app. Son
los archivos estáticos de la carpeta `web/`, servidos por Apache desde el clon
del repositorio, igual que el panel `/admin`.

**VPS.** Después de fusionar a `master`, la primera vez:

```bash
git -C /opt/vocesleft pull
bash /opt/vocesleft/scripts/vps/instalar-web.sh
```

Crea el vhost de `vocesdeizquierda.com` y `www.vocesdeizquierda.com` y pide el
certificado. Para actualizar la página después basta el `git pull`.

---

## Opcional: traer los datos del servidor anterior

El servidor nuevo arranca con la base vacía. Para traer creadores, cuentas y
favoritos de producción, después del primer despliegue a `master`. Estos
comandos no se han probado contra tus servidores: haz primero la prueba con
`test`.

**VPS** (nuevo):

```bash
# 1. Volcar la base en el servidor anterior y traerla.
ssh root@216.238.90.94 'docker exec $(docker ps -q \
  -f label=com.docker.compose.project=relay-prod \
  -f label=com.docker.compose.service=db) \
  pg_dump -U relay -d relay_prod --no-owner --clean --if-exists' > /root/relay_prod.sql

# 2. Restaurar con el servidor detenido.
cd /opt/vocesleft/runtime/prod
C="docker compose --env-file /opt/vocesleft/relay-server/.env.prod --env-file .env.imagen -p vocesleft-prod -f docker-compose.yml"
$C stop servidor
$C exec -T db psql -U relay -d relay_prod -v ON_ERROR_STOP=1 < /root/relay_prod.sql
$C start servidor

# 3. Volver a suscribir todos los canales con la URL nueva.
curl -X POST https://leftapp.vocesdeizquierda.com/internal/renovar \
  -H "X-Token-Interno: $(grep '^TOKEN_INTERNO=' /opt/vocesleft/relay-server/.env.prod | cut -d= -f2-)"
```

El paso 3 es obligatorio: las suscripciones de WebSub copiadas apuntan al
dominio anterior, y sin renovarlas el servidor nuevo no recibe ningún aviso.

**Apaga producción en el servidor anterior cuando termines.** Con los mismos
creadores y el mismo proyecto de Firebase, los dos servidores envían el aviso
de cada video al mismo topic y la gente lo recibe dos veces.

---

## Si algo falla

**El pipeline falla en "Configurar SSH" o con `Host key verification failed`.**
Repite `bash scripts/configurar-github.sh` y luego *Re-run failed jobs*.

**El dominio responde 503.** El proxy está bien, pero no hay contenedor detrás:
todavía no se ha desplegado ese ambiente, o no arrancó. En el VPS:
`docker ps -a` y `docker logs` del contenedor `vocesleft-prod-servidor-1`.

**El servidor arranca pero no envía avisos.** Revisa los permisos del JSON de
Firebase: `ls -ln /root/.config/vocesleft/` debe mostrar `-rw-r----- 0 101`.

**`preparar-vps.sh` dice que el puerto está ocupado.** Hay otro sistema usando
8080 u 8081 en ese VPS. Repite con `PUERTO_PROD=8090 PUERTO_TEST=8091`.
