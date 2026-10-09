-- Videos cortos (Shorts) aparte de los videos normales.
--
-- Hasta ahora un Short entraba en las novedades y avisaba como cualquier
-- video. Ahora van aparte y son opcionales en dos niveles:
--
--   · el panel decide si la app los muestra (ajuste `cortos`, apagado de
--     entrada): apagado, se siguen guardando pero ni salen ni avisan;
--   · encendido, cada persona decide en la app si los quiere (usuarios.cortos).
--     Salen en su propio apartado, nunca mezclados con los videos.
--
-- Todo es aditivo: la version anterior del servidor arranca sobre este
-- esquema si un despliegue se revierte.

-- ---------------------------------------------------------------------------
-- Ajustes generales, los que se cambian desde el panel
-- ---------------------------------------------------------------------------
-- Clave y valor: son un punado de interruptores, no merece una columna cada
-- uno. Un ajuste que no tiene fila vale lo que diga el codigo por defecto.
create table ajustes (
    clave           text primary key,
    valor           text not null,
    actualizado_en  timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- La preferencia de cada persona
-- ---------------------------------------------------------------------------
-- Solo cuenta mientras el panel los tenga encendidos. Encendida de entrada:
-- cuando el equipo decide mostrarlos, se ven; quien no los quiera los apaga.
alter table usuarios add column cortos boolean not null default true;
