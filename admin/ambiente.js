// En qué ambiente está abierto el panel, decidido una sola vez y antes que
// nada: de aquí salen la franja de arriba (app.js), y el nombre y el icono
// con los que el panel se instala como app.
//
// Producción y pruebas se instalan como dos apps distintas, con otro color y
// otro nombre, para que en el teléfono no se confunda una con otra.
(function () {
  var host = location.hostname;
  var ambiente = host.indexOf('testapp.') === 0 || host.indexOf('test') !== -1 ? 'pruebas'
    : (host === 'localhost' || host === '127.0.0.1' || location.protocol === 'file:') ? 'local'
    : 'produccion';
  window.VOCESLEFT_AMBIENTE = ambiente;

  if (ambiente === 'produccion') return;
  var cambios = { manifiesto: 'manifest-pruebas.json', icono: 'iconos/icono-pruebas-192.png', iconoApple: 'iconos/apple-pruebas-180.png' };
  Object.keys(cambios).forEach(function (id) {
    var el = document.getElementById(id);
    if (el) el.setAttribute('href', cambios[id]);
  });
  var titulo = document.querySelector('meta[name="apple-mobile-web-app-title"]');
  if (titulo) titulo.setAttribute('content', 'Admin pruebas');
})();
