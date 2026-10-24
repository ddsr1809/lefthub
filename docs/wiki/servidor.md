[← Inicio](README.md)

# Servidor relay (1.0.14)

El servidor es una app Spring Boot 3.4.1 en Java 21 con PostgreSQL 17, Flyway y hilos virtuales; vive en `relay-server/`, paquete `com.voces.backend`. Reemplazó a Firebase Auth, Firestore y Cloud Functions: de Google solo quedan FCM (push) y la YouTube Data API.

## Módulos

| Paquete | Qué hace |
| --- | --- |
| `websub` | Recibe los avisos de YouTube en `/websub`, verifica la firma HMAC, descarta repetidos y entradas de más de 6 h, renueva las suscripciones al hub |
| `directorio` | Creadores, medios (productoras), canales, publicaciones, perfil, favoritos, etiquetas, versiones, réplica a testing, borrado masivo, fotos |
| `push` | Envía notificaciones por FCM HTTP v1 a topics, un solo mensaje por video con condición «sigue a A o B» |
| `auth` | Sesión anónima por dispositivo, entrar con Google o Apple, JWT de 30 días |
| `cuenta` | Reportes de contenido y borrado de cuenta (revoca Apple) |
| `youtube` | «¿Estoy suscrito en YouTube?» con token `youtube.readonly` de un solo uso |
| `anuncios` | Folios de regalo y compras de Google Play para quitar anuncios |
| `chat` | Chat persona–equipo guardado y cifrado con AES-256-GCM |
| `recomendaciones` | La gente propone canales y el equipo los revisa |
| `editor` | Cuentas autorizadas dan de alta creadores, medios y canales desde la app |
| `stickers` | Paquetes de stickers por creador (WebP 512×512) |
| `avisos` | Notificaciones que el equipo manda desde el panel, con historial |
| `version` | `/api/servidor`, mantenimiento (503), versión mínima y bajas por versión (426) |
| `acceso` | Último acceso por cuenta, país y ASN con bases DB-IP locales, posibles bots |
| `seguridad` | Datos personales cifrados en la base (correo, IP, token de Apple, compras), huella del teléfono, cierre de sesiones, límite de entradas por IP, limpieza de IPs viejas, y la llave de la app (firma + App Check). Ver [Seguridad](seguridad.md) |

## Rutas principales

| Grupo | Rutas |
| --- | --- |
| Sesión | `POST /api/auth/anonimo`, `/google`, `/apple`, `/renovar`, `/salir` (desliga el teléfono al cerrar sesión) |
| Directorio | `GET /api/creadores`, `/api/creadores/{id}`, `/api/creadores/{id}/publicaciones?parte=propios\|compartidos`, `/api/productoras[/{id}]`, `/api/productoras/{id}/publicaciones`, `/api/canales/{id}/publicaciones`, `/api/canales/ultimos-videos`, `/api/etiquetas` |
| Novedades | `GET /api/publicaciones[?tipo=cortos]`, `/api/publicaciones/mas-vistas?periodo=hoy\|semana`, `/api/videos/{videoId}` |
| Perfil | `GET /api/perfil`, `PUT /api/preferencias`, `PUT/DELETE /api/favoritos/{id}`, `/api/favoritos/productoras/{id}`, `/api/favoritos/canales/{id}` |
| Cuenta | `POST /api/reportes`, `DELETE /api/cuenta`, `POST /api/cuenta/cerrar-sesiones` |
| Funciones | `/api/youtube/suscripciones`, `/api/anuncios/folio`, `/api/anuncios/compra`, `/api/chat/*`, `/api/recomendaciones`, `/api/editor/*`, `/api/stickers/*`, `/api/fotos/{id}` |
| Panel | `/api/admin/*` (requiere `es_admin`) |
| Internas | `/websub`, `/internal/renovar`, `/internal/replica/*`, `/internal/versiones/*`, `GET /api/servidor`, `/actuator/health` |

Las apps mandan `X-App-Version` y `X-App-Plataforma` en cada petición; el servidor anota la versión de cada cuenta.

## Tareas programadas

| Tarea | Frecuencia | Para qué |
| --- | --- | --- |
| Vigilar directos | cada 2 min | Detectar cuándo empieza y termina un directo |
| Sondear feeds | cada 5 min | Red de seguridad por si WebSub no entrega |
| Títulos de canales | cada 10 min | Nombre real del canal desde YouTube (1 unidad por 50) |
| Revisión de videos | cada 10 min | Completar duración y miniatura; reclasificar Shorts |
| Repescar pendientes | cada 15 min | Reintentar suscripciones al hub que no se verificaron |
| Cifras de videos | cada 30 min | Vistas y «me gusta»: 2 días cada 30 min, 1 mes cada 6 h, resto cada 3 días |
| Bases DB-IP | cada 12 h | País y compañía de internet de cada IP |
| Purgar suscripciones YouTube | diario 03:30 | Borrar lo que lleve 30 días sin refrescarse |
| Renovar WebSub | 04:00 cada 4 días (Hermosillo) | El arrendamiento del hub dura 10 días |
| Limpiar accesos | diario 04:30 (Hermosillo) | Borrar IP, compañía y agente de cuentas sin abrir la app en `DATOS_DIAS_IP` días (90) |
| Cifrar lo pendiente | al arrancar | Cifra lo que siga legible y convierte `device_id` en huella (la primera vez tras V26) |

## Base de datos

Flyway aplica 28 migraciones (`V1`–`V28`) al arrancar y Hibernate solo valida (`ddl-auto: validate`). **Un número de migración que ya llegó a un servidor nunca se reutiliza ni se borra**: el servidor no arrancaría (`Migration checksum mismatch`).

| Migración | Qué agrega |
| --- | --- |
| V1–V4 | Esquema inicial, datos de acceso, directos, suscripciones de YouTube |
| V5–V7 | Origen de réplica, canales y productoras, canales compartidos |
| V8–V9 | Videos cortos y tabla `ajustes`; fotos guardadas |
| V10–V12 | Versiones del directorio, anuncios (folios, compras), etiquetas |
| V13–V15 | Versión de app por cuenta, seguir canales sueltos, títulos de canales y avisos de cortos |
| V16–V18 | Revisión de videos, cifras, avisos del equipo |
| V19–V21 | Chat, recomendaciones, YouTube por cuenta |
| V22–V25 | Duración en segundos, mensajes del chat, editores desde la app, stickers |
| V26 | Huellas para buscar correo, IP y comprobantes cifrados; `sesiones_desde` para cerrar sesiones |
| V27 | `llave_firma`, `llave_app_check`, `llave_vista_en`: cómo llegó la última petición de cada cuenta |
| V28 | `videos_vistos`: los videos que cada cuenta abrió o marcó «Ya lo vi» |
| V29 | `llave_numero`, `llave_app_check_app`, `app_version_orden`: número de llave, app de App Check y versión ordenable de cada cuenta |

Tablas centrales: `usuarios`, `creadores`, `productoras`, `canales`, `canales_creadores`, `creadores_productoras`, `publicaciones`, `favoritos*`, `ajustes`, `fotos`, `folios`, `compras`, `mensajes_chat`, `paquetes_stickers`, `stickers`.

## Variables de entorno

| Variable | Para qué |
| --- | --- |
| `DB_URL`, `DB_USUARIO`, `DB_CLAVE` | Conexión a Postgres |
| `RELAY_URL_PUBLICA` | URL pública; el hub de Google la usa para entregar avisos |
| `JWT_SECRETO` | Firma las sesiones; cambiarla cierra todas |
| `WEBSUB_SECRETO`, `WEBSUB_TOKEN_CALLBACK`, `TOKEN_INTERNO` | Firma de WebSub y rutas internas |
| `YOUTUBE_API_KEY` | Data API; sin ella no hay títulos, cifras ni duración |
| `GOOGLE_CLIENT_ID` | Cliente web OAuth del proyecto 445243795956 |
| `FCM_PROYECTO_ID`, `FCM_CREDENCIALES_HOST` | Push; JSON montado en `/secretos/fcm.json` |
| `COMPRAS_PAQUETE`, `COMPRAS_PRODUCTO` | Confirmar compras de Play (`sin_anuncios`) |
| `CHAT_LLAVES` | Llaves del chat, formato `1:vieja,2:nueva` |
| `DATOS_LLAVES` | **Obligatoria.** Cifran correo, IP, token de Apple y compras en la base; mismo formato que `CHAT_LLAVES` |
| `DATOS_INDICE` | **Obligatoria.** Llave de las huellas para buscar un correo o una IP; no se cambia nunca |
| `DATOS_DIAS_IP` | Días sin abrir la app tras los que se borra la IP (90; 0 = nunca) |
| `APP_LLAVES` | Llave de la firma de la app, `1:base64`; la misma va en `android/firma.properties`. Ver [Seguridad](seguridad.md#llave-de-la-app) |
| `APP_CHECK_PROYECTO`, `APP_CHECK_APPS` | Número del proyecto de Firebase y apps aceptadas para App Check |
| `REPLICA_URL`, `REPLICA_TOKEN` | Copia de producción a testing |
| `APP_VERSION_MINIMA_ANDROID`, `_IOS` | Versión mínima atendida; vacío = todas |
| `CORS_ORIGENES` | Orígenes permitidos del panel |
| `APPLE_*` | Entrar con Apple y revocarlo al borrar la cuenta |
| `FOTOS_API_KEY`, `PERFILES_API_KEY` | unavatar.io y microlink.io; sin clave, 25 al día |
