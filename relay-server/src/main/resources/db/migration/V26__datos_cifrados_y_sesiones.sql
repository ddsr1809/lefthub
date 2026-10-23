-- Datos personales cifrados y sesiones que se pueden cerrar.
--
-- 1. El correo, la IP, el refresh token de Apple, el comprobante y la orden
--    de una compra, y el autor de una alta desde la app pasan a guardarse
--    cifrados (AES-256-GCM, ver seguridad/CifradorDeDatos). Esta migración
--    solo prepara las columnas: el cifrado lo hace el servidor al arrancar
--    (seguridad/CifradoDeDatosExistentes), porque las llaves no están aquí.
--
-- 2. Para seguir encontrando una cuenta por su correo o su IP, y una compra
--    por su comprobante, cada uno lleva al lado su huella (HMAC-SHA256 con
--    DATOS_INDICE). La huella compara igualdad y no deja recuperar el dato.
--
-- 3. device_id pasa a ser la huella SHA-256 del identificador de la app (ver
--    el final). El identificador es lo que la app presenta para recuperar su
--    cuenta sin pedir nada; guardado tal cual, quien tuviera una copia de la
--    base podría entrar en cualquier cuenta.
--
-- 4. sesiones_desde: los tokens emitidos antes de esa fecha ya no valen.

alter table usuarios add column email_huella text;
alter table usuarios add column ip_huella text;
alter table usuarios add column sesiones_desde timestamptz;

-- Los índices sobre el texto legible ya no sirven: se busca por huella.
drop index if exists idx_usuarios_email;
drop index if exists idx_usuarios_ip;
create index idx_usuarios_email_huella on usuarios (email_huella) where email_huella is not null;
create index idx_usuarios_ip_huella on usuarios (ip_huella) where ip_huella is not null;

-- El mismo comprobante cifrado dos veces da dos textos distintos: lo que no
-- se puede repetir ahora es su huella. Mientras el servidor no la calcula,
-- las filas anteriores la tienen en null, y null no choca con null.
alter table compras add column token_huella text;
alter table compras drop constraint if exists compras_token_unico;
create unique index compras_token_huella_unica on compras (token_huella);

-- device_id NO se convierte aquí: lo hace el servidor al arrancar bien
-- (CifradoDeDatosExistentes), con la misma fórmula que HuellaDeDispositivo:
--   'h1:' || encode(sha256(convert_to(device_id, 'UTF8')), 'hex')
-- Así, si el servidor nuevo fallara al arrancar y el despliegue volviera solo
-- a la versión anterior, esta seguiría encontrando las cuentas por su
-- teléfono. Todo lo de esta migración es aditivo para la versión anterior.
