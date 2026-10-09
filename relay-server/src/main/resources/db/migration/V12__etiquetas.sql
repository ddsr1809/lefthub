-- Etiquetas: la forma de agrupar el directorio que decide el equipo.
--
-- Sustituyen a los temas fijos (comida, cine, politica...). Se crean desde el
-- panel, empiezan apagadas y la app solo ensena las que esten encendidas y
-- tengan a alguien dentro. Una misma etiqueta se le puede poner a creadores,
-- a medios (productoras) y a canales, y cada uno puede llevar varias.
--
-- La columna `categoria` de creadores y productoras no se toca: es lo que
-- siguen leyendo las versiones de la app anteriores a esto.
create table etiquetas (
    id         uuid primary key default gen_random_uuid(),
    nombre     text not null,
    -- Apagada no sale en la app, tenga a quien tenga dentro.
    activa     boolean not null default false,
    orden      integer not null default 0,
    creado_en  timestamptz not null default now()
);

-- "Noticias" y "noticias" son la misma.
create unique index idx_etiquetas_nombre on etiquetas (lower(nombre));

create table etiquetas_creadores (
    etiqueta_id  uuid not null references etiquetas (id) on delete cascade,
    creador_id   uuid not null references creadores (id) on delete cascade,
    primary key (etiqueta_id, creador_id)
);
create index idx_etiquetas_creadores_creador on etiquetas_creadores (creador_id);

create table etiquetas_productoras (
    etiqueta_id    uuid not null references etiquetas (id) on delete cascade,
    productora_id  uuid not null references productoras (id) on delete cascade,
    primary key (etiqueta_id, productora_id)
);
create index idx_etiquetas_productoras_productora on etiquetas_productoras (productora_id);

create table etiquetas_canales (
    etiqueta_id  uuid not null references etiquetas (id) on delete cascade,
    canal_id     uuid not null references canales (id) on delete cascade,
    primary key (etiqueta_id, canal_id)
);
create index idx_etiquetas_canales_canal on etiquetas_canales (canal_id);
