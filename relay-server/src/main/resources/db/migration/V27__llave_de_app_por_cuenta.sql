-- Cómo llegó la última petición de cada cuenta: con la firma de la llave de
-- la app y con el token de App Check, o sin ellos. Es lo que el panel enseña
-- en Usuarios ("Firma bien", "Sin firma", "Firma con error"...) y en la
-- tarjeta Apps > Llave de la app. Ver seguridad/RegistroDeLlaves.
--
-- llave_firma:     ok | falta | mala | hora | repetida
-- llave_app_check: ok | falta | malo | sin_verificar
-- null = la cuenta no ha hecho ninguna petición comprobada desde que existe
-- esto, o esa comprobación estaba apagada.

alter table usuarios add column llave_firma text;
alter table usuarios add column llave_app_check text;
alter table usuarios add column llave_vista_en timestamptz;

create index idx_usuarios_llave on usuarios (llave_vista_en) where llave_vista_en is not null;
