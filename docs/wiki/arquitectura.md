[← Inicio](README.md)

# Arquitectura

YouTube avisa al servidor y el servidor avisa a los teléfonos.

```mermaid
flowchart TB
    YT["YouTube<br/>Hub WebSub + Data API"]
    GA["Google y Apple<br/>verifican el inicio de sesión"]
    FCM["Firebase FCM<br/>entrega los push por topics"]

    subgraph VPS["VPS Vultr 216.238.70.157 · Docker Compose"]
        AP["Apache con HTTPS (Let's Encrypt)<br/>pasa la API y sirve /admin"]
        PROD["Producción · leftapp :8080<br/>Spring Boot 1.0.14 · Postgres relay_prod"]
        TEST["Testing · testapp :8081<br/>Postgres relay_test"]
        AP --> PROD
        AP --> TEST
        PROD -- réplica --> TEST
    end

    AND["App Android 1.0.15<br/>Directorio, Novedades, stickers y chat"]
    PAN["Panel /admin<br/>entra con Google"]
    IOS["App iOS 0.1.0<br/>sin publicar"]

    YT -- "aviso WebSub" --> AP
    PROD -- "verifica el token" --> GA
    PROD -- "pide el envío" --> FCM
    FCM -- push --> AND
    AND -- "/api + JWT" --> AP
    PAN -- "/api/admin" --> AP
    IOS -. pendiente .-> AP
```

Un video nuevo sigue este camino: YouTube avisa por WebSub a `/websub`, el servidor de producción pide los datos a la Data API (1 unidad), lo guarda en Postgres y pide a FCM un solo envío para todos los topics de quienes siguen a ese creador, medio o canal. El teléfono abre la ficha del video y de ahí YouTube.

Fuera del VPS quedan GitHub (código, imágenes en GHCR y respaldo `datos/`) y el sitio legal `vocesdeizquierda.com`. Las carpetas `functions/` y `firestore.rules` son del diseño anterior con Firebase y ya no se despliegan.
