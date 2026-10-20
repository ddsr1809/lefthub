-- Chat interno entre el equipo y una cuenta.
--
-- Lo único que se guarda es el permiso: el panel lo enciende por cuenta y solo
-- entonces un administrador puede abrirle una conversación. Los mensajes no
-- tocan la base: viajan cifrados de extremo a extremo y el servidor solo los
-- tiene en memoria hasta que el otro lado los recoge (ver chat/SalasDeChat).
--
-- Aditivo: la versión anterior del servidor arranca sobre este esquema.

alter table usuarios add column chat_habilitado boolean not null default false;

-- Para la lista de la sección Chat del panel: son pocas cuentas entre muchas.
create index usuarios_con_chat on usuarios (chat_habilitado) where chat_habilitado;
