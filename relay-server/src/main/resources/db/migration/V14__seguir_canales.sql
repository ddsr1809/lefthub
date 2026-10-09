-- Seguir un canal de YouTube por si solo.
--
-- Hasta ahora se seguia a un creador (y con el, todos sus canales) o a un
-- medio (y con el, todos los suyos). Eso sigue igual: es "seguirlos a todos".
-- Esta tabla agrega lo otro: quedarse con un canal concreto sin seguir a su
-- dueno. Las dos formas se suman; un canal llega a la persona si lo sigue a
-- el, a su creador, a su medio o a alguno de los creadores con los que aparece.
--
-- Aditivo: la version anterior del servidor arranca sobre este esquema.
create table favoritos_canales (
    usuario_id  uuid not null references usuarios (id) on delete cascade,
    canal_id    uuid not null references canales (id) on delete cascade,
    creado_en   timestamptz not null default now(),
    primary key (usuario_id, canal_id)
);

create index idx_favoritos_canales_canal on favoritos_canales (canal_id);
