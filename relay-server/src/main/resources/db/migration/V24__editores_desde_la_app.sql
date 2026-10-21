-- Agregar al directorio desde la app.
--
-- Las cuentas que el equipo autoriza desde el panel pueden dar de alta un
-- creador, un medio o un canal de YouTube desde su teléfono, sin pasar por el
-- panel ni esperar a que nadie lo apruebe: queda visible en el acto, igual
-- que si lo hubiera guardado un administrador. Empieza apagado para todas
-- las cuentas, administradores incluidos.
--
-- Aditivo: la versión anterior del servidor arranca sobre este esquema.

alter table usuarios add column editor_habilitado boolean not null default false;

-- Para la lista de la sección Editores del panel: son pocas cuentas entre muchas.
create index usuarios_editores on usuarios (editor_habilitado) where editor_habilitado;

-- Qué agregó cada cuenta desde la app. Como no hay aprobación previa, esto es
-- lo que permite al equipo revisar después lo que entró y quién lo metió.
create table altas_desde_app (
    id          bigserial primary key,
    -- Si se borra la cuenta, el registro se queda con el correo de abajo.
    usuario_id  uuid references usuarios (id) on delete set null,
    -- El correo en el momento del alta: el historial se lee igual aunque la
    -- cuenta cambie o desaparezca.
    autor       text,
    tipo        text not null,      -- creador | medio | canal
    -- El id del creador, de la productora o del canal. Sin clave foránea: si
    -- el equipo lo borra después, el registro sigue diciendo qué se agregó.
    objeto_id   uuid not null,
    nombre      text not null,
    channel_id  text,
    creado_en   timestamptz not null default now()
);

create index idx_altas_desde_app_recientes on altas_desde_app (creado_en desc);
create index idx_altas_desde_app_de_usuario on altas_desde_app (usuario_id, creado_en desc);
