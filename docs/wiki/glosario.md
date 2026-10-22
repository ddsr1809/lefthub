[← Inicio](README.md)

# Glosario y preguntas frecuentes

| Término | Significado |
| --- | --- |
| Creador | Una persona del directorio; puede tener canales de YouTube, redes o ambos |
| Medio / productora | La casa detrás de varios creadores. En código y API es `productora`; en la app y el panel, **Medio** |
| Canal | Canal de YouTube o red social. Su dueño es un creador o, si no tiene, un medio. Puede «aparecer con» otros creadores |
| Publicación | Un video detectado: normal, corto (Short) o directo |
| Topic | Canal de FCM al que se suscribe el teléfono: `creator_<id>`, `productora_<id>`, `canal_<id>`, `todos` |
| WebSub | Protocolo con que Google avisa al servidor de cada video nuevo, sin gastar cuota |
| Réplica | Copia automática de creadores y medios de producción a testing, con id distinto (`origen_id`) |
| Versión del directorio | Foto numerada del directorio de un ambiente, usada para migrar de pruebas a producción |
| Folio | Código de regalo `ABCDE-FGHJK` que quita los anuncios; se borra al canjearse |
| Editor | Cuenta autorizada a dar de alta creadores, medios y canales desde la app |
| Sabor | Variante de la app Android por ambiente: `developer`, `pruebas`, `produccion` |
| Relé / RelayApp | Nombres anteriores del proyecto que aún aparecen en carpetas y clases (`relay-server`, `ios/Relay`) |

## Preguntas frecuentes

**¿Por qué la app no reproduce los videos?** Para no alojar contenido ajeno, evitar problemas de derechos y mandar tráfico real a YouTube. También simplifica la revisión de las tiendas.

**¿Por qué una persona no recibe avisos de un creador que sigue?** Revisar que el canal esté en verde en Canales, que el dueño no esté oculto y que la persona no haya elegido solo algunos canales. Los cortos no avisan salvo que la persona lo encienda.

**¿Qué pasa si se borra un creador en producción?** Se van sus canales, videos y seguidores. En testing se conserva: los borrados no se replican. El borrado masivo corta antes una versión automática del directorio.

**¿Cómo se pasa un cambio de pruebas a producción?** En el panel de pruebas, Versiones → Crear versión. En el de producción, Versiones → Migrar desde pruebas → Revisar y migrar, resolviendo conflictos.

**¿Las etiquetas y los stickers viajan entre ambientes?** No. Se crean y suben en cada panel por separado.

**¿Se puede usar la app sin cuenta?** Sí. Se crea una cuenta anónima por dispositivo; guardarla con Google conserva lo seguido al cambiar de teléfono.

**¿Qué falta por hacer?** Migrar iOS al servidor actual; poner los IDs reales de AdMob; aprobar la verificación OAuth de `youtube.readonly` para abrirlo a todos; avisos de Twitch por EventSub; más pruebas automáticas e interfaz; stickers en iPhone.
