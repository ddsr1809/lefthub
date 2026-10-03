// Configuración del panel. Es el único archivo que hay que tocar.
//
// googleClientId: el client_id WEB de Google. Debe ser el mismo que el
// servidor tiene en GOOGLE_CLIENT_ID, porque lo usa como audiencia al
// verificar el token. Es el mismo WEB_CLIENT_ID de android/app/build.gradle.kts.
// No es secreto: viaja en cualquier página que muestre el botón de Google.
//
// apiBase: vacío cuando el panel se sirve desde el mismo dominio que la API
// (https://leftapp.vocesdeizquierda.com/admin o https://testapp.vocesdeizquierda.com/admin). Solo para
// abrirlo desde otro origen, pon aquí la URL del servidor y agrega ese origen
// a CORS_ORIGENES en el .env.
window.VOCESLEFT_CONFIG = {
  googleClientId: '445243795956-jso91c84gsepuukis0h5dr91r9a0t4eu.apps.googleusercontent.com',
  apiBase: ''
};
