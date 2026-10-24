-- Con qué llave firma cada cuenta, de qué app viene su App Check, y su
-- versión de la app siempre al día.
--
-- llave_numero: el número de la llave con la que firmó su última petición
--   (la cabecera X-App-Llave: el 1 de APP_LLAVES=1:...,2:...). Al cambiar de
--   llave se ve quién sigue con la vieja, para saber cuándo quitarla.
-- llave_app_check_app: la app de Firebase de su último token de App Check
--   bueno (el `sub` del token, "1:1025901070678:android:..."). Dice si viene
--   de la app de producción, de la de pruebas o de developer.
-- app_version_orden: la versión de la app como número (1.2.2 -> 1002002),
--   para ordenar bien: como texto, "1.1.10" iría antes que "1.1.9".
--
-- Las dos primeras las escribe solo seguridad/RegistroDeLlaves, como las de
-- V27. La versión la escribe también él con cada petición de la app, no solo
-- al entrar: así quien instala la nueva cambia de versión en el panel en un
-- minuto. Aditivo: la versión anterior del servidor arranca sobre esto.

alter table usuarios add column llave_numero smallint;
alter table usuarios add column llave_app_check_app text;
alter table usuarios add column app_version_orden integer;

update usuarios
set app_version_orden =
        least(coalesce(nullif(split_part(app_version, '.', 1), '')::int, 0), 999) * 1000000
      + least(coalesce(nullif(split_part(app_version, '.', 2), '')::int, 0), 999) * 1000
      + least(coalesce(nullif(split_part(app_version, '.', 3), '')::int, 0), 999)
where app_version ~ '^[0-9]+(\.[0-9]+){0,3}$';

create index idx_usuarios_app_version on usuarios (app_plataforma, app_version_orden);
