[← Inicio](README.md)

# Operación y mantenimiento

La operación diaria pasa por el panel; el VPS solo se toca para secretos, respaldos y emergencias. Los comandos de esta página se corren en el VPS (`ssh root@216.238.70.157`) salvo que se diga otra cosa.

## Tareas frecuentes

| Tarea | Cómo |
| --- | --- |
| Ver qué versión corre | `curl https://leftapp.vocesdeizquierda.com/api/servidor` (también en Resumen del panel) |
| Salud del servidor | `curl -fsS http://127.0.0.1:8080/actuator/health` (prod) o `:8081` (test) |
| Actualizar el panel | `git -C /opt/vocesleft pull` |
| Nombrar administrador | Desde el panel (Administradores); el primero, por SQL abajo |
| Encender la copia a testing | `bash /opt/vocesleft/scripts/vps/replicar-creadores.sh` (repetible, no duplica) |
| Respaldo del directorio a `datos/` | `bash /opt/vocesleft/scripts/vps/respaldo-datos.sh instalar`; revisar con `journalctl -u vocesleft-datos -n 30 --no-pager` |
| Mantenimiento | Panel → Apps → interruptor y mensaje; las apps reciben 503, el panel y los avisos siguen |
| Dar de baja una versión de la app | Panel → Apps; esa versión recibe 426. Publicar antes la nueva |
| Versión mínima | `APP_VERSION_MINIMA_ANDROID` en `.env.prod` y recrear el servidor |

**Primer administrador** (la cuenta debe haber entrado antes al panel; si responde `UPDATE 0`, todavía no existe):

```bash
cd /opt/vocesleft/runtime/prod
docker compose --env-file /opt/vocesleft/relay-server/.env.prod --env-file .env.imagen \
  -p vocesleft-prod -f docker-compose.yml \
  exec db psql -U relay -d relay_prod \
  -c "update usuarios set es_admin = true where email = 'correo@gmail.com';"
```

**Recrear el servidor sin compilar** (cambiar `prod` por `test` para pruebas; nunca `--build` en el VPS):

```bash
cd /opt/vocesleft/runtime/prod
docker compose --env-file /opt/vocesleft/relay-server/.env.prod --env-file .env.imagen \
  -p vocesleft-prod -f docker-compose.yml up -d --no-deps --force-recreate servidor
```

**Volver a una versión exacta:** escribir `SERVIDOR_IMAGEN=ghcr.io/ddsr1809/vocesleft-relay:X.Y.Z` en `.env.imagen` y recrear. Las migraciones no se deshacen; la versión anterior arranca porque todas son aditivas.

## Respaldos

| Qué | Dónde | Cómo |
| --- | --- | --- |
| Base completa (cuentas, favoritos, chat, compras) con sus llaves | `/var/backups/vocesleft/` en el VPS, cifrada para la llave del dueño | `bash /opt/vocesleft/scripts/vps/respaldo-base.sh instalar` (diario 3:15, 14 días). Bajarlos a tu equipo con `rsync`. Ver [Seguridad](seguridad.md#respaldos-cifrados) |
| Directorio (creadores, medios, canales, fotos, ajustes) | `datos/{testing,produccion}/` en `master` | Temporizador del VPS cada 5 min; commits que no disparan el pipeline |
| Llaves `CHAT_LLAVES`, `DATOS_LLAVES`, `DATOS_INDICE` | `.env.prod` / `.env.test`, y dentro del respaldo cifrado | Guardar copia fuera del VPS; sin ellas el chat, los correos y las IPs no se leen |
| Versiones del panel | Base de datos | Van en el `pg_dump`, no en `datos/` |

Pendiente de revisar: en `master` la carpeta `datos/` solo tiene su `README.md`, sin commits de respaldo desde el 8 oct. El temporizador quizá no está instalado o no tiene permiso de escritura (Deploy key con *Allow write access*).

## Cuota de YouTube

Hay 10,000 unidades al día. WebSub cuesta 0; cada video nuevo, 1; cifras y títulos, 1 por cada 50; dar de alta un canal, 1; consultar suscripciones de una persona, 1 por cada 50 creadores. Con mil videos las cifras gastan unas 30 unidades al día.

## Cosas que se rompen

| Síntoma | Causa probable | Arreglo |
| --- | --- | --- |
| Dejan de llegar avisos a los 10 días | Caducó el arrendamiento de WebSub | Buscar «Ciclo de renovación terminado» en los registros; `POST /internal/renovar` |
| Canal en ámbar en el panel | El hub no verificó el webhook | Revisar `RELAY_URL_PUBLICA` y el proxy de Apache |
| Videos sin duración ni miniatura | `YOUTUBE_API_KEY` inválida o sin cuota | Probar la clave con `curl …/videos?part=contentDetails`; `API_KEY_INVALID` = crear otra y recrear |
| Avisos de videos viejos | El hub reenvía entradas al suscribirse | Corte de 6 h en `relay.websub.antiguedad-maxima-horas` |
| Servidor no arranca: `Migration checksum mismatch` | Se editó o reutilizó una migración | Restaurar el archivo original; lo nuevo va en otra migración |
| Servidor no arranca tras cambiar el chat | `CHAT_LLAVES` mal escrita | Corregir formato `n:base64`; el pipeline revierte solo |
| Servidor no arranca: «Falta DATOS_LLAVES» o «DATOS_INDICE» | El `.env` no las tiene o están mal escritas | `deploy-vps.sh` las crea solas; a mano: `echo "1:$(openssl rand -base64 32)"` y `openssl rand -base64 32` |
| El panel muestra correos que empiezan por `c1:` | Se perdió o cambió la llave con que se cifraron | Reponer la `DATOS_LLAVES` anterior (del respaldo cifrado); los datos siguen intactos |
| La app dice «Hay demasiadas personas entrando desde tu red» | Límite de entradas por IP (429) | Se pasa solo en segundos; si es constante, revisar quién inunda `/api/auth` en el log de Apache |
| La app enseña «actualiza la app» o «No pudimos comprobar que esta es la app original» | Se exige la firma o App Check (Apps → Llave de la app) y esa app no la trae buena | Ver la tarjeta: si son muchas, volver a «Solo avisar». Una app compilada sin `llave_app_produccion` no trae firma |
| Anuncios encendidos y no sale ninguno | App en «Debe revisarse» en AdMob, o la instalada es anterior a 1.0.16 | Vincular la app a Play en AdMob y esperar la revisión; actualizar la app |
| No aparece el botón de comprar | Falta `COMPRAS_PAQUETE` o el producto en Play | Panel → Anuncios dice *Compra sin configurar*; registro `ComprasRepo` |
| Entrar con Google falla en el panel | `origin_mismatch` | Agregar el dominio a orígenes autorizados del cliente web |
| Entrar con Google falla en Android | SHA-1 o `WEB_CLIENT_ID` equivocados | Cliente Android por cada `applicationId` en el proyecto 445243795956 |
