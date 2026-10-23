# Wiki de LeftHub (Voces de Izquierda)

Estado de producción al 21 de octubre de 2026.

Voces Izquierda (proyecto LeftHub) es un directorio curado de creadores de contenido: avisa cuando publican en YouTube los creadores, canales y medios que la persona sigue, y la lleva a la app oficial para ver el video. No reproduce, no aloja ni descarga contenido, y no hace scraping.

## Páginas

| Página | De qué trata |
| --- | --- |
| [Arquitectura](arquitectura.md) | Componentes del sistema y cómo viaja un video nuevo |
| [Servidor relay](servidor.md) | Módulos, rutas, tareas programadas, base de datos y variables |
| [App Android](android.md) | Sabores, pantallas, notificaciones, anuncios y firma |
| [App iOS](ios.md) | Estado actual y lo que falta |
| [Panel de administración](panel.md) | Secciones del panel `/admin` |
| [Infraestructura y ambientes](infraestructura.md) | VPS, Docker, Apache, proyectos de Google y secretos |
| [Flujo de ramas y pipeline](pipeline.md) | `development` → `testing` → `master` y despliegue |
| [Desarrollo local](desarrollo-local.md) | `./local.sh`, comandos `make` y convenciones |
| [Operación y mantenimiento](operacion.md) | Tareas frecuentes, respaldos y problemas comunes |
| [Seguridad](seguridad.md) | Datos cifrados, llaves, sesiones, VPS endurecido y respaldos cifrados |
| [Historial](historial.md) | Versiones publicadas en producción |
| [Glosario y preguntas](glosario.md) | Términos y preguntas frecuentes |

## Producción hoy

| Dato | Valor |
| --- | --- |
| Rama | `master`, commit `1c9910c` (PR #66, 21 oct 2026 19:47) |
| Servidor | 1.0.14 (`relay-server/build.gradle.kts`) |
| App Android | 1.0.15, versionCode 115, `com.vocesdeizquierda.lefthub` |
| App iOS | 0.1.0, sin publicar; funciones básicas solamente |
| Último tag publicado | `v1.0.13`; el pipeline crea `v1.0.14` cuando producción queda sana |
| API | `https://leftapp.vocesdeizquierda.com` |
| Panel | `https://leftapp.vocesdeizquierda.com/admin` |
| Sitio legal | `https://vocesdeizquierda.com` (privacidad y condiciones, ES/EN) |
| Repositorio | `github.com/ddsr1809/lefthub` |

**Para quién.** Personas mayores y público general que quieren seguir a voces de izquierda sin perderse en el algoritmo. Por eso la app prioriza letra grande, botones de 48 dp o más y contraste 8:1.

## Principios que no se negocian

- **Curaduría a mano.** Solo el equipo (o editores autorizados) da de alta creadores; no hay buscador abierto. Esto cumple la guía 3.2.2 de App Store.
- **Redirigir, no reproducir.** Cada video se abre en YouTube con un enlace profundo: genera tráfico real para Google y evita problemas de derechos.
- **Cuota mínima.** Se detecta lo publicado por WebSub (0 unidades) y se gasta ~1 unidad por video. `search.list` (100 unidades) está prohibido.
- **Marca.** El nombre no puede contener «Tube» ni imitar el logo de YouTube.
- **Compatibilidad hacia atrás.** Las migraciones son solo aditivas y las apps viejas siguen funcionando con servidores nuevos.

## Funciones en producción

Directorio de creadores, canales y medios con etiquetas; Novedades con videos, cortos opcionales y «Lo más visto»; seguir creadores, medios o canales sueltos; notificaciones push; ficha del video; «¿Estoy suscrito en YouTube?»; anuncios AdMob con compra o folio para quitarlos; chat con el equipo; recomendaciones de canales; editores desde la app; stickers para WhatsApp y otras apps; avisos del equipo; mantenimiento y baja de versiones.

El `README.md` de la raíz conserva partes del diseño original (carpeta `server/`, Cloud Run, Firebase Hosting). Esta wiki describe lo que realmente corre hoy. Al cambiar algo importante, actualiza la página que corresponda en el mismo commit.

La pestaña Wiki de GitHub es una copia de esta carpeta tal como está en `master`. Se publica con `bash scripts/publicar-wiki.sh` (`--prueba` para ver antes qué cambiaría); no se edita allá.
