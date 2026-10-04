-- De que creador de produccion es copia cada creador de testing.
--
-- Produccion manda a testing cada creador que se guarda en su panel. La copia
-- NO conserva el id de produccion: los dos servidores usan el mismo proyecto
-- de Firebase y el topic de los avisos se arma con el id del creador
-- (creator_<id>). Con el mismo id, un aviso mandado desde testing llegaria
-- tambien a quienes siguen a ese creador en la app de produccion.
--
-- Por eso testing genera su propio id y guarda aqui el de origen, que es lo
-- que permite actualizar la misma fila cuando el creador cambia de nombre o
-- de canal en produccion. En produccion la columna queda siempre vacia.
alter table creadores add column origen_id uuid;

create unique index idx_creadores_origen on creadores (origen_id) where origen_id is not null;
