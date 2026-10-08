-- Anuncios en Novedades, y las dos maneras de quitarlos.
--
-- La app de Android muestra anuncios entre los videos de Novedades. Una
-- persona deja de verlos de dos formas: comprandolo una vez en Google Play, o
-- con un folio de regalo que reparte el equipo. Las dos quedan apuntadas aqui,
-- en su cuenta, para que la acompanen si cambia de telefono.
--
-- El interruptor general (si la app muestra anuncios o no) es un ajuste mas de
-- la tabla `ajustes` de V8, con la clave `anuncios`. Sin fila, no hay anuncios.
--
-- Todo es aditivo: la version anterior del servidor arranca sobre este
-- esquema si un despliegue se revierte.

-- ---------------------------------------------------------------------------
-- La cuenta que ya no ve anuncios
-- ---------------------------------------------------------------------------
-- `origen` dice por que: compra | folio | panel (alguien del equipo lo puso a
-- mano, por ejemplo para atender a quien pago y no se le quitaron).
alter table usuarios add column sin_anuncios boolean not null default false;
alter table usuarios add column sin_anuncios_origen text;
alter table usuarios add column sin_anuncios_desde timestamptz;

-- ---------------------------------------------------------------------------
-- Folios de regalo
-- ---------------------------------------------------------------------------
-- Cada fila es un folio que todavia no se ha usado. Al canjearlo se BORRA la
-- fila: un folio vale una sola vez, y lo que no esta en la tabla no se puede
-- volver a usar. Lo que queda del canje es la marca en la cuenta de arriba.
--
-- El codigo se guarda sin guion y en mayusculas (ABCDEFGHJK), que es como se
-- compara; el guion de en medio (ABCDE-FGHJK) solo se pinta.
create table folios (
    codigo      text primary key,
    nota        text,                   -- para quien es, o de que campana
    creado_por  uuid references usuarios (id) on delete set null,
    creado_en   timestamptz not null default now()
);

create index idx_folios_recientes on folios (creado_en desc);

-- ---------------------------------------------------------------------------
-- Compras verificadas con Google Play
-- ---------------------------------------------------------------------------
-- Una fila por compra que Google confirmo. `token` es el comprobante que la
-- app recibe de Google Play y el servidor le pregunta a Google si es de
-- verdad; `orden` es el numero de pedido (GPA.1234-...), el que aparece en el
-- recibo de la persona y en la consola de Play.
--
-- La misma compra puede llegar otra vez desde otro telefono de la misma
-- persona (es su cuenta de Google Play la que compro): entonces la fila pasa
-- a apuntar a la cuenta que la trajo de ultimo.
--
-- Se va con la cuenta: borrar la cuenta lo borra todo. Si la persona vuelve a
-- instalar la app, Google Play le devuelve su compra y se apunta de nuevo.
create table compras (
    id           uuid primary key default gen_random_uuid(),
    usuario_id   uuid not null references usuarios (id) on delete cascade,
    tienda       text not null default 'google',
    producto     text not null,
    token        text not null,
    orden        text,
    comprado_en  timestamptz,
    creado_en    timestamptz not null default now(),
    constraint compras_token_unico unique (token)
);

create index idx_compras_usuario on compras (usuario_id);
