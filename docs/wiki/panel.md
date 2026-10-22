[← Inicio](README.md)

# Panel de administración

El panel es una página estática (`admin/index.html`, `app.js`, `versiones.js`) que Apache sirve en `/admin` de cada dominio, desde el clon `/opt/vocesleft` del VPS. **No lo actualiza el pipeline**: se actualiza con `git -C /opt/vocesleft pull`. Se entra con Google (Google Identity Services, cliente web 445243795956) y la cuenta debe tener `es_admin = true`. `apiBase` vacío hace que hable con el mismo dominio que lo sirve.

| Sección | Para qué |
| --- | --- |
| Resumen | Cifras generales, versión y commit del servidor, y **Borrar datos** con código de 6 cifras |
| Creadores | Ficha con datos, redes, canales de YouTube, medios y etiquetas; «Llenar con los datos de» una cuenta; foto de perfil |
| Medios | Productoras: canales propios, creadores, si aparecen en el directorio |
| Canales | Ficha de cada canal: dueño, medio, con quién más aparece, estado de la suscripción al hub |
| Etiquetas | Crear, encender, ordenar y asignar etiquetas (nacen apagadas) |
| Versiones | Fotos del directorio y migración de pruebas a producción con conflictos campo por campo |
| Publicaciones | Videos con duración, vistas, «me gusta»; totales por creador, canal o medio; interruptor de cortos |
| Reportes | Enlaces rotos y contenido reportado desde la app |
| Recomendaciones | Canales propuestos por la gente y a quién se le permite proponer |
| Editores | Cuentas que pueden agregar al directorio desde la app y lo que agregaron |
| Stickers | Paquetes por creador y quién los ve |
| Avisos | Mandar notificaciones a todos o a seguidores; historial |
| Anuncios | Interruptor de AdMob, folios de regalo, estado de la compra |
| Apps | Cuentas por versión, bajas (426), mantenimiento (503) y alcance de YouTube |
| Usuarios | Filtros combinables, orden por columna, páginas de 25 a 200; ficha con chat, stickers, YouTube, anuncios y editor |
| Chat | Bandeja de conversaciones, primero las que esperan respuesta |
| Administradores | Nombrar administradores por correo |

**Alcances por cuenta.** Recomendaciones, stickers y YouTube usan el mismo esquema de cuatro valores: *Nadie*, *Solo administradores*, *Administradores y cuentas elegidas*, *Todos*. Las cuentas elegidas se marcan en su ficha de Usuarios.

**Pruebas del panel:** `make panel-test` (o `node admin/versiones.test.js`) cubre la comparación de versiones y la migración.
