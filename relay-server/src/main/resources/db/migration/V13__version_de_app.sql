-- Con que version de la app entra cada cuenta.
--
-- Las apps mandan X-App-Version y X-App-Plataforma en cada peticion, y el
-- servidor lo anota aqui en cada inicio de sesion, que es cada vez que se abre
-- la app. El panel lo usa para saber cuanta gente tiene cada version antes de
-- darla de baja.
--
-- Las dos columnas quedan en null en las cuentas que no han vuelto a abrir la
-- app y en las que entran con una app que todavia no manda esas cabeceras.
--
-- Las versiones dadas de baja no necesitan tabla: van en una fila de
-- `ajustes` (V8), con la clave `apps_dadas_de_baja`.
--
-- Todo es aditivo: la version anterior del servidor arranca sobre este
-- esquema si un despliegue se revierte.

alter table usuarios add column app_version text;      -- "1.0.2", solo los numeros
alter table usuarios add column app_plataforma text;   -- android | ios
