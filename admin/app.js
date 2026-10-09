// Panel de administración de VocesLeft.
//
// Solo usa rutas que ya existen en el relay-server: /api/auth/google para
// entrar y /api/admin/* para todo lo demás. El acceso lo decide el servidor,
// que exige el rol de administrador en cada llamada a /api/admin.
//
// Apache lo sirve como archivos estáticos en /admin del mismo dominio que la
// API, así que las llamadas son relativas y no hace falta CORS.

(function () {
  'use strict';

  // ---------------------------------------------------------------------------
  // Utilidades
  // ---------------------------------------------------------------------------
  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => Array.from(r.querySelectorAll(s));
  const esc = (v) => String(v ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const main = $('#main');
  const CONFIG = window.VOCESLEFT_CONFIG || {};
  const BASE = (CONFIG.apiBase || '').replace(/\/$/, '');

  const CATEGORIAS = { cine: 'Cine', comida: 'Comida', politica: 'Política', musica: 'Música', salud: 'Salud', noticias: 'Noticias', tecnologia: 'Tecnología', otros: 'Otros' };
  // En el orden en que se ofrecen. Solo YouTube genera avisos de videos; las
  // demás son enlaces del perfil.
  const PLATAFORMAS = { youtube: 'YouTube', tiktok: 'TikTok', twitch: 'Twitch', instagram: 'Instagram', x: 'X', facebook: 'Facebook', threads: 'Threads', telegram: 'Telegram', spotify: 'Spotify', patreon: 'Patreon', web: 'Web' };

  // Para no obligar a pegar el enlace entero: con el usuario basta.
  const PERFILES = {
    tiktok: (u) => 'https://www.tiktok.com/@' + u,
    twitch: (u) => 'https://www.twitch.tv/' + u,
    instagram: (u) => 'https://www.instagram.com/' + u,
    x: (u) => 'https://x.com/' + u,
    facebook: (u) => 'https://www.facebook.com/' + u,
    threads: (u) => 'https://www.threads.net/@' + u,
    telegram: (u) => 'https://t.me/' + u,
    patreon: (u) => 'https://www.patreon.com/' + u
  };

  /**
   * El enlace de una red a partir de lo que se escribió: un enlace completo,
   * un dominio sin el https, o solo el usuario ("@claudia"). Devuelve null si
   * no se puede armar un enlace con eso.
   */
  function enlaceDeRed(plataforma, texto) {
    const t = (texto || '').trim();
    if (!t) return null;
    if (/^https?:\/\//i.test(t)) return t;
    if (/^[\w-]+(\.[\w-]+)+(\/\S*)?$/.test(t) && !t.startsWith('@')) {
      // "instagram.com/claudia", "claudia.mx". Un usuario con punto y sin
      // barra ("ana.lopez") es un usuario, no un dominio, si la red los admite.
      if (t.includes('/') || !PERFILES[plataforma]) return 'https://' + t;
    }
    const usuario = t.replace(/^@/, '');
    if (PERFILES[plataforma] && /^[\w.-]+$/.test(usuario)) return PERFILES[plataforma](usuario);
    return null;
  }
  const ESTADOS_SUSC = {
    ACTIVA: ['Activa', 'b-ok'],
    PENDIENTE_VERIFICACION: ['Pendiente', 'b-warn'],
    CANCELADA: ['Cancelada', 'b-mute'],
    ERROR: ['Error', 'b-bad']
  };

  const fmtFecha = (iso) => {
    if (!iso) return '—';
    const d = new Date(iso);
    return isNaN(d) ? '—' : d.toLocaleString('es-MX', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  };
  const relativo = (iso) => {
    if (!iso) return '—';
    const ms = new Date(iso) - new Date();
    const abs = Math.abs(ms), futuro = ms > 0;
    const min = 60e3, h = 60 * min, d = 24 * h;
    let t;
    if (abs < h) t = Math.max(1, Math.round(abs / min)) + ' min';
    else if (abs < d) t = Math.round(abs / h) + ' h';
    else t = Math.round(abs / d) + ' d';
    return futuro ? 'en ' + t : 'hace ' + t;
  };
  const num = (n) => new Intl.NumberFormat('es-MX').format(n || 0);
  const plural = (n, uno, varios) => num(n) + ' ' + (n === 1 ? uno : varios);

  let toastTimer;
  function toast(msg, malo) {
    const t = $('#toast');
    t.textContent = msg;
    t.className = 'toast show' + (malo ? ' danger' : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.className = 'toast' + (malo ? ' danger' : ''); }, malo ? 5000 : 2800);
  }

  // ---------------------------------------------------------------------------
  // Sesión
  // ---------------------------------------------------------------------------
  // sessionStorage y no localStorage: el token de administrador se olvida al
  // cerrar el navegador. Volver a entrar es un clic.
  const CLAVE = 'vocesleft_admin';
  let sesion = null;
  try { sesion = JSON.parse(sessionStorage.getItem(CLAVE) || 'null'); } catch (e) { sesion = null; }

  function guardarSesion(s) {
    sesion = s;
    try {
      if (s) sessionStorage.setItem(CLAVE, JSON.stringify(s));
      else sessionStorage.removeItem(CLAVE);
    } catch (e) { /* modo privado: la sesión vive solo en memoria */ }
  }

  function caducado(token) {
    try {
      const datos = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
      return !datos.exp || datos.exp * 1000 < Date.now() + 60e3;
    } catch (e) { return true; }
  }

  function salir(motivo) {
    guardarSesion(null);
    $('#app').hidden = true;
    $('#puerta').hidden = false;
    mostrarErrorPuerta(motivo || '');
    try { window.google && google.accounts.id.disableAutoSelect(); } catch (e) { /* nada */ }
    prepararGoogle();
  }

  function mostrarErrorPuerta(msg) {
    const el = $('#puertaError');
    el.textContent = msg;
    el.hidden = !msg;
  }

  // ---------------------------------------------------------------------------
  // API
  // ---------------------------------------------------------------------------
  class ErrorApi extends Error {
    constructor(msg, estado) { super(msg); this.estado = estado; }
  }

  async function api(ruta, { metodo = 'GET', cuerpo } = {}) {
    const cabeceras = {};
    if (sesion && sesion.token) cabeceras.Authorization = 'Bearer ' + sesion.token;
    if (cuerpo !== undefined) cabeceras['Content-Type'] = 'application/json';

    let r;
    try {
      r = await fetch(BASE + ruta, { method: metodo, headers: cabeceras, body: cuerpo !== undefined ? JSON.stringify(cuerpo) : undefined });
    } catch (e) {
      throw new ErrorApi('No hay conexión con el servidor.', 0);
    }

    const texto = await r.text();
    let datos = null;
    try { datos = texto ? JSON.parse(texto) : null; } catch (e) { datos = null; }

    if (r.status === 401 || r.status === 403) {
      if (ruta.startsWith('/api/admin')) {
        salir(r.status === 401 ? 'Tu sesión caducó. Vuelve a entrar.' : 'Tu cuenta ya no tiene rol de administrador.');
      }
      throw new ErrorApi((datos && datos.message) || 'Sin permiso.', r.status);
    }
    if (!r.ok) {
      // El servidor escribe el motivo en `message`, pensado para mostrarse tal cual.
      throw new ErrorApi((datos && (datos.message || datos.error)) || ('Error ' + r.status), r.status);
    }
    return datos;
  }

  // ---------------------------------------------------------------------------
  // Entrada con Google
  // ---------------------------------------------------------------------------
  const host = location.hostname;
  const config = {
    googleClientId: CONFIG.googleClientId || '',
    ambiente: host.startsWith('testapp.') || host.includes('test') ? 'pruebas'
      : (host === 'localhost' || host === '127.0.0.1' || location.protocol === 'file:') ? 'local'
      : 'produccion'
  };

  function arrancar() {
    pintarAmbiente();
    if (sesion && sesion.token && !caducado(sesion.token)) {
      entrarAlPanel();
      return;
    }
    guardarSesion(null);
    prepararGoogle();
  }

  function pintarAmbiente() {
    const el = $('#ambiente');
    const textos = {
      produccion: 'Producción: todo lo que hagas aquí lo ven los usuarios reales.',
      pruebas: 'Ambiente de pruebas',
      local: 'Servidor local'
    };
    el.className = 'ambiente ' + config.ambiente;
    el.textContent = textos[config.ambiente];
    el.hidden = false;
    document.title = (config.ambiente === 'produccion' ? '' : '[' + config.ambiente + '] ') + 'Panel de VocesLeft';
  }

  let googleListo = false;
  function prepararGoogle(intentos = 0) {
    if (googleListo) return;
    const caja = $('#botonGoogle');
    if (!config.googleClientId) {
      caja.innerHTML = '';
      mostrarErrorPuerta('Falta googleClientId en admin/config.js.');
      return;
    }
    if (!(window.google && google.accounts && google.accounts.id)) {
      if (intentos > 50) {
        caja.innerHTML = '';
        mostrarErrorPuerta('No cargó el inicio de sesión de Google. Revisa la conexión o algún bloqueador.');
        return;
      }
      setTimeout(() => prepararGoogle(intentos + 1), 200);
      return;
    }
    google.accounts.id.initialize({
      client_id: config.googleClientId,
      callback: alRecibirCredencial,
      ux_mode: 'popup',
      auto_select: false
    });
    caja.innerHTML = '';
    google.accounts.id.renderButton(caja, { theme: 'outline', size: 'large', text: 'signin_with', shape: 'rectangular', locale: 'es' });
    googleListo = true;
  }

  async function alRecibirCredencial(respuesta) {
    mostrarErrorPuerta('');
    try {
      const s = await api('/api/auth/google', { metodo: 'POST', cuerpo: { token: respuesta.credential } });
      if (!s.esAdmin) {
        mostrarErrorPuerta('La cuenta ' + (s.email || '') + ' no es administradora. Pide a otro administrador que te nombre desde la sección Administradores.');
        return;
      }
      guardarSesion({ token: s.token, email: s.email, id: s.usuarioId });
      entrarAlPanel();
    } catch (e) {
      mostrarErrorPuerta(e.message);
    }
  }

  function entrarAlPanel() {
    $('#puerta').hidden = true;
    $('#app').hidden = false;
    $('#quien').textContent = sesion.email || 'Administrador';
    ir(estado.vista);
    refrescarContadores();
  }

  $('#salir').addEventListener('click', () => salir(''));
  $('#salirMovil').addEventListener('click', () => salir(''));

  // ---------------------------------------------------------------------------
  // Estado y navegación
  // ---------------------------------------------------------------------------
  const estado = {
    vista: (location.hash || '#resumen').slice(1),
    creadores: null,        // caché compartida: formularios, nombres en reportes, avisos
    productoras: null,      // igual: las usan el formulario de creador y los canales
    etiquetas: null,        // las del directorio, con quién lleva cada una
    filtroCreadores: { q: '', categoria: '' },
    publicaciones: null,
    filtroPubs: 'todos',    // todos | videos | cortos
    ajustes: null,          // null: el servidor es anterior a los ajustes
    anuncios: null,         // lo último que dijo /api/admin/anuncios
    foliosNuevos: [],       // los folios recién creados, para copiarlos
    usuarios: null,         // la página que está en pantalla
    migracion: null,        // la revisión de una migración que está en pantalla
    filtroUsuarios: { q: '', filtro: '', pais: '', orden: 'vistos', pagina: 0 }
  };

  const VISTAS = {
    resumen: vistaResumen,
    creadores: vistaCreadores,
    productoras: vistaProductoras,
    canales: vistaCanales,
    etiquetas: vistaEtiquetas,
    versiones: vistaVersiones,
    publicaciones: vistaPublicaciones,
    reportes: vistaReportes,
    anuncios: vistaAnuncios,
    usuarios: vistaUsuarios,
    administradores: vistaAdministradores
  };

  // La sección se llamaba Suscripciones: los enlaces guardados siguen valiendo.
  const ALIAS = { suscripciones: 'canales' };

  function ir(vista) {
    vista = ALIAS[vista] || vista;
    if (!VISTAS[vista]) vista = 'resumen';
    estado.vista = vista;
    if (location.hash !== '#' + vista) history.replaceState(null, '', '#' + vista);
    $$('#nav button').forEach((b) => b.setAttribute('aria-current', b.dataset.vista === vista ? 'page' : 'false'));
    main.innerHTML = '<div class="cargando">Cargando…</div>';
    main.focus({ preventScroll: true });
    window.scrollTo(0, 0);
    VISTAS[vista]().catch((e) => {
      if (e.estado === 401 || e.estado === 403) return;
      main.innerHTML = '<div class="panel"><div class="empty">No se pudo cargar: ' + esc(e.message) + '<br><br><button class="btn" data-accion="recargar">Reintentar</button></div></div>';
    });
  }

  $('#nav').addEventListener('click', (e) => {
    const b = e.target.closest('button[data-vista]');
    if (b) ir(b.dataset.vista);
  });

  async function cargarCreadores(forzar) {
    if (!estado.creadores || forzar) estado.creadores = await api('/api/admin/creadores');
    return estado.creadores;
  }

  // Un servidor anterior a las productoras no tiene la ruta: el panel sigue
  // funcionando sin ellas en vez de quedarse en blanco.
  async function cargarProductoras(forzar) {
    if (!estado.productoras || forzar) {
      try {
        estado.productoras = await api('/api/admin/productoras');
        estado.sinProductoras = false;
      } catch (e) {
        if (e.estado !== 404) throw e;
        estado.productoras = [];
        estado.sinProductoras = true;
      }
    }
    return estado.productoras;
  }

  // Un servidor anterior a las etiquetas no tiene la ruta: entonces el panel
  // no enseña nada de ellas.
  async function cargarEtiquetas(forzar) {
    if (!estado.etiquetas || forzar) {
      try {
        estado.etiquetas = await api('/api/admin/etiquetas');
        estado.sinEtiquetas = false;
      } catch (e) {
        if (e.estado !== 404) throw e;
        estado.etiquetas = [];
        estado.sinEtiquetas = true;
      }
    }
    return estado.etiquetas;
  }

  // Los contadores del menú salen de las mismas rutas que usan las vistas.
  async function refrescarContadores() {
    try {
      const [creadores, productoras, reportes] = await Promise.all([
        cargarCreadores(true), cargarProductoras(true), api('/api/admin/reportes?limite=200')]);
      const problemas = vigilados().filter(conProblema).length;
      const ns = $('#n-susc'), nr = $('#n-rep');
      ns.textContent = problemas; ns.hidden = !problemas;
      nr.textContent = reportes.length; nr.hidden = !reportes.length;
      return { creadores, productoras, reportes };
    } catch (e) { return null; }
  }

  // Los canales de un creador. Un servidor anterior solo manda `conexiones`
  // (una por plataforma) y el estado de la suscripción a nivel de creador.
  const canalesDe = (c) => c.canales
    || (c.conexiones || []).map((x) => Object.assign({}, x, { estadoSuscripcion: c.estadoSuscripcion, expiraEn: c.expiraEn }));
  const esDeYouTube = (k) => k.plataforma === 'youtube' && k.channelId;

  // Cada canal de YouTube que el servidor vigila, con su dueño: el creador o,
  // en los canales propios de una productora, la productora.
  function vigilados() {
    const lista = [];
    (estado.creadores || []).forEach((c) => canalesDe(c).filter(esDeYouTube)
      .forEach((k) => lista.push({ canal: k, tipo: 'creador', dueno: c })));
    (estado.productoras || []).forEach((p) => (p.canales || []).filter((k) => esDeYouTube(k) && !k.creadorId)
      .forEach((k) => lista.push({ canal: k, tipo: 'productora', dueno: p })));
    return lista;
  }
  // Un canal de un dueño visible debería tener la suscripción ACTIVA; si no, no llegan avisos.
  const conProblema = (v) => v.dueno.activo && v.canal.estadoSuscripcion !== 'ACTIVA';
  const vencePronto = (k) => k.estadoSuscripcion === 'ACTIVA' && k.expiraEn && (new Date(k.expiraEn) - Date.now()) < 2 * 864e5;
  const nombreProductora = (id) => { const p = (estado.productoras || []).find((x) => x.id === id); return p ? p.nombre : null; };
  const nombreCreador = (id) => { const c = (estado.creadores || []).find((x) => x.id === id); return c ? c.nombre : null; };
  const nombresDe = (ids) => (ids || []).map(nombreCreador).filter(Boolean);

  // ¿El servidor ya tiene fichas de canal y productoras en el directorio? Un
  // servidor anterior no manda esos campos, y entonces el panel no los ofrece.
  function servidorConFichas() {
    const c = (estado.creadores || [])[0], p = (estado.productoras || [])[0];
    if (c) return c.canalesCompartidos !== undefined;
    if (p) return p.enDirectorio !== undefined;
    return !estado.sinProductoras;
  }

  // ---------------------------------------------------------------------------
  // Modal
  // ---------------------------------------------------------------------------
  const modal = $('#modal');
  function abrirModal(titulo, cuerpoHtml, acciones, alAbrir) {
    $('#mTitulo').textContent = titulo;
    $('#mCuerpo').innerHTML = cuerpoHtml;
    const pie = $('#mPie');
    pie.innerHTML = '';
    acciones.forEach((a) => {
      const b = document.createElement('button');
      b.className = 'btn ' + (a.tipo || '');
      b.textContent = a.texto;
      b.addEventListener('click', async () => {
        if (!a.alPulsar) { cerrarModal(); return; }
        b.disabled = true;
        let seguir;
        try { seguir = await a.alPulsar(); } catch (e) { toast(e.message, true); seguir = false; }
        b.disabled = false;
        if (seguir !== false) cerrarModal();
      });
      pie.appendChild(b);
    });
    if (typeof modal.showModal === 'function') { if (!modal.open) modal.showModal(); } else modal.setAttribute('open', '');
    if (alAbrir) alAbrir();
    const primero = $('#mCuerpo input:not([type=hidden]), #mCuerpo select, #mCuerpo textarea');
    if (primero) setTimeout(() => primero.focus(), 30);
  }
  function cerrarModal() {
    if (!modal.open) return;
    if (typeof modal.close === 'function') modal.close(); else modal.removeAttribute('open');
  }
  function confirmar(titulo, texto, textoOk, peligroso) {
    return new Promise((resolver) => {
      let decidido = false;
      abrirModal(titulo, '<p style="margin:0">' + esc(texto) + '</p>', [
        { texto: 'Cancelar', alPulsar: () => { decidido = true; resolver(false); } },
        { texto: textoOk, tipo: peligroso ? 'danger solid' : 'primary', alPulsar: () => { decidido = true; resolver(true); } }
      ]);
      modal.addEventListener('close', () => { if (!decidido) resolver(false); }, { once: true });
    });
  }

  // ---------------------------------------------------------------------------
  // Resumen
  // ---------------------------------------------------------------------------
  async function vistaResumen() {
    const [base, pubs] = await Promise.all([refrescarContadores(), api('/api/admin/publicaciones?limite=200')]);
    if (!base) throw new ErrorApi('No se pudo leer el directorio.', 0);
    const { creadores, productoras, reportes } = base;
    estado.publicaciones = pubs;

    const activos = creadores.filter((c) => c.activo);
    const canales = vigilados().filter((v) => v.dueno.activo);
    const activas = canales.filter((v) => v.canal.estadoSuscripcion === 'ACTIVA').length;
    const problemas = canales.filter(conProblema).length;
    const porVencer = canales.filter((v) => vencePronto(v.canal)).length;
    const seguidores = creadores.reduce((s, c) => s + (c.seguidores || 0), 0)
      + productoras.reduce((s, p) => s + (p.seguidores || 0), 0);
    const hace7d = Date.now() - 7 * 864e5;
    const pubs7d = pubs.filter((p) => p.publicadoEn && new Date(p.publicadoEn) > hace7d).length;
    const movidos = pubs.filter((p) => p.estado === 'moved').length;

    const alertas = [];
    if (problemas) alertas.push(['b-warn', (problemas === 1 ? '1 canal visible no tiene la suscripción de YouTube activa, así que no genera avisos.' : num(problemas) + ' canales visibles no tienen la suscripción de YouTube activa, así que no generan avisos.'), 'canales']);
    if (porVencer) alertas.push(['b-warn', (porVencer === 1 ? '1 suscripción vence' : num(porVencer) + ' suscripciones vencen') + ' en menos de 2 días. El servidor las renueva solo cada 4 días; si no se renuevan, revisa los registros.', 'canales']);
    if (reportes.length) alertas.push(['b-info', plural(reportes.length, 'reporte pendiente', 'reportes pendientes') + ' de enlaces rotos.', 'reportes']);

    main.innerHTML = `
      <div class="head"><div><h1>Resumen</h1><p class="sub">Estado del directorio, de las suscripciones a YouTube y de los reportes.</p></div>
        <div class="toolbar"><button class="btn primary" data-accion="nuevo-creador">Nuevo creador</button></div></div>
      <div class="stack">
        <div class="stats tres">
          <div class="stat"><div class="k">Creadores visibles</div><div class="v">${num(activos.length)}</div><div class="n">de ${num(creadores.length)} en el directorio${productoras.length ? ' · ' + plural(productoras.length, 'medio', 'medios') : ''}</div></div>
          <div class="stat"><div class="k">Suscripciones activas</div><div class="v">${num(activas)}</div><div class="n">${problemas ? num(problemas) + ' con problemas' : 'de ' + num(canales.length) + ' canales, todas bien'}</div></div>
          <div class="stat"><div class="k">Seguimientos</div><div class="v">${num(seguidores)}</div><div class="n">suma de seguidores de creadores y medios</div></div>
          <div class="stat"><div class="k">Reportes pendientes</div><div class="v">${num(reportes.length)}</div><div class="n">enlaces rotos y otras incidencias</div></div>
          <div class="stat"><div class="k">Videos en 7 días</div><div class="v">${num(pubs7d)}</div><div class="n">de los últimos ${num(pubs.length)} detectados</div></div>
          <div class="stat"><div class="k">Videos movidos</div><div class="v">${num(movidos)}</div><div class="n">redirigidos a otro enlace</div></div>
        </div>
        <section class="panel"><div class="panel-head"><h2>Qué revisar</h2></div>
          ${alertas.length ? '<ul class="alertas">' + alertas.map(([c, t, v]) => `<li><span><span class="badge ${c}">${c === 'b-warn' ? 'Atención' : 'Pendiente'}</span> ${esc(t)}</span><button class="link" data-ir="${v}">Revisar</button></li>`).join('') + '</ul>' : '<div class="empty">Todo en orden.</div>'}
        </section>
        ${tarjetaBorrar()}
      </div>`;
  }

  // ---------------------------------------------------------------------------
  // Borrar de golpe una parte del directorio
  // ---------------------------------------------------------------------------
  // Lo que se puede borrar así, con lo que se lleva por delante cada cosa.
  const PARTES_BORRABLES = [
    ['creadores', 'Todos los creadores', 'con sus canales, sus redes, sus videos y quién los sigue'],
    ['productoras', 'Todos los medios', 'con sus canales propios, los videos de esos canales y quién los sigue'],
    ['publicaciones', 'Todas las publicaciones', 'los videos que detectó el servidor'],
    ['reportes', 'Todos los reportes', 'los avisos de enlaces rotos']
  ];

  function tarjetaBorrar() {
    return `<section class="panel"><div class="panel-head"><h2>Borrar datos</h2><span class="badge b-bad">No se puede deshacer</span></div>
      <div class="ajuste">
        <p class="hint">Para vaciar de golpe una parte del directorio, por ejemplo después de hacer pruebas. Marca qué quieres borrar; antes de borrar nada verás cuánto se pierde y tendrás que escribir un número que te da el sistema. Los usuarios, los administradores y los ajustes no se tocan.</p>
        <div class="borrables">${PARTES_BORRABLES.map(([clave, titulo, detalle]) => `<label class="check"><input type="checkbox" data-borrar="${clave}"> <span><b>${titulo}</b> <span class="hint">· ${detalle}</span></span></label>`).join('')}</div>
        <div><button class="btn danger" data-accion="borrar-datos">Borrar lo marcado…</button></div>
      </div></section>`;
  }

  // "5 creadores", "1 productora"… de lo que diga la cuenta, sin los ceros.
  function cuentaEnPalabras(c) {
    return [
      [c.creadores, 'creador', 'creadores'], [c.productoras, 'medio', 'medios'],
      [c.canales, 'canal o red', 'canales y redes'], [c.publicaciones, 'video', 'videos'],
      [c.reportes, 'reporte', 'reportes']
    ].filter(([n]) => n > 0).map(([n, uno, varios]) => plural(n, uno, varios));
  }

  async function abrirBorrado(boton) {
    const partes = $$('#main [data-borrar]:checked').map((x) => x.dataset.borrar);
    if (!partes.length) { toast('Marca primero qué quieres borrar.', true); return; }

    // El servidor cuenta lo que se perdería y da el número que hay que
    // escribir. Sin ese número, la ruta de borrar no borra nada.
    let p;
    boton.disabled = true;
    try {
      p = await api('/api/admin/borrado/preparar', { metodo: 'POST', cuerpo: { partes } });
    } catch (e) {
      toast(e.message === 'Esa ruta no existe.' ? 'Este servidor todavía no sabe borrar de golpe. Actualízalo.' : e.message, true);
      return;
    } finally {
      boton.disabled = false;
    }

    const titulos = PARTES_BORRABLES.filter(([clave]) => p.partes.includes(clave));
    const cuenta = cuentaEnPalabras(p.cuenta);
    const conDirectorio = p.partes.includes('creadores') || p.partes.includes('productoras');
    const minutos = Math.max(1, Math.round(p.caducaEnSegundos / 60));

    abrirModal('¿SEGURO QUE QUIERES BORRAR TODO ESTO?', `
      <div class="stack" style="gap:14px">
        <ul class="borrado-lista">${titulos.map(([, titulo, detalle]) => `<li><b>${titulo}</b>, ${detalle}.</li>`).join('')}</ul>
        <p style="margin:0" id="bCuenta">${cuenta.length ? 'Ahora mismo son <b>' + esc(juntar(cuenta)) + '</b>.' : 'Ahora mismo no hay nada de eso: no se borraría nada.'}</p>
        <p style="margin:0"><b>No se puede deshacer.</b> ${conDirectorio ? 'Quien los seguía en la app deja de seguirlos y de recibir sus avisos, y para recuperarlos habría que darlos de alta otra vez. Justo antes de borrar se guarda una versión del directorio (sección Versiones) para poder consultar cómo estaba.' : 'Lo borrado no vuelve.'}</p>
        <div class="codigo-caja">
          <span class="hint">Para confirmar, escribe este número:</span>
          <div class="codigo" id="bCodigo" aria-label="Número de confirmación: ${esc(p.codigo.split('').join(' '))}">${esc(p.codigo.slice(0, 3))} ${esc(p.codigo.slice(3))}</div>
          <input class="input" id="bEscrito" inputmode="numeric" autocomplete="off" maxlength="9" placeholder="Escribe aquí el número" aria-label="Escribe aquí el número">
          <span class="hint">Vale ${minutos} minutos y una sola vez.</span>
        </div>
      </div>`, [
      { texto: 'Cancelar' },
      { texto: 'Borrar definitivamente', tipo: 'danger solid', alPulsar: async () => {
        const escrito = $('#bEscrito').value.replace(/\s/g, '');
        if (escrito !== p.codigo) {
          toast(escrito ? 'El número no coincide. Revísalo.' : 'Escribe el número que se muestra arriba.', true);
          $('#bEscrito').focus();
          return false;
        }
        let r;
        try {
          r = await api('/api/admin/borrado', { metodo: 'POST', cuerpo: { partes: p.partes, codigo: escrito } });
        } catch (e) {
          toast(e.message, true);
          // Un 409 es que el número ya no vale: no tiene sentido seguir aquí.
          return e.estado === 409;
        }
        const borrado = cuentaEnPalabras(r.borrado);
        toast((borrado.length ? 'Se borraron ' + juntar(borrado) + '.' : 'No había nada que borrar.')
          + (r.version ? ' Quedó guardada la versión ' + r.version + '.' : ''));
        // Lo que el panel tenía cargado ya no existe.
        estado.creadores = null; estado.productoras = null; estado.publicaciones = null;
        ir(estado.vista);
      } }
    ]);
  }

  // ---------------------------------------------------------------------------
  // Creadores
  // ---------------------------------------------------------------------------
  function badgeSusc(estadoSusc) {
    if (!estadoSusc) return '<span class="badge b-mute">Sin YouTube</span>';
    const [t, c] = ESTADOS_SUSC[estadoSusc] || [estadoSusc, 'b-mute'];
    return `<span class="badge ${c}">${esc(t)}</span>`;
  }
  function avatar(url) {
    return url ? `<img src="${esc(url)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : '<span class="ph"></span>';
  }

  // Un canal en la lista: la plataforma, su etiqueta si tiene y, en otro
  // color, si es de una productora.
  function chipCanal(k, conDueno) {
    const productora = k.productoraId ? nombreProductora(k.productoraId) : null;
    const texto = (conDueno && k.creadorNombre ? k.creadorNombre + ' · ' : '')
      + (PLATAFORMAS[k.plataforma] || k.plataforma) + (k.nombre ? ' · ' + k.nombre : '');
    return `<a class="badge ${productora && !conDueno ? 'b-info' : 'b-mute'}" href="${esc(k.url)}" target="_blank" rel="noopener"${productora && !conDueno ? ` title="De ${esc(productora)}"` : ''}>${esc(texto)}</a>`;
  }

  // De quién es un canal: su creador o, si no tiene, su productora.
  const duenoDe = (k) => k.creadorNombre || nombreCreador(k.creadorId) || nombreProductora(k.productoraId) || 'otro';

  // Un canal de otro en el que también aparece este creador.
  function chipCompartido(k) {
    const texto = duenoDe(k) + ' · ' + (PLATAFORMAS[k.plataforma] || k.plataforma) + (k.nombre ? ' · ' + k.nombre : '');
    return `<a class="badge b-comp" href="${esc(k.url)}" target="_blank" rel="noopener" title="Canal de ${esc(duenoDe(k))} en el que también aparece">${esc(texto)}</a>`;
  }

  function filasCreadores() {
    const { q, categoria } = estado.filtroCreadores;
    const t = q.trim().toLowerCase();
    const lista = (estado.creadores || []).filter((c) =>
      (!t || c.nombre.toLowerCase().includes(t)) && (!categoria || c.categoria === categoria));
    if (!lista.length) {
      return `<tr><td colspan="6"><div class="empty">${estado.creadores && estado.creadores.length ? 'Ningún creador coincide.' : 'Todavía no hay creadores. Agrega el primero con su canal de YouTube o sus redes.'}</div></td></tr>`;
    }
    return lista.map((c) => {
      const casas = (c.productoras || []).map(nombreProductora).filter(Boolean);
      const chips = canalesDe(c).map((k) => chipCanal(k)).concat((c.canalesCompartidos || []).map(chipCompartido));
      return `<tr>
      <td><div class="who">${avatar(c.fotoUrl)}<div><b>${esc(c.nombre)}</b><span>${esc(CATEGORIAS[c.categoria] || c.categoria)}${casas.length ? ' · ' + esc(casas.join(', ')) : ''}</span></div></div></td>
      <td><div class="chips">${chips.join('') || '<span class="muted">—</span>'}</div></td>
      <td>${badgeSusc(c.estadoSuscripcion)}${c.expiraEn ? `<div class="muted" style="font-size:12.5px">vence ${esc(relativo(c.expiraEn))}</div>` : ''}</td>
      <td class="num">${num(c.seguidores)}</td>
      <td>${c.activo ? '<span class="badge b-ok">Visible</span>' : '<span class="badge b-mute">Oculto</span>'}</td>
      <td class="acciones">
        <button class="btn sm" data-accion="editar-creador" data-id="${esc(c.id)}">Editar</button>
        <button class="btn sm" data-accion="probar-aviso" data-id="${esc(c.id)}">Probar aviso</button>
        <button class="btn sm danger" data-accion="borrar-creador" data-id="${esc(c.id)}">Eliminar</button>
      </td></tr>`;
    }).join('');
  }

  async function vistaCreadores() {
    await Promise.all([cargarCreadores(true), cargarProductoras(false)]);
    const f = estado.filtroCreadores;
    main.innerHTML = `
      <div class="head"><div><h1>Creadores</h1><p class="sub">El directorio que ven las apps. Un creador puede figurar en varios medios y tener varios canales de YouTube, varias redes sociales, o solo redes. Al guardarlo, el servidor suscribe al hub cada canal de YouTube para recibir sus videos nuevos.</p></div>
        <button class="btn primary" data-accion="nuevo-creador">Nuevo creador</button></div>
      <section class="panel">
        <div class="panel-head"><div class="toolbar">
          <input class="input buscar" id="qCreadores" type="search" placeholder="Buscar por nombre" value="${esc(f.q)}" aria-label="Buscar creadores">
          <select class="input" id="catCreadores" aria-label="Categoría"><option value="">Todas las categorías</option>${Object.entries(CATEGORIAS).map(([k, v]) => `<option value="${k}" ${f.categoria === k ? 'selected' : ''}>${v}</option>`).join('')}</select>
        </div><span class="muted" style="font-size:13px">${estado.creadores.length} en total</span></div>
        <div class="tablewrap"><table><thead><tr><th>Creador</th><th>Canales y redes</th><th>Suscripción</th><th>Seguidores</th><th>En la app</th><th></th></tr></thead>
        <tbody id="filasCreadores">${filasCreadores()}</tbody></table></div>
      </section>`;
    $('#qCreadores').addEventListener('input', (e) => { f.q = e.target.value; $('#filasCreadores').innerHTML = filasCreadores(); });
    $('#catCreadores').addEventListener('change', (e) => { f.categoria = e.target.value; $('#filasCreadores').innerHTML = filasCreadores(); });
  }

  // ---------------------------------------------------------------------------
  // Editor de canales y redes (lo comparten el creador y la productora)
  // ---------------------------------------------------------------------------
  // Dos listas en pantalla, canales de YouTube y redes sociales, sobre una sola
  // lista en memoria. Dentro de cada una, el orden en pantalla es el orden en
  // que se guarda, y el primero de cada plataforma es el principal: el que ven
  // las versiones de la app anteriores a los canales múltiples.
  // Las dos listas van por separado para que cada formulario las ponga en su
  // orden: el de un creador empieza por sus redes, que es lo que siempre
  // tiene; sus canales de YouTube son opcionales y se pueden agregar después.
  function seccionesDeCanales(conProductora) {
    return seccionYouTube(conProductora) + seccionRedes(conProductora);
  }

  function seccionYouTube(conProductora) {
    return `
      <fieldset><legend>${conProductora ? 'Canales de YouTube <span class="opcional">opcional</span>' : 'Canales propios de YouTube'}</legend>
        <div class="canales" id="fCanalesYT"></div>
        <div class="agregar" style="margin-top:10px">
          <input class="input" id="fBuscarCanal" placeholder="@handle, URL del canal o ID que empieza por UC" aria-label="Canal de YouTube">
          <button class="btn" type="button" id="btnBuscarCanal">Buscar y agregar</button>
        </div>
        <p class="hint" style="margin:10px 0 0">${conProductora
          ? 'No hace falta para dar de alta al creador: déjalo vacío y agrégalos cuando quieras, aquí o en la sección Canales. Puede tener varios, y de cada uno puedes decir si es de un medio. Guardar los demás datos del creador no cambia sus canales.'
          : 'Solo los canales que son del medio y de ningún creador. Con qué creadores aparece cada uno se elige en su ficha, en la sección Canales.'}</p>
      </fieldset>`;
  }

  function seccionRedes(conProductora) {
    const otras = Object.keys(PLATAFORMAS).filter((p) => p !== 'youtube');
    return `
      <fieldset><legend>Redes sociales</legend>
        <div class="canales" id="fRedes"></div>
        <div class="agregar" style="margin-top:10px">
          <select class="input" id="fOtraPlat" aria-label="Red social">${otras.map((p) => `<option value="${p}">${PLATAFORMAS[p]}</option>`).join('')}</select>
          <input class="input" id="fOtraUrl" placeholder="@usuario o enlace" aria-label="Usuario o enlace">
          <button class="btn" type="button" id="btnOtraPlat">Agregar</button>
        </div>
        <p class="hint" style="margin:10px 0 0">${conProductora
          ? 'Elige la red, escribe su usuario (o pega el enlace) y pulsa Agregar; repítelo con cada una. Al agregar la primera, el nombre, la descripción y la foto de abajo se llenan solos con lo que tenga en esa cuenta. Con una sola red ya se puede crear el creador. Los avisos de videos nuevos solo salen de sus canales de YouTube.'
          : 'X, Instagram, TikTok, Facebook, su página… Basta con su usuario.'}</p>
      </fieldset>`;
  }

  function editorDeCanales(inicial, conProductora) {
    const cajas = { yt: $('#fCanalesYT'), redes: $('#fRedes') };
    const grupo = (k) => (k.plataforma === 'youtube' ? 'yt' : 'redes');
    const lista = (inicial || []).map((k) => ({
      id: k.id || null, plataforma: k.plataforma, nombre: k.nombre || '', url: k.url || '',
      handle: k.handle || null, channelId: k.channelId || null, productoraId: k.productoraId || '',
      // Solo para enseñarlo: con quién aparece un canal se cambia en su ficha.
      creadores: k.creadores || []
    }));

    // El índice del anterior de su misma lista, o -1 si es el primero.
    const anterior = (i) => {
      for (let j = i - 1; j >= 0; j--) if (grupo(lista[j]) === grupo(lista[i])) return j;
      return -1;
    };

    function fila(k, i) {
      const yt = k.plataforma === 'youtube';
      const nombrePlat = PLATAFORMAS[k.plataforma] || k.plataforma;
      // "Principal" solo se dice cuando hay con qué confundirlo.
      const varios = lista.filter((x) => x.plataforma === k.plataforma).length > 1;
      const principal = varios && lista.findIndex((x) => x.plataforma === k.plataforma) === i;
      const con = nombresDe(k.creadores);
      const botones = `<button class="btn sm" type="button" data-fila="subir" ${anterior(i) < 0 ? 'disabled' : ''} title="Subir en la lista" aria-label="Subir">↑</button>
          <button class="btn sm danger" type="button" data-fila="quitar">Quitar</button>`;
      // Una red social es un enlace y nada más: cabe en un renglón.
      if (!yt) {
        return `<div class="canal-fila" data-i="${i}">
        <div class="canal-datos"><span class="badge b-mute">${esc(nombrePlat)}</span>${principal ? '<span class="badge b-ok">Principal</span>' : ''}
          <input class="input" data-campo="url" value="${esc(k.url)}" placeholder="https://" aria-label="Enlace de ${esc(nombrePlat)}">
          ${botones}
        </div></div>`;
      }
      return `<div class="canal-fila" data-i="${i}">
        <div class="canal-quien"><span class="badge b-mute">${esc(nombrePlat)}</span>${principal ? '<span class="badge b-ok">Principal</span>' : ''}<span class="mono">${esc(k.handle ? '@' + k.handle.replace(/^@/, '') : (k.channelId || k.url))}</span></div>
        ${con.length ? `<div class="hint">También aparece con: ${esc(con.join(', '))}</div>` : ''}
        <div class="canal-datos">
          <input class="input" data-campo="nombre" maxlength="60" value="${esc(k.nombre)}" placeholder="Etiqueta: Clips, Directos… (opcional)" aria-label="Etiqueta">
          ${conProductora ? `<select class="input" data-campo="productoraId" aria-label="Medio del canal"><option value="">Solo del creador</option>${(estado.productoras || []).map((p) => `<option value="${esc(p.id)}" ${k.productoraId === p.id ? 'selected' : ''}>De ${esc(p.nombre)}</option>`).join('')}</select>` : ''}
          ${botones}
        </div></div>`;
    }

    // Quien quiera enterarse de que la lista cambió (la sección de la foto,
    // que ofrece una fuente por cada cuenta).
    const oyentes = [];
    // Y quien quiera saber que se acaba de agregar una red a mano (para
    // rellenar la ficha con sus datos).
    let alAgregarRed = null;

    function pintar() {
      const html = { yt: [], redes: [] };
      lista.forEach((k, i) => html[grupo(k)].push(fila(k, i)));
      cajas.yt.innerHTML = html.yt.join('') || '<div class="hint">Sin canales de YouTube.</div>';
      cajas.redes.innerHTML = html.redes.join('') || '<div class="hint">Sin redes sociales.</div>';
      oyentes.forEach((fn) => fn());
    }

    const anotar = (e) => {
      const fila = e.target.closest('[data-i]');
      const campo = e.target.dataset.campo;
      if (fila && campo) lista[Number(fila.dataset.i)][campo] = e.target.value;
    };
    const alPulsar = (e) => {
      const b = e.target.closest('[data-fila]');
      if (!b) return;
      const i = Number(b.closest('[data-i]').dataset.i);
      if (b.dataset.fila === 'quitar') lista.splice(i, 1);
      else if (anterior(i) >= 0) lista.splice(anterior(i), 0, lista.splice(i, 1)[0]);
      pintar();
    };
    Object.values(cajas).forEach((caja) => {
      caja.addEventListener('input', anotar);
      caja.addEventListener('change', anotar);
      caja.addEventListener('click', alPulsar);
    });

    $('#btnOtraPlat').addEventListener('click', () => {
      const plataforma = $('#fOtraPlat').value;
      const url = enlaceDeRed(plataforma, $('#fOtraUrl').value);
      if (!url) {
        toast(PERFILES[plataforma] ? 'Escribe su usuario de ' + PLATAFORMAS[plataforma] + ' o pega el enlace.' : 'Pega el enlace completo, empezando por https://', true);
        $('#fOtraUrl').focus();
        return;
      }
      const nueva = { id: null, plataforma, nombre: '', url, handle: null, channelId: null, productoraId: '', creadores: [] };
      lista.push(nueva);
      $('#fOtraUrl').value = '';
      pintar();
      if (alAgregarRed) alAgregarRed(nueva);
    });

    pintar();
    return {
      // Las cuentas que hay ahora en el formulario, guardadas o no.
      cuentas: () => lista.slice(),
      // Llama a `fn` ahora y cada vez que la lista cambie.
      escuchar(fn) { oyentes.push(fn); fn(); },
      // Llama a `fn` con la red que se acaba de agregar con el botón.
      alAgregarRed(fn) { alAgregarRed = fn; },
      // Devuelve false si el canal ya estaba en la lista.
      agregarYouTube(d) {
        if (lista.some((k) => k.plataforma === 'youtube' && k.channelId === d.channelId)) return false;
        const handle = d.handle ? d.handle.replace(/^@/, '') : null;
        lista.push({ id: null, plataforma: 'youtube', nombre: '', productoraId: '', handle, channelId: d.channelId, creadores: [],
          url: handle ? 'https://www.youtube.com/@' + handle : 'https://www.youtube.com/channel/' + d.channelId });
        pintar();
        return true;
      },
      // La lista lista para mandar, o null si algún enlace no vale (ya avisó).
      // Sin `creadores`: así el servidor deja a cada canal apareciendo con
      // quien ya aparecía.
      valores() {
        // Lo que quedó escrito sin pulsar "Agregar" también cuenta: es fácil
        // escribir el usuario y darle directamente a guardar.
        const red = $('#fOtraUrl');
        if (red && red.value.trim()) {
          $('#btnOtraPlat').click();
          if (red.value.trim()) return null;      // no valía; ya se avisó
        }
        // Un canal de YouTube no se puede agregar solo: hay que buscarlo.
        const yt = $('#fBuscarCanal');
        if (yt && yt.value.trim()) {
          toast('Escribiste un canal de YouTube pero falta agregarlo: pulsa «Buscar y agregar», o borra ese texto.', true);
          yt.focus();
          return null;
        }
        for (const k of lista) {
          if (!/^https?:\/\//i.test((k.url || '').trim())) {
            toast('El enlace de ' + (PLATAFORMAS[k.plataforma] || k.plataforma) + ' debe empezar por https://', true);
            return null;
          }
        }
        // Como en pantalla: primero los canales de YouTube y después las redes.
        return lista.filter((k) => grupo(k) === 'yt').concat(lista.filter((k) => grupo(k) === 'redes')).map((k) => ({
          id: k.id, plataforma: k.plataforma, nombre: k.nombre.trim() || null, url: k.url.trim(),
          handle: k.handle, channelId: k.channelId,
          productoraId: conProductora && k.plataforma === 'youtube' ? (k.productoraId || null) : null
        }));
      }
    };
  }

  // Busca el canal en YouTube y se lo pasa a quien abrió el formulario.
  async function buscarCanal(alEncontrar) {
    const q = $('#fBuscarCanal').value.trim();
    if (!q) { toast('Escribe un @handle, una URL o un ID de canal.', true); return; }
    const btn = $('#btnBuscarCanal');
    const texto = btn.textContent;
    btn.disabled = true; btn.textContent = 'Buscando…';
    try {
      const d = await api('/api/admin/canal?query=' + encodeURIComponent(q));
      alEncontrar(d);
      $('#fBuscarCanal').value = '';
    } catch (e) {
      toast(e.message, true);
    } finally {
      // Quien recibe el canal puede haberle cambiado el texto al botón.
      btn.disabled = false; if (btn.textContent === 'Buscando…') btn.textContent = texto;
    }
  }

  function rellenarDesdeCanal(d) {
    if (!$('#fNombre').value.trim()) $('#fNombre').value = (d.titulo || '').slice(0, 60);
    if (!$('#fBio').value.trim() && d.descripcion) $('#fBio').value = d.descripcion.slice(0, 600);
    if (!$('#fFoto').value.trim() && d.fotoUrl) ponerFoto(d.fotoUrl);
  }

  // ---------------------------------------------------------------------------
  // La foto de perfil: de una de sus cuentas, o a mano
  // ---------------------------------------------------------------------------
  // De qué redes sabe el servidor traer la foto. De una página web, no.
  const REDES_CON_FOTO = ['youtube', 'x', 'instagram', 'tiktok', 'facebook', 'threads', 'telegram', 'twitch', 'spotify', 'patreon'];

  function seccionFoto(titulo, url) {
    return `
      <fieldset><legend>${esc(titulo)}</legend>
        <div class="foto">
          <span class="foto-previa" id="fFotoPrevia"></span>
          <div class="foto-datos">
            <input class="input" id="fFoto" value="${esc(url)}" placeholder="https://… dirección de la imagen" aria-label="Dirección de la imagen">
            <div class="foto-fuentes" id="fFotoFuentes"></div>
          </div>
        </div>
        <p class="hint" style="margin:10px 0 0">Elige de cuál de sus cuentas tomarla, o «Manual» para pegar la dirección de una imagen. No cambia sola: si la persona cambia su foto en esa red, vuelve a pulsar el botón.</p>
      </fieldset>`;
  }

  /** Pone la dirección en el campo y la enseña. */
  function ponerFoto(url) {
    $('#fFoto').value = url || '';
    pintarFoto();
  }

  function pintarFoto() {
    const caja = $('#fFotoPrevia');
    if (!caja) return;
    const url = $('#fFoto').value.trim();
    caja.classList.remove('rota');
    caja.innerHTML = /^https?:\/\//i.test(url) ? `<img src="${esc(url)}" alt="Foto de perfil" referrerpolicy="no-referrer">` : '';
    const img = caja.querySelector('img');
    // Una dirección que no carga se nota aquí, antes de guardar.
    if (img) img.addEventListener('error', () => { caja.innerHTML = ''; caja.classList.add('rota'); });
  }

  // Cómo se nombra una cuenta en su botón: "@usuario" o lo último del enlace.
  function nombreDeCuenta(k) {
    if (k.handle) return '@' + k.handle.replace(/^@/, '');
    const fin = ((k.url || '').split(/[?#]/)[0].replace(/\/+$/, '').split('/').pop() || '').replace(/^@/, '');
    return fin.length > 26 ? fin.slice(0, 25) + '…' : fin;
  }

  /**
   * Una fila de botones, uno por cada cuenta del formulario, y «Manual».
   * Se repinta sola cuando se agregan o se quitan cuentas.
   *
   * @param caja      dónde van los botones
   * @param opciones  etiqueta: el texto de delante; redes: de cuáles se ofrece;
   *                  ocupado: el texto del botón mientras trabaja;
   *                  alElegir(cuenta): async, lo que hace el botón;
   *                  alManual(): lo que hace «Manual»
   */
  function botonesDeCuentas(caja, editor, opciones) {
    let cuentas = [];

    editor.escuchar(() => {
      const todas = editor.cuentas().filter((k) => opciones.redes.includes(k.plataforma) && (k.plataforma !== 'youtube' || k.channelId));
      // YouTube primero: es la fuente más segura y no gasta del cupo diario.
      cuentas = todas.filter((k) => k.plataforma === 'youtube').concat(todas.filter((k) => k.plataforma !== 'youtube'));
      caja.innerHTML = `<span class="hint">${esc(opciones.etiqueta)}</span>`
        + cuentas.map((k, i) => `<button class="btn sm" type="button" data-fuente="${i}">${esc(PLATAFORMAS[k.plataforma] || k.plataforma)} · ${esc(nombreDeCuenta(k))}</button>`).join('')
        + '<button class="btn sm" type="button" data-fuente="manual">Manual</button>'
        + (cuentas.length ? '' : '<span class="hint">Agrega una red o un canal y aparecerá aquí.</span>');
    });

    caja.addEventListener('click', async (e) => {
      const b = e.target.closest('[data-fuente]');
      if (!b) return;
      if (b.dataset.fuente === 'manual') { opciones.alManual(); return; }

      const texto = b.textContent;
      b.disabled = true; b.textContent = opciones.ocupado;
      try {
        await opciones.alElegir(cuentas[Number(b.dataset.fuente)]);
      } finally {
        // La lista pudo repintarse mientras tanto; entonces el botón ya es otro.
        if (b.isConnected) { b.disabled = false; b.textContent = texto; }
      }
    });
  }

  const paraCuenta = (k) => {
    const p = new URLSearchParams({ plataforma: k.plataforma, url: k.url || '' });
    if (k.channelId) p.set('channelId', k.channelId);
    return p.toString();
  };
  // Un servidor anterior a esto no tiene estas rutas.
  const avisoDeRuta = (err, queNoSabe) => (err.message === 'Esa ruta no existe.'
    ? 'Este servidor todavía no sabe ' + queNoSabe + '. Actualízalo, o escríbelo a mano.'
    : err.message);

  /** Los botones de la sección de la foto: de qué cuenta tomarla. */
  function prepararFoto(editor) {
    botonesDeCuentas($('#fFotoFuentes'), editor, {
      etiqueta: 'Tomarla de:', redes: REDES_CON_FOTO, ocupado: 'Trayendo…',
      alManual: () => { $('#fFoto').focus(); $('#fFoto').select(); },
      alElegir: async (k) => {
        try {
          const r = await api('/api/admin/foto?' + paraCuenta(k));
          ponerFoto(r.url);
          toast('Foto tomada de ' + (PLATAFORMAS[k.plataforma] || k.plataforma) + '. Se guarda al guardar el formulario.');
        } catch (err) {
          toast(avisoDeRuta(err, 'tomar fotos de las redes'), true);
        }
      }
    });

    $('#fFoto').addEventListener('input', pintarFoto);
    pintarFoto();
  }

  // ---------------------------------------------------------------------------
  // Llenar la ficha con los datos de una cuenta
  // ---------------------------------------------------------------------------
  // De una página web también se puede leer el nombre y la descripción.
  const REDES_CON_DATOS = REDES_CON_FOTO.concat('web');

  function filaDeLlenado() {
    return `<div class="llenar"><div class="foto-fuentes" id="fLlenarFuentes"></div>
      <p class="hint" style="margin:6px 0 0">Pone el nombre, la descripción y la foto que tenga en esa cuenta. Revísalos antes de guardar: se pueden corregir a mano.</p></div>`;
  }

  const juntar = (partes) => (partes.length > 1 ? partes.slice(0, -1).join(', ') + ' y ' + partes[partes.length - 1] : partes[0] || '');

  /**
   * Trae los datos de la cuenta y los pone en el formulario.
   *
   * @param soloVacios true: solo rellena lo que esté vacío (al agregar una
   *                   cuenta). false: pone todo lo que venga, que para eso se
   *                   eligió esa cuenta.
   */
  async function llenarDesde(k, soloVacios) {
    const red = PLATAFORMAS[k.plataforma] || k.plataforma;
    let r;
    try {
      r = await api('/api/admin/cuenta?' + paraCuenta(k));
    } catch (err) {
      // Al agregar una cuenta nadie pidió nada: si el servidor no sabe, se calla.
      if (!(soloVacios && err.message === 'Esa ruta no existe.')) toast(avisoDeRuta(err, 'leer los datos de una cuenta'), true);
      return;
    }
    // El formulario pudo cerrarse mientras llegaba la respuesta.
    if (!$('#fNombre')) return;

    const puestos = [];
    const poner = (campo, valor, que, alPoner) => {
      if (!valor || (soloVacios && campo.value.trim())) return;
      if (alPoner) alPoner(valor); else campo.value = valor;
      puestos.push(que);
    };
    poner($('#fNombre'), r.nombre && r.nombre.slice(0, 60), 'el nombre');
    poner($('#fBio'), r.descripcion && r.descripcion.slice(0, 600), 'la descripción');
    poner($('#fFoto'), r.fotoUrl, 'la foto', ponerFoto);

    const avisos = (r.avisos || []).join(' ');
    // Con avisos se deja más tiempo en pantalla, para que dé tiempo a leerlos.
    if (puestos.length) toast('De ' + red + ' se puso ' + juntar(puestos) + '.' + (avisos ? ' ' + avisos : ''), !!avisos);
    else if (!soloVacios) toast(avisos || 'En ' + red + ' no había nada que poner.', true);
  }

  /** Los botones «Llenar con los datos de», y el llenado solo al agregar la primera cuenta. */
  function prepararLlenado(editor, esAlta) {
    const caja = $('#fLlenarFuentes');
    if (!caja) return;

    botonesDeCuentas(caja, editor, {
      etiqueta: 'Llenar con los datos de:', redes: REDES_CON_DATOS, ocupado: 'Leyendo…',
      alManual: () => { $('#fNombre').focus(); $('#fNombre').select(); },
      alElegir: (k) => llenarDesde(k, false)
    });

    // En un alta, la primera cuenta que se agrega rellena la ficha sola. Si
    // ya hay nombre, no: o lo escribió alguien o ya se llenó con otra cuenta.
    // Al editar nunca: ahí los datos ya están y cambiarlos es cosa del botón.
    if (esAlta) {
      editor.alAgregarRed((k) => {
        if (REDES_CON_DATOS.includes(k.plataforma) && !$('#fNombre').value.trim()) llenarDesde(k, true);
      });
    }
  }

  function prepararBusqueda(alEncontrar) {
    $('#btnBuscarCanal').addEventListener('click', () => buscarCanal(alEncontrar));
    $('#fBuscarCanal').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); buscarCanal(alEncontrar); } });
    const otra = $('#fOtraUrl');
    if (otra) otra.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); $('#btnOtraPlat').click(); } });
  }

  // Lo que entiende un servidor anterior a los canales múltiples: el canal
  // principal de cada plataforma. Se manda siempre junto con `canales`; el
  // servidor nuevo lee `canales` y no hace caso de esto.
  function conexionesDe(canales) {
    const conexiones = {};
    canales.forEach((k) => {
      if (!conexiones[k.plataforma]) conexiones[k.plataforma] = { plataforma: k.plataforma, url: k.url, handle: k.handle || null, channelId: k.channelId || null };
    });
    return conexiones;
  }

  function casillas(nombreGrupo, opciones, marcadas, vacio) {
    if (!opciones.length) return `<span class="hint">${esc(vacio)}</span>`;
    return `<div class="casillas">${opciones.map((o) => `<label class="check"><input type="checkbox" data-grupo="${nombreGrupo}" value="${esc(o.id)}" ${marcadas.includes(o.id) ? 'checked' : ''}> ${esc(o.nombre)}</label>`).join('')}</div>`;
  }
  const marcadas = (grupo) => $$('#mCuerpo [data-grupo="' + grupo + '"]:checked').map((x) => x.value);

  function formularioCreador(c) {
    c = c || { activo: true, categoria: 'otros' };
    const compartidos = c.canalesCompartidos || [];
    return `
      <div class="stack" style="gap:14px">
        ${seccionRedes(true)}
        ${seccionYouTube(true)}
        <fieldset><legend>Datos</legend>
        ${filaDeLlenado()}
        <div class="form" style="margin-top:12px">
          <label class="f">Nombre<input class="input" id="fNombre" maxlength="60" value="${esc(c.nombre)}"></label>
          <label class="f">Tema <span class="opcional">solo para apps anteriores</span><select class="input" id="fCategoria">${Object.entries(CATEGORIAS).map(([k, v]) => `<option value="${k}" ${c.categoria === k ? 'selected' : ''}>${v}</option>`).join('')}</select></label>
          <label class="f full">Descripción<textarea class="input" id="fBio" rows="3" maxlength="600">${esc(c.bio)}</textarea></label>
          <label class="check full"><input type="checkbox" id="fActivo" ${c.activo ? 'checked' : ''}> Visible en la app y suscrito a sus videos</label>
        </div>
        </fieldset>
        ${seccionFoto('Foto de perfil', c.fotoUrl)}
        ${compartidos.length ? `<fieldset><legend>Canales de otros en los que aparece</legend>
          <div class="chips">${compartidos.map(chipCompartido).join('')}</div>
          <p class="hint" style="margin:10px 0 0">Los videos de estos canales también les llegan a quienes lo siguen. Se cambia en la ficha de cada canal, en la sección Canales.</p>
        </fieldset>` : ''}
        ${seccionEtiquetas('creador', c.id)}
        ${estado.sinProductoras ? '' : `<fieldset><legend>Medios en los que figura</legend>
          ${casillas('productoras', estado.productoras || [], c.productoras || [], 'Todavía no hay medios. Se dan de alta en la sección Medios.')}
        </fieldset>`}
      </div>`;
  }

  async function abrirCreador(id) {
    await Promise.all([cargarProductoras(false), cargarEtiquetas(false)]);
    const c = id ? (estado.creadores || []).find((x) => x.id === id) : null;
    if (id && !c) return;
    let editor;
    abrirModal(c ? 'Editar creador' : 'Nuevo creador', formularioCreador(c), [
      { texto: 'Cancelar' },
      { texto: c ? 'Guardar cambios' : 'Crear creador', tipo: 'primary', alPulsar: async () => {
        const nombre = $('#fNombre').value.trim();
        if (nombre.length < 2) { toast('El nombre necesita al menos 2 letras.', true); return false; }
        const canales = editor.valores();
        if (!canales) return false;
        if (!canales.length) { toast('Agrega al menos una red social (o un canal de YouTube).', true); return false; }
        const cuerpo = {
          id: c ? c.id : null, nombre, categoria: $('#fCategoria').value, bio: $('#fBio').value.trim() || null,
          fotoUrl: $('#fFoto').value.trim() || null, activo: $('#fActivo').checked,
          canales, conexiones: conexionesDe(canales)
        };
        if (!estado.sinProductoras) cuerpo.productoras = marcadas('productoras');
        const r = await api('/api/admin/creadores', { metodo: 'POST', cuerpo });
        await guardarEtiquetasDe('creador', r.id, c ? etiquetasDe('creador', c.id) : []);
        if (r.avisoSuscripcion) toast('Creador guardado, pero el hub de YouTube respondió: ' + r.avisoSuscripcion, true);
        else if (r.avisoReplica) toast('Creador guardado, pero no se copió a testing: ' + r.avisoReplica, true);
        else toast(c ? 'Cambios guardados.' : (canales.some((k) => k.plataforma === 'youtube')
          ? 'Creador creado. La suscripción a YouTube queda pendiente hasta que el hub la verifique.' : 'Creador creado.'));
        estado.creadores = null;
        estado.productoras = null;
        if (estado.vista === 'creadores') vistaCreadores();
        refrescarContadores();
      } }
    ], () => {
      editor = editorDeCanales(c ? canalesDe(c) : [], !estado.sinProductoras);
      prepararBusqueda((d) => {
        if (!editor.agregarYouTube(d)) { toast('Ese canal ya está en la lista.', true); return; }
        // En un alta, el canal que se busca rellena lo que falte del creador.
        // Al editar no: un canal de clips no debe ponerle descripción ni foto.
        if (!c) rellenarDesdeCanal(d);
        toast('Canal agregado: ' + (d.titulo || d.channelId));
      });
      prepararFoto(editor);
      prepararLlenado(editor, !c);
    });
  }

  // ---------------------------------------------------------------------------
  // Etiquetas
  // ---------------------------------------------------------------------------
  // En qué lista de la etiqueta va cada cosa.
  const LISTA_DE_ETIQUETA = { creador: 'creadores', productora: 'productoras', canal: 'canales' };

  /** Los ids de las etiquetas que lleva un creador, un medio o un canal. */
  const etiquetasDe = (tipo, id) => (estado.etiquetas || [])
    .filter((e) => (e[LISTA_DE_ETIQUETA[tipo]] || []).includes(id)).map((e) => e.id);

  /** Las casillas de etiquetas de una ficha. Con un servidor anterior, nada. */
  function seccionEtiquetas(tipo, id) {
    if (estado.sinEtiquetas) return '';
    const opciones = (estado.etiquetas || []).map((e) => ({ id: e.id, nombre: e.nombre + (e.activa ? '' : ' (apagada)') }));
    return `<fieldset><legend>Etiquetas</legend>
      ${casillas('etiquetas', opciones, id ? etiquetasDe(tipo, id) : [], 'Todavía no hay etiquetas. Se crean en la sección Etiquetas.')}
      ${opciones.length ? '<p class="hint" style="margin:10px 0 0">Puede llevar varias. En la app solo se ven las encendidas; las demás se quedan guardadas para cuando las enciendas.</p>' : ''}
    </fieldset>`;
  }

  /**
   * Guarda las etiquetas marcadas en la ficha, si cambiaron. Va después de
   * guardar la ficha y no falla con ella: si esto no sale, la ficha ya quedó
   * guardada y solo se avisa.
   */
  async function guardarEtiquetasDe(tipo, id, antes) {
    if (estado.sinEtiquetas || !id || !(estado.etiquetas || []).length) return;
    const ahora = marcadas('etiquetas');
    if (ahora.length === antes.length && ahora.every((x) => antes.includes(x))) return;
    try {
      await api('/api/admin/etiquetas/de/' + tipo + '/' + encodeURIComponent(id), { metodo: 'PUT', cuerpo: { etiquetas: ahora } });
      estado.etiquetas = null;
    } catch (e) {
      toast('Se guardó, pero no se pudieron poner sus etiquetas: ' + e.message, true);
    }
  }

  // Los canales de YouTube que pueden llevar etiqueta, con un nombre que los distinga.
  const canalesEtiquetables = () => vigilados().filter((v) => v.canal.id).map((v) => ({
    id: v.canal.id,
    nombre: v.dueno.nombre + (v.canal.nombre ? ' · ' + v.canal.nombre : (v.canal.handle ? ' · @' + v.canal.handle.replace(/^@/, '') : ''))
  }));

  async function vistaEtiquetas() {
    await Promise.all([cargarEtiquetas(true), cargarCreadores(false), cargarProductoras(false)]);
    if (estado.sinEtiquetas) {
      main.innerHTML = '<div class="head"><div><h1>Etiquetas</h1></div></div><div class="panel"><div class="empty">Este servidor todavía no tiene la versión con etiquetas. Aparecerán aquí cuando se despliegue.</div></div>';
      return;
    }
    const lista = estado.etiquetas;
    const encendidas = lista.filter((e) => e.activa).length;
    const cuantos = (e) => e.creadores.length + e.productoras.length + e.canales.length;

    main.innerHTML = `
      <div class="head"><div><h1>Etiquetas</h1><p class="sub">Las etiquetas agrupan el directorio de la app: sustituyen a los temas fijos de antes. Las creas aquí y se las pones a creadores, medios y canales; cada uno puede llevar varias. Una etiqueta nueva nace <b>apagada</b>: no sale en la app hasta que la enciendas, y aun encendida solo aparece si alguien la lleva. Sin ninguna encendida, la app no enseña ningún filtro.</p></div>
        <button class="btn primary" data-accion="nueva-etiqueta">Nueva etiqueta</button></div>
      <div class="stack">
      <section class="panel"><div class="panel-head"><h2>En la app</h2><span class="badge ${encendidas ? 'b-ok' : 'b-mute'}" id="etqResumen">${lista.length ? plural(encendidas, 'encendida', 'encendidas') + ' de ' + num(lista.length) : 'Ninguna todavía'}</span></div>
      <div class="tablewrap"><table><thead><tr><th>Etiqueta</th><th>En la app</th><th>Creadores</th><th>Medios</th><th>Canales</th><th></th></tr></thead><tbody>
      ${lista.length ? lista.map((e, i) => `<tr>
          <td><b>${esc(e.nombre)}</b></td>
          <td>${e.activa ? '<span class="badge b-ok">Encendida</span>' : '<span class="badge b-mute">Apagada</span>'}${e.activa && !cuantos(e) ? '<div class="muted" style="font-size:12.5px">Nadie la lleva: no sale</div>' : ''}</td>
          <td class="num">${num(e.creadores.length)}</td>
          <td class="num">${num(e.productoras.length)}</td>
          <td class="num">${num(e.canales.length)}</td>
          <td class="acciones">
            <button class="btn sm" data-accion="alternar-etiqueta" data-id="${esc(e.id)}">${e.activa ? 'Apagar' : 'Encender'}</button>
            <button class="btn sm" data-accion="editar-etiqueta" data-id="${esc(e.id)}">Editar</button>
            <button class="btn sm" data-accion="subir-etiqueta" data-id="${esc(e.id)}" ${i === 0 ? 'disabled' : ''} title="Subir en la lista" aria-label="Subir">↑</button>
            <button class="btn sm danger" data-accion="borrar-etiqueta" data-id="${esc(e.id)}">Eliminar</button>
          </td></tr>`).join('') : '<tr><td colspan="6"><div class="empty">Todavía no hay etiquetas. Mientras no haya ninguna encendida, la app no enseña filtros en el Directorio.</div></td></tr>'}
      </tbody></table></div></section>
      </div>`;
  }

  async function abrirEtiqueta(id) {
    await Promise.all([cargarEtiquetas(false), cargarCreadores(false), cargarProductoras(false)]);
    const e = id ? (estado.etiquetas || []).find((x) => x.id === id) : null;
    if (id && !e) return;
    const de = e || { nombre: '', activa: false, creadores: [], productoras: [], canales: [] };

    abrirModal(e ? 'Editar etiqueta' : 'Nueva etiqueta', `
      <div class="stack" style="gap:14px">
        <div class="form">
          <label class="f full">Nombre<input class="input" id="fEtqNombre" maxlength="30" value="${esc(de.nombre)}" placeholder="Por ejemplo: Noticias"></label>
          <label class="check full"><input type="checkbox" id="fEtqActiva" ${de.activa ? 'checked' : ''}> Encendida: se muestra en la app</label>
        </div>
        <fieldset><legend>Creadores que la llevan</legend>
          ${casillas('etqCreadores', estado.creadores || [], de.creadores, 'Todavía no hay creadores.')}
        </fieldset>
        <fieldset><legend>Medios que la llevan</legend>
          ${casillas('etqProductoras', estado.productoras || [], de.productoras, 'Todavía no hay medios.')}
        </fieldset>
        <fieldset><legend>Canales de YouTube que la llevan</legend>
          ${casillas('etqCanales', canalesEtiquetables(), de.canales, 'Todavía no hay canales de YouTube.')}
        </fieldset>
        <p class="hint" style="margin:0">También se pueden poner desde la ficha de cada creador, medio o canal.</p>
      </div>`, [
      { texto: 'Cancelar' },
      { texto: e ? 'Guardar cambios' : 'Crear etiqueta', tipo: 'primary', alPulsar: async () => {
        const nombre = $('#fEtqNombre').value.trim();
        if (nombre.length < 2) { toast('La etiqueta necesita un nombre de al menos 2 letras.', true); return false; }
        const activa = $('#fEtqActiva').checked;
        await api('/api/admin/etiquetas', { metodo: 'POST', cuerpo: {
          id: e ? e.id : null, nombre, activa,
          creadores: marcadas('etqCreadores'), productoras: marcadas('etqProductoras'), canales: marcadas('etqCanales')
        } });
        toast(e ? 'Cambios guardados.' : (activa ? 'Etiqueta creada y encendida.' : 'Etiqueta creada. Está apagada: enciéndela cuando quieras que salga en la app.'));
        estado.etiquetas = null;
        if (estado.vista === 'etiquetas') vistaEtiquetas();
      } }
    ]);
  }

  // ---------------------------------------------------------------------------
  // Productoras
  // ---------------------------------------------------------------------------
  async function vistaProductoras() {
    await Promise.all([cargarProductoras(true), cargarCreadores(false)]);
    if (estado.sinProductoras) {
      main.innerHTML = '<div class="head"><div><h1>Medios</h1></div></div><div class="panel"><div class="empty">Este servidor todavía no tiene la versión con medios. Aparecerán aquí cuando se despliegue.</div></div>';
      return;
    }
    const lista = estado.productoras;
    main.innerHTML = `
      <div class="head"><div><h1>Medios</h1><p class="sub">Lo que hay detrás de varios creadores: un noticiero, un programa, una institución. Un medio tiene sus canales propios y, además, los canales de creadores que se le asignen; quien lo sigue recibe avisos de todos. Puede aparecer en el directorio como un creador más. Para que los videos de un canal suyo les lleguen también a quienes siguen a sus creadores, márcalos en la ficha de ese canal, en la sección Canales.</p></div>
        <button class="btn primary" data-accion="nueva-productora">Nuevo medio</button></div>
      <section class="panel"><div class="tablewrap"><table><thead><tr><th>Medio</th><th>Canales</th><th>Creadores</th><th>Seguidores</th><th>En la app</th><th></th></tr></thead><tbody>
      ${lista.length ? lista.map((p) => {
        const figuran = nombresDe(p.creadores);
        return `<tr>
          <td><div class="who">${avatar(p.logoUrl)}<div><b>${esc(p.nombre)}</b>${p.descripcion ? `<span class="clip" style="max-width:260px">${esc(p.descripcion)}</span>` : ''}</div></div></td>
          <td><div class="chips">${(p.canales || []).map((k) => chipCanal(k, true)).join('') || '<span class="muted">—</span>'}</div></td>
          <td>${figuran.length ? esc(figuran.join(', ')) : '<span class="muted">—</span>'}</td>
          <td class="num">${num(p.seguidores)}</td>
          <td>${p.activo ? '<span class="badge b-ok">Visible</span>' : '<span class="badge b-mute">Oculto</span>'}${p.enDirectorio ? '<div style="margin-top:4px"><span class="badge b-info">En el directorio</span></div>' : ''}</td>
          <td class="acciones">
            <button class="btn sm" data-accion="editar-productora" data-id="${esc(p.id)}">Editar</button>
            <button class="btn sm danger" data-accion="borrar-productora" data-id="${esc(p.id)}">Eliminar</button>
          </td></tr>`;
      }).join('') : '<tr><td colspan="6"><div class="empty">Todavía no hay medios. Crea el primero y después asígnale canales y creadores.</div></td></tr>'}
      </tbody></table></div></section>`;
  }

  function formularioProductora(p) {
    p = p || { activo: true };
    const deCreadores = (p.canales || []).filter((k) => k.creadorId);
    return `
      <div class="stack" style="gap:14px">
        <div class="form">
          <label class="f full">Nombre<input class="input" id="fNombre" maxlength="60" value="${esc(p.nombre)}"></label>
          <label class="f full">Descripción<textarea class="input" id="fBio" rows="3" maxlength="600">${esc(p.descripcion)}</textarea></label>
          <label class="check full"><input type="checkbox" id="fActivo" ${p.activo ? 'checked' : ''}> Visible en la app y suscrito a los videos de sus canales propios</label>
        </div>
        ${servidorConFichas() ? `<fieldset><legend>En el directorio</legend>
          <label class="check"><input type="checkbox" id="fEnDirectorio" ${p.enDirectorio ? 'checked' : ''}> Aparece en el directorio como un creador más</label>
          <label class="f" style="margin-top:10px">Tema en el que sale<select class="input" id="fCategoria">${Object.entries(CATEGORIAS).map(([k, v]) => `<option value="${k}" ${(p.categoria || 'otros') === k ? 'selected' : ''}>${v}</option>`).join('')}</select></label>
          <p class="hint" style="margin:10px 0 0">Sale en el listado de creadores, con sus canales, y la gente lo sigue desde ahí. Sirve también para las versiones de la app que no tienen la lista de medios.</p>
        </fieldset>` : ''}
        ${seccionesDeCanales(false)}
        <fieldset><legend>Llenar automáticamente</legend>${filaDeLlenado()}</fieldset>
        ${seccionFoto('Logo', p.logoUrl)}
        ${seccionEtiquetas('productora', p.id)}
        ${deCreadores.length ? `<fieldset><legend>Canales de creadores que son de este medio</legend><div class="chips">${deCreadores.map((k) => chipCanal(k, true)).join('')}</div></fieldset>` : ''}
        <fieldset><legend>Creadores que figuran en él</legend>
          ${casillas('creadores', estado.creadores || [], p.creadores || [], 'Todavía no hay creadores.')}
          <p class="hint" style="margin:10px 0 0">Figurar en el medio no hace que les lleguen los videos de sus canales: eso se marca en la ficha de cada canal.</p>
        </fieldset>
      </div>`;
  }

  async function abrirProductora(id) {
    await Promise.all([cargarProductoras(false), cargarCreadores(false), cargarEtiquetas(false)]);
    const p = id ? (estado.productoras || []).find((x) => x.id === id) : null;
    if (id && !p) return;
    let editor;
    abrirModal(p ? 'Editar medio' : 'Nuevo medio', formularioProductora(p), [
      { texto: 'Cancelar' },
      { texto: p ? 'Guardar cambios' : 'Crear medio', tipo: 'primary', alPulsar: async () => {
        const nombre = $('#fNombre').value.trim();
        if (nombre.length < 2) { toast('El nombre necesita al menos 2 letras.', true); return false; }
        const canales = editor.valores();
        if (!canales) return false;
        const cuerpo = {
          id: p ? p.id : null, nombre, descripcion: $('#fBio').value.trim() || null,
          logoUrl: $('#fFoto').value.trim() || null, activo: $('#fActivo').checked,
          canales, creadores: marcadas('creadores')
        };
        if ($('#fEnDirectorio')) {
          cuerpo.enDirectorio = $('#fEnDirectorio').checked;
          cuerpo.categoria = $('#fCategoria').value;
        }
        const r = await api('/api/admin/productoras', { metodo: 'POST', cuerpo });
        await guardarEtiquetasDe('productora', r.id, p ? etiquetasDe('productora', p.id) : []);
        if (r.avisoSuscripcion) toast('Medio guardado, pero el hub de YouTube respondió: ' + r.avisoSuscripcion, true);
        else if (r.avisoReplica) toast('Medio guardado, pero no se copió a testing: ' + r.avisoReplica, true);
        else toast(p ? 'Cambios guardados.' : 'Medio creado.');
        estado.creadores = null;
        estado.productoras = null;
        if (estado.vista === 'productoras') vistaProductoras();
        refrescarContadores();
      } }
    ], () => {
      editor = editorDeCanales(p ? (p.canales || []).filter((k) => !k.creadorId) : [], false);
      prepararBusqueda((d) => {
        if (!editor.agregarYouTube(d)) { toast('Ese canal ya está en la lista.', true); return; }
        if (!p) rellenarDesdeCanal(d);
        toast('Canal agregado: ' + (d.titulo || d.channelId));
      });
      prepararFoto(editor);
      prepararLlenado(editor, !p);
    });
  }

  // ---------------------------------------------------------------------------
  // Canales de YouTube: su ficha y su suscripción al hub
  // ---------------------------------------------------------------------------
  async function vistaCanales() {
    await Promise.all([cargarCreadores(true), cargarProductoras(true)]);
    const fichas = servidorConFichas();
    const canales = vigilados()
      .sort((a, b) => Number(conProblema(b)) - Number(conProblema(a)) || String(a.canal.expiraEn || '').localeCompare(String(b.canal.expiraEn || '')));
    const fallidas = canales.filter(conProblema).length;
    main.innerHTML = `
      <div class="head"><div><h1>Canales de YouTube</h1><p class="sub">Cada canal de YouTube del directorio tiene su ficha: de quién es, de qué medio y con qué otros creadores aparece. Lo que publica le llega a quien sigue a cualquiera de ellos. El servidor se suscribe al hub de Google para enterarse de cada video; el hub corta la suscripción a los 10 días y el servidor la renueva solo cada 4.</p></div>
        <div class="toolbar">${fallidas ? `<button class="btn" data-accion="reintentar-fallidas">${fallidas === 1 ? 'Reintentar la que tiene problemas' : 'Reintentar las ' + num(fallidas) + ' con problemas'}</button>` : ''}
        ${fichas ? '<button class="btn primary" data-accion="nuevo-canal">Nuevo canal</button>' : ''}</div></div>
      <section class="panel"><div class="tablewrap"><table><thead><tr><th>Canal</th><th>De quién es</th><th>También aparece con</th><th>Suscripción</th><th></th></tr></thead><tbody>
      ${canales.length ? canales.map((v) => {
        const k = v.canal, d = v.dueno, deProductora = v.tipo === 'productora';
        const casa = !deProductora && k.productoraId ? nombreProductora(k.productoraId) : null;
        const con = nombresDe(k.creadores);
        return `<tr>
          <td><b>${esc(k.nombre || (k.handle ? '@' + k.handle.replace(/^@/, '') : 'Canal'))}</b><div><a class="mono" href="https://www.youtube.com/channel/${esc(k.channelId)}" target="_blank" rel="noopener">${esc(k.channelId)}</a></div></td>
          <td><div class="who">${avatar(deProductora ? d.logoUrl : d.fotoUrl)}<div><b>${esc(d.nombre)}</b><span>${deProductora ? 'Medio' : (casa ? 'Creador · canal de ' + esc(casa) : 'Creador')}${d.activo ? '' : ' · oculto'}</span></div></div></td>
          <td>${con.length ? esc(con.join(', ')) : '<span class="muted">—</span>'}</td>
          <td>${d.activo ? badgeSusc(k.estadoSuscripcion || 'PENDIENTE_VERIFICACION') : '<span class="badge b-mute">Sin suscribir</span>'}${k.expiraEn ? `<div class="muted" style="font-size:12.5px" title="${esc(fmtFecha(k.expiraEn))}">vence ${esc(relativo(k.expiraEn))}${vencePronto(k) ? ' <span class="badge b-warn">pronto</span>' : ''}</div>` : ''}</td>
          <td class="acciones">${fichas && k.id ? `<button class="btn sm" data-accion="editar-canal" data-id="${esc(k.id)}">Editar</button>` : ''}${d.activo ? `<button class="btn sm" data-accion="reintentar" data-tipo="${v.tipo}" data-id="${esc(d.id)}">Reintentar</button>` : ''}${fichas && k.id ? `<button class="btn sm danger" data-accion="borrar-canal" data-id="${esc(k.id)}">Eliminar</button>` : ''}</td>
        </tr>`;
      }).join('') : '<tr><td colspan="5"><div class="empty">Todavía no hay ningún canal de YouTube en el directorio.</div></td></tr>'}
      </tbody></table></div></section>`;
  }

  const tarjetaCanal = (k, titulo) => `<div class="canal"><div><b>${esc(titulo || (k.handle ? '@' + k.handle.replace(/^@/, '') : 'Canal vinculado'))}</b><div class="mono muted">${esc(k.channelId)}</div></div></div>`;

  function formularioCanal(k) {
    k = k || {};
    return `
      <div class="stack" style="gap:14px">
        <fieldset><legend>Canal de YouTube</legend>
          <div id="fKActual">${k.channelId ? tarjetaCanal(k) : '<span class="hint">Busca el canal por su @handle, su URL o su ID.</span>'}</div>
          <div class="agregar" style="margin-top:10px">
            <input class="input" id="fBuscarCanal" placeholder="@handle, URL del canal o ID que empieza por UC" aria-label="Canal de YouTube">
            <button class="btn" type="button" id="btnBuscarCanal">${k.channelId ? 'Cambiar' : 'Buscar'}</button>
          </div>
          <input type="hidden" id="fKUrl" value="${esc(k.url)}">
          <input type="hidden" id="fKHandle" value="${esc(k.handle)}">
          <input type="hidden" id="fKId" value="${esc(k.channelId)}">
        </fieldset>
        <div class="form">
          <label class="f full">Etiqueta<input class="input" id="fKNombre" maxlength="60" value="${esc(k.nombre)}" placeholder="Oficial, Clips, Directos… (opcional)"></label>
          <label class="f">Creador dueño<select class="input" id="fKCreador"><option value="">Ninguno: es propio del medio</option>${(estado.creadores || []).map((c) => `<option value="${esc(c.id)}" ${k.creadorId === c.id ? 'selected' : ''}>${esc(c.nombre)}</option>`).join('')}</select></label>
          <label class="f">Medio<select class="input" id="fKProductora"><option value="">Ninguno</option>${(estado.productoras || []).map((p) => `<option value="${esc(p.id)}" ${k.productoraId === p.id ? 'selected' : ''}>${esc(p.nombre)}</option>`).join('')}</select></label>
        </div>
        <fieldset><legend>También aparece con estos creadores</legend>
          ${casillas('con', estado.creadores || [], k.creadores || [], 'Todavía no hay creadores.')}
          <p class="hint" style="margin:10px 0 0">Lo que publique este canal les llega también a quienes siguen a los creadores marcados, y sale en sus novedades. Los avisos van a nombre del dueño; si no tiene, a nombre del medio.</p>
        </fieldset>
        ${seccionEtiquetas('canal', k && k.id)}
      </div>`;
  }

  async function abrirCanal(id) {
    await Promise.all([cargarCreadores(false), cargarProductoras(false), cargarEtiquetas(false)]);
    const v = id ? vigilados().find((x) => x.canal.id === id) : null;
    if (id && !v) return;
    const k = v ? v.canal : null;

    // El dueño no puede estar además entre "los demás".
    const alCambiarDueno = () => {
      const dueno = $('#fKCreador').value;
      $$('#mCuerpo [data-grupo="con"]').forEach((x) => {
        x.disabled = x.value === dueno;
        if (x.disabled) x.checked = false;
      });
    };

    abrirModal(k ? 'Ficha del canal' : 'Nuevo canal de YouTube', formularioCanal(k), [
      { texto: 'Cancelar' },
      { texto: k ? 'Guardar cambios' : 'Agregar canal', tipo: 'primary', alPulsar: async () => {
        const channelId = $('#fKId').value.trim();
        if (!channelId) { toast('Busca primero el canal de YouTube.', true); $('#fBuscarCanal').focus(); return false; }
        const creadorId = $('#fKCreador').value || null;
        const productoraId = $('#fKProductora').value || null;
        if (!creadorId && !productoraId) { toast('Di de quién es el canal: elige un creador dueño o un medio.', true); return false; }
        let r;
        try {
          r = await api('/api/admin/canales', { metodo: 'POST', cuerpo: {
            id: k ? k.id : null, nombre: $('#fKNombre').value.trim() || null,
            url: $('#fKUrl').value || 'https://www.youtube.com/channel/' + channelId,
            handle: $('#fKHandle').value || null, channelId, creadorId, productoraId,
            creadores: marcadas('con').filter((x) => x !== creadorId)
          } });
        } catch (e) {
          if (e.estado === 404 && !k) throw new ErrorApi('Este servidor todavía no tiene la versión con fichas de canal.', 404);
          throw e;
        }
        await guardarEtiquetasDe('canal', r.id, k ? etiquetasDe('canal', k.id) : []);
        if (r.avisoSuscripcion) toast('Canal guardado, pero el hub de YouTube respondió: ' + r.avisoSuscripcion, true);
        else if (r.avisoReplica) toast('Canal guardado, pero no se copió a testing: ' + r.avisoReplica, true);
        else toast(k ? 'Cambios guardados.' : 'Canal agregado. La suscripción queda pendiente hasta que el hub la verifique.');
        estado.creadores = null;
        estado.productoras = null;
        if (estado.vista === 'canales') vistaCanales();
        refrescarContadores();
      } }
    ], () => {
      $('#fKCreador').addEventListener('change', alCambiarDueno);
      alCambiarDueno();
      prepararBusqueda((d) => {
        const handle = d.handle ? d.handle.replace(/^@/, '') : '';
        $('#fKId').value = d.channelId;
        $('#fKHandle').value = handle;
        $('#fKUrl').value = handle ? 'https://www.youtube.com/@' + handle : 'https://www.youtube.com/channel/' + d.channelId;
        $('#fKActual').innerHTML = tarjetaCanal({ handle, channelId: d.channelId }, d.titulo);
        $('#btnBuscarCanal').textContent = 'Cambiar';
      });
    });
  }

  /** El mismo cuerpo que manda el formulario, armado desde el listado. */
  function cuerpoDe(c) {
    const conexiones = {};
    (c.conexiones || []).forEach((x) => { conexiones[x.plataforma] = x; });
    const cuerpo = { id: c.id, nombre: c.nombre, categoria: c.categoria, bio: c.bio || null, fotoUrl: c.fotoUrl || null, activo: c.activo, conexiones };
    // Sin `productoras`: así el servidor deja como están las que tenga.
    if (c.canales) {
      cuerpo.canales = c.canales.map((k) => ({ id: k.id, plataforma: k.plataforma, nombre: k.nombre || null, url: k.url, handle: k.handle || null, channelId: k.channelId || null, productoraId: k.productoraId || null }));
    }
    return cuerpo;
  }

  /** Sin canales ni creadores: el servidor conserva los que tiene y repite sus altas en el hub. */
  function cuerpoDeProductora(p) {
    return { id: p.id, nombre: p.nombre, descripcion: p.descripcion || null, logoUrl: p.logoUrl || null, activo: p.activo };
  }

  /** Repite la solicitud al hub volviendo a guardar al dueño del canal. */
  function reintentarDueno(tipo, dueno) {
    return tipo === 'productora'
      ? api('/api/admin/productoras', { metodo: 'POST', cuerpo: cuerpoDeProductora(dueno) })
      : api('/api/admin/creadores', { metodo: 'POST', cuerpo: cuerpoDe(dueno) });
  }

  // ---------------------------------------------------------------------------
  // Publicaciones
  // ---------------------------------------------------------------------------
  const ESTADOS_PUB = { ok: ['Normal', 'b-ok'], moved: ['Movido', 'b-info'], removed: ['Retirado', 'b-bad'] };

  // Qué se lista: todo, solo los videos normales o solo los cortos.
  const FILTROS_PUB = { todos: 'Todos', videos: 'Videos', cortos: 'Cortos' };
  const VACIO_PUB = {
    todos: 'Todavía no se ha detectado ningún video.',
    videos: 'Todavía no se ha detectado ningún video normal.',
    cortos: 'No hay ningún video corto guardado.'
  };

  // El interruptor general de los videos cortos. Con un servidor anterior a
  // esto no hay ajustes que leer, y el panel no enseña nada de los cortos.
  function tarjetaCortos(ajustes) {
    const on = !!ajustes.cortos;
    return `<section class="panel"><div class="panel-head"><h2>Videos cortos (Shorts)</h2>
        <span class="badge ${on ? 'b-ok' : 'b-mute'}" id="cortosEstado">${on ? 'Encendidos' : 'Apagados'}</span></div>
      <div class="ajuste">
        <label class="check"><input type="checkbox" id="cortosOn" data-accion="ajuste-cortos" ${on ? 'checked' : ''}> Mostrar los videos cortos en la app</label>
        <p class="hint">${on
          ? 'Salen en un apartado propio de Novedades, nunca mezclados con los demás videos, y avisan solo a quien los quiere. Cada persona puede apagarlos para sí en los Ajustes de la app.'
          : 'El servidor los guarda, pero no avisan a nadie ni salen en la app, y la app no ofrece la opción de verlos. Si los enciendes, aparecen en un apartado propio y cada persona decide si los quiere.'}</p>
        <p class="hint">¿Hay videos normales marcados como cortos, o al revés? Corrígelos en la lista con «Es corto» / «No es corto», o deja que el servidor le pregunte a YouTube por los últimos: <button class="link" data-accion="revisar-cortos">Repasar los cortos guardados</button></p>
      </div></section>`;
  }

  async function vistaPublicaciones() {
    const filtro = FILTROS_PUB[estado.filtroPubs] ? estado.filtroPubs : 'todos';
    const [lista, ajustes] = await Promise.all([
      api('/api/admin/publicaciones?limite=100' + (filtro !== 'todos' ? '&tipo=' + filtro : '')),
      api('/api/admin/ajustes').catch(() => null)
    ]);
    estado.publicaciones = lista;
    estado.ajustes = ajustes;
    main.innerHTML = `
      <div class="head"><div><h1>Publicaciones</h1><p class="sub">Los videos que detectó el servidor. Si una plataforma tumba uno, muévelo a otro enlace y avisa a quienes siguen a su creador o a su medio.</p></div>
        ${ajustes ? `<div class="toolbar" id="filtroPubs">${Object.entries(FILTROS_PUB).map(([k, v]) => `<button class="btn sm ${k === filtro ? 'primary' : ''}" data-accion="filtro-pubs" data-valor="${k}" aria-pressed="${k === filtro}">${v}</button>`).join('')}</div>` : ''}</div>
      <div class="stack">
      ${ajustes ? tarjetaCortos(ajustes) : ''}
      <section class="panel"><div class="tablewrap"><table><thead><tr><th></th><th>Video</th><th>Creador</th><th>Estado</th><th>Publicado</th><th></th></tr></thead><tbody>
      ${lista.length ? lista.map((p) => {
        const [t, c] = ESTADOS_PUB[p.estado] || [p.estado, 'b-mute'];
        const enlace = p.estado === 'moved' && p.destinoUrl ? p.destinoUrl : p.url;
        return `<tr>
          <td>${p.miniaturaUrl ? `<img class="thumb" src="${esc(p.miniaturaUrl)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : ''}</td>
          <td><div class="clip" style="max-width:380px"><b>${esc(p.titulo)}</b></div><span class="muted" style="font-size:13px">${p.enVivo ? 'En vivo' : p.tipo === 'short' ? '<span class="badge b-warn">Corto</span>' : 'Video'} · <span class="mono">${esc(p.videoId)}</span></span></td>
          <td>${esc(p.creadorNombre || '—')}${p.productoraNombre && p.productoraNombre !== p.creadorNombre ? `<div class="muted" style="font-size:12.5px">${esc(p.productoraNombre)}</div>` : ''}</td>
          <td><span class="badge ${c}">${esc(t)}</span>${p.estado === 'moved' ? `<div class="muted" style="font-size:12.5px">a ${esc(PLATAFORMAS[p.destinoPlataforma] || p.destinoPlataforma || '')}</div>` : ''}</td>
          <td class="num">${esc(fmtFecha(p.publicadoEn))}</td>
          <td class="acciones">${enlace ? `<a class="btn sm" href="${esc(enlace)}" target="_blank" rel="noopener">Abrir</a>` : ''}<button class="btn sm" data-accion="mover" data-video="${esc(p.videoId)}">Mover</button>${ajustes && !p.enVivo ? `<button class="btn sm" data-accion="tipo-video" data-video="${esc(p.videoId)}" data-valor="${p.tipo === 'short' ? 'video' : 'short'}">${p.tipo === 'short' ? 'No es corto' : 'Es corto'}</button>` : ''}</td>
        </tr>`;
      }).join('') : `<tr><td colspan="6"><div class="empty">${VACIO_PUB[ajustes ? filtro : 'todos']}</div></td></tr>`}
      </tbody></table></div></section>
      </div>`;
  }

  function abrirMover(videoId) {
    const p = (estado.publicaciones || []).find((x) => x.videoId === videoId);
    abrirModal('Mover video', `
      <p style="margin:0 0 14px">${p ? '<b>' + esc(p.titulo) + '</b> de ' + esc(p.creadorNombre || 'este creador') : 'Video <span class="mono">' + esc(videoId) + '</span>'}. La app abrirá el enlace nuevo en lugar del original.</p>
      <div class="form">
        <label class="f full">Enlace nuevo<input class="input" id="mvUrl" placeholder="https://" value="${esc(p && p.destinoUrl || '')}"></label>
        <label class="f">Plataforma<select class="input" id="mvPlat">${Object.entries(PLATAFORMAS).map(([k, v]) => `<option value="${k}" ${(p && p.destinoPlataforma || 'web') === k ? 'selected' : ''}>${v}</option>`).join('')}</select></label>
        <label class="check" style="align-self:end;padding-bottom:8px"><input type="checkbox" id="mvAvisar" checked> Avisar a los seguidores</label>
      </div>`, [
      { texto: 'Cancelar' },
      { texto: 'Mover video', tipo: 'primary', alPulsar: async () => {
        const url = $('#mvUrl').value.trim();
        if (!/^https:\/\//i.test(url)) { toast('El enlace debe empezar por https://', true); return false; }
        await api('/api/admin/videos/' + encodeURIComponent(videoId) + '/mover', { metodo: 'POST', cuerpo: { url, plataforma: $('#mvPlat').value, avisar: $('#mvAvisar').checked } });
        toast($('#mvAvisar').checked ? 'Video movido y seguidores avisados.' : 'Video movido.');
        if (estado.vista === 'publicaciones') vistaPublicaciones();
      } }
    ]);
  }

  // ---------------------------------------------------------------------------
  // Reportes
  // ---------------------------------------------------------------------------
  const MOTIVOS = { enlace_roto: 'Enlace roto', apple_revoke_failed: 'Falló la revocación con Apple' };

  async function vistaReportes() {
    const [lista, creadores, pubs] = await Promise.all([
      api('/api/admin/reportes?limite=200'),
      cargarCreadores(false).catch(() => []),
      api('/api/admin/publicaciones?limite=200').catch(() => [])
    ]);
    estado.publicaciones = pubs;
    const nombre = (id) => { const c = creadores.find((x) => x.id === id); return c ? c.nombre : null; };
    const pub = (v) => pubs.find((x) => x.videoId === v);

    main.innerHTML = `
      <div class="head"><div><h1>Reportes</h1><p class="sub">Enlaces que los usuarios marcaron como rotos, más incidencias internas. Resuelve cada uno cuando lo atiendas.</p></div></div>
      <section class="panel"><div class="tablewrap"><table><thead><tr><th>Motivo</th><th>Contenido</th><th>Creador</th><th>Recibido</th><th></th></tr></thead><tbody>
      ${lista.length ? lista.map((r) => {
        const p = r.videoId ? pub(r.videoId) : null;
        return `<tr>
          <td><span class="badge ${r.motivo === 'enlace_roto' ? 'b-warn' : 'b-bad'}">${esc(MOTIVOS[r.motivo] || r.motivo)}</span>${r.detalle ? `<div class="muted clip" style="font-size:12.5px" title="${esc(r.detalle)}">${esc(r.detalle)}</div>` : ''}</td>
          <td>${r.videoId ? `<div class="clip"><b>${esc(p ? p.titulo : 'Video')}</b></div><a class="mono" href="https://www.youtube.com/watch?v=${esc(r.videoId)}" target="_blank" rel="noopener">${esc(r.videoId)}</a>` : '<span class="muted">—</span>'}</td>
          <td>${esc(nombre(r.creadorId) || (p && p.creadorNombre) || '—')}</td>
          <td class="num">${esc(relativo(r.creadoEn))}</td>
          <td class="acciones">${r.videoId ? `<button class="btn sm" data-accion="mover" data-video="${esc(r.videoId)}">Mover video</button>` : ''}<button class="btn sm" data-accion="resolver" data-id="${esc(r.id)}">Resuelto</button></td>
        </tr>`;
      }).join('') : '<tr><td colspan="5"><div class="empty">No hay reportes pendientes.</div></td></tr>'}
      </tbody></table></div></section>`;
  }

  // ---------------------------------------------------------------------------
  // Usuarios
  // ---------------------------------------------------------------------------
  // Esta vista solo lee. Las fechas, los seguimientos y las preferencias los
  // guarda el servidor desde siempre; la IP, el país, la compañía de internet
  // y el indicio de bot los anota en cada inicio de sesión, y describen la
  // última conexión de la cuenta, no un historial.
  const PROVEEDORES = { anonimo: ['Invitado', 'b-mute'], google: ['Google', 'b-info'], apple: ['Apple', 'b-info'] };
  const ESCALAS = { normal: 'Normal', grande: 'Grande', muyGrande: 'Muy grande' };
  const TEMAS = { sistema: 'Como el teléfono', oscuro: 'Oscuro', claro: 'Claro' };

  const pct = (parte, total) => (total ? Math.round((parte / total) * 100) : 0) + '%';
  const fmtDia = (iso) => {
    if (!iso) return '—';
    const d = new Date(iso);
    return isNaN(d) ? '—' : d.toLocaleDateString('es-MX', { day: 'numeric', month: 'short', year: 'numeric' });
  };
  // "2026-10-01" es un día de calendario, no un instante: se arma a mano para
  // que el navegador no lo corra un día al interpretarlo como UTC.
  const fmtDiaCorto = (dia) => {
    const [a, m, d] = String(dia).split('-').map(Number);
    return new Date(a, m - 1, d).toLocaleDateString('es-MX', { day: 'numeric', month: 'short' });
  };
  const badgeProveedor = (u) => {
    const [t, c] = PROVEEDORES[u.proveedor] || [u.proveedor, 'b-mute'];
    return `<span class="badge ${c}">${esc(t)}</span>`
      + (u.esAdmin ? ' <span class="badge b-warn">Admin</span>' : '')
      + (u.posibleBot ? ' <span class="badge b-bad">Posible bot</span>' : '');
  };

  // El servidor guarda el código de dos letras; el nombre lo pone el navegador.
  let nombresDePais = null;
  try { nombresDePais = new Intl.DisplayNames(['es'], { type: 'region' }); } catch (e) { nombresDePais = null; }
  const nombrePais = (codigo) => {
    if (!codigo) return '';
    try { return (nombresDePais && nombresDePais.of(codigo)) || codigo; } catch (e) { return codigo; }
  };

  /** Los países con más cuentas y, en una sola fila, todos los demás. */
  function repartoPaises(porPais) {
    const filas = Object.entries(porPais || {}).sort((a, b) => b[1] - a[1]);
    const total = filas.reduce((s, [, n]) => s + n, 0);
    if (!total) return '<div class="empty">Todavía no hay cuentas con país. Se llena cuando cada persona vuelve a abrir la app.</div>';
    const TOPE = 8;
    const visibles = filas.slice(0, TOPE);
    const resto = filas.slice(TOPE).reduce((s, [, n]) => s + n, 0);
    const fila = (nombre, n) => `<span>${esc(nombre)}</span><span class="pista"><i style="width:${(n / total) * 100}%"></i></span><span class="cifra">${num(n)} · ${pct(n, total)}</span>`;
    return `<div class="reparto">${visibles.map(([k, n]) => fila(nombrePais(k), n)).join('')}${resto ? fila('Otros ' + num(filas.length - TOPE) + ' países', resto) : ''}</div>`;
  }

  function graficaAltas(altas) {
    if (!altas.length) return '<div class="empty">Sin datos.</div>';
    const pico = Math.max(...altas.map((a) => a.altas));
    const total = altas.reduce((s, a) => s + a.altas, 0);
    return `
      <div class="barras" role="img" aria-label="${esc(plural(total, 'cuenta nueva', 'cuentas nuevas') + ' en los últimos ' + altas.length + ' días')}">
        ${altas.map((a) => `<div class="col ${a.altas ? '' : 'cero'}" title="${esc(fmtDiaCorto(a.dia) + ': ' + plural(a.altas, 'cuenta nueva', 'cuentas nuevas'))}"><i style="height:${a.altas ? Math.max(4, Math.round((a.altas / pico) * 100)) : 0}%"></i></div>`).join('')}
      </div>
      <div class="barras-pie"><span>${esc(fmtDiaCorto(altas[0].dia))}</span><span>${total ? 'El mejor día: ' + num(pico) : 'Ninguna en este periodo'}</span><span>${esc(fmtDiaCorto(altas[altas.length - 1].dia))}</span></div>`;
  }

  function reparto(titulo, mapa, etiquetas, total) {
    // Primero las opciones conocidas, en su orden; después cualquier valor
    // que el servidor tenga y este panel todavía no conozca.
    const claves = Object.keys(etiquetas).concat(Object.keys(mapa || {}).filter((k) => !(k in etiquetas)));
    return `<p class="reparto-titulo">${esc(titulo)}</p><div class="reparto">${claves.map((k) => {
      const n = (mapa && mapa[k]) || 0;
      return `<span>${esc(etiquetas[k] || k)}</span><span class="pista"><i style="width:${total ? (n / total) * 100 : 0}%"></i></span><span class="cifra">${num(n)} · ${pct(n, total)}</span>`;
    }).join('')}</div>`;
  }

  let pedidoUsuarios = 0;
  /** Trae la página que piden los filtros. Devuelve false si llegó tarde y otra más nueva ya ganó. */
  async function cargarUsuarios() {
    const f = estado.filtroUsuarios;
    const miPedido = ++pedidoUsuarios;
    const p = new URLSearchParams({ q: f.q.trim(), filtro: f.filtro, pais: f.pais, orden: f.orden, pagina: String(f.pagina) });
    const datos = await api('/api/admin/usuarios?' + p.toString());
    if (miPedido !== pedidoUsuarios) return false;
    estado.usuarios = datos;
    return true;
  }

  function filasUsuarios() {
    const pag = estado.usuarios;
    if (!pag || !pag.usuarios.length) {
      const f = estado.filtroUsuarios;
      return `<tr><td colspan="7"><div class="empty">${f.q.trim() || f.filtro || f.pais ? 'Ninguna cuenta coincide.' : 'Todavía no hay cuentas.'}</div></td></tr>`;
    }
    return pag.usuarios.map((u) => `<tr>
      <td><div class="clip"><b>${u.email ? esc(u.email) : '<span class="muted">Sin correo</span>'}</b></div><span class="mono muted">${esc(u.id)}</span></td>
      <td>${badgeProveedor(u)}</td>
      <td>${u.pais || u.red ? `<div>${esc(nombrePais(u.pais) || '—')}</div><div class="muted clip" style="font-size:12.5px;max-width:220px" title="${esc(u.red || '')}">${esc(u.red || '')}</div>` : '<span class="muted">—</span>'}</td>
      <td class="num">${num(u.favoritos)}</td>
      <td class="num">${esc(fmtDia(u.creadoEn))}</td>
      <td class="num" title="${esc(fmtFecha(u.vistoEn))}">${esc(relativo(u.vistoEn))}</td>
      <td class="acciones"><button class="btn sm" data-accion="ver-usuario" data-id="${esc(u.id)}">Ver</button></td>
    </tr>`).join('');
  }

  function pieUsuarios() {
    const pag = estado.usuarios;
    if (!pag) return '';
    const desde = pag.total ? pag.pagina * pag.tamano + 1 : 0;
    const hasta = Math.min((pag.pagina + 1) * pag.tamano, pag.total);
    return `<span class="muted">${pag.total ? num(desde) + '–' + num(hasta) + ' de ' + num(pag.total) : '0 cuentas'}</span>
      <button class="btn sm" data-accion="pagina-usuarios" data-pagina="${pag.pagina - 1}" ${pag.pagina <= 0 ? 'disabled' : ''}>Anterior</button>
      <button class="btn sm" data-accion="pagina-usuarios" data-pagina="${pag.pagina + 1}" ${pag.pagina >= pag.paginas - 1 ? 'disabled' : ''}>Siguiente</button>`;
  }

  async function repintarUsuarios() {
    let llego;
    try { llego = await cargarUsuarios(); } catch (e) {
      if (e.estado !== 401 && e.estado !== 403) toast(e.message, true);
      return;
    }
    if (!llego || estado.vista !== 'usuarios') return;
    const filas = $('#filasUsuarios'), pie = $('#pieUsuarios');
    if (filas) filas.innerHTML = filasUsuarios();
    if (pie) pie.innerHTML = pieUsuarios();
  }

  /** Cambia los filtros desde un botón y deja la tabla a la vista. */
  async function filtrarUsuarios(cambios) {
    const f = Object.assign(estado.filtroUsuarios, cambios, { pagina: 0 });
    const tabla = $('#filasUsuarios');
    if (estado.vista !== 'usuarios' || !tabla) { ir('usuarios'); return; }
    $('#qUsuarios').value = f.q;
    $('#filtroUsuarios').value = f.filtro;
    $('#paisUsuarios').value = f.pais;
    await repintarUsuarios();
    const panel = tabla.closest('.panel');
    if (panel && panel.scrollIntoView) panel.scrollIntoView({ block: 'start' });
  }

  async function vistaUsuarios() {
    const f = estado.filtroUsuarios;
    let zona = 'UTC';
    try { zona = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'; } catch (e) { /* UTC */ }

    const [r] = await Promise.all([
      api('/api/admin/usuarios/resumen?zona=' + encodeURIComponent(zona)),
      cargarUsuarios()
    ]);
    const guardadas = r.conGoogle + r.conApple;
    const promedio = r.conFavoritos ? (r.seguimientos / r.conFavoritos) : 0;

    main.innerHTML = `
      <div class="head"><div><h1>Usuarios</h1><p class="sub">Cada instalación crea una cuenta de invitado al abrir la app; pasa a tener correo cuando la persona la guarda con Google o Apple. "Activo" significa que abrió la app en ese periodo. El país y la compañía de internet son los de la última vez que la abrió.</p></div></div>
      <div class="stack">
        <div class="stats tres">
          <div class="stat"><div class="k">Cuentas</div><div class="v">${num(r.total)}</div><div class="n">${num(r.invitados)} de invitado</div></div>
          <div class="stat"><div class="k">Cuentas guardadas</div><div class="v">${num(guardadas)}</div><div class="n">${pct(guardadas, r.total)} del total · ${num(r.conGoogle)} con Google, ${num(r.conApple)} con Apple</div></div>
          <div class="stat"><div class="k">Siguen a alguien</div><div class="v">${num(r.conFavoritos)}</div><div class="n">${pct(r.conFavoritos, r.total)} del total · ${promedio.toLocaleString('es-MX', { maximumFractionDigits: 1 })} creadores en promedio</div></div>
          <div class="stat"><div class="k">Abrieron la app en 24 h</div><div class="v">${num(r.activos24h)}</div><div class="n">${pct(r.activos24h, r.total)} de las cuentas</div></div>
          <div class="stat"><div class="k">Activos en 7 días</div><div class="v">${num(r.activos7d)}</div><div class="n">${num(r.activos30d)} en 30 días</div></div>
          <div class="stat"><div class="k">Cuentas nuevas en 7 días</div><div class="v">${num(r.nuevos7d)}</div><div class="n">${num(r.nuevos30d)} en 30 días</div></div>
        </div>
        <div class="dos">
          <section class="panel"><div class="panel-head"><h2>Cuentas nuevas por día</h2><span class="muted" style="font-size:13px">últimos ${r.altas.length} días</span></div>
            <div class="panel-body">${graficaAltas(r.altas)}</div></section>
          <section class="panel"><div class="panel-head"><h2>Cómo usan la app</h2></div>
            <div class="panel-body stack" style="gap:18px">
              <div>${reparto('Tamaño de letra', r.porEscalaTexto, ESCALAS, r.total)}</div>
              <div>${reparto('Fondo', r.porTema, TEMAS, r.total)}</div>
            </div></section>
        </div>
        <div class="dos">
          <section class="panel"><div class="panel-head"><h2>Desde dónde se conectan</h2><span class="muted" style="font-size:13px">${plural(Object.keys(r.porPais || {}).length, 'país', 'países')}</span></div>
            <div class="panel-body">${repartoPaises(r.porPais)}</div></section>
          <section class="panel"><div class="panel-head"><h2>Posibles bots</h2></div>
            <div class="panel-body">
              <div class="stat" style="padding:0"><div class="v">${num(r.posiblesBots)}</div><div class="n">${pct(r.posiblesBots, r.total)} de las cuentas</div></div>
              <p class="hint" style="margin:12px 0">Cuentas cuya última conexión no vino de la app, o vino de un centro de datos en lugar de una red de casa o de celular. Es un indicio: una persona con VPN también aparece aquí, igual que los teléfonos de prueba de Google Play.</p>
              ${r.posiblesBots ? '<button class="btn" data-accion="ver-bots">Ver estas cuentas</button>' : ''}
            </div></section>
        </div>
        <section class="panel">
          <div class="panel-head"><div class="toolbar">
            <input class="input buscar" id="qUsuarios" type="search" placeholder="Buscar por correo, IP o ID" value="${esc(f.q)}" aria-label="Buscar cuentas">
            <select class="input" id="filtroUsuarios" aria-label="Tipo de cuenta">
              ${[['', 'Todas las cuentas'], ['anonimo', 'Invitados'], ['google', 'Con Google'], ['apple', 'Con Apple'], ['admin', 'Administradores'], ['bot', 'Posibles bots']].map(([k, v]) => `<option value="${k}" ${f.filtro === k ? 'selected' : ''}>${v}</option>`).join('')}
            </select>
            <select class="input" id="paisUsuarios" aria-label="País">
              <option value="">Todos los países</option>
              ${Object.keys(r.porPais || {}).map((k) => [k, nombrePais(k)]).sort((a, b) => a[1].localeCompare(b[1], 'es')).map(([k, v]) => `<option value="${esc(k)}" ${f.pais === k ? 'selected' : ''}>${esc(v)}</option>`).join('')}
            </select>
            <select class="input" id="ordenUsuarios" aria-label="Orden">
              <option value="vistos" ${f.orden === 'vistos' ? 'selected' : ''}>Últimos en abrir la app</option>
              <option value="nuevos" ${f.orden === 'nuevos' ? 'selected' : ''}>Cuentas más nuevas</option>
            </select>
          </div></div>
          <div class="tablewrap"><table><thead><tr><th>Cuenta</th><th>Tipo</th><th>Conexión</th><th>Sigue a</th><th>Alta</th><th>Abrió la app</th><th></th></tr></thead>
          <tbody id="filasUsuarios">${filasUsuarios()}</tbody></table></div>
          <div class="paginas" id="pieUsuarios">${pieUsuarios()}</div>
        </section>
        <p class="hint" style="margin:0">País y compañía de internet según las tablas gratuitas de DB-IP, que el servidor consulta en su propia memoria: <a href="https://db-ip.com" target="_blank" rel="noopener">IP Geolocation by DB-IP</a>.</p>
      </div>`;

    let espera;
    $('#qUsuarios').addEventListener('input', (e) => {
      f.q = e.target.value; f.pagina = 0;
      clearTimeout(espera);
      espera = setTimeout(repintarUsuarios, 300);
    });
    $('#filtroUsuarios').addEventListener('change', (e) => { f.filtro = e.target.value; f.pagina = 0; repintarUsuarios(); });
    $('#paisUsuarios').addEventListener('change', (e) => { f.pais = e.target.value; f.pagina = 0; repintarUsuarios(); });
    $('#ordenUsuarios').addEventListener('change', (e) => { f.orden = e.target.value; f.pagina = 0; repintarUsuarios(); });
  }

  async function abrirUsuario(id) {
    const d = await api('/api/admin/usuarios/' + encodeURIComponent(id));
    const u = d.usuario;
    const soyYo = sesion && sesion.id === u.id;
    const invitado = u.proveedor === 'anonimo';

    let rol = '';
    if (u.esAdmin) {
      rol = soyYo
        ? '<p class="hint" style="margin:0">Es tu cuenta. El rol te lo tiene que quitar otro administrador.</p>'
        : `<button class="btn danger" data-accion="rol-usuario" data-id="${esc(u.id)}" data-valor="false" data-nombre="${esc(u.email || u.id)}">Quitar acceso al panel</button>`;
    } else if (!invitado) {
      rol = `<button class="btn" data-accion="rol-usuario" data-id="${esc(u.id)}" data-valor="true" data-nombre="${esc(u.email || u.id)}">Dar acceso al panel</button>`;
    }

    abrirModal(u.email || 'Cuenta de invitado', `
      <div class="stack" style="gap:16px">
        ${u.posibleBot ? `<div class="aviso-mal"><b>Posible bot.</b> ${esc(u.motivoBot || '')}</div>` : ''}
        <dl class="ficha">
          <dt>Tipo</dt><dd>${badgeProveedor(u)}</dd>
          <dt>ID</dt><dd class="mono">${esc(u.id)}</dd>
          <dt>Alta</dt><dd>${esc(fmtDia(u.creadoEn))}</dd>
          <dt>Abrió la app</dt><dd>${esc(relativo(u.vistoEn))} <span class="muted">(${esc(fmtFecha(u.vistoEn))})</span></dd>
          <dt>IP</dt><dd>${u.ip ? `<span class="mono">${esc(u.ip)}</span> <button class="link" data-accion="buscar-ip" data-ip="${esc(u.ip)}">Ver cuentas con esta IP</button>` : '<span class="muted">Sin dato: no ha abierto la app desde que se guarda.</span>'}</dd>
          <dt>País</dt><dd>${u.pais ? esc(nombrePais(u.pais)) : '<span class="muted">—</span>'}</dd>
          <dt>Compañía de internet</dt><dd>${u.red ? esc(u.red) + (u.asn ? ' <span class="muted mono">AS' + esc(u.asn) + '</span>' : '') : '<span class="muted">—</span>'}</dd>
          <dt>Aplicación</dt><dd class="mono">${u.agente ? esc(u.agente) : '<span class="muted">—</span>'}</dd>
          <dt>Tamaño de letra</dt><dd>${esc(ESCALAS[u.escalaTexto] || u.escalaTexto)}</dd>
          <dt>Fondo</dt><dd>${esc(TEMAS[u.tema] || u.tema)}</dd>
          <dt>Enlaces reportados</dt><dd>${num(d.reportes)}</dd>
          ${u.sinAnuncios === undefined ? '' : `<dt>Anuncios</dt><dd>${u.sinAnuncios
            ? 'No los ve: ' + esc(MOTIVOS_SIN_ANUNCIOS[u.sinAnunciosOrigen] || u.sinAnunciosOrigen || 'quitados') + (u.sinAnunciosDesde ? ' <span class="muted">(' + esc(fmtDia(u.sinAnunciosDesde)) + ')</span>' : '')
            : 'Los ve <span class="muted">si están encendidos</span>'}</dd>`}
        </dl>
        <div>
          <p class="reparto-titulo">Sigue a ${plural(d.sigue.length, 'creador', 'creadores')}</p>
          ${d.sigue.length ? '<ul class="lista-simple">' + d.sigue.map((c) => `<li><span><b>${esc(c.nombre)}</b> <span class="muted">· ${esc(CATEGORIAS[c.categoria] || c.categoria)}</span></span>${c.activo ? '' : '<span class="badge b-mute">Oculto</span>'}</li>`).join('') + '</ul>' : '<p class="hint" style="margin:0">Todavía no sigue a nadie, así que no recibe avisos.</p>'}
        </div>
        ${rol ? '<div>' + rol + '</div>' : ''}
        ${u.sinAnuncios === undefined ? '' : `<div><button class="btn ${u.sinAnuncios ? 'danger' : ''}" data-accion="sin-anuncios-usuario" data-id="${esc(u.id)}" data-valor="${u.sinAnuncios ? 'false' : 'true'}" data-origen="${esc(u.sinAnunciosOrigen || '')}" data-nombre="${esc(u.email || 'esta cuenta de invitado')}">${u.sinAnuncios ? 'Devolverle los anuncios' : 'Quitarle los anuncios'}</button></div>`}
      </div>`, [{ texto: 'Cerrar' }]);
  }

  // ---------------------------------------------------------------------------
  // Anuncios
  // ---------------------------------------------------------------------------
  // Por qué una cuenta ya no ve anuncios, como lo guarda el servidor.
  const MOTIVOS_SIN_ANUNCIOS = { compra: 'los compró', folio: 'folio de regalo', panel: 'desde el panel' };

  /** Copia un texto. Devuelve si se pudo, para decirlo o pedir que se copie a mano. */
  async function copiar(texto) {
    try {
      await navigator.clipboard.writeText(texto);
      return true;
    } catch (e) {
      // Sin HTTPS o sin permiso el navegador no deja: se intenta a la antigua.
      const caja = document.createElement('textarea');
      caja.value = texto;
      caja.setAttribute('readonly', '');
      caja.style.position = 'fixed';
      caja.style.opacity = '0';
      // Dentro del modal si está abierto: fuera de él no se puede seleccionar nada.
      (modal.open ? modal : document.body).appendChild(caja);
      caja.select();
      let hecho = false;
      try { hecho = document.execCommand('copy'); } catch (e2) { hecho = false; }
      caja.remove();
      return hecho;
    }
  }

  async function vistaAnuncios() {
    const a = await api('/api/admin/anuncios');
    estado.anuncios = a;
    const on = !!a.encendidos;
    const sin = a.sinAnuncios || {};
    const total = Object.values(sin).reduce((s, n) => s + (n || 0), 0);
    const nuevos = estado.foliosNuevos || [];
    const folios = a.folios || [];

    main.innerHTML = `
      <div class="head"><div><h1>Anuncios</h1><p class="sub">Los anuncios que la app de Android muestra entre los videos de Novedades, y las dos maneras de quitarlos: una compra en Google Play o un folio de regalo.</p></div></div>
      <div class="stack">
        <div class="stats">
          <div class="stat"><div class="k">Cuentas sin anuncios</div><div class="v">${num(total)}</div><div class="n">${sin.panel ? plural(sin.panel, 'puesta', 'puestas') + ' desde el panel' : 'entre compras y folios'}</div></div>
          <div class="stat"><div class="k">Por compra</div><div class="v">${num(sin.compra)}</div><div class="n">pagaron en Google Play</div></div>
          <div class="stat"><div class="k">Por folio</div><div class="v">${num(sin.folio)}</div><div class="n">canjearon un folio de regalo</div></div>
          <div class="stat"><div class="k">Folios sin usar</div><div class="v">${num(a.foliosSinUsar)}</div><div class="n">esperando a que alguien los canjee</div></div>
        </div>

        <section class="panel"><div class="panel-head"><h2>Anuncios en la app</h2>
            <span class="badge ${on ? 'b-ok' : 'b-mute'}">${on ? 'Encendidos' : 'Apagados'}</span></div>
          <div class="ajuste">
            <label class="check"><input type="checkbox" data-accion="ajuste-anuncios" ${on ? 'checked' : ''}> Mostrar anuncios en la app</label>
            <p class="hint">${on
              ? 'Los ve todo el mundo menos quien los quitó. Salen entre los videos de Novedades, marcados como «Publicidad», tres como mucho. En Ajustes de la app aparece la sección para quitarlos.'
              : 'La app no pide ni muestra ningún anuncio, y no ofrece quitarlos. Quien ya los quitó no pierde nada: si los enciendes, sigue sin verlos.'}</p>
            <p class="hint">${a.comprasListas
              ? `<span class="badge b-ok">Compra lista</span> El servidor confirma las compras del producto <span class="mono">${esc(a.producto)}</span> de <span class="mono">${esc(a.paquete)}</span> con Google Play.`
              : `<span class="badge b-warn">Compra sin configurar</span> La app no ofrece pagar por quitar los anuncios; solo el folio de regalo. Falta <span class="mono">COMPRAS_PAQUETE</span> en el servidor, o la cuenta de servicio no se pudo leer. Los pasos están en el README, sección «Anuncios».`}</p>
          </div></section>

        <section class="panel"><div class="panel-head"><h2>Folios de regalo</h2></div>
          <div class="panel-body">
            <p class="hint" style="margin:0 0 12px;font-size:13.5px">Un folio quita los anuncios a la cuenta que lo canjea, sin pagar. Sirve una sola vez: al usarlo se borra de aquí. La persona lo escribe en la app, en Ajustes → Anuncios → «Tengo un folio de regalo».</p>
            <div class="toolbar">
              <label class="hint" for="folCantidad">Cuántos</label>
              <input class="input" id="folCantidad" type="number" min="1" max="100" value="1" style="width:84px">
              <input class="input" id="folNota" maxlength="200" placeholder="Para quién o de qué campaña (opcional)" aria-label="Nota" style="flex:1;min-width:220px">
              <button class="btn primary" data-accion="crear-folios">Crear folios</button>
            </div>
            ${nuevos.length ? `<div class="aviso-ok folios-nuevos" style="margin-top:14px">
              <div><b>${plural(nuevos.length, 'folio nuevo', 'folios nuevos')}.</b> Cópialos y repártelos; también quedan en la lista de abajo.</div>
              <div class="folios">${nuevos.map((f) => `<span class="folio">${esc(f.codigo)}</span>`).join('')}</div>
              <div><button class="btn sm" data-accion="copiar-folios">${nuevos.length === 1 ? 'Copiar' : 'Copiar todos'}</button></div>
            </div>` : ''}
          </div>
          <div class="tablewrap"><table><thead><tr><th>Folio</th><th>Nota</th><th>Creado</th><th></th></tr></thead><tbody>
          ${folios.length ? folios.map((f) => `<tr>
              <td><span class="folio">${esc(f.codigo)}</span></td>
              <td>${f.nota ? esc(f.nota) : '<span class="muted">—</span>'}</td>
              <td class="num">${esc(fmtFecha(f.creadoEn))}</td>
              <td class="acciones"><button class="btn sm" data-accion="copiar-folio" data-codigo="${esc(f.codigo)}">Copiar</button><button class="btn sm danger" data-accion="anular-folio" data-codigo="${esc(f.codigo)}">Anular</button></td>
            </tr>`).join('') : '<tr><td colspan="4"><div class="empty">No hay folios sin usar. Los que se canjean desaparecen de esta lista.</div></td></tr>'}
          </tbody></table></div>
          ${a.foliosSinUsar > folios.length ? `<div class="panel-body"><p class="hint" style="margin:0">Se ven los ${num(folios.length)} más recientes de ${num(a.foliosSinUsar)} sin usar.</p></div>` : ''}
        </section>
      </div>`;

    $('#folNota').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('[data-accion="crear-folios"]').click(); });
  }

  // ---------------------------------------------------------------------------
  // Administradores
  // ---------------------------------------------------------------------------
  async function vistaAdministradores() {
    let perfil = null;
    try { perfil = await api('/api/perfil'); } catch (e) { perfil = null; }
    main.innerHTML = `
      <div class="head"><div><h1>Administradores</h1><p class="sub">Da acceso a este panel a otra persona. Primero tiene que haber iniciado sesión con Google o Apple al menos una vez, en la app o aquí, para que su cuenta exista.</p></div></div>
      <div class="stack">
        <section class="panel"><div class="panel-head"><h2>Nombrar administrador</h2></div><div class="panel-body">
          <div class="toolbar" style="flex-wrap:nowrap"><input class="input" id="admCorreo" type="email" placeholder="correo@gmail.com" aria-label="Correo"><button class="btn primary" data-accion="nombrar-admin">Dar acceso</button></div>
          <p class="hint" style="margin:10px 0 0">El rol viaja dentro del token de sesión: la persona tiene que cerrar sesión y volver a entrar para que surta efecto.</p>
          <div id="admResultado" style="margin-top:12px"></div>
        </div></section>
        <section class="panel"><div class="panel-head"><h2>Tu cuenta</h2></div><div class="panel-body">
          <p style="margin:0"><b>${esc(sesion && sesion.email || (perfil && perfil.email) || '—')}</b></p>
          <p class="mono muted" style="margin:4px 0 0">${esc((perfil && perfil.id) || (sesion && sesion.id) || '')}</p>
          <p class="hint" style="margin:10px 0 0">Para ver quién tiene el rol o quitárselo a alguien, entra a <button class="link" data-accion="ver-admins">Usuarios</button> y filtra por Administradores.</p>
        </div></section>
      </div>`;
    $('#admCorreo').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('[data-accion="nombrar-admin"]').click(); });
  }

  // ---------------------------------------------------------------------------
  // Versiones y migración de pruebas a producción
  // ---------------------------------------------------------------------------
  // Qué cambia, qué choca y qué se guarda lo decide versiones.js. Aquí está la
  // pantalla y el orden de las peticiones.
  const V = window.Versiones;
  const TIPO_FICHA = { creador: 'Creador', productora: 'Medio' };
  const contenidoDe = (v) => plural(v.creadores, 'creador', 'creadores') + ' · ' + plural(v.productoras, 'medio', 'medios');

  function filasDeVersiones(lista, botones, vacio) {
    if (!lista.length) return `<tr><td colspan="5"><div class="empty">${vacio}</div></td></tr>`;
    return lista.map((v) => `<tr>
      <td><b>Versión ${esc(v.numero)}</b>${v.automatica ? ' <span class="badge b-mute">Automática</span>' : ''}</td>
      <td>${v.nota ? esc(v.nota) : '<span class="muted">Sin nota</span>'}${v.creadoPor ? `<div class="muted" style="font-size:12.5px">${esc(v.creadoPor)}</div>` : ''}</td>
      <td class="num">${esc(fmtFecha(v.creadoEn))}</td>
      <td class="muted">${esc(contenidoDe(v))}</td>
      <td class="acciones">${botones(v)}</td></tr>`).join('');
  }

  const tablaDeVersiones = (filas) => `<div class="tablewrap"><table><thead><tr><th>Versión</th><th>Nota</th><th>Cuándo</th><th>Contenido</th><th></th></tr></thead><tbody>${filas}</tbody></table></div>`;

  function panelMigrar(datos, remotas, error) {
    const m = datos.migracion;
    const aplicada = m ? m.version : 0;
    const cabecera = m
      ? `Producción tiene aplicada la <b>versión ${esc(m.version)}</b> de pruebas (${esc(relativo(m.aplicadaEn))}${m.aplicadaPor ? ', por ' + esc(m.aplicadaPor) : ''}).`
        + (m.fallidas ? ` <span class="badge b-warn">${plural(m.fallidas, 'ficha no se pudo guardar', 'fichas no se pudieron guardar')}</span>` : '')
      : 'Todavía no se ha migrado ninguna versión de pruebas.';
    const cuerpo = error
      ? `<div class="panel-body"><div class="aviso-mal">No se pudieron leer las versiones de pruebas: ${esc(error)}</div></div>`
      : tablaDeVersiones(filasDeVersiones(remotas.versiones.filter((v) => !v.automatica), (v) => (v.numero === aplicada
        ? (m.fallidas
          ? `<button class="btn sm" data-accion="revisar-migracion" data-id="${esc(v.numero)}">Reintentar lo que faltó</button>`
          : '<span class="badge b-ok">Aplicada</span>')
        : v.numero < aplicada
          ? '<span class="muted">Anterior</span>'
          : `<button class="btn sm primary" data-accion="revisar-migracion" data-id="${esc(v.numero)}">Revisar y migrar</button>`),
        'En pruebas todavía no hay versiones. Entra al panel de pruebas, sección Versiones, y crea una.'));
    return `<section class="panel"><div class="panel-head"><h2>Migrar desde pruebas</h2></div>
      <div class="panel-body" style="border-bottom:1px solid var(--line)"><p style="margin:0">${cabecera}</p>
      <p class="hint" style="margin:6px 0 0">Antes de guardar nada verás qué cambia y, si algo se tocó en los dos lados, eliges cuál se queda.</p></div>${cuerpo}</section>`;
  }

  function panelPendientes(datos) {
    const lista = datos.pendientes || [];
    return `<section class="panel"><div class="panel-head"><h2>Cambios de pruebas sin migrar</h2></div>
      <div class="panel-body" style="border-bottom:1px solid var(--line)"><p class="hint" style="margin:0">Lo que se dio de alta o se cambió aquí y producción todavía no tiene. Mientras estén en esta lista, la copia que manda producción no los pisa. Para llevarlos: crea una versión y, en el panel de producción, entra a Versiones y pulsa «Revisar y migrar».</p></div>
      ${lista.length ? `<ul class="alertas">${lista.map((x) => `<li>
        <span><b>${esc(x.nombre)}</b> <span class="muted">· ${esc(TIPO_FICHA[x.tipo] || x.tipo)}</span> ${x.nuevo ? '<span class="badge b-info">Nuevo aquí</span>' : '<span class="badge b-warn">Con cambios</span>'}</span>
        ${x.nuevo ? '' : `<button class="btn sm" data-accion="descartar-pendiente" data-id="${esc(x.id)}" data-tipo="${esc(x.tipo)}" data-nombre="${esc(x.nombre)}">Descartar</button>`}</li>`).join('')}</ul>`
        : '<div class="empty">Nada pendiente: pruebas no tiene cambios que producción no conozca.</div>'}</section>`;
  }

  async function vistaVersiones() {
    estado.migracion = null;
    let datos;
    try {
      datos = await api('/api/admin/versiones');
    } catch (e) {
      if (e.estado !== 404) throw e;
      main.innerHTML = '<div class="head"><div><h1>Versiones</h1></div></div><div class="panel"><div class="empty">Este servidor todavía no tiene la versión con versiones del directorio. Aparecerán aquí cuando se despliegue.</div></div>';
      return;
    }

    let remotas = null, error = null;
    if (datos.papel === 'produccion') {
      try { remotas = await api('/api/admin/versiones/remotas'); } catch (e) { error = e.message; }
    }

    main.innerHTML = `
      <div class="head"><div><h1>Versiones</h1><p class="sub">Una versión es una foto numerada de los creadores, los medios y sus canales tal como están en este momento. Sirve para saber qué cambió desde entonces${datos.papel === 'pruebas' ? ' y es lo que producción trae cuando migra' : datos.papel === 'produccion' ? ' y para traer a producción lo que se preparó en pruebas' : ''}.</p></div>
        <button class="btn primary" data-accion="crear-version">Crear versión</button></div>
      <div class="stack">
      ${datos.papel === 'produccion' ? panelMigrar(datos, remotas, error) : ''}
      ${datos.papel === 'pruebas' ? panelPendientes(datos) : ''}
      <section class="panel"><div class="panel-head"><h2>Versiones de ${config.ambiente === 'produccion' ? 'producción' : config.ambiente === 'pruebas' ? 'pruebas' : 'este servidor'}</h2></div>
      ${tablaDeVersiones(filasDeVersiones(datos.versiones, (v) => `<button class="btn sm" data-accion="comparar-version" data-id="${esc(v.numero)}">Qué cambió desde entonces</button>`,
        'Todavía no hay versiones. Crea la primera para tener un punto con el que comparar.'))}</section>
      </div>`;
  }

  function crearVersion() {
    abrirModal('Crear versión', `<div class="form">
        <p class="full" style="margin:0">Se guarda una foto del directorio tal como está ahora. No cambia nada en la app.</p>
        <label class="full">Nota <span class="opcional">(opcional)</span><input class="input" id="verNota" maxlength="200" placeholder="Qué trae esta versión" autocomplete="off"></label>
      </div>`, [
      { texto: 'Cancelar' },
      { texto: 'Crear versión', tipo: 'primary', alPulsar: async () => {
        const r = await api('/api/admin/versiones', { metodo: 'POST', cuerpo: { nota: $('#verNota').value.trim() || null } });
        toast('Versión ' + r.numero + ' creada.');
        if (estado.vista === 'versiones') await vistaVersiones();
      } }
    ]);
  }

  async function compararVersion(numero) {
    const [v, ahora] = await Promise.all([api('/api/admin/versiones/' + numero), api('/api/admin/versiones/actual')]);
    const dif = V.comparar(v.contenido, ahora.contenido);
    const marca = { nuevo: ['Nuevo', 'b-ok'], quitado: ['Retirado', 'b-bad'], cambia: ['Cambió', 'b-warn'] };
    abrirModal('Qué cambió desde la versión ' + numero, dif.length
      ? `<ul class="cambios">${dif.map((d) => `<li><div><b>${esc(d.nombre)}</b> <span class="muted">· ${esc(TIPO_FICHA[d.tipo])}</span> <span class="badge ${marca[d.estado][1]}">${marca[d.estado][0]}</span></div>
          ${d.cambios.map((c) => `<div class="cambio"><span class="muted">${esc(c.etiqueta)}:</span> <s>${esc(c.antes)}</s> → ${esc(c.despues)}</div>`).join('')}</li>`).join('')}</ul>`
      : '<p style="margin:0">Nada: el directorio está igual que en esa versión.</p>', [{ texto: 'Cerrar' }]);
  }

  // --- Revisar una migración ---------------------------------------------------

  async function revisarMigracion(numero) {
    main.innerHTML = '<div class="cargando">Comparando la versión de pruebas con producción…</div>';
    const [remota, ahora, ultima] = await Promise.all([
      api('/api/admin/versiones/remotas/' + numero),
      api('/api/admin/versiones/actual'),
      api('/api/admin/migraciones/ultima')]);
    const anterior = (ultima && ultima.estado) || {};

    estado.migracion = {
      numero,
      nota: remota.nota,
      plan: V.planear({ base: anterior.base || null, pruebas: remota.contenido, produccion: ahora.contenido, ids: anterior.ids || null }),
      decisiones: {},
      ids: anterior.ids || null,
      existen: new Set(ahora.contenido.creadores.concat(ahora.contenido.productoras).map((f) => f.id))
    };
    pintarRevision();
  }

  function itemsDe(plan, clase) {
    const lista = [];
    plan.fichas.forEach((f) => f.items.forEach((i) => { if (i.clase === clase) lista.push({ f, i }); }));
    return lista;
  }

  const deQuien = (f, i) => `<b>${esc(f.nombre)}</b> <span class="muted">· ${esc(TIPO_FICHA[f.tipo])} · ${esc(i.etiqueta)}</span>`;

  function pintarRevision() {
    const m = estado.migracion, plan = m.plan, r = plan.resumen;
    const conflictos = itemsDe(plan, 'conflicto'), porConfirmar = itemsDe(plan, 'confirmar'), autos = itemsDe(plan, 'auto');
    const nuevos = plan.fichas.filter((f) => f.estado === 'nuevo');
    const marcado = (i, lado) => (m.decisiones[i.id] === lado ? ' checked' : '');

    main.innerHTML = `
      <div class="head"><div><h1>Migrar la versión ${esc(m.numero)} de pruebas</h1>
        <p class="sub">${m.nota ? '«' + esc(m.nota) + '». ' : ''}Todavía no se ha guardado nada. Revisa qué va a cambiar en producción y, donde las dos partes tocaron lo mismo, elige cuál se queda.</p></div>
        <button class="btn" data-accion="volver-versiones">Volver</button></div>
      <div class="stack">
      <div class="chips">
        <span class="badge b-ok">${plural(r.nuevos, 'ficha nueva', 'fichas nuevas')}</span>
        <span class="badge b-info">${plural(r.cambios, 'cambio', 'cambios')}</span>
        <span class="badge ${r.conflictos ? 'b-bad' : 'b-mute'}">${plural(r.conflictos, 'conflicto', 'conflictos')}</span>
        <span class="badge ${r.confirmar ? 'b-warn' : 'b-mute'}">${num(r.confirmar)} por confirmar</span>
        <span class="badge b-mute">${num(r.iguales)} sin cambios</span>
      </div>
      ${plan.primeraVez ? '<div class="aviso-ok">Es la primera migración: no hay una versión anterior con la que saber qué lado cambió cada cosa, así que cada diferencia se pregunta. Las siguientes solo preguntarán lo que de verdad se haya tocado en los dos lados.</div>' : ''}

      ${plan.avisos.length ? `<section class="panel"><div class="panel-head"><h2>Para tener en cuenta</h2></div><ul class="cambios">${plan.avisos.map((a) => `<li>${esc(a)}</li>`).join('')}</ul></section>` : ''}

      ${conflictos.length ? `<section class="panel"><div class="panel-head"><h2>Conflictos</h2>
          <div class="toolbar"><button class="btn sm" data-accion="elegir-todos" data-valor="pruebas">Pruebas en todos</button><button class="btn sm" data-accion="elegir-todos" data-valor="produccion">Producción en todos</button></div></div>
        <div class="panel-body" style="border-bottom:1px solid var(--line)"><p class="hint" style="margin:0">Se cambió en los dos lados y no coincide. Lo que elijas queda en producción, y pruebas quedará igual.</p></div>
        <div class="panel-body choques">${conflictos.map(({ f, i }) => `<fieldset class="choque">
          <legend>${deQuien(f, i)}</legend>
          ${i.textoBase !== null ? `<p class="hint" style="margin:0 0 8px">Antes de que cambiara: ${esc(i.textoBase)}</p>` : ''}
          <div class="lados">
            <label class="lado"><input type="radio" name="d-${esc(i.id)}" value="pruebas" data-decision="${esc(i.id)}"${marcado(i, 'pruebas')}><span><b>Lo de pruebas</b><span class="valor">${esc(i.textoPruebas)}</span></span></label>
            <label class="lado"><input type="radio" name="d-${esc(i.id)}" value="produccion" data-decision="${esc(i.id)}"${marcado(i, 'produccion')}><span><b>Lo de producción</b> <span class="muted">(dejar como está)</span><span class="valor">${esc(i.textoProduccion)}</span></span></label>
          </div></fieldset>`).join('')}</div></section>` : ''}

      ${porConfirmar.length ? `<section class="panel"><div class="panel-head"><h2>Canales que se quitaron en pruebas</h2></div>
        <div class="panel-body" style="border-bottom:1px solid var(--line)"><p class="hint" style="margin:0">En producción se conservan, salvo que marques la casilla; y como pruebas copia lo que hay en producción, allá volverán a aparecer. Quitar un canal de YouTube deja de vigilarlo.</p></div>
        <ul class="alertas">${porConfirmar.map(({ f, i }) => `<li><span>${deQuien(f, i)}<div class="valor">${esc(i.textoProduccion)}</div></span>
          <label class="check"><input type="checkbox" data-quitar="${esc(i.id)}"${marcado(i, 'pruebas')}> Quitarlo también en producción</label></li>`).join('')}</ul></section>` : ''}

      ${nuevos.length || autos.length ? `<section class="panel"><div class="panel-head"><h2>Se lleva a producción sin preguntar</h2></div>
        <ul class="cambios">
          ${nuevos.map((f) => `<li><b>${esc(f.nombre)}</b> <span class="muted">· ${esc(TIPO_FICHA[f.tipo])}</span> <span class="badge b-ok">Alta nueva</span></li>`).join('')}
          ${autos.map(({ f, i }) => `<li>${deQuien(f, i)}<div class="cambio"><s>${esc(i.textoProduccion)}</s> → ${esc(i.textoPruebas)}</div></li>`).join('')}
        </ul></section>` : ''}

      ${!conflictos.length && !porConfirmar.length && !nuevos.length && !autos.length ? '<section class="panel"><div class="empty">Esta versión no trae nada que producción no tenga ya. Puedes darla por aplicada para que quede como punto de partida de la siguiente.</div></section>' : ''}

      <div class="pie-migrar"><span id="migrarFalta"></span><button class="btn primary" id="migrarBoton" data-accion="migrar"></button></div>
      </div>`;
    actualizarPieDeMigracion();
  }

  function actualizarPieDeMigracion() {
    const m = estado.migracion;
    if (!m || !$('#migrarBoton')) return;
    const faltan = V.sinDecidir(m.plan, m.decisiones).length;
    const cuantas = V.operaciones(m.plan, m.decisiones).filter((op) => !op.copia).length;
    $('#migrarFalta').textContent = faltan ? (faltan === 1 ? 'Falta 1 conflicto por decidir.' : 'Faltan ' + faltan + ' conflictos por decidir.') : '';
    const boton = $('#migrarBoton');
    boton.disabled = faltan > 0;
    boton.textContent = cuantas ? 'Migrar a producción (' + plural(cuantas, 'ficha', 'fichas') + ')'
      : faltan ? 'Migrar a producción' : 'Dar por aplicada la versión ' + m.numero;
  }

  main.addEventListener('change', (e) => {
    const m = estado.migracion;
    if (!m) return;
    const t = e.target;
    if (t.dataset.decision) m.decisiones[t.dataset.decision] = t.value;
    else if (t.dataset.quitar) m.decisiones[t.dataset.quitar] = t.checked ? 'pruebas' : 'produccion';
    else return;
    actualizarPieDeMigracion();
  });

  /**
   * Aplica el plan. Cada ficha se guarda por la ruta de siempre, así que pasa
   * por las mismas reglas que un guardado a mano y se copia de vuelta a
   * pruebas. Si algo falla a medias no hay que deshacer nada: lo que no entró
   * se queda fuera de la base y la siguiente revisión lo vuelve a proponer.
   */
  async function ejecutarMigracion() {
    const m = estado.migracion;
    if (V.sinDecidir(m.plan, m.decisiones).length) { toast('Faltan conflictos por decidir.', true); return; }
    const ops = V.operaciones(m.plan, m.decisiones);
    const cambian = ops.filter((op) => !op.copia).length;

    const seguro = await confirmar('¿Migrar la versión ' + m.numero + ' a producción?',
      cambian
        ? 'Se van a guardar ' + plural(cambian, 'ficha', 'fichas') + ' en producción. Los usuarios de la app lo verán de inmediato.'
        : 'No hay nada que cambiar en producción. La versión queda anotada como aplicada y será el punto de partida de la siguiente.',
      cambian ? 'Migrar' : 'Dar por aplicada', cambian > 0);
    if (!seguro) return;

    main.innerHTML = `<div class="head"><div><h1>Migrando la versión ${esc(m.numero)}…</h1><p class="sub">No cierres esta pestaña hasta que termine.</p></div></div>
      <section class="panel"><ul class="cambios" id="migrarPasos"></ul></section>`;
    const paso = (html, clase) => { const li = document.createElement('li'); li.innerHTML = html; if (clase) li.className = clase; $('#migrarPasos').appendChild(li); return li; };

    try {
      const r = await api('/api/admin/migraciones/preparar', { metodo: 'POST', cuerpo: { version: m.numero } });
      paso('Producción quedó guardada como estaba en su <b>versión ' + esc(r.respaldo) + '</b>.');
    } catch (e) {
      paso('No se pudo empezar: ' + esc(e.message) + ' No se cambió nada.', 'mal');
      paso('<button class="btn" data-accion="volver-versiones">Volver a Versiones</button>');
      return;
    }

    const nuevos = {}, fallidas = [], avisos = [], pendientes = [];
    let guardadas = 0;

    const guardar = async (op) => {
      const ruta = op.tipo === 'creador' ? '/api/admin/creadores' : '/api/admin/productoras';
      let { cuerpo, incompleto } = V.cuerpoDe(op, nuevos, m.existen);
      let r = await api(ruta, { metodo: 'POST', cuerpo });
      if (op.nuevo && !nuevos[op.id]) {
        nuevos[op.id] = r.id;
        m.existen.add(r.id);
        // Al crearlo, producción ya mandó su copia, pero pruebas todavía no
        // sabía que es su misma ficha. Se le dice cuál es (antes de que otra
        // ficha lo nombre) y se guarda otra vez, para que la copia llegue ahora
        // a la ficha correcta. Lo que avisara la primera copia ya no cuenta.
        try {
          await api('/api/admin/migraciones/enlazar', { metodo: 'POST', cuerpo: { tipo: op.tipo, pruebas: op.idPruebas, produccion: r.id } });
          ({ cuerpo, incompleto } = V.cuerpoDe(op, nuevos, m.existen));
          r = await api(ruta, { metodo: 'POST', cuerpo });
        } catch (e) {
          avisos.push('«' + op.nombre + '» se creó, pero no se pudo enlazar con su ficha de pruebas: ' + e.message);
        }
      }
      if (r.avisoReplica) avisos.push('«' + op.nombre + '» se guardó, pero no se copió de vuelta a pruebas: ' + r.avisoReplica);
      if (r.avisoSuscripcion) avisos.push('«' + op.nombre + '» se guardó, pero YouTube no confirmó la suscripción: ' + r.avisoSuscripcion);
      return incompleto;
    };

    for (const op of ops) {
      const li = paso(esc(TIPO_FICHA[op.tipo]) + ' <b>' + esc(op.nombre) + '</b>…');
      try {
        if (await guardar(op)) pendientes.push(op);
        if (!op.copia) guardadas++;
        li.innerHTML = esc(TIPO_FICHA[op.tipo]) + ' <b>' + esc(op.nombre) + '</b>: '
          + (op.nuevo ? 'dado de alta.' : op.copia ? 'se quedó como estaba aquí, y así se copió a pruebas.' : 'actualizado.');
      } catch (e) {
        // Una copia que falla no deja nada a medias en producción.
        if (op.copia) { li.remove(); avisos.push('«' + op.nombre + '» se quedó como estaba aquí, pero no se pudo copiar así a pruebas: ' + e.message); continue; }
        fallidas.push(op.id);
        li.className = 'mal';
        li.innerHTML = esc(TIPO_FICHA[op.tipo]) + ' <b>' + esc(op.nombre) + '</b>: no se guardó. ' + esc(e.message);
      }
    }
    // Segunda vuelta para quien nombraba a alguien que todavía no existía.
    for (const op of pendientes) {
      try { await guardar(op); } catch (e) { avisos.push('«' + op.nombre + '» quedó sin alguna de sus ligas con fichas nuevas: ' + e.message); }
    }

    const fin = V.estadoFinal(m.plan, nuevos, fallidas, m.ids);
    let anotada = true;
    try {
      await api('/api/admin/migraciones', { metodo: 'POST', cuerpo: { version: m.numero, estado: { base: fin.base, ids: fin.ids, resumen: { guardadas, fallidas: fallidas.length } } } });
    } catch (e) {
      anotada = false;
      paso('Las fichas se guardaron, pero no se pudo anotar la migración: ' + esc(e.message) + ' Repite «Revisar y migrar» con esta misma versión: no duplicará nada.', 'mal');
    }

    avisos.forEach((a) => paso(esc(a), 'aviso'));
    $('#main h1').textContent = fallidas.length ? 'La versión ' + m.numero + ' se migró a medias' : 'Versión ' + m.numero + ' migrada';
    $('#main .sub').textContent = plural(guardadas, 'ficha guardada', 'fichas guardadas') + ' en producción'
      + (fallidas.length ? ', ' + plural(fallidas.length, 'no se pudo guardar', 'no se pudieron guardar') + '. Corrige lo que indica cada una y, en Versiones, pulsa «Reintentar lo que faltó»: solo propondrá eso.' : '.')
      + (anotada ? '' : ' La migración no quedó anotada.');
    paso('<button class="btn primary" data-accion="volver-versiones">Volver a Versiones</button>');

    estado.migracion = null;
    estado.creadores = null;
    estado.productoras = null;
    refrescarContadores();
  }

  // ---------------------------------------------------------------------------
  // Acciones
  // ---------------------------------------------------------------------------
  document.addEventListener('click', async (e) => {
    const destino = e.target.closest('[data-ir]');
    if (destino) {
      ir(destino.dataset.ir);
      return;
    }

    const b = e.target.closest('[data-accion]');
    if (!b) return;
    const { accion, id } = b.dataset;

    try {
      switch (accion) {
        case 'recargar': ir(estado.vista); break;

        case 'crear-version': crearVersion(); break;
        case 'comparar-version': await compararVersion(Number(id)); break;
        case 'revisar-migracion': await revisarMigracion(Number(id)); break;
        case 'volver-versiones': ir('versiones'); break;
        case 'migrar': await ejecutarMigracion(); break;

        case 'elegir-todos': {
          const m = estado.migracion;
          if (!m) break;
          itemsDe(m.plan, 'conflicto').forEach(({ i }) => { m.decisiones[i.id] = b.dataset.valor; });
          pintarRevision();
          break;
        }

        case 'descartar-pendiente': {
          const ok = await confirmar('¿Descartar los cambios de ' + b.dataset.nombre + '?',
            'Pruebas deja de proteger lo que se cambió aquí. La próxima vez que esa ficha se guarde en producción, o cuando se repita la copia completa, quedará igual que allá. Si prefieres llevar los cambios a producción, no los descartes: crea una versión y mígrala.',
            'Descartar', true);
          if (!ok) break;
          await api('/api/admin/versiones/descartar', { metodo: 'POST', cuerpo: { tipo: b.dataset.tipo, id } });
          toast('Cambios descartados.');
          await vistaVersiones();
          break;
        }
        case 'nuevo-creador': await abrirCreador(null); break;
        case 'editar-creador': await abrirCreador(id); break;
        case 'nueva-productora': await abrirProductora(null); break;
        case 'nuevo-canal': await abrirCanal(null); break;
        case 'editar-canal': await abrirCanal(id); break;

        case 'borrar-canal': {
          const v = vigilados().find((x) => x.canal.id === id);
          if (!v) break;
          const k = v.canal;
          const ok = await confirmar('¿Eliminar el canal ' + (k.nombre || (k.handle ? '@' + k.handle.replace(/^@/, '') : k.channelId)) + '?',
            'Sale del directorio y el servidor deja de vigilarlo: ya no habrá avisos de sus videos. '
              + (v.tipo === 'productora' ? 'Se borran también los videos que publicó.' : 'Los videos que ya publicó siguen siendo de ' + v.dueno.nombre + '.')
              + ' No se puede deshacer.', 'Eliminar', true);
          if (!ok) break;
          const r = await api('/api/admin/canales/' + id, { metodo: 'DELETE' });
          toast(r.mensaje || 'Canal eliminado.');
          estado.creadores = null;
          estado.productoras = null;
          vistaCanales();
          refrescarContadores();
          break;
        }

        case 'editar-productora': await abrirProductora(id); break;

        case 'borrar-productora': {
          const p = (estado.productoras || []).find((x) => x.id === id);
          if (!p) break;
          const propios = (p.canales || []).filter((k) => !k.creadorId).length;
          const ok = await confirmar('¿Eliminar a ' + p.nombre + '?',
            'Desaparece del directorio' + (propios ? ', junto con ' + plural(propios, 'canal propio', 'canales propios') + ' y lo que publicaron' : '')
              + '. Los canales de sus creadores se quedan con cada creador, ya sin la liga. '
              + plural(p.seguidores, 'persona que lo sigue deja', 'personas que lo siguen dejan') + ' de recibir sus avisos. No se puede deshacer; si solo quieres ocultarlo, edítalo y desmarca "Visible".',
            'Eliminar', true);
          if (!ok) break;
          const r = await api('/api/admin/productoras/' + id, { metodo: 'DELETE' });
          toast(r.mensaje || 'Medio eliminado.');
          estado.creadores = null;
          estado.productoras = null;
          vistaProductoras();
          refrescarContadores();
          break;
        }

        case 'borrar-creador': {
          const c = (estado.creadores || []).find((x) => x.id === id);
          if (!c) break;
          const ok = await confirmar('¿Eliminar a ' + c.nombre + '?',
            'Desaparece del directorio, se cancela su suscripción a YouTube y se borran sus publicaciones. Sus ' + c.seguidores + ' seguidores dejan de recibir avisos. No se puede deshacer; si solo quieres ocultarlo, edítalo y desmarca "Visible".',
            'Eliminar', true);
          if (!ok) break;
          const r = await api('/api/admin/creadores/' + id, { metodo: 'DELETE' });
          toast(r.mensaje || 'Creador eliminado.');
          vistaCreadores();
          refrescarContadores();
          break;
        }

        // Aviso de prueba: comprueba el tramo servidor → Firebase → teléfono
        // sin esperar a que el creador publique. Le llega a quien lo sigue.
        case 'probar-aviso': {
          const c = (estado.creadores || []).find((x) => x.id === id);
          if (!c) break;
          const ok = await confirmar('¿Mandar un aviso de prueba de ' + c.nombre + '?',
            'Le llega a ' + plural(c.seguidores, 'persona que lo sigue', 'personas que lo siguen') + '. Sirve para comprobar que las notificaciones funcionan.',
            'Mandar aviso', config.ambiente === 'produccion');
          if (!ok) break;
          b.disabled = true;
          const r = await api('/api/admin/creadores/' + id + '/aviso-de-prueba', { metodo: 'POST' });
          toast(r.mensaje || 'Aviso enviado.');
          b.disabled = false;
          break;
        }

        case 'reintentar': {
          const tipo = b.dataset.tipo || 'creador';
          const c = ((tipo === 'productora' ? estado.productoras : estado.creadores) || []).find((x) => x.id === id);
          if (!c) break;
          b.disabled = true;
          const r = await reintentarDueno(tipo, c);
          if (r.avisoSuscripcion) toast('El hub respondió: ' + r.avisoSuscripcion, true);
          else toast('Solicitud enviada. Pasa a Activa cuando el hub la verifique.');
          setTimeout(() => { if (estado.vista === 'canales') vistaCanales(); refrescarContadores(); }, 2000);
          break;
        }

        case 'reintentar-fallidas': {
          // Un dueño con dos canales caídos se guarda una sola vez.
          const lista = [];
          vigilados().filter(conProblema).forEach((v) => {
            if (!lista.some((x) => x.tipo === v.tipo && x.dueno.id === v.dueno.id)) lista.push(v);
          });
          b.disabled = true;
          let bien = 0, mal = 0;
          for (const v of lista) {
            b.textContent = `Reintentando ${bien + mal + 1} de ${lista.length}…`;
            try {
              const r = await reintentarDueno(v.tipo, v.dueno);
              if (r.avisoSuscripcion) mal++; else bien++;
            } catch (err) { mal++; }
          }
          toast(mal ? `${bien} enviadas, ${mal} fallaron.` : plural(bien, 'solicitud enviada', 'solicitudes enviadas') + ' al hub.', !!mal);
          setTimeout(() => { if (estado.vista === 'canales') vistaCanales(); refrescarContadores(); }, 2000);
          break;
        }

        case 'mover': abrirMover(b.dataset.video); break;

        case 'borrar-datos': await abrirBorrado(b); break;

        case 'nueva-etiqueta': await abrirEtiqueta(null); break;
        case 'editar-etiqueta': await abrirEtiqueta(id); break;

        case 'alternar-etiqueta': {
          const e = (estado.etiquetas || []).find((x) => x.id === id);
          if (!e) break;
          await api('/api/admin/etiquetas', { metodo: 'POST', cuerpo: { id, activa: !e.activa } });
          const nadie = !(e.creadores.length + e.productoras.length + e.canales.length);
          toast(e.activa ? 'Etiqueta apagada: ya no sale en la app.'
            : (nadie ? 'Etiqueta encendida. Todavía no la lleva nadie, así que la app no la enseña.' : 'Etiqueta encendida: ya sale en la app.'));
          await vistaEtiquetas();
          break;
        }

        case 'subir-etiqueta': {
          // Se renumeran todas: así el orden queda limpio aunque dos tuvieran el mismo.
          const lista = (estado.etiquetas || []).slice();
          const i = lista.findIndex((x) => x.id === id);
          if (i <= 0) break;
          lista.splice(i - 1, 0, lista.splice(i, 1)[0]);
          for (let n = 0; n < lista.length; n++) {
            if (lista[n].orden !== n) await api('/api/admin/etiquetas', { metodo: 'POST', cuerpo: { id: lista[n].id, orden: n } });
          }
          await vistaEtiquetas();
          break;
        }

        case 'borrar-etiqueta': {
          const e = (estado.etiquetas || []).find((x) => x.id === id);
          if (!e) break;
          const llevan = e.creadores.length + e.productoras.length + e.canales.length;
          const ok = await confirmar('¿Eliminar la etiqueta ' + e.nombre + '?',
            'Desaparece de la app y se le quita a ' + (llevan ? plural(llevan, 'ficha que la lleva', 'fichas que la llevan') : 'quien la llevara')
              + '. No se borra ningún creador, medio ni canal. Si solo quieres que no se vea, apágala.',
            'Eliminar', true);
          if (!ok) break;
          const r = await api('/api/admin/etiquetas/' + id, { metodo: 'DELETE' });
          toast(r.mensaje || 'Etiqueta eliminada.');
          estado.etiquetas = null;
          await vistaEtiquetas();
          break;
        }

        case 'filtro-pubs': {
          estado.filtroPubs = b.dataset.valor;
          await vistaPublicaciones();
          break;
        }

        case 'ajuste-cortos': {
          // La casilla ya cambió al pulsarla. Si algo falla o se cancela, se
          // vuelve a pintar la sección con lo que diga el servidor.
          const encender = b.checked;
          try {
            const seguro = await confirmar(
              encender ? 'Mostrar los videos cortos' : 'Dejar de mostrar los videos cortos',
              encender
                ? 'La app enseñará un apartado de videos cortos en Novedades, con los que ya están guardados y los que lleguen. Cada persona podrá apagarlos para sí en Ajustes. No se avisa de los que ya estaban guardados.'
                : 'Los videos cortos dejan de salir en la app y de avisar, y desaparece la opción en los Ajustes de la app. No se borra nada: si los vuelves a encender, ahí siguen.',
              encender ? 'Mostrarlos' : 'Dejar de mostrarlos', !encender);
            if (seguro) {
              await api('/api/admin/ajustes', { metodo: 'PUT', cuerpo: { cortos: encender } });
              toast(encender ? 'Videos cortos encendidos.' : 'Videos cortos apagados.');
            }
          } finally {
            if (estado.vista === 'publicaciones') await vistaPublicaciones();
          }
          break;
        }

        case 'tipo-video': {
          const corto = b.dataset.valor === 'short';
          await api('/api/admin/videos/' + encodeURIComponent(b.dataset.video) + '/tipo', { metodo: 'PUT', cuerpo: { tipo: b.dataset.valor } });
          toast(corto ? 'Marcado como video corto.' : 'Marcado como video normal.');
          if (estado.vista === 'publicaciones') await vistaPublicaciones();
          break;
        }

        case 'revisar-cortos': {
          b.disabled = true;
          const antes = b.textContent;
          b.textContent = 'Preguntando a YouTube…';
          try {
            const r = await api('/api/admin/videos/revisar-cortos', { metodo: 'POST' });
            toast(!r.revisados ? 'No hay videos cortos guardados que repasar.'
              : (r.corregidos ? plural(r.corregidos, 'video pasó', 'videos pasaron') + ' a normal' : 'Ninguno cambió')
                + ' de ' + plural(r.revisados, 'corto repasado', 'cortos repasados')
                + (r.sinRespuesta ? '; de ' + num(r.sinRespuesta) + ' YouTube no aclaró nada.' : '.'));
          } finally {
            b.disabled = false;
            b.textContent = antes;
          }
          if (estado.vista === 'publicaciones') await vistaPublicaciones();
          break;
        }

        case 'ajuste-anuncios': {
          // Igual que con los cortos: la casilla ya cambió al pulsarla, y al
          // final se vuelve a pintar con lo que diga el servidor.
          const encender = b.checked;
          try {
            const seguro = await confirmar(
              encender ? 'Mostrar anuncios en la app' : 'Dejar de mostrar anuncios',
              encender
                ? 'La app de Android empezará a mostrar anuncios de Google entre los videos de Novedades a quien no los haya quitado. Los teléfonos que la tienen abierta se enteran en un minuto como mucho. Antes de encenderlos, la política de privacidad publicada tiene que decir que hay anuncios.'
                : 'La app deja de mostrar anuncios y de ofrecer quitarlos. Quien ya los quitó no pierde nada: si los vuelves a encender, sigue sin verlos.',
              encender ? 'Mostrarlos' : 'Dejar de mostrarlos', encender && config.ambiente === 'produccion');
            if (seguro) {
              await api('/api/admin/ajustes', { metodo: 'PUT', cuerpo: { anuncios: encender } });
              toast(encender ? 'Anuncios encendidos.' : 'Anuncios apagados.');
            }
          } finally {
            if (estado.vista === 'anuncios') await vistaAnuncios();
          }
          break;
        }

        case 'crear-folios': {
          const cantidad = Number($('#folCantidad').value);
          if (!Number.isInteger(cantidad) || cantidad < 1 || cantidad > 100) { toast('Se pueden crear de 1 a 100 folios cada vez.', true); break; }
          b.disabled = true;
          const nota = $('#folNota').value.trim();
          estado.foliosNuevos = await api('/api/admin/folios', { metodo: 'POST', cuerpo: { cantidad, nota: nota || null } });
          toast(plural(estado.foliosNuevos.length, 'folio creado', 'folios creados') + '.');
          if (estado.vista === 'anuncios') await vistaAnuncios();
          break;
        }

        case 'copiar-folio': {
          toast(await copiar(b.dataset.codigo) ? 'Folio copiado.' : 'No se pudo copiar. Selecciónalo y cópialo a mano.', false);
          break;
        }

        case 'copiar-folios': {
          const todos = (estado.foliosNuevos || []).map((f) => f.codigo).join('\n');
          toast(await copiar(todos) ? 'Copiados.' : 'No se pudo copiar. Selecciónalos y cópialos a mano.', false);
          break;
        }

        case 'anular-folio': {
          const codigo = b.dataset.codigo;
          const ok = await confirmar('¿Anular el folio ' + codigo + '?', 'Deja de valer: si ya se lo diste a alguien, no podrá canjearlo. No se puede deshacer.', 'Anular', true);
          if (!ok) break;
          const r = await api('/api/admin/folios/' + encodeURIComponent(codigo), { metodo: 'DELETE' });
          estado.foliosNuevos = (estado.foliosNuevos || []).filter((f) => f.codigo !== codigo);
          toast(r.mensaje || 'Folio anulado.');
          if (estado.vista === 'anuncios') await vistaAnuncios();
          break;
        }

        case 'sin-anuncios-usuario': {
          const quitar = b.dataset.valor === 'true';
          const nombre = b.dataset.nombre;
          const ok = await confirmar(
            quitar ? '¿Quitarle los anuncios a ' + nombre + '?' : '¿Devolverle los anuncios a ' + nombre + '?',
            quitar ? 'Deja de ver anuncios sin pagar ni usar un folio. Queda apuntado que se hizo desde el panel.'
              : b.dataset.origen === 'compra'
                ? 'Los compró. Si solo se los devuelves aquí, su teléfono vuelve a presentar la compra al abrir la app y se le quitan otra vez: para que sea definitivo, devuélvele antes el dinero en Play Console.'
                : b.dataset.origen === 'folio'
                  ? 'Los quitó con un folio de regalo, que ya se gastó. Volverá a ver anuncios y el folio no se recupera.'
                  : 'Volverá a ver anuncios como cualquier otra cuenta.',
            quitar ? 'Quitárselos' : 'Devolvérselos', !quitar);
          if (!ok) break;
          const r = await api('/api/admin/usuarios/' + encodeURIComponent(id) + '/sin-anuncios?valor=' + quitar, { metodo: 'POST' });
          toast(r.mensaje || 'Listo.');
          await abrirUsuario(id);
          break;
        }

        case 'ver-usuario': {
          b.disabled = true;
          await abrirUsuario(id);
          b.disabled = false;
          break;
        }

        case 'pagina-usuarios': {
          estado.filtroUsuarios.pagina = Math.max(0, Number(b.dataset.pagina) || 0);
          await repintarUsuarios();
          break;
        }

        case 'ver-admins': {
          Object.assign(estado.filtroUsuarios, { q: '', filtro: 'admin', pais: '', pagina: 0 });
          ir('usuarios');
          break;
        }

        case 'ver-bots': await filtrarUsuarios({ q: '', filtro: 'bot', pais: '' }); break;

        case 'buscar-ip': {
          cerrarModal();
          await filtrarUsuarios({ q: b.dataset.ip, filtro: '', pais: '' });
          break;
        }

        case 'rol-usuario': {
          const dar = b.dataset.valor === 'true';
          const nombre = b.dataset.nombre;
          const ok = await confirmar(
            dar ? '¿Dar acceso a ' + nombre + '?' : '¿Quitar el acceso a ' + nombre + '?',
            dar ? 'Podrá entrar a este panel, editar el directorio, mover videos, avisar a los seguidores y ver las cuentas de los usuarios.'
                : 'Ya no podrá volver a entrar al panel. Si lo tiene abierto ahora mismo, su sesión sigue valiendo hasta que la cierre o caduque.',
            dar ? 'Dar acceso' : 'Quitar acceso', !dar || config.ambiente === 'produccion');
          if (!ok) break;
          const r = await api('/api/admin/usuarios/' + encodeURIComponent(id) + '/admin?valor=' + dar, { metodo: 'POST' });
          toast(r.mensaje || 'Listo.');
          if (estado.vista === 'usuarios') repintarUsuarios();
          break;
        }

        case 'resolver': {
          b.disabled = true;
          await api('/api/admin/reportes/' + id + '/resolver', { metodo: 'POST' });
          toast('Reporte resuelto.');
          vistaReportes();
          refrescarContadores();
          break;
        }

        case 'nombrar-admin': {
          const correo = $('#admCorreo').value.trim();
          if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(correo)) { toast('Escribe un correo válido.', true); break; }
          const ok = await confirmar('¿Dar acceso a ' + correo + '?', 'Podrá entrar a este panel, editar el directorio, mover videos y avisar a los seguidores.', 'Dar acceso', config.ambiente === 'produccion');
          if (!ok) break;
          b.disabled = true;
          const r = await api('/api/admin/administradores?correo=' + encodeURIComponent(correo), { metodo: 'POST' });
          $('#admResultado').innerHTML = '<div class="aviso-ok">' + esc(r.mensaje || 'Listo.') + '</div>';
          $('#admCorreo').value = '';
          b.disabled = false;
          break;
        }
      }
    } catch (err) {
      if (err.estado !== 401 && err.estado !== 403) toast(err.message, true);
      if (b.isConnected) b.disabled = false;
    }
  });

  window.addEventListener('hashchange', () => {
    const v = ALIAS[location.hash.slice(1)] || location.hash.slice(1);
    if (sesion && v !== estado.vista && VISTAS[v]) ir(v);
  });

  arrancar();
})();
