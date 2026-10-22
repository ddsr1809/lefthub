-- Stickers de los creadores.
--
-- El equipo sube paquetes de stickers de un creador desde el panel y la app
-- los descarga para mandarlos por WhatsApp, Telegram, Signal, Messenger...
-- La app no los trae instalados: los baja del servidor y los guarda en su
-- carpeta privada, que Android borra al desinstalarla.
--
-- Empieza apagado para todos, administradores incluidos: el ajuste
-- 'stickers' (apagado | admins | elegidos | todos) no tiene fila hasta que
-- alguien lo cambia en el panel, y sin fila es "apagado". Con "elegidos", lo
-- ven los administradores y las cuentas marcadas con stickers_habilitado.
--
-- Aditivo: la versión anterior del servidor arranca sobre este esquema.

alter table usuarios add column stickers_habilitado boolean not null default false;

-- Para la lista de la sección Stickers del panel: son pocas cuentas entre muchas.
create index usuarios_con_stickers on usuarios (stickers_habilitado) where stickers_habilitado;

-- Un paquete es lo que WhatsApp llama "sticker pack": un nombre, un ícono y
-- de 3 a 30 stickers, todos fijos o todos animados.
create table paquetes_stickers (
    id              uuid primary key default gen_random_uuid(),
    -- Si se borra el creador, sus paquetes se van con él.
    creador_id      uuid not null references creadores (id) on delete cascade,
    nombre          text not null,
    -- Mientras no se publica, solo lo ve el panel.
    publicado       boolean not null default false,
    -- Lo decide el primer sticker: WhatsApp no deja mezclar fijos y animados.
    animado         boolean not null default false,
    -- Sube con cada cambio de stickers o de ícono. Es el image_data_version
    -- que lee WhatsApp para saber que tiene que volver a pedir las imágenes,
    -- y lo que la app compara para saber si su copia está vieja.
    version         integer not null default 1,
    orden           integer not null default 0,
    -- El ícono de la bandeja (96 x 96, PNG o WebP). Los bytes van aparte, en
    -- paquetes_iconos, para que listar paquetes no los lea.
    icono_tipo      text,
    icono_peso      integer,
    creado_en       timestamptz not null default now(),
    actualizado_en  timestamptz not null default now()
);

create index idx_paquetes_stickers_creador on paquetes_stickers (creador_id, orden);

create table paquetes_iconos (
    paquete_id  uuid primary key references paquetes_stickers (id) on delete cascade,
    datos       bytea not null
);

-- Cada sticker: WebP de 512 x 512. Fijo hasta 100 KB, animado hasta 500 KB.
create table stickers (
    id          uuid primary key default gen_random_uuid(),
    paquete_id  uuid not null references paquetes_stickers (id) on delete cascade,
    orden       integer not null default 0,
    -- De 1 a 3 emojis separados por un espacio. WhatsApp los usa para
    -- sugerir el sticker al escribir.
    emojis      text not null,
    animado     boolean not null default false,
    peso        integer not null,
    creado_en   timestamptz not null default now()
);

create index idx_stickers_paquete on stickers (paquete_id, orden);

-- Los bytes, aparte por lo mismo que el ícono.
create table stickers_datos (
    sticker_id  uuid primary key references stickers (id) on delete cascade,
    datos       bytea not null
);
