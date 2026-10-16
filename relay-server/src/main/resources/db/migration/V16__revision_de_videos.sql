-- Los videos que llegaron sin sus datos de YouTube.
--
-- Al llegar un video nuevo, el servidor le pide a la Data API su duración,
-- su miniatura y su descripción. Si no contesta (la clave no vale, se acabó
-- la cuota, YouTube todavía lo está procesando), el video se guarda como
-- normal y sin duración, y hasta ahora nadie volvía a preguntar: un Short que
-- llegaba así se quedaba para siempre entre los videos normales.
--
-- Ahora RevisionDeVideos vuelve a preguntar por ellos. Aditivo: la versión
-- anterior del servidor arranca sobre este esquema.

-- Cuándo se le volvió a preguntar a YouTube por un video sin datos. Null es
-- "todavía no": así no se pregunta dos veces por uno que YouTube ya no tiene.
alter table publicaciones add column datos_revisados_en timestamptz;

-- El equipo lo marcó a mano en el panel ("Es corto" / "No es corto"). La
-- revisión no le cambia el tipo a uno así.
alter table publicaciones add column tipo_a_mano boolean not null default false;

-- Solo los que faltan: es la consulta de cada pasada.
create index publicaciones_sin_datos on publicaciones (publicado_en desc)
    where duracion is null and datos_revisados_en is null;
