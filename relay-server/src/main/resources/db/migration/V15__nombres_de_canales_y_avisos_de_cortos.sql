-- Dos cosas, las dos aditivas: la versión anterior del servidor arranca sobre
-- este esquema si un despliegue se revierte.

-- ---------------------------------------------------------------------------
-- El nombre de cada canal tal como sale en YouTube
-- ---------------------------------------------------------------------------
-- La app lo enseña primero en la lista de canales, arriba del nombre del
-- creador. Lo llena solo el servidor (TitulosDeCanales), con una consulta a la
-- Data API por cada 50 canales, y lo repasa cada semana por si el canal
-- cambió de nombre. `titulo_en` es cuándo se preguntó por última vez: null
-- es "todavía no", y un canal al que se le cambia el channel_id vuelve a null.
alter table canales add column titulo text;
alter table canales add column titulo_en timestamptz;

-- ---------------------------------------------------------------------------
-- Los avisos de videos cortos, aparte de verlos
-- ---------------------------------------------------------------------------
-- Ver los cortos en su apartado sigue siendo `usuarios.cortos`. Que llegue
-- una notificación por cada corto es otra cosa, y va apagada para todos:
-- solo le llegan a quien la encienda en Ajustes.
alter table usuarios add column avisos_cortos boolean not null default false;
