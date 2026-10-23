-- Los videos que cada persona ya abrió desde la app o marcó a mano como
-- vistos ("Ya lo vi" en la ficha del video).
--
-- Antes estas marcas vivían solo en el teléfono. Ahora van con la cuenta para
-- que no se pierdan al cerrar sesión y volver a entrar, ni al cambiar de
-- teléfono. Se guarda lo mínimo: el id del video, si se marcó a mano y
-- cuándo. Sin referencia a publicaciones: un video retirado del directorio no
-- tiene por qué borrar la marca, y el id de YouTube es lo que la app compara.
--
-- a_mano: true si la persona dijo "Ya lo vi" sin abrirlo (o después de
-- abrirlo); false si solo lo abrió desde la app.
--
-- Se van con la cuenta (on delete cascade). El servidor guarda como mucho los
-- 500 más recientes de cada persona (ver VideosVistos).
--
-- Aditivo: la versión anterior del servidor arranca sobre este esquema.
create table videos_vistos (
    usuario_id  uuid not null references usuarios (id) on delete cascade,
    video_id    text not null,
    a_mano      boolean not null default false,
    marcado_en  timestamptz not null default now(),
    primary key (usuario_id, video_id)
);

create index idx_videos_vistos_recientes on videos_vistos (usuario_id, marcado_en desc);
