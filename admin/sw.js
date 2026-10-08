// Lo que hace que el panel funcione como app instalada: abre aunque no haya
// red, y recibe avisos aunque esté cerrado.
//
// Vive en la carpeta del panel, así que solo ve lo que hay bajo ella. Las
// llamadas a /api no pasan por aquí: los datos se piden siempre al servidor.

const CACHE = 'voces-admin-1';

// Lo necesario para pintar el panel. Lo demás (fotos, miniaturas) no se guarda.
const CASCARON = [
  './', 'estilos.css', 'ambiente.js', 'config.js', 'versiones.js', 'app.js',
  'manifest.json', 'manifest-pruebas.json',
  'iconos/icono-192.png', 'iconos/icono-pruebas-192.png', 'iconos/insignia-96.png'
];

const ALCANCE = self.registration.scope;
const DE_PRUEBAS = self.location.hostname.indexOf('test') !== -1;
const LOCAL = self.location.hostname === 'localhost' || self.location.hostname === '127.0.0.1';

self.addEventListener('install', (e) => {
  // Si algún archivo no baja, se instala igual: se guardará al usarse.
  e.waitUntil(caches.open(CACHE)
    .then((c) => Promise.all(CASCARON.map((u) => c.add(new Request(u, { cache: 'reload' })).catch(() => null))))
    .then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys()
    .then((claves) => Promise.all(claves.filter((k) => k.startsWith('voces-admin-') && k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

// Primero la red, siempre: después de un `git pull` en el VPS, la app instalada
// trae la versión nueva al abrirse, igual que el panel en el navegador. La
// copia guardada solo se usa cuando no hay conexión.
self.addEventListener('fetch', (e) => {
  const pedido = e.request;
  if (pedido.method !== 'GET' || !pedido.url.startsWith(ALCANCE)) return;
  const ruta = new URL(pedido.url).pathname;
  if (ruta.indexOf('/api/') !== -1) return;

  e.respondWith((async () => {
    const cache = await caches.open(CACHE);
    try {
      const fresca = await fetch(pedido);
      if (fresca.ok) cache.put(pedido, fresca.clone()).catch(() => null);
      return fresca;
    } catch (sinRed) {
      const guardada = await cache.match(pedido, { ignoreSearch: true })
        || (pedido.mode === 'navigate' ? await cache.match('./') : null);
      if (guardada) return guardada;
      throw sinRed;
    }
  })());
});

// -----------------------------------------------------------------------------
// Avisos
// -----------------------------------------------------------------------------
// El servidor manda {titulo, cuerpo, vista, etiqueta} (ver Aviso.java). Hay que
// mostrar siempre una notificación: el navegador le quita el permiso a quien
// recibe avisos y no enseña nada.
self.addEventListener('push', (e) => {
  let aviso = {};
  try { aviso = e.data ? e.data.json() : {}; } catch (err) { aviso = { cuerpo: e.data ? e.data.text() : '' }; }

  const titulo = (DE_PRUEBAS ? '[Pruebas] ' : LOCAL ? '[Local] ' : '') + (aviso.titulo || 'Voces Admin');
  const sufijo = DE_PRUEBAS || LOCAL ? '-pruebas' : '';

  e.waitUntil((async () => {
    await self.registration.showNotification(titulo, {
      body: aviso.cuerpo || '',
      // La misma etiqueta sustituye al aviso anterior en vez de apilarse.
      tag: aviso.etiqueta || 'aviso',
      icon: 'iconos/icono' + sufijo + '-192.png',
      badge: 'iconos/insignia-96.png',
      lang: 'es',
      data: { vista: aviso.vista || 'resumen' }
    });
    // Si el panel está abierto, que actualice sus contadores sin esperar.
    const abiertos = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    abiertos.forEach((c) => c.postMessage({ tipo: 'aviso', vista: aviso.vista || null }));
  })());
});

// Tocar el aviso abre el panel en la sección que toca. Si ya hay una ventana
// abierta se usa esa, en vez de abrir otra.
self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  const vista = String((e.notification.data && e.notification.data.vista) || 'resumen').replace(/[^a-z]/g, '');

  e.waitUntil((async () => {
    const abiertos = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const mia = abiertos.find((c) => c.url.startsWith(ALCANCE));
    if (mia) {
      mia.postMessage({ tipo: 'ir', vista });
      return mia.focus().catch(() => null);
    }
    return self.clients.openWindow(ALCANCE + '#' + vista);
  })());
});
