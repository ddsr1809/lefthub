-- Avisos que manda el equipo desde el panel.
--
-- Hasta ahora solo salían avisos automáticos (video nuevo, directo, corto) y
-- el de "este video cambió de lugar". Esta tabla guarda los que escribe una
-- persona: un creador, un medio o un canal nuevo en el directorio, un video
-- retirado, una cuenta bloqueada o un mensaje libre. Sirve de historial en el
-- panel y de prueba de qué se mandó, a quién y si FCM lo aceptó.

create table avisos (
    id              bigserial primary key,
    tipo            text not null,      -- nuevo_creador | nuevo_medio | nuevo_canal | video_retirado | cuenta_bloqueada | mensaje
    titulo          text not null,
    cuerpo          text not null,
    enlace          text,
    -- A quién: "Todos" o "Quienes siguen a ...". Texto ya redactado, para que
    -- el historial se lea igual aunque luego se borre el creador.
    destino         text not null,
    creador_id      uuid,
    productora_id   uuid,
    canal_id        uuid,
    video_id        text,
    enviado         boolean not null default false,
    error           text,
    autor_id        uuid,
    creado_en       timestamptz not null default now()
);

create index idx_avisos_recientes on avisos (creado_en desc);

-- Cuándo se avisó a todos de que el creador llegó al directorio. Sirve para
-- no anunciarlo dos veces y para ofrecer la casilla solo a los que faltan.
alter table creadores add column anunciado_en timestamptz;
