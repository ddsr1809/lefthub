# datos/: el directorio, en archivos

Aquí vive una copia de los creadores, las productoras y los canales de cada
ambiente, en archivos de texto:

```text
datos/
  development/    tu base local            la escribes tú con `make datos-guardar`
  testing/        testapp.vocesdeizquierda.com   la escribe el VPS, sola
  produccion/     leftapp.vocesdeizquierda.com   la escribe el VPS, sola
```

**Se sigue trabajando en el panel.** La base de datos es la que manda; estos
archivos son su reflejo. Sirven para dos cosas: que cada cambio quede en el
historial de Git (quién había, cómo estaba, cuándo cambió) y poder devolver a
la base lo que se borre o se pierda. No los edites a mano: la siguiente pasada
los vuelve a escribir desde la base.

Dentro de cada ambiente:

| Archivo | Qué lleva |
|---|---|
| `creadores/juan-perez-1a2b3c4d.json` | Un creador, con sus canales y redes, y las productoras en las que figura. |
| `productoras/estudio-x-9f8e7d6c.json` | Una productora, con sus canales propios y con qué creadores aparece cada uno. |
| `fotos/<id>.jpg` + `fotos/<id>.json` | Las fotos de perfil que el servidor guarda (las tomadas de X, Instagram...). |
| `ajustes.json` | Los interruptores del panel, como el de los videos cortos. |

El nombre del archivo sale del nombre del creador y del principio de su id. Si
le cambias el nombre en el panel, el archivo cambia de nombre.

**Lo que no está aquí**, a propósito: las cuentas de la gente, sus favoritos y
sus correos. Este repositorio es público, y aquí solo va lo que la app ya
enseña a cualquiera. Tampoco las publicaciones (los videos vuelven a llegar
solos). Para todo eso sigue haciendo falta la copia completa de la base: ver
"Copias de seguridad" en `relay-server/COMO-EJECUTAR.md`.

## Testing y producción: solos, desde el VPS

Un temporizador en el VPS lee las dos bases cada 5 minutos. Si algo cambió,
hace un commit en `master` solo con archivos de `datos/` y lo sube. Esos
commits no disparan el pipeline ni redespliegan nada.

Se enciende una vez (ver `PIPELINE.md`, "Respaldo del directorio en datos/"):

```bash
bash /opt/vocesleft/scripts/vps/respaldo-datos.sh instalar
```

## Development: a mano, en tu equipo

Con el ambiente local encendido (`./local.sh`):

```bash
make datos-guardar      # base local -> datos/development/
make datos-restaurar    # datos/development/ -> base local (solo lo que falte)
```

`datos-guardar` no hace commit: los archivos quedan en tu copia de trabajo y
los subes con el resto de tus cambios, si quieres conservarlos.

## Recuperar

Restaurar **solo agrega lo que falta**. Nunca pisa ni borra lo que ya está en
la base, así que se puede repetir sin miedo, y con `--simular` dice qué haría
sin guardar nada. Todo vuelve con el mismo id que tenía: los favoritos de la
gente y los avisos de la app siguen apuntando al mismo creador.

**Borré a un creador por error** (en el VPS). En GitHub, abre
`datos/produccion/creadores`, pulsa *History* y copia el código del último
commit en el que todavía estaba:

```bash
bash /opt/vocesleft/scripts/vps/respaldo-datos.sh restaurar produccion --desde a1b2c3d --solo juan-perez --simular
bash /opt/vocesleft/scripts/vps/respaldo-datos.sh restaurar produccion --desde a1b2c3d --solo juan-perez
```

`--solo` es un trozo del nombre del archivo. Vuelve con sus canales, sus redes,
su foto y sus productoras. Lo que no vuelve son sus videos ya publicados ni
quién lo tenía en favoritos: eso se borró con él y no está en estos archivos.

**Se perdió la base entera.** Primero deja que el servidor arranque contra la
base vacía (él crea las tablas). Luego:

```bash
bash /opt/vocesleft/scripts/vps/respaldo-datos.sh restaurar produccion
```

El servidor vuelve a suscribir los canales de YouTube en su siguiente repesca,
en menos de 15 minutos.

## Protecciones

- **Una base vacía no borra la copia.** Si la base aparece vacía, o le falta
  más de la mitad de lo que hay en la carpeta, la pasada no toca los archivos
  y lo deja escrito en su registro. Es el caso para el que existe la copia. Si
  de verdad borraste a tantos a propósito:
  `bash /opt/vocesleft/scripts/vps/respaldo-datos.sh guardar produccion --forzar`
- **Los ambientes no se cruzan.** Cada uno usa solo su carpeta. Testing y
  producción mandan los avisos al mismo proyecto de Firebase, por el id del
  creador: con los ids de producción en testing, un aviso de prueba llegaría a
  la app de verdad. Para llevar creadores de producción a testing ya existe la
  copia automática (`scripts/vps/replicar-creadores.sh`).

## Cuando cambie la base de datos

Los archivos llevan cada fila con todas sus columnas, tal cual están en la
base, y al restaurar solo se usan las columnas que existen en los dos lados:

- **Columna nueva** en `creadores`, `productoras`, `canales`, `ajustes` o
  `fotos`: no hay que hacer nada. Aparece sola en los archivos, y un archivo
  antiguo entra igual (la columna toma su valor por defecto).
- **Columna que desaparece**: tampoco. Se ignora al restaurar.
- **Tabla nueva** que forme parte del directorio: hay que agregarla a la lista
  `TABLAS` de `scripts/datos/datos.py` y decidir en qué archivo va. Mientras
  tanto no se guarda.

`"formato": 1` es la versión de esta estructura de archivos. Solo sube si un
cambio hace que el código anterior ya no pueda leerlos.

Pruebas de la herramienta, sin base de datos: `make datos-test`.
