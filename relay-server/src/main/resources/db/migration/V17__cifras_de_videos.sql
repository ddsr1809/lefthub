-- Las cifras públicas de cada video: vistas, "me gusta", comentarios y, en un
-- directo, cuántos lo están viendo. Solo las muestra el panel.
--
-- Son una foto: las pide a YouTube la tarea CifrasDeVideos cada cierto tiempo
-- (más seguido cuanto más nuevo es el video) y `cifras_en` dice de cuándo
-- son. Null en una cifra es que YouTube no la da (el canal oculta los "me
-- gusta", por ejemplo) o que todavía no se ha pedido.
--
-- La duración ya se guardaba (columna `duracion`, desde V1).
--
-- Aditivo: la versión anterior del servidor arranca sobre este esquema.

alter table publicaciones add column vistas bigint;
alter table publicaciones add column me_gusta bigint;
alter table publicaciones add column comentarios bigint;
alter table publicaciones add column espectadores bigint;
alter table publicaciones add column cifras_en timestamptz;

-- Para la tarea: primero las que nunca se han pedido, luego las más viejas.
create index publicaciones_por_cifras on publicaciones (cifras_en asc nulls first);

-- Para ordenar el panel por "más vistos".
create index publicaciones_por_vistas on publicaciones (vistas desc nulls last);
