-- Canales con identidad propia, y productoras.
--
-- Hasta ahora un creador tenia como mucho UNA conexion por plataforma (la
-- clave de `conexiones` era creador + plataforma). Con esto:
--
--   · un creador puede tener varios canales en la misma plataforma;
--   · existe la productora, a la que se ligan creadores y canales;
--   · las dos ligas son independientes:
--       creador <-> productora   muchos a muchos (creadores_productoras)
--       canal    -> productora   opcional, por canal (canales.productora_id)
--     De tres canales de un creador, uno puede ser de una productora y los
--     otros dos no. Y un canal puede ser de la productora sin creador: el
--     canal oficial de la casa.
--
-- Nada de lo anterior se borra ni se cambia de sitio. `conexiones` y
-- `youtube_suscripciones` se quedan donde estan aunque el servidor nuevo ya no
-- las use: si un despliegue se revierte, la version anterior arranca sobre
-- este esquema. Se retiran en una migracion posterior, cuando esto lleve un
-- tiempo en produccion.

-- ---------------------------------------------------------------------------
-- Productoras
-- ---------------------------------------------------------------------------
create table productoras (
    id              uuid primary key default gen_random_uuid(),
    nombre          text not null,
    descripcion     text,
    logo_url        text,
    activo          boolean not null default true,
    -- Solo en testing: id que tiene en produccion. Misma razon que en
    -- creadores (V5): el topic de los avisos se arma con el id.
    origen_id       uuid,
    creado_en       timestamptz not null default now(),
    actualizado_en  timestamptz not null default now()
);

create unique index idx_productoras_origen on productoras (origen_id) where origen_id is not null;

-- ---------------------------------------------------------------------------
-- Canales
-- ---------------------------------------------------------------------------
create table canales (
    id             uuid primary key default gen_random_uuid(),
    -- El dueno es el creador. Si no hay creador, es la productora.
    creador_id     uuid references creadores (id) on delete cascade,
    productora_id  uuid references productoras (id) on delete set null,
    plataforma     text not null,
    nombre         text,           -- "Clips", "Directos"... vacio en el canal de siempre
    url            text not null,
    handle         text,
    channel_id     text,
    -- Posicion dentro del dueno. El primero de cada plataforma es el
    -- principal: es el que ven las versiones de la app anteriores a esto.
    orden          integer not null default 0,
    creado_en      timestamptz not null default now(),
    constraint canales_con_dueno check (creador_id is not null or productora_id is not null)
);

create index idx_canales_creador on canales (creador_id) where creador_id is not null;
create index idx_canales_productora on canales (productora_id) where productora_id is not null;

-- Cada conexion de hoy pasa a ser un canal de su creador, en el mismo orden
-- en que la app las muestra.
insert into canales (creador_id, plataforma, url, handle, channel_id, orden)
select creador_id, plataforma, url, handle, nullif(btrim(channel_id), ''),
       coalesce(array_position(
           array['youtube', 'tiktok', 'twitch', 'instagram', 'spotify', 'patreon', 'web'],
           plataforma), 99) - 1
  from conexiones;

-- Un canal de YouTube es de un solo dueno: el webhook busca por channel_id en
-- cada aviso y tiene que encontrar una fila, no dos. Antes nada lo impedia en
-- la base. Si hubiera algun repetido, se queda con el canal el creador mas
-- antiguo; el otro conserva el enlace pero deja de recibir avisos, que es lo
-- que ya le pasaba: con dos filas el webhook fallaba para ese canal.
update canales k
   set channel_id = null
  from creadores kc
 where kc.id = k.creador_id
   and k.plataforma = 'youtube'
   and k.channel_id is not null
   and exists (
       select 1
         from canales o
         join creadores oc on oc.id = o.creador_id
        where o.plataforma = 'youtube'
          and o.channel_id = k.channel_id
          and o.id <> k.id
          and (oc.creado_en, o.id) < (kc.creado_en, k.id)
   );

-- La busqueda mas frecuente del sistema, ahora ademas con garantia de unico.
create unique index idx_canales_youtube on canales (channel_id)
    where plataforma = 'youtube' and channel_id is not null;

-- ---------------------------------------------------------------------------
-- Creadores de una productora
-- ---------------------------------------------------------------------------
create table creadores_productoras (
    creador_id     uuid not null references creadores (id) on delete cascade,
    productora_id  uuid not null references productoras (id) on delete cascade,
    primary key (creador_id, productora_id)
);

create index idx_creadores_productoras_productora on creadores_productoras (productora_id);

-- ---------------------------------------------------------------------------
-- Seguir a una productora
-- ---------------------------------------------------------------------------
create table favoritos_productoras (
    usuario_id     uuid not null references usuarios (id) on delete cascade,
    productora_id  uuid not null references productoras (id) on delete cascade,
    creado_en      timestamptz not null default now(),
    primary key (usuario_id, productora_id)
);

create index idx_favoritos_productoras_productora on favoritos_productoras (productora_id);

-- ---------------------------------------------------------------------------
-- Publicaciones: de que canal salio cada una
-- ---------------------------------------------------------------------------
-- El canal de una productora sin creador tambien publica, asi que el creador
-- deja de ser obligatorio.
alter table publicaciones alter column creador_id drop not null;

-- Si el canal se quita del directorio, la publicacion sigue siendo de su
-- creador; solo pierde la referencia.
alter table publicaciones add column canal_id uuid references canales (id) on delete set null;

-- Hasta hoy cada creador tenia un solo canal de YouTube: no hay duda de cual.
update publicaciones p
   set canal_id = k.id
  from canales k
 where k.creador_id = p.creador_id
   and k.plataforma = 'youtube'
   and p.plataforma = 'youtube';

-- El feed de quien sigue a una productora busca por canal.
create index idx_publicaciones_canal on publicaciones (canal_id, publicado_en desc)
    where canal_id is not null;

-- ---------------------------------------------------------------------------
-- Suscripciones de YouTube del usuario, ahora por canal
-- ---------------------------------------------------------------------------
-- Mismas reglas que youtube_suscripciones (V4): dato prestado por YouTube, se
-- reescribe en cada comprobacion y se purga a los 30 dias.
create table youtube_suscripciones_canales (
    usuario_id     uuid not null references usuarios (id) on delete cascade,
    canal_id       uuid not null references canales (id) on delete cascade,
    suscrito       boolean not null,
    verificado_en  timestamptz not null default now(),
    primary key (usuario_id, canal_id)
);

create index idx_youtube_suscripciones_canales_antiguedad
    on youtube_suscripciones_canales (verificado_en);

-- La tabla anterior ya no se actualiza. Se vacia para no conservar datos de
-- YouTube mas alla de lo que permiten sus politicas; la app los vuelve a pedir
-- sola la proxima vez que cada persona la abra.
delete from youtube_suscripciones;
