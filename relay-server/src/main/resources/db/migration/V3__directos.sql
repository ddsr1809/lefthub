-- Seguimiento de directos.
--
-- Hasta ahora solo se guardaba si el video estaba en vivo en el instante en
-- que se detecto. Un directo programado se guardaba como video normal y,
-- cuando arrancaba, el segundo aviso del hub se descartaba por repetido.
--
--   directo          no | programado | en_vivo | terminado
--   directo_avisado  ya se mando el aviso de "esta en vivo ahora"

alter table publicaciones add column directo text not null default 'no';
alter table publicaciones add column directo_avisado boolean not null default false;

-- El vigilante consulta esto cada par de minutos.
create index idx_publicaciones_directo on publicaciones (directo)
    where directo in ('programado', 'en_vivo');

-- Lo detectado en los ultimos dos dias se marca para revision: el vigilante lo
-- reclasifica en su primera pasada y avisa de lo que este en vivo en ese
-- momento. Lo anterior se queda como video normal.
update publicaciones
   set directo = 'programado'
 where not en_vivo
   and detectado_en > now() - interval '2 days';

-- Lo que ya figuraba en vivo se dio por avisado en su dia. El vigilante lo
-- apagara cuando YouTube diga que termino.
update publicaciones
   set directo = 'en_vivo', directo_avisado = true
 where en_vivo;
