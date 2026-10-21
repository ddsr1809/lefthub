-- El chat con el equipo, ahora guardado.
--
-- Hasta V19 el chat era en vivo y no se guardaba: si la persona no estaba
-- conectada en ese momento, no había conversación. Ahora el equipo puede
-- escribir y la persona contesta cuando abre la app, y al revés.
--
-- El texto NO se guarda en claro. Va cifrado con AES-256-GCM con una llave
-- que no está en la base sino en el servidor (CHAT_LLAVES, ver
-- chat/CifradorDeChat): una copia de la base, un respaldo o alguien que
-- consulte la tabla solo ven bytes. Cada mensaje lleva el número de la
-- llave con que se cifró, para poder cambiar de llave sin perder los viejos.
--
-- Se guardan hasta que el equipo borra la conversación, le quita el chat a
-- la cuenta o la persona borra su cuenta (on delete cascade). Al fundir una
-- cuenta de invitado en otra, se pasan a la que se queda (ver AuthController).
--
-- Aditivo: la versión anterior del servidor arranca sobre este esquema.

create table mensajes_chat (
    id          bigserial primary key,
    -- La cuenta con la que se habla. Es la conversación: una por cuenta.
    usuario_id  uuid not null references usuarios (id) on delete cascade,
    -- true: lo escribió alguien del equipo; false: la persona.
    de_admin    boolean not null,
    -- El administrador que lo escribió. Null si lo escribió la persona, o si
    -- después se borró la cuenta de ese administrador.
    autor_id    uuid references usuarios (id) on delete set null,
    -- Con qué llave de CHAT_LLAVES se cifró.
    llave       smallint not null,
    iv          bytea not null,
    -- El texto cifrado, con la etiqueta de autenticación de GCM al final.
    cifrado     bytea not null,
    creado_en   timestamptz not null default now(),
    -- Cuándo lo vio el otro lado. Null mientras no lo ha abierto.
    leido_en    timestamptz
);

-- La conversación de una cuenta, en orden: lo que más se consulta.
create index idx_mensajes_chat_conversacion on mensajes_chat (usuario_id, id);

-- Los que nadie ha leído todavía, para los contadores de "sin leer".
create index idx_mensajes_chat_sin_leer on mensajes_chat (usuario_id, de_admin) where leido_en is null;
