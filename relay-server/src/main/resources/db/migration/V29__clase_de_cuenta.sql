-- Qué tipo de cuenta es cada creador, medio y canal: general (no político),
-- televisión pública o política y gobierno.
--
-- La app ordena con esto sus listas: primero lo general, después la
-- televisión pública y al final las figuras políticas y las cuentas de
-- gobierno. Se elige en el panel.
--
-- Un canal puede no tener la suya (null): entonces es la de su dueño, el
-- creador o, si no tiene, el medio. Aditivo: la versión anterior del servidor
-- arranca sobre este esquema.

alter table creadores   add column clase text not null default 'general'
    check (clase in ('general', 'television_publica', 'politica'));
alter table productoras add column clase text not null default 'general'
    check (clase in ('general', 'television_publica', 'politica'));
alter table canales     add column clase text
    check (clase is null or clase in ('general', 'television_publica', 'politica'));

-- Las versiones (migrar de pruebas a producción) llevan también la clase.
-- Solo se escribe cuando no es la de siempre: así la huella de las fichas
-- que ya estaban no cambia, y no aparecen todas como pendientes de migrar.
create or replace function directorio_canales(de_creador uuid, de_productora uuid)
returns jsonb language sql stable as $$
    select coalesce(jsonb_agg(jsonb_build_object(
                   'plataforma', k.plataforma,
                   'nombre', k.nombre,
                   'url', k.url,
                   'handle', k.handle,
                   'channel_id', k.channel_id,
                   'productora_id', case when k.creador_id is not null then k.productora_id end,
                   'vinculados', (select coalesce(jsonb_agg(v.creador_id order by v.creador_id), '[]'::jsonb)
                                    from canales_creadores v where v.canal_id = k.id))
               || case when k.clase is not null then jsonb_build_object('clase', k.clase) else '{}'::jsonb end
               order by k.orden, k.creado_en, k.id), '[]'::jsonb)
      from canales k
     where (de_creador is not null and k.creador_id = de_creador)
        or (de_creador is null and k.creador_id is null and k.productora_id = de_productora)
$$;

create or replace function directorio_creador(c creadores)
returns jsonb language sql stable as $$
    select jsonb_build_object(
        'id', c.id,
        'origen_id', c.origen_id,
        'nombre', c.nombre,
        'categoria', c.categoria,
        'bio', c.bio,
        'foto_url', c.foto_url,
        'activo', c.activo,
        'productoras', (select coalesce(jsonb_agg(l.productora_id order by l.productora_id), '[]'::jsonb)
                          from creadores_productoras l where l.creador_id = c.id),
        'canales', directorio_canales(c.id, null))
        || case when c.clase <> 'general' then jsonb_build_object('clase', c.clase) else '{}'::jsonb end
$$;

create or replace function directorio_productora(p productoras)
returns jsonb language sql stable as $$
    select jsonb_build_object(
        'id', p.id,
        'origen_id', p.origen_id,
        'nombre', p.nombre,
        'descripcion', p.descripcion,
        'logo_url', p.logo_url,
        'activo', p.activo,
        'en_directorio', p.en_directorio,
        'categoria', p.categoria,
        'canales', directorio_canales(null, p.id))
        || case when p.clase <> 'general' then jsonb_build_object('clase', p.clase) else '{}'::jsonb end
$$;
