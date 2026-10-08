-- Fotos de perfil tomadas de una red social (X, Instagram, TikTok...).
--
-- Se guarda una copia y no solo la direccion porque las direcciones de esas
-- redes caducan a los pocos dias: la foto de un creador dejaria de verse sin
-- que nadie tocara nada. El servidor la sirve desde /api/fotos/{id}.
--
-- Una fila por cuenta (`origen` = "x/usuario"): volver a tomar la foto de la
-- misma cuenta la sustituye en vez de acumular copias.
create table fotos (
    id              uuid primary key default gen_random_uuid(),
    origen          text not null,
    tipo            text not null,          -- image/jpeg, image/png...
    datos           bytea not null,
    actualizado_en  timestamptz not null default now()
);

create unique index idx_fotos_origen on fotos (origen);
