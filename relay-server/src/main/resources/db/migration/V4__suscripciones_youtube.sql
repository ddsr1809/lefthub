-- Si la persona esta suscrita en YouTube al canal de cada creador.
--
-- Es un dato del usuario que YouTube nos presta, no nuestro. Por eso:
--   · solo existe para cuentas que entraron con Google y dieron el permiso;
--   · se reescribe entero cada vez que la app abre y se vuelve a comprobar;
--   · se borra si la persona retira el permiso, si borra la cuenta (cascada)
--     o si pasan 30 dias sin volver a comprobarse (tarea programada).
--
-- No se llama `suscripciones` porque esa tabla ya existe y es otra cosa: el
-- estado de WebSub de cada canal.
create table youtube_suscripciones (
    usuario_id      uuid not null references usuarios (id) on delete cascade,
    creador_id      uuid not null references creadores (id) on delete cascade,
    suscrito        boolean not null,
    verificado_en   timestamptz not null default now(),
    primary key (usuario_id, creador_id)
);

-- La limpieza diaria busca por antiguedad.
create index idx_youtube_suscripciones_antiguedad on youtube_suscripciones (verificado_en);
