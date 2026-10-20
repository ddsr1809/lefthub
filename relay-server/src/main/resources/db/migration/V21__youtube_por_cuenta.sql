-- Suscripciones de YouTube por cuenta.
--
-- El ajuste 'youtube' deja de ser un interruptor y pasa a decir a quién se
-- le ofrece "Conectar con YouTube", igual que las recomendaciones:
--   apagado  - a nadie
--   admins   - solo a los administradores
--   elegidos - a los administradores y a las cuentas marcadas aquí abajo
--   todos    - a cualquier cuenta que haya entrado con Google
--
-- Sirve para la verificación de OAuth: Google pide no ofrecer un permiso sin
-- verificar a todos los usuarios, pero sí poder probarlo dentro de la app de
-- producción con cuentas elegidas (la del revisor, por ejemplo).
--
-- Aditivo: la versión anterior del servidor arranca sobre este esquema. El
-- valor viejo "true" del ajuste se sigue leyendo como "todos" (ver
-- AjustesService.youtube).

alter table usuarios add column youtube_habilitado boolean not null default false;

create index usuarios_con_youtube on usuarios (youtube_habilitado) where youtube_habilitado;
