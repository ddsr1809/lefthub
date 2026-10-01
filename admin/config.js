// Configuración del panel. Es el único archivo que hay que tocar.
//
// googleClientId: el client_id WEB de Google. Debe ser el mismo que el
// servidor tiene en GOOGLE_CLIENT_ID, porque lo usa como audiencia al
// verificar el token. Es el mismo WEB_CLIENT_ID de android/app/build.gradle.kts.
// No es secreto: viaja en cualquier página que muestre el botón de Google.
//
// apiBase: vacío cuando el panel se sirve desde el mismo dominio que la API
// (https://ythub.d2600.com/admin o https://testhub.d2600.com/admin). Solo para
// abrirlo desde otro origen, pon aquí la URL del servidor y agrega ese origen
// a CORS_ORIGENES en el .env.
window.TUBEHUB_CONFIG = {
  googleClientId: '389825726990-b6ubrv9f9fv2n2rn2r9dcbho9dnmdv8c.apps.googleusercontent.com',
  apiBase: ''
};
