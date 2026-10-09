-- Canales que aparecen con varios creadores, y productoras en el directorio.
--
-- Hasta ahora el video de un canal solo le llegaba a quien seguia a su dueno:
-- su creador o, si no tenia, su productora. El canal oficial de una productora
-- no le llegaba a quien seguia a los creadores que figuran en ella, porque son
-- dos ligas distintas (V6). Con esto:
--
--   · un canal puede aparecer ademas con otros creadores, los que se elijan
--     en su ficha: sus videos les llegan tambien a quienes los siguen;
--   · una productora puede aparecer en el directorio como un creador mas,
--     para seguirla directamente tambien desde las versiones de la app que no
--     conocen las productoras.
--
-- Todo es aditivo: la version anterior del servidor arranca sobre este
-- esquema si un despliegue se revierte.

-- ---------------------------------------------------------------------------
-- Con que otros creadores aparece un canal
-- ---------------------------------------------------------------------------
-- El dueno sigue siendo canales.creador_id (o la productora si no tiene): es
-- quien firma los avisos y quien decide, con estar visible u oculto, si el
-- canal se vigila. Estos son los demas.
create table canales_creadores (
    canal_id    uuid not null references canales (id) on delete cascade,
    creador_id  uuid not null references creadores (id) on delete cascade,
    primary key (canal_id, creador_id)
);

-- El feed de quien sigue a un creador busca los canales en los que aparece.
create index idx_canales_creadores_creador on canales_creadores (creador_id);

-- ---------------------------------------------------------------------------
-- Productoras en el directorio
-- ---------------------------------------------------------------------------
alter table productoras
    add column en_directorio boolean not null default false,
    -- En que tema del directorio sale. Mismos valores que creadores.categoria.
    add column categoria text not null default 'otros';
