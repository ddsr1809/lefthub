-- Recomendaciones de canales: la gente propone canales de YouTube que le
-- gustaría ver en el directorio, y el equipo los revisa desde el panel.
--
-- Empieza apagado para todos, administradores incluidos. Quién puede
-- recomendar lo dice el ajuste 'recomendaciones' (ver AjustesService):
--   apagado  - nadie (es lo que vale sin fila en la tabla)
--   admins   - solo los administradores
--   elegidos - los administradores y las cuentas marcadas aquí abajo
--   todos    - cualquier cuenta
--
-- Aditivo: la versión anterior del servidor arranca sobre este esquema.

alter table usuarios add column recomendar_habilitado boolean not null default false;

-- Para la lista de cuentas elegidas del panel: son pocas entre muchas.
create index usuarios_que_recomiendan on usuarios (recomendar_habilitado) where recomendar_habilitado;

create table recomendaciones (
    id              bigserial primary key,
    -- Al borrar la cuenta se van sus recomendaciones: el motivo lo escribió
    -- la persona y puede decir algo de ella. Al fundir dos cuentas se pasan
    -- a la que se queda (ver AuthController).
    usuario_id      uuid not null references usuarios (id) on delete cascade,
    -- Lo que escribió, tal cual: un enlace, un @handle o solo un nombre.
    texto           text not null,
    motivo          text,
    -- Lo que se supo del canal en YouTube al recibirla. Null si escribió solo
    -- un nombre o si YouTube no lo encontró: el equipo lo busca a mano.
    channel_id      text,
    titulo          text,
    handle          text,
    foto_url        text,
    estado          text not null default 'pendiente',   -- pendiente | agregado | descartado
    revisado_en     timestamptz,
    revisado_por    uuid,
    creado_en       timestamptz not null default now()
);

create index idx_recomendaciones_recientes on recomendaciones (creado_en desc);
create index idx_recomendaciones_de_usuario on recomendaciones (usuario_id, creado_en desc);
