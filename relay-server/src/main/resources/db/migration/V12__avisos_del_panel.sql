-- Avisos al telefono de quien administra.
--
-- El panel se puede instalar como app y recibir avisos: llego un reporte, un
-- canal dejo de recibir publicaciones. Son avisos de navegador (Web Push), no
-- de Firebase: el navegador entrega una direccion de su propio servicio de
-- avisos y dos claves, y con eso basta para escribirle. Cada fila es un
-- navegador o una app instalada que dijo "avisame".
--
-- No tiene que ver con los avisos de publicaciones de la app, que van por
-- topics de FCM y no guardan nada de ningun telefono.
--
-- Las claves con las que el servidor firma estos avisos se crean solas la
-- primera vez y viven en la tabla `ajustes` de V8 (`avisos_panel_publica` y
-- `avisos_panel_privada`): cada ambiente tiene las suyas.
--
-- Todo es aditivo: la version anterior del servidor arranca sobre este
-- esquema si un despliegue se revierte.

create table dispositivos_admin (
    id            uuid primary key default gen_random_uuid(),

    -- Se va con la cuenta. Que la cuenta siga siendo administradora se
    -- comprueba al enviar: quitar el rol corta los avisos sin borrar nada.
    usuario_id    uuid not null references usuarios (id) on delete cascade,

    -- La direccion que da el navegador. Una por instalacion: si el mismo
    -- navegador se vuelve a registrar, es la misma fila.
    endpoint      text not null unique,
    p256dh        text not null,         -- su clave publica, para cifrarle
    auth          text not null,         -- su secreto de autenticacion

    nombre        text,                  -- "Chrome en Android", para reconocerlo
    creado_en     timestamptz not null default now(),
    visto_en      timestamptz not null default now(),  -- ultima vez que el panel lo confirmo
    ultimo_envio  timestamptz,           -- ultimo aviso que su servicio acepto
    ultimo_error  text                   -- por que fallo el ultimo, si fallo
);

create index idx_dispositivos_admin_usuario on dispositivos_admin (usuario_id);
