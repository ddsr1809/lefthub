-- Datos de la última conexión de cada cuenta.
--
-- Van como columnas de `usuarios` y no en una tabla aparte a propósito: se
-- guarda solo el ÚLTIMO acceso, no un historial. Así no crece sin límite, y al
-- borrar una cuenta se van con ella sin tener que acordarse de otra tabla.
--
-- Todas admiten NULL o traen valor por defecto, de modo que la versión
-- anterior del servidor sigue funcionando sobre este esquema si un despliegue
-- se revierte.

alter table usuarios
    add column ip           text,
    add column pais         text,       -- ISO 3166-1 alfa-2: MX, US, ES...
    add column asn          bigint,     -- número de sistema autónomo de la red
    -- Nombre de la compañía de internet (Telcel, Telmex, Amazon...). Se llama
    -- `red` porque `proveedor` ya existe y significa otra cosa: con qué entra
    -- la persona (anonimo | google | apple).
    add column red          text,
    add column agente       text,       -- cabecera User-Agent de la app
    add column posible_bot  boolean not null default false,
    add column motivo_bot   text;

-- El panel busca "todas las cuentas que salen de esta IP".
create index idx_usuarios_ip on usuarios (ip) where ip is not null;
