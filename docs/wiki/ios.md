[← Inicio](README.md)

# App iOS (0.1.0, sin publicar)

La app de iPhone no está lista para producción: sigue usando Firebase Auth y Firestore, mientras que el servidor actual emite sus propios JWT. Antes de publicarla hay que migrarla como se migró Android.

| Dato | Valor |
| --- | --- |
| Carpeta | `ios/Relay` (SwiftUI), proyecto generado con XcodeGen desde `ios/project.yml` |
| Bundle id | `com.vocesdeizquierda.lefthub` |
| Versión | `CFBundleShortVersionString` 0.1.0 |
| Servidor | `localhost:8080` en DEBUG, `leftapp.vocesdeizquierda.com` en release |
| Último cambio | 8 oct 2026, «Apps: mandan su version al servidor» |

**Lo que tiene:** directorio, ajustes, bienvenida, reportar enlace, borrar cuenta y entrar con Apple (primero, como exige la guía 4.8).

**Lo que falta frente a Android:** sesión con el JWT del servidor (`ApiRelay.swift` todavía pide el token a `Auth.auth()`), Novedades, fichas de canal y medio, seguir canales sueltos, etiquetas, «¿Estoy suscrito en YouTube?», chat, stickers, recomendaciones y editores. No muestra anuncios.

**Para compilar:** `brew install xcodegen`, luego `cd ios && xcodegen generate && open Relay.xcodeproj`. Faltan fuera del repositorio `GoogleService-Info.plist` y la clave APNs `.p8` (se sube a Firebase). Las notificaciones solo funcionan en un iPhone real con cuenta de desarrollador de pago.
