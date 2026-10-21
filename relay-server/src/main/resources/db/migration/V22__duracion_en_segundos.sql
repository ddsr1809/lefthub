-- La duración de cada video en segundos, para poder ordenar por ella.
--
-- La columna `duracion` guarda lo que da YouTube ("PT1H2M3S"), y ordenar ese
-- texto pone "PT9M" después de "PT10M". Esta va al lado, en segundos; la
-- llena el servidor cada vez que guarda una duración (Publicacion.setDuracion)
-- y aquí se calcula para los videos que ya había. Un directo sin terminar
-- ("P0D") o un video sin datos se quedan en null.
--
-- Aditivo: la versión anterior del servidor arranca sobre este esquema.

alter table publicaciones add column duracion_segundos integer;

update publicaciones
set duracion_segundos = nullif(
        coalesce(substring(duracion from 'P(\d+)D')::int, 0) * 86400
      + coalesce(substring(duracion from 'T(\d+)H')::int, 0) * 3600
      + coalesce(substring(duracion from 'T(?:\d+H)?(\d+)M')::int, 0) * 60
      + coalesce(substring(duracion from 'T(?:\d+H)?(?:\d+M)?(\d+)S')::int, 0),
      0)
where duracion is not null;
