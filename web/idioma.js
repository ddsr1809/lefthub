// Idioma del sitio. Las páginas en español viven en la raíz y las de inglés
// en /en/. Si el navegador no está en español, las páginas en español mandan
// a su versión en inglés. Quien elige un idioma con el enlace de arriba se
// queda en ese idioma: la elección se guarda en su propio navegador.
//
// Va en el <head>, antes de pintar nada, para que no se vea el cambio.
(function () {
  var CLAVE = 'idioma';
  // Secciones a las que se enlaza desde fuera y cambian de nombre en inglés.
  var ANCLAS = { '#borrar-cuenta': '#delete-account' };

  function elegido() {
    try { return localStorage.getItem(CLAVE); } catch (e) { return null; }
  }

  var raiz = document.documentElement;
  var enIngles = raiz.getAttribute('data-en');
  var navegador = (navigator.languages && navigator.languages[0]) || navigator.language || '';
  // Los buscadores y las vistas previas de enlaces deben ver cada página tal
  // cual, o la versión en español nunca aparecería en los resultados.
  var robot = /bot|crawl|spider|slurp|preview|facebookexternalhit|whatsapp/i.test(navigator.userAgent);

  // Manda lo que la persona eligió; si no ha elegido, el idioma del navegador.
  var quiere = elegido() || (navegador && !/^es\b/i.test(navegador) ? 'en' : 'es');

  if (raiz.lang === 'es' && enIngles && quiere === 'en' && !robot) {
    location.replace(enIngles + (ANCLAS[location.hash] || ''));
    return;
  }

  document.addEventListener('click', function (e) {
    var enlace = e.target.closest ? e.target.closest('a[data-idioma]') : null;
    if (!enlace) return;
    try { localStorage.setItem(CLAVE, enlace.getAttribute('data-idioma')); } catch (err) {}
  });
})();
