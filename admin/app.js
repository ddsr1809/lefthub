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
    // El chat se va con la sesión: sin token ya no se puede ni despedir.
    if (chat) olvidarChat();
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
    // El panel se identifica para que la versión mínima de las apps no le
    // corte el paso: entra con Google y lee su perfil por rutas de la app.
    const cabeceras = { 'X-App-Plataforma': 'panel' };
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
    pubs: null,             // pestaña, periodo, orden y filtro de Publicaciones: opcionesPub()
    ajustes: null,          // null: el servidor es anterior a los ajustes
    anuncios: null,         // lo último que dijo /api/admin/anuncios
    foliosNuevos: [],       // los folios recién creados, para copiarlos
    usuarios: null,         // la página que está en pantalla
    migracion: null,        // la revisión de una migración que está en pantalla
    servidor: null,         // versión y commit del servidor; null si no los dice
    aviso: null,            // lo que se está redactando en Avisos
    avisoPreset: null,      // con qué abrir Avisos (desde la ficha de un video)
    chatAbierto: null,      // la conversación del chat que se abre al entrar a la sección
    filtroUsuarios: { q: '', tipos: [], marcas: [], pais: '', orden: 'visto', dir: 'desc', pagina: 0, tamano: 50 },
    filtroRecs: 'pendientes', // pendientes | revisadas | todas
    stickers: null          // lo último que dijo /api/admin/stickers
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
    recomendaciones: vistaRecomendaciones,
    editores: vistaEditores,
    stickers: vistaStickers,
    avisos: vistaAvisos,
    anuncios: vistaAnuncios,
    apps: vistaApps,
    usuarios: vistaUsuarios,
    chat: vistaChat,
    administradores: vistaAdministradores
  };

  // La sección se llamaba Suscripciones: los enlaces guardados siguen valiendo.
  const ALIAS = { suscripciones: 'canales' };

  function ir(vista) {
    vista = ALIAS[vista] || vista;
    if (!VISTAS[vista]) vista = 'resumen';
    // Fuera de la sección Chat no se queda ninguna conversación escuchando.
    if (vista !== 'chat' && chat) olvidarChat();
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
    // Aparte y primero: que un fallo de los demás no deje el chat sin número.
    contarChat();
    try {
      const [creadores, productoras, reportes] = await Promise.all([
        cargarCreadores(true), cargarProductoras(true), api('/api/admin/reportes?limite=200')]);
      const problemas = vigilados().filter(conProblema).length;
      const ns = $('#n-susc'), nr = $('#n-rep');
      ns.textContent = problemas; ns.hidden = !problemas;
      nr.textContent = reportes.length; nr.hidden = !reportes.length;
      contarRecomendaciones();
      return { creadores, productoras, reportes };
    } catch (e) { return null; }
  }

  // Aparte: un servidor anterior a las recomendaciones no tiene la ruta, y
  // eso no debe tumbar los demás contadores.
  async function contarRecomendaciones() {
    const n = $('#n-recs');
    if (!n) return;
    try {
      const r = await api('/api/admin/recomendaciones/cuantas');
      n.textContent = r.pendientes; n.hidden = !r.pendientes;
    } catch (e) { n.hidden = true; }
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

  // Qué servidor responde: su versión y el commit del que salió. La ruta no
  // pide sesión. Si el servidor es anterior a ella, no se muestra nada.
  async function leerServidor() {
    try {
      const r = await fetch(BASE + '/api/servidor');
      estado.servidor = r.ok ? await r.json() : null;
    } catch (e) {
      estado.servidor = null;
    }
    return estado.servidor;
  }

  function textoServidor(s) {
    if (!s || !s.version) return '';
    const minimas = [];
    if (s.appMinimaAndroid) minimas.push('Android ' + s.appMinimaAndroid);
    if (s.appMinimaIos) minimas.push('iOS ' + s.appMinimaIos);
    return ' Servidor <b>' + esc(s.version) + '</b>'
      + (s.commit ? ' <span class="mono">(' + esc(String(s.commit).slice(0, 7)) + ')</span>' : '')
      + (minimas.length ? ' · app mínima: ' + esc(minimas.join(', ')) : '') + '.'
      + (s.mantenimiento ? ' <span class="badge b-warn">En mantenimiento</span> Las apps están fuera; se apaga en la sección Apps.' : '');
  }

  async function vistaResumen() {
    const [base, pubs, servidor] = await Promise.all([refrescarContadores(), api('/api/admin/publicaciones?limite=200'), leerServidor()]);
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
      <div class="head"><div><h1>Resumen</h1><p class="sub">Estado del directorio, de las suscripciones a YouTube y de los reportes.${textoServidor(servidor)}</p></div>
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
        ${!c.anunciadoEn ? `<fieldset><legend>Aviso a todos</legend>
          <label class="check"><input type="checkbox" id="fAvisar" ${!c.id ? 'checked' : ''}> Avisar a todos que llegó al directorio</label>
          <div class="form" id="fAvisoTextos" style="margin-top:12px" ${!c.id ? '' : 'hidden'}>
            <label class="f full">Título<input class="input" id="fAvTitulo" maxlength="${MAX_TITULO_AVISO}" value="${esc(c.nombre ? textoCreadorNuevo(c.nombre).titulo : '')}"></label>
            <label class="f full">Texto<textarea class="input" id="fAvCuerpo" rows="2" maxlength="${MAX_CUERPO_AVISO}">${esc(c.nombre ? textoCreadorNuevo(c.nombre).cuerpo : '')}</textarea></label>
          </div>
          <p class="hint" style="margin:10px 0 0">Les llega a todas las personas con la app, sigan a quien sigan; al tocarlo se abre su ficha. Se manda una sola vez, al guardar, y solo si queda visible.</p>
        </fieldset>` : ''}
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

        // El aviso a todos se lee antes de guardar: si le falta texto, no se guarda nada.
        const avisar = $('#fAvisar') && $('#fAvisar').checked;
        if (avisar) textosDeAvisoDeCreador();
        const aviso = avisar ? { titulo: $('#fAvTitulo').value.trim(), cuerpo: $('#fAvCuerpo').value.trim() } : null;
        if (aviso && (!aviso.titulo || !aviso.cuerpo)) { toast('El aviso a todos necesita título y texto, o desmarca la casilla.', true); return false; }

        const r = await api('/api/admin/creadores', { metodo: 'POST', cuerpo });
        await guardarEtiquetasDe('creador', r.id, c ? etiquetasDe('creador', c.id) : []);

        let sobreAviso = '';
        let avisoFallo = false;
        if (aviso && !cuerpo.activo) {
          sobreAviso = ' No se avisó a todos: quedó oculto.';
        } else if (aviso) {
          try {
            const a = await api('/api/admin/avisos', { metodo: 'POST', cuerpo: { tipo: 'nuevo_creador', id: r.id, titulo: aviso.titulo, cuerpo: aviso.cuerpo } });
            sobreAviso = a.enviado ? ' Se avisó a todos.' : ' El aviso a todos no salió: ' + (a.error || 'motivo desconocido');
            avisoFallo = !a.enviado;
          } catch (e) {
            sobreAviso = ' El aviso a todos no salió: ' + avisoDeRuta(e, 'mandar avisos');
            avisoFallo = true;
          }
        }

        if (r.avisoSuscripcion) toast('Creador guardado, pero el hub de YouTube respondió: ' + r.avisoSuscripcion + sobreAviso, true);
        else if (r.avisoReplica) toast('Creador guardado, pero no se copió a testing: ' + r.avisoReplica + sobreAviso, true);
        else toast((c ? 'Cambios guardados.' : (canales.some((k) => k.plataforma === 'youtube')
          ? 'Creador creado. La suscripción a YouTube queda pendiente hasta que el hub la verifique.' : 'Creador creado.')) + sobreAviso, avisoFallo);
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
      prepararAvisoDeCreador();
    });
  }

  /** La casilla de aviso a todos: enseña el texto y lo sigue al nombre. */
  function prepararAvisoDeCreador() {
    const casilla = $('#fAvisar');
    if (!casilla) return;
    const titulo = $('#fAvTitulo');
    const cuerpo = $('#fAvCuerpo');
    // Lo que la persona escribe a mano se respeta; lo demás sigue al nombre.
    titulo.addEventListener('input', () => { titulo.dataset.tocado = '1'; });
    cuerpo.addEventListener('input', () => { cuerpo.dataset.tocado = '1'; });
    casilla.addEventListener('change', () => { $('#fAvisoTextos').hidden = !casilla.checked; });
    $('#fNombre').addEventListener('input', textosDeAvisoDeCreador);
    // Buscar un canal llena el nombre sin que se teclee: al volver a la casilla se pone al día.
    casilla.closest('fieldset').addEventListener('focusin', textosDeAvisoDeCreador);
    casilla.addEventListener('change', textosDeAvisoDeCreador);
  }

  /** Pone el texto del aviso al día con el nombre, salvo lo que se escribió a mano. */
  function textosDeAvisoDeCreador() {
    const nombre = $('#fNombre') ? $('#fNombre').value.trim() : '';
    const titulo = $('#fAvTitulo');
    const cuerpo = $('#fAvCuerpo');
    if (!nombre || !titulo) return;
    const t = textoCreadorNuevo(nombre);
    if (!titulo.dataset.tocado) titulo.value = t.titulo.slice(0, MAX_TITULO_AVISO);
    if (!cuerpo.dataset.tocado) cuerpo.value = t.cuerpo.slice(0, MAX_CUERPO_AVISO);
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
          <td><b>${esc(k.titulo || k.nombre || (k.handle ? '@' + k.handle.replace(/^@/, '') : 'Canal'))}</b>${k.titulo && k.nombre ? ' <span class="badge b-mute">' + esc(k.nombre) + '</span>' : ''}<div><a class="mono" href="https://www.youtube.com/channel/${esc(k.channelId)}" target="_blank" rel="noopener">${esc(k.channelId)}</a></div></td>
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

  /**
   * @param opciones.buscar   con qué llenar y lanzar la búsqueda del canal al abrir
   * @param opciones.alGuardar qué hacer además cuando el canal quede guardado
   */
  async function abrirCanal(id, opciones = {}) {
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
        if (opciones.alGuardar) await opciones.alGuardar(r);
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
      if (opciones.buscar) {
        $('#fBuscarCanal').value = opciones.buscar;
        $('#btnBuscarCanal').click();
      }
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
        <p class="hint">¿Videos que llegaron sin duración ni miniatura (porque YouTube no contestó en ese momento)? Sin duración no se sabe si son cortos. El servidor los revisa solo cada diez minutos, o ahora: <button class="link" data-accion="completar-videos">Completar los videos sin datos</button></p>
      </div></section>`;
  }

  // Las cifras vienen de YouTube y no siempre están: el canal puede ocultar
  // los "me gusta", o el servidor todavía no las ha pedido. Vacío no es cero.
  const hay = (n) => n !== null && n !== undefined;
  // "15.3 mil", "1.2 M": el formato corto del navegador dice "15.3 k".
  const unDecimal = new Intl.NumberFormat('es-MX', { maximumFractionDigits: 1 });
  const compacto = (n) => (!hay(n) ? '—'
    : n < 1e4 ? num(n)
    : n < 999500 ? unDecimal.format(n >= 1e5 ? Math.round(n / 1e3) : Math.round(n / 100) / 10) + ' mil'
    : unDecimal.format(n >= 1e8 ? Math.round(n / 1e6) : Math.round(n / 1e5) / 10) + ' M');
  const cifra = (n) => (hay(n) ? num(n) : '—');

  /** 754 → "12:34"; 3723 → "1:02:03". */
  function fmtDuracion(segundos) {
    if (!segundos) return '';
    const h = Math.floor(segundos / 3600), m = Math.floor((segundos % 3600) / 60), s = segundos % 60;
    const dos = (x) => String(x).padStart(2, '0');
    return h ? h + ':' + dos(m) + ':' + dos(s) : m + ':' + dos(s);
  }

  /** Lo que se pinta en la esquina de la miniatura, como en YouTube. */
  function etiquetaDuracion(p) {
    if (p.directo === 'en_vivo' || p.enVivo) return '<span class="duracion en-vivo">EN VIVO</span>';
    if (p.directo === 'programado') return '<span class="duracion">PROGRAMADO</span>';
    const d = fmtDuracion(p.duracionSegundos);
    return d ? `<span class="duracion">${d}</span>` : '';
  }

  // ---------------------------------------------------------------------------
  // Qué se ve: los videos uno por uno, o sumados por creador, canal o medio.
  // Todo dentro del mismo periodo y del mismo tipo (todos, videos o cortos).
  // ---------------------------------------------------------------------------
  const VISTAS_PUB = { videos: 'Videos', creador: 'Por creador', canal: 'Por canal', medio: 'Por medio' };

  const PERIODOS = {
    todo: 'Cualquier fecha',
    '24h': 'Últimas 24 horas',
    7: 'Últimos 7 días',
    30: 'Últimos 30 días',
    90: 'Últimos 90 días',
    anio: 'Este año',
    365: 'Últimos 12 meses',
    propio: 'Elegir fechas…'
  };

  // Las columnas de la lista de videos que ordenan al tocarlas. Las de texto
  // empiezan de la A a la Z; las demás, de más a menos.
  const COLUMNAS_PUB = [
    ['duracion', 'Duración'], ['titulo', 'Video'], ['creador', 'Creador'], ['vistas', 'Vistas'],
    ['meGusta', 'Me gusta'], ['comentarios', 'Coment.'], ['estado', 'Estado'], ['publicado', 'Publicado']
  ];
  const DE_LA_A = ['titulo', 'creador', 'estado', 'nombre', 'detalle'];

  // Lo mismo para los totales por grupo. Aquí se ordena en el panel: son pocas filas.
  const COLUMNAS_TOTALES = [
    ['nombre', 'Nombre'], ['videos', 'Videos'], ['vistas', 'Vistas'], ['promedio', 'Promedio por video'],
    ['meGusta', 'Me gusta'], ['comentarios', 'Comentarios'], ['ultimo', 'Último video']
  ];

  /** Lo que el panel recuerda de esta sección mientras está abierto. */
  function opcionesPub() {
    if (!estado.pubs) {
      estado.pubs = { vista: 'videos', periodo: 'todo', desde: '', hasta: '', orden: 'publicado', dir: 'desc',
        filtro: null, limite: 100, totalOrden: 'vistas', totalDir: 'desc' };
    }
    return estado.pubs;
  }

  /** El periodo elegido como instantes: desde (incluido) y hasta (sin incluir). Las fechas propias son en tu hora local. */
  function rangoPub(o) {
    const ahora = new Date();
    const dia = (texto, mas) => { const [a, m, d] = texto.split('-').map(Number); return new Date(a, m - 1, d + (mas || 0)); };
    switch (o.periodo) {
      case '24h': return { desde: new Date(ahora - 864e5) };
      case 'anio': return { desde: new Date(ahora.getFullYear(), 0, 1) };
      case 'propio': return { desde: o.desde ? dia(o.desde) : null, hasta: o.hasta ? dia(o.hasta, 1) : null };
      case 'todo': return {};
      default: return { desde: new Date(ahora - Number(o.periodo) * 864e5) };
    }
  }

  function parametrosPub(o) {
    const q = new URLSearchParams();
    const filtro = FILTROS_PUB[estado.filtroPubs] ? estado.filtroPubs : 'todos';
    if (filtro !== 'todos') q.set('tipo', filtro);
    const r = rangoPub(o);
    if (r.desde) q.set('desde', r.desde.toISOString());
    if (r.hasta) q.set('hasta', r.hasta.toISOString());
    return q;
  }

  /** Para ordenar en el panel lo que llegó, igual que el servidor: lo que no tiene el dato, al final. */
  function comparar(valor, asc) {
    return (a, b) => {
      const x = valor(a), y = valor(b);
      if (!hay(x) || x === '') return hay(y) && y !== '' ? 1 : 0;
      if (!hay(y) || y === '') return -1;
      // En español: sin distinguir acentos ni mayúsculas, y «¿Qué…» va con la Q.
      const r = typeof x === 'string' ? x.localeCompare(y, 'es', { sensitivity: 'base', ignorePunctuation: true }) : x - y;
      return asc ? r : -r;
    };
  }
  const VALOR_PUB = {
    duracion: (p) => p.duracionSegundos, titulo: (p) => p.titulo, creador: (p) => p.creadorNombre,
    vistas: (p) => p.vistas, meGusta: (p) => p.meGusta, comentarios: (p) => p.comentarios,
    estado: (p) => p.estado, publicado: (p) => (p.publicadoEn ? Date.parse(p.publicadoEn) : null)
  };
  const VALOR_TOTAL = {
    nombre: (t) => t.nombre, videos: (t) => t.videos, vistas: (t) => t.vistas,
    promedio: (t) => (t.conCifras ? t.vistas / t.conCifras : null),
    meGusta: (t) => t.meGusta, comentarios: (t) => t.comentarios,
    ultimo: (t) => (t.ultimo ? Date.parse(t.ultimo) : null)
  };

  /** Un encabezado que ordena al tocarlo, con su flecha si es el activo. */
  function encabezado([clave, texto], activo, dir, accion) {
    const es = clave === activo;
    return `<th aria-sort="${es ? (dir === 'asc' ? 'ascending' : 'descending') : 'none'}"><button class="orden${es ? ' activo' : ''}" data-accion="${accion}" data-valor="${clave}" title="Ordenar por ${esc(texto.toLowerCase())}">${esc(texto)}<span aria-hidden="true">${es ? (dir === 'asc' ? '▲' : '▼') : '↕'}</span></button></th>`;
  }

  /** Lo que se cambia de una vez en las cuatro pestañas: pestaña, tipo, periodo. */
  function barraPub(o, conCifras, ajustes) {
    const filtro = FILTROS_PUB[estado.filtroPubs] ? estado.filtroPubs : 'todos';
    return `<section class="panel filtros-pub"><div class="panel-body">
      <div class="toolbar">
        <div class="segmentos" role="tablist">${Object.entries(VISTAS_PUB).map(([k, v]) => `<button class="btn sm ${k === o.vista ? 'primary' : ''}" role="tab" aria-selected="${k === o.vista}" data-accion="vista-pubs" data-valor="${k}">${v}</button>`).join('')}</div>
        <span class="separa"></span>
        ${ajustes ? `<div class="segmentos" id="filtroPubs">${Object.entries(FILTROS_PUB).map(([k, v]) => `<button class="btn sm ${k === filtro ? 'primary' : ''}" data-accion="filtro-pubs" data-valor="${k}" aria-pressed="${k === filtro}">${v}</button>`).join('')}</div>` : ''}
        <select class="input" id="periodoPub" aria-label="Periodo" style="width:auto">${Object.entries(PERIODOS).map(([k, v]) => `<option value="${k}" ${String(o.periodo) === String(k) ? 'selected' : ''}>${v}</option>`).join('')}</select>
        ${o.periodo === 'propio' ? `<label class="hint" for="pubDesde">Del</label><input class="input" type="date" id="pubDesde" value="${esc(o.desde)}" style="width:auto">
          <label class="hint" for="pubHasta">al</label><input class="input" type="date" id="pubHasta" value="${esc(o.hasta)}" style="width:auto">
          <button class="btn sm primary" data-accion="aplicar-fechas">Aplicar</button>` : ''}
        ${o.vista === 'videos' && conCifras ? '<span class="separa"></span><button class="btn sm" data-accion="actualizar-cifras">Actualizar cifras</button>' : ''}
      </div>
      <p class="hint" style="margin:10px 0 0">Por fecha de publicación en YouTube. Las cifras son las públicas de YouTube y el servidor las pone al día solo: los videos de los últimos dos días cada media hora, los del último mes cada seis horas y los demás cada tres días; los directos al aire, cada dos minutos.</p>
    </div></section>`;
  }

  /** Arriba de la lista: lo que suman los videos que se están viendo. */
  function resumenDeCifras(lista) {
    const con = lista.filter((p) => hay(p.vistas));
    if (!con.length) return '';
    const suma = (k) => lista.reduce((t, p) => t + (p[k] || 0), 0);
    const vistas = suma('vistas');
    const top = con.reduce((a, b) => (b.vistas > a.vistas ? b : a));
    return `<div class="stats">
      <div class="stat"><div class="k">Vistas</div><div class="v">${compacto(vistas)}</div><div class="n">entre los ${num(lista.length)} videos de la lista</div></div>
      <div class="stat"><div class="k">Promedio por video</div><div class="v">${compacto(Math.round(vistas / con.length))}</div><div class="n">de los ${num(con.length)} con cifras</div></div>
      <div class="stat"><div class="k">Me gusta · comentarios</div><div class="v">${compacto(suma('meGusta'))}</div><div class="n">${num(suma('comentarios'))} comentarios</div></div>
      <div class="stat"><div class="k">El más visto</div><div class="v">${compacto(top.vistas)}</div><div class="n clip" style="max-width:100%"><button class="link" data-accion="ver-video" data-video="${esc(top.videoId)}" style="font-size:12.5px">${esc(top.titulo)}</button></div></div>
    </div>`;
  }

  /** Un nombre que, al tocarlo, deja solo los videos de ese creador, canal o medio. */
  const filtrable = (tipo, id, texto, clase) => (id && texto
    ? `<button class="link filtrable ${clase || ''}" data-accion="filtrar-pubs" data-tipo="${tipo}" data-id="${esc(id)}" data-nombre="${esc(texto)}" title="Ver solo sus videos">${esc(texto)}</button>`
    : esc(texto || '—'));

  async function vistaPublicaciones() {
    const o = opcionesPub();
    if (o.vista !== 'videos') return vistaTotalesPub(o);

    const q = parametrosPub(o);
    q.set('limite', o.limite);
    q.set('orden', o.orden);
    q.set('dir', o.dir);
    if (o.filtro) q.set(o.filtro.tipo, o.filtro.id);
    const [lista, ajustes] = await Promise.all([
      api('/api/admin/publicaciones?' + q.toString()),
      api('/api/admin/ajustes').catch(() => null)
    ]);
    if (estado.vista !== 'publicaciones') return;
    // El servidor ya los manda en orden. Ordenarlos aquí también deja la
    // lista bien con un servidor anterior, que no entiende el orden pedido.
    lista.sort(comparar(VALOR_PUB[o.orden] || VALOR_PUB.publicado, o.dir === 'asc'));
    estado.publicaciones = lista;
    estado.ajustes = ajustes;
    // Un servidor anterior a las cifras no manda detectadoEn (que siempre tiene valor).
    const conCifras = lista.some((p) => 'detectadoEn' in p);
    const columnas = conCifras ? COLUMNAS_PUB : COLUMNAS_PUB.filter(([k]) => !['duracion', 'vistas', 'meGusta', 'comentarios'].includes(k));
    const QUE = { creador: 'creador', canal: 'canal', medio: 'medio' };

    main.innerHTML = `
      <div class="head"><div><h1>Publicaciones</h1><p class="sub">Los videos que detectó el servidor, con su duración y sus cifras en YouTube. Toca el encabezado de una columna para ordenar por ella, y el nombre de un creador, canal o medio para ver solo sus videos. Si una plataforma tumba un video, muévelo a otro enlace y avisa a quienes lo siguen.</p></div></div>
      <div class="stack">
      ${barraPub(o, conCifras, ajustes)}
      ${o.filtro ? `<div class="aviso-ok filtro-activo"><span>Solo los videos del ${esc(QUE[o.filtro.tipo] || o.filtro.tipo)} <b>${esc(o.filtro.nombre)}</b></span><button class="btn sm" data-accion="quitar-filtro-pubs">Ver todos</button></div>` : ''}
      ${conCifras ? resumenDeCifras(lista) : ''}
      ${ajustes && !o.filtro ? tarjetaCortos(ajustes) : ''}
      <section class="panel">
        <div class="tablewrap"><table class="tabla-pubs"><thead><tr>${columnas.map((c) => encabezado(c, o.orden, o.dir, 'ordenar-pubs')).join('')}<th></th></tr></thead><tbody>
      ${lista.length ? lista.map((p) => {
        const [t, c] = ESTADOS_PUB[p.estado] || [p.estado, 'b-mute'];
        const enlace = p.estado === 'moved' && p.destinoUrl ? p.destinoUrl : p.url;
        const titulo = conCifras
          ? `<button class="link titulo-video" data-accion="ver-video" data-video="${esc(p.videoId)}">${esc(p.titulo)}</button>`
          : `<b>${esc(p.titulo)}</b>`;
        const cuando = p.cifrasEn ? ` title="Cifras de YouTube de ${esc(relativo(p.cifrasEn))}"` : '';
        return `<tr>
          ${conCifras ? `<td><div class="miniatura">${p.miniaturaUrl ? `<img class="thumb" src="${esc(p.miniaturaUrl)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : '<span class="thumb"></span>'}${etiquetaDuracion(p)}</div></td>` : ''}
          <td><div class="titulo-2">${titulo}</div><span class="muted" style="font-size:13px">${p.enVivo ? 'En vivo' : p.tipo === 'short' ? '<span class="badge b-warn">Corto</span>' : 'Video'}${p.canalNombre ? ' · ' + filtrable('canal', p.canalId, p.canalNombre, 'tenue') : ''} · <span class="mono">${esc(p.videoId)}</span></span>${p.reportes ? ` <span class="badge b-warn" title="Veces que lo reportaron como roto">${num(p.reportes)} ${p.reportes === 1 ? 'reporte' : 'reportes'}</span>` : ''}</td>
          <td>${filtrable('creador', p.creadorId, p.creadorNombre)}${p.productoraNombre && p.productoraNombre !== p.creadorNombre ? `<div style="font-size:12.5px">${filtrable('medio', p.productoraId, p.productoraNombre, 'tenue')}</div>` : ''}</td>
          ${conCifras ? `<td class="num"${cuando}>${hay(p.vistas) ? `<b>${compacto(p.vistas)}</b>` : `<span class="muted">${p.cifrasEn ? 'Ocultas' : 'Sin cifras aún'}</span>`}${hay(p.espectadores) ? `<div><span class="badge b-bad">${esc(num(p.espectadores))} viendo ahora</span></div>` : ''}</td>
          <td class="num"${cuando}>${hay(p.meGusta) ? compacto(p.meGusta) : '<span class="muted">—</span>'}</td>
          <td class="num"${cuando}>${hay(p.comentarios) ? compacto(p.comentarios) : '<span class="muted">—</span>'}</td>` : ''}
          <td><span class="badge ${c}">${esc(t)}</span>${p.estado === 'moved' ? `<div class="muted" style="font-size:12.5px">a ${esc(PLATAFORMAS[p.destinoPlataforma] || p.destinoPlataforma || '')}</div>` : ''}</td>
          <td class="num fecha" title="${esc(fmtFecha(p.publicadoEn))}">${esc(fmtFecha(p.publicadoEn))}<div class="muted" style="font-size:12.5px">${esc(relativo(p.publicadoEn))}</div></td>
          <td class="acciones"><div class="botones">${enlace ? `<a class="btn sm" href="${esc(enlace)}" target="_blank" rel="noopener">Abrir</a>` : ''}<button class="btn sm" data-accion="mover" data-video="${esc(p.videoId)}">Mover</button>${ajustes && !p.enVivo ? `<button class="btn sm" data-accion="tipo-video" data-video="${esc(p.videoId)}" data-valor="${p.tipo === 'short' ? 'video' : 'short'}">${p.tipo === 'short' ? 'No es corto' : 'Es corto'}</button>` : ''}</div></td>
        </tr>`;
      }).join('') : `<tr><td colspan="${columnas.length + 1}"><div class="empty">${o.filtro || o.periodo !== 'todo' ? 'No hay videos en este periodo.' : VACIO_PUB[ajustes ? (FILTROS_PUB[estado.filtroPubs] ? estado.filtroPubs : 'todos') : 'todos']}</div></td></tr>`}
      </tbody></table></div>
      ${lista.length >= o.limite && o.limite < 500 ? `<div class="paginas"><span class="muted">Se ven los primeros ${num(lista.length)}.</span><button class="btn sm" data-accion="mas-pubs">Mostrar 100 más</button></div>` : ''}
      </section>
      </div>`;
    prepararBarraPub(o);
  }

  // Los totales por creador, canal o medio. El servidor suma; aquí se ordena.
  async function vistaTotalesPub(o) {
    const q = parametrosPub(o);
    q.set('por', o.vista);
    let lista;
    const [r, ajustes] = await Promise.all([
      api('/api/admin/publicaciones/totales?' + q.toString()).catch((e) => e),
      estado.ajustes ? Promise.resolve(estado.ajustes) : api('/api/admin/ajustes').catch(() => null)
    ]);
    if (r instanceof Error) {
      if (r.estado !== 404) throw r;
      lista = null;
    } else lista = r;
    if (estado.vista !== 'publicaciones') return;
    estado.ajustes = ajustes;
    const NOMBRES = { creador: ['creador', 'creadores'], canal: ['canal', 'canales'], medio: ['medio', 'medios'] };
    const [uno, varios] = NOMBRES[o.vista];

    let cuerpo;
    if (!lista) {
      cuerpo = '<section class="panel"><div class="empty">Este servidor todavía no tiene la versión con los totales. Aparecerán aquí cuando se despliegue.</div></section>';
    } else {
      lista.sort(comparar(VALOR_TOTAL[o.totalOrden] || VALOR_TOTAL.vistas, o.totalDir === 'asc'));
      const total = lista.reduce((t, x) => t + x.vistas, 0);
      const videos = lista.reduce((t, x) => t + x.videos, 0);
      const top = lista.reduce((a, b) => (!a || b.vistas > a.vistas ? b : a), null);
      cuerpo = `
        ${lista.length ? `<div class="stats">
          <div class="stat"><div class="k">Vistas</div><div class="v">${compacto(total)}</div><div class="n">de ${plural(videos, 'video', 'videos')} en el periodo</div></div>
          <div class="stat"><div class="k">${varios[0].toUpperCase() + varios.slice(1)} con videos</div><div class="v">${num(lista.length)}</div><div class="n">en el periodo</div></div>
          <div class="stat"><div class="k">Promedio por ${uno}</div><div class="v">${compacto(Math.round(total / lista.length))}</div><div class="n">vistas</div></div>
          <div class="stat"><div class="k">El más visto</div><div class="v">${compacto(top.vistas)}</div><div class="n clip" style="max-width:100%">${esc(top.nombre || 'ya no está en el directorio')}</div></div>
        </div>` : ''}
        <section class="panel">
          ${o.vista === 'medio' ? '<div class="panel-body" style="border-bottom:1px solid var(--line)"><p class="hint" style="margin:0">Un video cuenta para un medio si salió de uno de los canales de ese medio, también los de sus creadores que estén asignados al medio.</p></div>' : ''}
          <div class="tablewrap"><table class="tabla-totales"><thead><tr><th>#</th>${COLUMNAS_TOTALES.map((c) => encabezado(c, o.totalOrden, o.totalDir, 'ordenar-totales')).join('')}<th></th></tr></thead><tbody>
          ${lista.length ? lista.map((t, i) => `<tr>
            <td class="num muted">${i + 1}</td>
            <td><div class="who">${o.vista === 'canal' ? '' : avatar(t.fotoUrl)}<div><b>${t.nombre ? esc(t.nombre) : '<span class="muted">Ya no está en el directorio</span>'}</b>${t.detalle || (t.nombre && !t.visible) ? `<span>${t.detalle ? esc(t.detalle) : ''}${t.nombre && !t.visible ? (t.detalle ? ' · ' : '') + 'oculto en la app' : ''}</span>` : ''}</div></div></td>
            <td class="num">${num(t.videos)}</td>
            <td class="num"><b>${compacto(t.vistas)}</b>${t.conCifras < t.videos ? `<div class="muted" style="font-size:12px" title="Los demás todavía no tienen cifras">de ${num(t.conCifras)} con cifras</div>` : ''}</td>
            <td class="num">${t.conCifras ? compacto(Math.round(t.vistas / t.conCifras)) : '—'}</td>
            <td class="num">${compacto(t.meGusta)}</td>
            <td class="num">${compacto(t.comentarios)}</td>
            <td class="num" title="${esc(fmtFecha(t.ultimo))}">${esc(relativo(t.ultimo))}</td>
            <td class="acciones">${t.nombre ? `<button class="btn sm" data-accion="filtrar-pubs" data-tipo="${o.vista}" data-id="${esc(t.id)}" data-nombre="${esc(t.nombre)}" data-orden="vistas">Ver sus videos</button>` : ''}</td>
          </tr>`).join('') : `<tr><td colspan="${COLUMNAS_TOTALES.length + 2}"><div class="empty">Ningún ${uno} publicó videos en este periodo.</div></td></tr>`}
          </tbody></table></div>
        </section>`;
    }

    main.innerHTML = `
      <div class="head"><div><h1>Publicaciones</h1><p class="sub">Lo que suman los videos de cada ${uno} en el periodo elegido. Toca el encabezado de una columna para ordenar, y «Ver sus videos» para la lista de ese ${uno}, de la más vista a la menos.</p></div></div>
      <div class="stack">
        ${barraPub(o, true, ajustes)}
        ${cuerpo}
      </div>`;
    prepararBarraPub(o);
  }

  function prepararBarraPub(o) {
    const sel = $('#periodoPub');
    if (sel) {
      sel.addEventListener('change', () => {
        o.periodo = sel.value;
        o.limite = 100;
        // Fechas propias: se espera a que las elija y pulse Aplicar.
        if (o.periodo === 'propio' && !(o.desde || o.hasta)) {
          const hoy = new Date();
          const iso = (d) => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
          o.hasta = iso(hoy);
          o.desde = iso(new Date(hoy - 30 * 864e5));
        }
        vistaPublicaciones();
      });
    }
    const enter = (e) => { if (e.key === 'Enter') { const b = $('[data-accion="aplicar-fechas"]'); if (b) b.click(); } };
    ['#pubDesde', '#pubHasta'].forEach((id) => { const el = $(id); if (el) el.addEventListener('keydown', enter); });
  }

  /** "5 min después", "2 h después": lo que tardó el servidor en enterarse. */
  function demora(desde, hasta) {
    if (!desde || !hasta) return '';
    const min = Math.round((new Date(hasta) - new Date(desde)) / 60000);
    if (min < 1) return 'al momento';
    if (min < 60) return min + ' min después';
    if (min < 48 * 60) return Math.round(min / 60) + ' h después';
    return Math.round(min / 1440) + ' días después';
  }

  const DIRECTOS = { programado: 'Programado', en_vivo: 'Al aire ahora', terminado: 'Terminado' };

  // La ficha de un video: todo lo que se sabe de él. Al abrirla, el servidor
  // pide a YouTube las cifras de ahora si las que tiene son de hace más de diez minutos.
  async function abrirVideo(videoId) {
    let p;
    try {
      p = await api('/api/admin/videos/' + encodeURIComponent(videoId));
    } catch (e) {
      if (e.estado !== 404) throw e;
      // Un servidor anterior a la ficha: se enseña lo que ya trae la lista.
      p = (estado.publicaciones || []).find((x) => x.videoId === videoId);
      if (!p) throw e;
    }
    const lista = estado.publicaciones || [];
    const i = lista.findIndex((x) => x.videoId === videoId);
    if (i >= 0) lista[i] = Object.assign({}, lista[i], p, { descripcion: undefined });

    const [t, c] = ESTADOS_PUB[p.estado] || [p.estado, 'b-mute'];
    const enlace = p.estado === 'moved' && p.destinoUrl ? p.destinoUrl : p.url;
    // Con menos de un día publicado, el promedio diario no dice nada.
    const dias = p.publicadoEn ? (Date.now() - new Date(p.publicadoEn)) / 864e5 : 0;
    const porDia = hay(p.vistas) && dias >= 1 ? Math.round(p.vistas / dias) : null;
    // Debajo del número grande, el exacto, solo si el grande está abreviado.
    const exacto = (n, siNo) => (!hay(n) ? siNo : compacto(n) === cifra(n) ? '&nbsp;' : cifra(n));
    const dato = (k, v) => `<dt>${k}</dt><dd>${v}</dd>`;

    abrirModal(p.titulo || 'Video', `
      <div class="stack" style="gap:16px">
        ${p.miniaturaUrl ? `<div class="miniatura grande"><img src="${esc(p.miniaturaUrl)}" alt="" referrerpolicy="no-referrer">${etiquetaDuracion(p)}</div>` : ''}
        <div class="stats" style="grid-template-columns:repeat(3,minmax(0,1fr))">
          <div class="stat"><div class="k">Vistas</div><div class="v">${compacto(p.vistas)}</div><div class="n">${exacto(p.vistas, p.cifrasEn ? 'YouTube no las da' : 'aún no se piden')}</div></div>
          <div class="stat"><div class="k">Me gusta</div><div class="v">${compacto(p.meGusta)}</div><div class="n">${exacto(p.meGusta, 'ocultos o sin pedir')}</div></div>
          <div class="stat"><div class="k">Comentarios</div><div class="v">${compacto(p.comentarios)}</div><div class="n">${exacto(p.comentarios, 'cerrados o sin pedir')}</div></div>
        </div>
        <dl class="ficha">
          ${hay(p.espectadores) ? dato('Viendo ahora', `<span class="badge b-bad">${esc(num(p.espectadores))} en directo</span>`) : ''}
          ${dato('Duración', p.duracionSegundos ? esc(fmtDuracion(p.duracionSegundos)) + ` <span class="muted">(${esc(plural(p.duracionSegundos, 'segundo', 'segundos'))})</span>` : p.directo && p.directo !== 'no' && p.directo !== 'terminado' ? 'Hasta que termine el directo' : '<span class="muted">No se sabe todavía</span>')}
          ${porDia !== null ? dato('Vistas al día', esc(num(porDia)) + ' <span class="muted">de promedio desde que se publicó</span>') : ''}
          ${dato('Tipo', p.tipo === 'short' ? '<span class="badge b-warn">Corto</span>' : 'Video')}
          ${p.directo && p.directo !== 'no' ? dato('Directo', esc(DIRECTOS[p.directo] || p.directo)) : ''}
          ${dato('Creador', esc(p.creadorNombre || '—') + (p.productoraNombre && p.productoraNombre !== p.creadorNombre ? ' <span class="muted">· ' + esc(p.productoraNombre) + '</span>' : ''))}
          ${p.canalNombre ? dato('Canal', esc(p.canalNombre)) : ''}
          ${dato('Publicado', esc(fmtFecha(p.publicadoEn)) + ' <span class="muted">(' + esc(relativo(p.publicadoEn)) + ')</span>')}
          ${p.detectadoEn ? dato('Lo detectó el servidor', esc(fmtFecha(p.detectadoEn)) + ' <span class="muted">(' + esc(demora(p.publicadoEn, p.detectadoEn)) + ')</span>') : ''}
          ${'notificado' in p ? dato('Aviso a los seguidores', p.notificado ? '<span class="badge b-ok">Enviado</span>' : '<span class="badge b-mute">No se envió</span> <span class="muted">(llegó tarde, es un corto con los cortos apagados, o falló)</span>') : ''}
          ${dato('Estado', `<span class="badge ${c}">${esc(t)}</span>` + (p.estado === 'moved' && p.destinoUrl ? ` <a href="${esc(p.destinoUrl)}" target="_blank" rel="noopener">${esc(p.destinoUrl)}</a>` : ''))}
          ${'reportes' in p ? dato('Reportes', p.reportes ? `<span class="badge b-warn">${esc(plural(p.reportes, 'vez', 'veces'))}</span> como enlace roto` : 'Ninguno') : ''}
          ${dato('ID del video', `<span class="mono">${esc(p.videoId)}</span>`)}
          ${p.cifrasEn ? dato('Cifras', 'de YouTube, ' + esc(relativo(p.cifrasEn))) : ''}
        </dl>
        ${p.descripcion ? `<details class="descripcion"><summary>Descripción en YouTube</summary><p>${esc(p.descripcion)}</p></details>` : ''}
      </div>`, [
      { texto: 'Mover', alPulsar: () => { setTimeout(() => abrirMover(videoId), 0); } },
      { texto: 'Avisar que se retiró', alPulsar: () => { estado.avisoPreset = { tipo: 'video_retirado', videoId }; setTimeout(() => ir('avisos'), 0); } },
      ...(enlace ? [{ texto: 'Abrir en ' + (p.estado === 'moved' ? 'su enlace nuevo' : 'YouTube'), alPulsar: () => { window.open(enlace, '_blank', 'noopener'); return false; } }] : []),
      { texto: 'Cerrar', tipo: 'primary' }
    ]);
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
  // Recomendaciones de canales
  // ---------------------------------------------------------------------------
  // Canales de YouTube que la gente propone para el directorio. Empieza
  // apagado para todos, administradores incluidos: aquí se decide quién
  // puede recomendar y se revisa lo que llega. Las del mismo canal (o con el
  // mismo texto, si no se supo el canal) se ven juntas y se revisan juntas.
  const ALCANCES = {
    apagado: ['Nadie', 'La app no enseña la sección a nadie, tampoco a los administradores.'],
    admins: ['Solo administradores', 'Para probarlo antes de abrirlo: lo ven las cuentas con acceso a este panel.'],
    elegidos: ['Administradores y cuentas elegidas', 'Además de los administradores, las cuentas que elijas una por una desde su ficha en Usuarios.'],
    todos: ['Todos', 'Cualquier persona que use la app, también las cuentas de invitado.']
  };
  const ESTADOS_REC = {
    pendiente: ['Pendiente', 'b-warn'],
    agregado: ['Agregado', 'b-ok'],
    descartado: ['Descartado', 'b-mute']
  };
  const FILTROS_REC = { pendientes: 'Pendientes', revisadas: 'Revisadas', todas: 'Todas' };

  const quienRecomienda = (r) => r.email || (r.proveedor === 'anonimo' || !r.proveedor ? 'Invitado ' + String(r.usuarioId).slice(0, 8) : 'Cuenta ' + String(r.usuarioId).slice(0, 8));

  /** Junta las del mismo canal; si no se supo el canal, las del mismo texto. */
  function agruparRecs(lista) {
    const grupos = new Map();
    lista.forEach((r) => {
      const clave = r.channelId ? 'c:' + r.channelId : 't:' + String(r.texto || '').trim().toLowerCase().replace(/\s+/g, ' ').replace(/^@/, '');
      if (!grupos.has(clave)) grupos.set(clave, { clave, items: [] });
      grupos.get(clave).items.push(r);
    });
    return Array.from(grupos.values()).map((g) => {
      const conDatos = g.items.find((r) => r.titulo) || g.items[0];
      const pendientes = g.items.filter((r) => r.estado === 'pendiente');
      return Object.assign(g, {
        channelId: conDatos.channelId, titulo: conDatos.titulo, handle: conDatos.handle,
        fotoUrl: conDatos.fotoUrl, texto: conDatos.texto,
        enDirectorio: g.items.some((r) => r.enDirectorio),
        estado: pendientes.length ? 'pendiente' : g.items[0].estado,
        pendientes,
        ultima: g.items[0].creadoEn
      });
    });
  }

  function enlaceDeRec(g) {
    if (g.handle) return 'https://www.youtube.com/@' + g.handle.replace(/^@/, '');
    if (g.channelId) return 'https://www.youtube.com/channel/' + g.channelId;
    if (/^https?:\/\//i.test(g.texto || '')) return g.texto;
    return null;
  }

  function filaRec(g) {
    const enlace = enlaceDeRec(g);
    const [et, ec] = ESTADOS_REC[g.estado] || [g.estado, 'b-mute'];
    const ids = esc(g.items.map((r) => r.id).join(','));
    const nombre = g.titulo || g.texto;
    const quienes = Array.from(new Set(g.items.map(quienRecomienda)));
    const motivos = g.items.filter((r) => r.motivo);
    const MAX_MOTIVOS = 3;

    let acciones = '';
    if (g.estado === 'pendiente') {
      if (g.channelId && !g.enDirectorio) acciones += `<button class="btn sm primary" data-accion="rec-agregar" data-ids="${ids}" data-canal="${esc(g.channelId)}">Agregar al directorio</button>`;
      acciones += `<button class="btn sm" data-accion="rec-estado" data-ids="${ids}" data-valor="agregado" title="Ya lo agregaste por tu cuenta o ya estaba">Ya está</button>`;
      acciones += `<button class="btn sm" data-accion="rec-estado" data-ids="${ids}" data-valor="descartado">Descartar</button>`;
    } else {
      acciones += `<button class="btn sm" data-accion="rec-estado" data-ids="${ids}" data-valor="pendiente">Volver a pendiente</button>`;
    }
    acciones += `<button class="btn sm danger" data-accion="rec-borrar" data-ids="${ids}" data-nombre="${esc(nombre)}">Borrar</button>`;

    return `<tr>
      <td><div class="rec-canal">
        ${g.fotoUrl ? `<img src="${esc(g.fotoUrl)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : '<span class="rec-sin-foto" aria-hidden="true">?</span>'}
        <div style="min-width:0">
          <div class="clip"><b>${esc(nombre)}</b></div>
          ${g.titulo && g.texto !== g.titulo ? `<div class="muted clip" style="font-size:12.5px" title="${esc(g.texto)}">Escribió: ${esc(g.texto)}</div>` : ''}
          ${g.channelId ? `<div class="mono muted" style="font-size:12px">${esc(g.handle ? '@' + g.handle : g.channelId)}</div>` : '<div class="hint">No se identificó el canal: búscalo a mano.</div>'}
          ${enlace ? `<a href="${esc(enlace)}" target="_blank" rel="noopener" style="font-size:13px">Abrir en YouTube</a>` : ''}
        </div></div></td>
      <td>
        <div class="rec-estado"><span class="badge ${ec}">${esc(et)}</span>${g.enDirectorio ? '<span class="badge b-info">Ya en el directorio</span>' : ''}</div>
        <div><b>${esc(plural(g.items.length, 'vez', 'veces'))}</b> <span class="muted" style="font-size:12.5px" title="${esc(fmtFecha(g.ultima))}">· la última ${esc(relativo(g.ultima))}</span></div>
        <div class="muted clip rec-quien" style="font-size:12.5px" title="${esc(quienes.join(', '))}">${esc(quienes.slice(0, 2).join(', ') + (quienes.length > 2 ? ' y ' + (quienes.length - 2) + ' más' : ''))}</div>
      </td>
      <td>${motivos.length ? '<ul class="rec-motivos">' + motivos.slice(0, MAX_MOTIVOS).map((r) => `<li>«${esc(r.motivo)}» <span class="muted">— ${esc(quienRecomienda(r))}</span></li>`).join('') + (motivos.length > MAX_MOTIVOS ? `<li class="muted">y ${num(motivos.length - MAX_MOTIVOS)} más</li>` : '') + '</ul>' : '<span class="muted">—</span>'}</td>
      <td class="acciones"><div class="rec-acciones">${acciones}</div></td>
    </tr>`;
  }

  function tarjetaAlcance(alcance) {
    const encendido = alcance && alcance !== 'apagado';
    return `<section class="panel"><div class="panel-head"><h2>Quién puede recomendar</h2>
        <span class="badge ${encendido ? 'b-ok' : 'b-mute'}">${encendido ? 'Encendido' : 'Apagado'}</span></div>
      <div class="panel-body"><div class="opciones-alcance" role="radiogroup" aria-label="Quién puede recomendar canales">
        ${Object.entries(ALCANCES).map(([k, [t, d]]) => `<label class="check"><input type="radio" name="alcanceRec" data-accion="alcance-recs" data-valor="${k}" ${alcance === k ? 'checked' : ''}><span><b>${esc(t)}</b><span class="hint" style="display:block">${esc(d)}</span></span></label>`).join('')}
      </div>
      <p class="hint" style="margin:12px 0 0;font-size:13.5px">La app enseña «Recomendar un canal» en Ajustes a quien pueda recomendar. El cambio vale la próxima vez que cada persona abra la app. Solo funciona en las versiones de la app que traen la sección.</p>
      </div></section>`;
  }

  function tarjetaElegidas(d) {
    const cuenta = (u) => u.email || 'Invitado ' + String(u.id).slice(0, 8);
    const nota = d.alcance === 'elegidos' ? ''
      : `<p class="hint" style="margin:0 0 10px">Ahora mismo estas cuentas ${d.alcance === 'todos' ? 'pueden recomendar como todas las demás' : 'no pueden recomendar'}: la elección solo cuenta con «Administradores y cuentas elegidas».</p>`;
    return `<section class="panel"><div class="panel-head"><h2>Cuentas elegidas</h2><span class="muted" style="font-size:13px">${plural(d.elegidas.length, 'cuenta', 'cuentas')}</span></div>
      <div class="panel-body">${nota}
        ${d.elegidas.length ? '<ul class="lista-simple">' + d.elegidas.map((u) => `<li><span class="clip"><b>${esc(cuenta(u))}</b>${u.esAdmin ? ' <span class="badge b-warn">Admin</span>' : ''} <span class="muted" style="font-size:12.5px">· abrió la app ${esc(relativo(u.vistoEn))}</span></span><button class="btn sm danger" data-accion="recomendar-usuario" data-desde="lista" data-id="${esc(u.id)}" data-valor="false" data-nombre="${esc(cuenta(u))}">Quitar</button></li>`).join('') + '</ul>' : '<p class="hint" style="margin:0">Ninguna todavía.</p>'}
        <p class="hint" style="margin:10px 0 0">Para elegir una cuenta, búscala en <button class="link" data-ir="usuarios">Usuarios</button>, ábrela con «Ver» y pulsa «Dejar que recomiende canales».</p>
      </div></section>`;
  }

  async function vistaRecomendaciones() {
    let d;
    try {
      d = await api('/api/admin/recomendaciones');
    } catch (e) {
      if (e.estado === 404) {
        main.innerHTML = '<div class="head"><div><h1>Recomendaciones</h1></div></div><div class="panel"><div class="empty">Este servidor todavía no tiene las recomendaciones de canales. Actualízalo para usarlas.</div></div>';
        return;
      }
      throw e;
    }
    if (estado.vista !== 'recomendaciones') return;

    const filtro = estado.filtroRecs;
    const grupos = agruparRecs(d.recomendaciones);
    const visibles = grupos.filter((g) => filtro === 'todas' || (filtro === 'pendientes' ? g.estado === 'pendiente' : g.estado !== 'pendiente'))
      // Primero lo que más gente pidió; a igualdad, lo más reciente.
      .sort((a, b) => (b.items.length - a.items.length) || (new Date(b.ultima) - new Date(a.ultima)));
    const VACIO = {
      pendientes: d.alcance === 'apagado' ? 'No hay nada pendiente. Mientras esté apagado no llegan recomendaciones.' : 'No hay recomendaciones pendientes.',
      revisadas: 'Todavía no has revisado ninguna.',
      todas: 'Todavía no ha llegado ninguna recomendación.'
    };

    main.innerHTML = `
      <div class="head"><div><h1>Recomendaciones</h1><p class="sub">Canales de YouTube que la gente propone para el directorio. Empieza apagado para todos: elige aquí quién puede recomendar. Cada persona puede mandar 5 al día.</p></div></div>
      <div class="stack">
        <div class="dos">
          ${tarjetaAlcance(d.alcance)}
          ${tarjetaElegidas(d)}
        </div>
        <section class="panel">
          <div class="panel-head"><div class="toolbar">${Object.entries(FILTROS_REC).map(([k, v]) => `<button class="btn sm ${k === filtro ? 'primary' : ''}" data-accion="filtro-recs" data-valor="${k}" aria-pressed="${k === filtro}">${v}${k === 'pendientes' && d.pendientes ? ' · ' + num(grupos.filter((g) => g.estado === 'pendiente').length) : ''}</button>`).join('')}</div>
            <span class="muted" style="font-size:13px">${plural(d.recomendaciones.length, 'recomendación', 'recomendaciones')} en total</span></div>
          <div class="tablewrap"><table><thead><tr><th>Canal</th><th>Recomendado</th><th>Por qué</th><th></th></tr></thead><tbody>
          ${visibles.length ? visibles.map(filaRec).join('') : `<tr><td colspan="4"><div class="empty">${esc(VACIO[filtro])}</div></td></tr>`}
          </tbody></table></div>
        </section>
      </div>`;
  }

  /** Bloque de la ficha de una cuenta: elegirla o quitarla para recomendar. */
  function fichaRecomendar(u) {
    const nombre = esc(u.email || 'esta cuenta de invitado');
    return u.recomendarHabilitado
      ? `<div><button class="btn danger" data-accion="recomendar-usuario" data-id="${esc(u.id)}" data-valor="false" data-nombre="${nombre}">Ya no dejar que recomiende canales</button>
          <p class="hint" style="margin:6px 0 0">Está entre las cuentas elegidas de <button class="link" data-ir="recomendaciones">Recomendaciones</button>.</p></div>`
      : `<div><button class="btn" data-accion="recomendar-usuario" data-id="${esc(u.id)}" data-valor="true" data-nombre="${nombre}">Dejar que recomiende canales</button>
          <p class="hint" style="margin:6px 0 0">Verá «Recomendar un canal» en Ajustes de la app mientras en Recomendaciones esté elegido «Administradores y cuentas elegidas».</p></div>`;
  }

  // ---------------------------------------------------------------------------
  // Editores: agregar al directorio desde la app
  // ---------------------------------------------------------------------------
  // Las cuentas autorizadas aquí dan de alta creadores, medios y canales desde
  // su teléfono, sin pasar por el panel ni esperar aprobación: sale en el acto.
  // Por eso esta sección es sobre todo un historial, para revisar después qué
  // entró y quién lo metió. Empieza sin nadie autorizado, tampoco los admins.
  const TIPOS_ALTA = { creador: ['Creador', 'b-ok'], medio: ['Medio', 'b-info'], canal: ['Canal', 'b-warn'] };
  const ACCION_ALTA = { creador: 'editar-creador', medio: 'editar-productora', canal: 'editar-canal' };

  async function vistaEditores() {
    let d;
    try {
      // Frescos: lo agregado desde la app no está en lo que el panel tenía
      // guardado, y «Revisar» abre su ficha desde ahí.
      [d] = await Promise.all([api('/api/admin/editores'), cargarCreadores(true), cargarProductoras(true)]);
    } catch (e) {
      if (e.estado === 404) {
        main.innerHTML = '<div class="head"><div><h1>Editores</h1></div></div><div class="panel"><div class="empty">Este servidor todavía no permite agregar desde la app. Actualízalo para usarlo.</div></div>';
        return;
      }
      throw e;
    }
    if (estado.vista !== 'editores') return;

    const cuenta = (u) => u.email || 'Cuenta ' + String(u.id).slice(0, 8);
    const hoy = d.altas.filter((a) => Date.now() - new Date(a.creadoEn) < 864e5).length;

    main.innerHTML = `
      <div class="head"><div><h1>Editores</h1><p class="sub">Cuentas que pueden agregar creadores, medios y canales de YouTube desde la app. Lo que agregan sale en el directorio en el acto, sin pasar por aquí: revísalo abajo. Cada cuenta puede hacer hasta 20 altas al día.</p></div></div>
      <div class="stack">
        <section class="panel"><div class="panel-head"><h2>Cuentas autorizadas</h2><span class="muted" style="font-size:13px">${plural(d.editores.length, 'cuenta', 'cuentas')}</span></div>
          <div class="panel-body">
            ${d.editores.length ? '<ul class="lista-simple">' + d.editores.map((u) => `<li><span class="clip"><b>${esc(cuenta(u))}</b>${u.esAdmin ? ' <span class="badge b-warn">Admin</span>' : ''} <span class="muted" style="font-size:12.5px">· ${esc(plural(u.altas, 'alta', 'altas'))} · abrió la app ${esc(relativo(u.vistoEn))}</span></span><button class="btn sm danger" data-accion="editor-usuario" data-desde="lista" data-id="${esc(u.id)}" data-valor="false" data-nombre="${esc(cuenta(u))}">Quitar</button></li>`).join('') + '</ul>' : '<p class="hint" style="margin:0">Nadie todavía. Mientras no autorices a nadie, la app no enseña esta opción a ninguna cuenta, tampoco a los administradores.</p>'}
            <p class="hint" style="margin:10px 0 0">Para autorizar una cuenta, búscala en <button class="link" data-ir="usuarios">Usuarios</button>, ábrela con «Ver» y pulsa «Dejar que agregue desde la app». Tiene que ser una cuenta guardada con Google o Apple.</p>
          </div></section>
        <section class="panel">
          <div class="panel-head"><h2>Lo que se agregó desde la app</h2><span class="muted" style="font-size:13px">${plural(d.altas.length, 'alta', 'altas')}${hoy ? ' · ' + num(hoy) + ' en 24 h' : ''}</span></div>
          <div class="tablewrap"><table><thead><tr><th>Qué</th><th>Nombre</th><th>Quién</th><th>Cuándo</th><th></th></tr></thead><tbody>
          ${d.altas.length ? d.altas.map((a) => {
            const [t, c] = TIPOS_ALTA[a.tipo] || [a.tipo, 'b-mute'];
            return `<tr>
              <td><span class="badge ${c}">${esc(t)}</span></td>
              <td><div class="clip"><b>${esc(a.nombre)}</b></div>${a.channelId ? `<a class="mono" style="font-size:12px" href="https://www.youtube.com/channel/${esc(a.channelId)}" target="_blank" rel="noopener">${esc(a.channelId)}</a>` : ''}</td>
              <td><div class="clip">${esc(a.autor || 'Cuenta borrada')}</div></td>
              <td class="num" title="${esc(fmtFecha(a.creadoEn))}">${esc(relativo(a.creadoEn))}</td>
              <td class="acciones">${a.existe ? `<button class="btn sm" data-accion="${ACCION_ALTA[a.tipo]}" data-id="${esc(a.objetoId)}">Revisar</button>` : '<span class="badge b-mute">Ya no está</span>'}</td>
            </tr>`;
          }).join('') : '<tr><td colspan="5"><div class="empty">Todavía no se ha agregado nada desde la app.</div></td></tr>'}
          </tbody></table></div>
        </section>
      </div>`;
  }

  /** Bloque de la ficha de una cuenta: dejarla o no agregar al directorio desde la app. */
  function fichaEditor(u) {
    const nombre = esc(u.email || 'esta cuenta');
    if (u.editorHabilitado) {
      return `<div><button class="btn danger" data-accion="editor-usuario" data-id="${esc(u.id)}" data-valor="false" data-nombre="${nombre}">Ya no dejar que agregue desde la app</button>
          <p class="hint" style="margin:6px 0 0">Puede agregar creadores, medios y canales desde la app. Lo que agregó está en <button class="link" data-ir="editores">Editores</button>.</p></div>`;
    }
    if (u.proveedor === 'anonimo') {
      return '<div><p class="hint" style="margin:0">Para dejar que agregue al directorio desde la app, primero tiene que guardar su cuenta con Google o Apple.</p></div>';
    }
    return `<div><button class="btn" data-accion="editor-usuario" data-id="${esc(u.id)}" data-valor="true" data-nombre="${nombre}">Dejar que agregue desde la app</button>
          <p class="hint" style="margin:6px 0 0">Verá «Agregar al directorio» en Ajustes de la app. Lo que agregue sale en el acto, sin aprobación.</p></div>`;
  }

  // ---------------------------------------------------------------------------
  // Stickers
  // ---------------------------------------------------------------------------
  // Paquetes de stickers de los creadores. La app los baja del servidor y los
  // manda a WhatsApp como paquete, o uno por uno a Telegram, Signal,
  // Messenger... Empiezan apagados para todos, administradores incluidos:
  // quién los ve se decide aquí, con el mismo esquema que las recomendaciones.
  const ALCANCES_STICKERS = {
    apagado: ['Nadie', 'La app no enseña el apartado Stickers a nadie, tampoco a los administradores.'],
    admins: ['Solo administradores', 'Para probarlos con las cuentas que tienen acceso a este panel.'],
    elegidos: ['Administradores y cuentas elegidas', 'Además de los administradores, las cuentas que elijas una por una desde su ficha en Usuarios.'],
    todos: ['Todos', 'Cualquier persona que use la app.']
  };

  // Lo que pide WhatsApp. El servidor lo vuelve a revisar al recibirlo.
  const STK = { lado: 512, ladoIcono: 96, fijo: 100 * 1024, animado: 500 * 1024, icono: 50 * 1024, maximo: 30 };

  // Las imágenes piden sesión, así que no se pueden poner en un <img> tal
  // cual: se bajan con el token y se enseñan desde la memoria del navegador.
  // La dirección lleva la versión del paquete, así que una copia nunca se queda vieja.
  const imagenesConSesion = new Map();

  async function blobConSesion(ruta) {
    if (imagenesConSesion.has(ruta)) return imagenesConSesion.get(ruta).blob;
    const r = await fetch(BASE + ruta, { headers: { Authorization: 'Bearer ' + ((sesion && sesion.token) || ''), 'X-App-Plataforma': 'panel' } });
    if (!r.ok) throw new ErrorApi('No se pudo bajar la imagen.', r.status);
    const blob = await r.blob();
    imagenesConSesion.set(ruta, { blob, url: URL.createObjectURL(blob) });
    return blob;
  }

  async function pintarImagenesConSesion(raiz) {
    await Promise.all($$('img[data-src]', raiz).map(async (img) => {
      const ruta = img.dataset.src;
      try {
        await blobConSesion(ruta);
        img.src = imagenesConSesion.get(ruta).url;
        img.removeAttribute('data-src');
      } catch (e) { img.alt = 'No se pudo cargar'; }
    }));
  }

  function aBase64(blob) {
    return new Promise((ok, mal) => {
      const r = new FileReader();
      r.onload = () => ok(String(r.result).split(',')[1] || '');
      r.onerror = () => mal(new Error('No se pudo leer el archivo.'));
      r.readAsDataURL(blob);
    });
  }

  // ¿Es un WebP animado? Lo dice la marca de animación de su cabecera VP8X.
  async function esWebpAnimado(archivo) {
    const b = new Uint8Array(await archivo.slice(0, 32).arrayBuffer());
    const txt = (i) => String.fromCharCode(b[i], b[i + 1], b[i + 2], b[i + 3]);
    return b.length >= 21 && txt(0) === 'RIFF' && txt(8) === 'WEBP' && txt(12) === 'VP8X' && (b[20] & 0x02) !== 0;
  }

  async function abrirImagen(blob) {
    try { return await createImageBitmap(blob); } catch (e) {
      throw new Error('No se pudo abrir «' + (blob.name || 'la imagen') + '». Usa PNG, JPG o WebP.');
    }
  }

  /**
   * Dibuja la imagen centrada en un cuadrado de `lado`, sin recortarla y con
   * fondo transparente, y la guarda en `tipo` bajando la calidad hasta que
   * pese menos de `pesoMaximo`.
   */
  async function cuadrar(blob, lado, tipo, pesoMaximo) {
    const img = await abrirImagen(blob);
    const lienzo = document.createElement('canvas');
    lienzo.width = lienzo.height = lado;
    const ctx = lienzo.getContext('2d');
    const escala = Math.min(lado / img.width, lado / img.height);
    const w = Math.round(img.width * escala), h = Math.round(img.height * escala);
    ctx.imageSmoothingQuality = 'high';
    ctx.drawImage(img, Math.round((lado - w) / 2), Math.round((lado - h) / 2), w, h);
    if (img.close) img.close();
    const calidades = tipo === 'image/png' ? [undefined] : [0.92, 0.85, 0.75, 0.65, 0.55, 0.45, 0.35];
    for (const calidad of calidades) {
      const salida = await new Promise((ok) => lienzo.toBlob(ok, tipo, calidad));
      // Safari no sabe crear WebP: devuelve un PNG sin decir nada.
      if (!salida || salida.type !== tipo) throw new Error('Este navegador no sabe crear imágenes WebP. Usa Chrome, Edge o Firefox.');
      if (salida.size <= pesoMaximo) return salida;
    }
    throw new Error('No pudimos dejar «' + (blob.name || 'la imagen') + '» por debajo de ' + Math.round(pesoMaximo / 1024) + ' KB. Prueba con una imagen más sencilla.');
  }

  /** Deja un archivo listo para ser sticker: WebP de 512 × 512 que pese lo que permite WhatsApp. */
  async function prepararSticker(archivo) {
    if (await esWebpAnimado(archivo)) {
      // Una animación no se puede rehacer en el navegador: va tal cual y el
      // servidor dice si mide, pesa y dura lo que debe.
      if (archivo.size > STK.animado) throw new Error('«' + archivo.name + '» pesa ' + Math.round(archivo.size / 1024) + ' KB y un sticker animado no puede pasar de 500 KB.');
      return { blob: archivo, animado: true };
    }
    if (archivo.type === 'image/webp' && archivo.size <= STK.fijo) {
      const img = await abrirImagen(archivo);
      const exacto = img.width === STK.lado && img.height === STK.lado;
      if (img.close) img.close();
      if (exacto) return { blob: archivo, animado: false };
    }
    return { blob: await cuadrar(archivo, STK.lado, 'image/webp', STK.fijo), animado: false, gif: archivo.type === 'image/gif' };
  }

  /** El ícono de la bandeja de WhatsApp: 96 × 96, PNG (o WebP si el PNG pesa demasiado). */
  async function prepararIcono(blob) {
    const png = await cuadrar(blob, STK.ladoIcono, 'image/png', Infinity);
    return png.size <= STK.icono ? png : cuadrar(blob, STK.ladoIcono, 'image/webp', STK.icono);
  }

  async function vistaStickers() {
    let d;
    try {
      [d] = await Promise.all([api('/api/admin/stickers'), cargarCreadores()]);
    } catch (e) {
      if (e.estado === 404) {
        main.innerHTML = '<div class="head"><div><h1>Stickers</h1></div></div><div class="panel"><div class="empty">Este servidor todavía no tiene stickers. Actualízalo para usarlos.</div></div>';
        return;
      }
      throw e;
    }
    if (estado.vista !== 'stickers') return;
    estado.stickers = d;
    pintarStickers();
  }

  function pintarStickers() {
    const d = estado.stickers;
    const alcance = d.alcance || 'apagado';
    const encendido = alcance !== 'apagado';
    const cuenta = (u) => u.email || 'Cuenta de invitado ' + String(u.id).slice(0, 8);
    const creadores = (estado.creadores || []).slice().sort((a, b) => a.nombre.localeCompare(b.nombre, 'es'));
    const publicados = d.paquetes.filter((p) => p.publicado).length;

    main.innerHTML = `
      <div class="head"><div><h1>Stickers</h1><p class="sub">Paquetes de stickers de los creadores. La app los baja del servidor y la persona los agrega a WhatsApp o los manda uno por uno por Telegram, Signal, Messenger o cualquier otra app. No van dentro de la app: se guardan en el teléfono mientras la app esté instalada.</p></div></div>
      <div class="stack">
        <section class="panel"><div class="panel-head"><h2>Quién los ve</h2>
            <span class="badge ${encendido ? 'b-ok' : 'b-mute'}">${encendido ? esc(ALCANCES_STICKERS[alcance][0]) : 'Apagados'}</span></div>
          <div class="panel-body">
            <div class="opciones-alcance" role="radiogroup" aria-label="Quién ve los stickers">
              ${Object.entries(ALCANCES_STICKERS).map(([k, [ti, de]]) => `<label class="check"><input type="radio" name="alcanceStk" data-accion="alcance-stickers" data-valor="${k}" ${alcance === k ? 'checked' : ''}><span><b>${esc(ti)}</b><span class="hint" style="display:block">${esc(de)}</span></span></label>`).join('')}
            </div>
            <p class="reparto-titulo" style="margin:16px 0 8px">Cuentas elegidas <span class="muted">· ${plural(d.elegidas.length, 'cuenta', 'cuentas')}</span></p>
            ${d.elegidas.length ? '<ul class="lista-simple">' + d.elegidas.map((u) => `<li><span class="clip"><b>${esc(cuenta(u))}</b>${u.esAdmin ? ' <span class="badge b-warn">Admin</span>' : ''} <span class="muted" style="font-size:12.5px">· abrió la app ${esc(relativo(u.vistoEn))}</span></span><button class="btn sm danger" data-accion="stickers-usuario" data-desde="lista" data-id="${esc(u.id)}" data-valor="false" data-nombre="${esc(cuenta(u))}">Quitar</button></li>`).join('') + '</ul>' : '<p class="hint" style="margin:0">Ninguna todavía.</p>'}
            <p class="hint" style="margin:10px 0 0">Las cuentas elegidas solo cuentan con «Administradores y cuentas elegidas». Para elegir una, búscala en <button class="link" data-ir="usuarios">Usuarios</button>, ábrela con «Ver» y pulsa «Darle los stickers». El cambio vale la próxima vez que cada persona abra la app. Solo funciona en la app de Android 1.0.15 o posterior.</p>
          </div></section>

        <section class="panel"><div class="panel-head"><h2>Paquete nuevo</h2><span class="muted" style="font-size:13px">${plural(d.paquetes.length, 'paquete', 'paquetes')} · ${plural(publicados, 'publicado', 'publicados')}</span></div>
          <div class="panel-body">
            ${creadores.length ? `<div class="form">
              <div><label class="hint" for="stkCreador">Creador</label>
                <select class="input" id="stkCreador" style="margin-top:6px">${creadores.map((c) => `<option value="${esc(c.id)}">${esc(c.nombre)}${c.activo ? '' : ' (oculto)'}</option>`).join('')}</select></div>
              <div><label class="hint" for="stkNombre">Nombre del paquete <span class="opcional">si lo dejas vacío, el del creador</span></label>
                <input class="input" id="stkNombre" maxlength="128" style="margin-top:6px"></div>
            </div>
            <div class="toolbar" style="margin-top:12px"><button class="btn primary" data-accion="crear-paquete">Crear paquete</button></div>
            <p class="hint" style="margin:10px 0 0">Cada paquete lleva de 3 a 30 stickers, todos fijos o todos animados, y un ícono. Si un creador tiene más de 30, hazle otro paquete.</p>`
              : '<p class="hint" style="margin:0">Primero agrega creadores al directorio.</p>'}
          </div></section>

        ${d.paquetes.length ? d.paquetes.map(tarjetaPaquete).join('') : '<div class="panel"><div class="empty">Todavía no hay paquetes. Crea el primero arriba.</div></div>'}
      </div>`;
    pintarImagenesConSesion(main);
  }

  function tarjetaPaquete(p) {
    const n = p.stickers.length;
    const id = esc(p.id);
    const tipo = n ? (p.animado ? 'Animados' : 'Fijos') : 'Vacío';
    return `<section class="panel" id="paq-${id}">
      <div class="panel-head">
        <div style="min-width:0"><h2 style="margin:0" class="clip">${esc(p.nombre)}</h2>
          <span class="muted" style="font-size:13px">de <b>${esc(p.creador || 'un creador borrado')}</b></span>
          ${p.creadorActivo ? '' : ' <span class="badge b-warn">Creador oculto: no sale en la app</span>'}</div>
        <div class="chips">${p.publicado ? '<span class="badge b-ok">Publicado</span>' : '<span class="badge b-mute">Sin publicar</span>'}
          <span class="badge b-info">${tipo}</span><span class="badge b-mute">${n} / ${STK.maximo}</span></div>
      </div>
      <div class="panel-body stickers-cuerpo">
        <div class="stickers-icono">
          <div class="sticker-celda icono">${p.conIcono ? `<img data-src="/api/stickers/paquetes/${id}/icono?v=${p.version}" alt="Ícono del paquete">` : '<span class="hint">Sin ícono</span>'}</div>
          <div style="display:flex;flex-direction:column;gap:6px">
            <span class="hint">Ícono de la bandeja de WhatsApp (96 × 96)${p.iconoPeso ? ' · ' + Math.round(p.iconoPeso / 1024) + ' KB' : ''}</span>
            <span class="toolbar">
              <label class="btn sm">Subir ícono<input type="file" accept="image/png,image/webp,image/jpeg" data-subir="icono" data-id="${id}" hidden></label>
              ${n ? `<button class="btn sm" data-accion="icono-desde-sticker" data-id="${id}">Usar el primer sticker</button>` : ''}
            </span>
          </div>
        </div>
        ${p.falta.length ? `<div class="aviso-mal">${p.falta.map(esc).join(' ')}</div>` : ''}
        ${n ? `<ul class="stickers-rejilla">${p.stickers.map((s, i) => `<li class="sticker-celda">
            <img data-src="/api/stickers/${esc(s.id)}/imagen?v=${p.version}" alt="Sticker ${i + 1}" loading="lazy">
            <button class="link sticker-emojis" data-accion="emojis-sticker" data-id="${esc(s.id)}" data-emojis="${esc(s.emojis)}" title="Cambiar los emojis">${esc(s.emojis)}</button>
            <span class="sticker-pie">
              <button class="btn sm" data-accion="mover-sticker" data-paquete="${id}" data-id="${esc(s.id)}" data-dir="-1" ${i === 0 ? 'disabled' : ''} aria-label="Mover antes">←</button>
              <span class="hint">${Math.round(s.peso / 1024)} KB</span>
              <button class="btn sm" data-accion="mover-sticker" data-paquete="${id}" data-id="${esc(s.id)}" data-dir="1" ${i === n - 1 ? 'disabled' : ''} aria-label="Mover después">→</button>
              <button class="btn sm danger" data-accion="quitar-sticker" data-id="${esc(s.id)}" aria-label="Quitar este sticker">✕</button>
            </span></li>`).join('')}</ul>` : ''}
        ${n < STK.maximo ? `<div class="toolbar">
            <label class="btn ${n < 3 ? 'primary' : ''}">Agregar stickers<input type="file" multiple accept="image/webp,image/png,image/jpeg,image/gif" data-subir="stickers" data-id="${id}" hidden></label>
            <label class="hint" style="display:flex;gap:6px;align-items:center">Emojis de los que subas <input class="input" style="width:110px" id="emo-${id}" value="😀" maxlength="40"></label>
          </div>
          <p class="hint" style="margin:0">PNG, JPG o WebP: el panel los deja en 512 × 512 y en WebP de hasta 100 KB, sin recortarlos. Los animados súbelos ya en WebP animado de 512 × 512, hasta 500 KB y 10 segundos. Un GIF se sube como sticker fijo, con su primer cuadro. Tocando los emojis de cada sticker los cambias: WhatsApp los usa para sugerirlo al escribir.</p>` : ''}
        <div class="toolbar" style="justify-content:flex-end">
          <button class="btn sm" data-accion="renombrar-paquete" data-id="${id}" data-nombre="${esc(p.nombre)}">Renombrar</button>
          <button class="btn sm ${p.publicado ? '' : 'primary'}" data-accion="publicar-paquete" data-id="${id}" data-valor="${!p.publicado}" ${!p.publicado && p.falta.length ? 'disabled title="Antes complétalo"' : ''}>${p.publicado ? 'Despublicar' : 'Publicar'}</button>
          <button class="btn sm danger" data-accion="borrar-paquete" data-id="${id}" data-nombre="${esc(p.nombre)}">Borrar paquete</button>
        </div>
      </div></section>`;
  }

  /** Pone el paquete que devolvió el servidor en su sitio y repinta. */
  function paqueteActualizado(p) {
    const lista = estado.stickers.paquetes;
    const i = lista.findIndex((x) => x.id === p.id);
    if (i >= 0) lista[i] = p; else lista.push(p);
    if (estado.vista === 'stickers') pintarStickers();
  }

  async function subirIcono(paqueteId, blob) {
    const r = await api('/api/admin/stickers/paquetes/' + encodeURIComponent(paqueteId) + '/icono', { metodo: 'PUT', cuerpo: { datos: await aBase64(blob) } });
    paqueteActualizado(r.paquete);
    return r;
  }

  async function subirStickers(paqueteId, archivos) {
    const emojis = ($('#emo-' + paqueteId) || {}).value || '';
    let subidos = 0, primero = null, gif = false;
    const errores = [];
    for (const archivo of archivos) {
      try {
        const listo = await prepararSticker(archivo);
        const r = await api('/api/admin/stickers/paquetes/' + encodeURIComponent(paqueteId) + '/stickers',
          { metodo: 'POST', cuerpo: { datos: await aBase64(listo.blob), emojis } });
        paqueteActualizado(r.paquete);
        subidos++;
        primero = primero || listo.blob;
        gif = gif || !!listo.gif;
      } catch (e) {
        errores.push(archivo.name + ': ' + e.message);
        // Lleno: los que siguen tampoco caben.
        if (/30 stickers/.test(e.message)) break;
      }
    }
    // Sin ícono todavía: se hace solo con el primero que entró.
    const p = estado.stickers.paquetes.find((x) => x.id === paqueteId);
    if (primero && p && !p.conIcono) {
      try { await subirIcono(paqueteId, await prepararIcono(primero)); } catch (e) { errores.push('Ícono: ' + e.message); }
    }
    if (errores.length) {
      abrirModal(subidos ? 'Algunos no se subieron' : 'No se subió ninguno',
        (subidos ? '<p style="margin:0 0 10px">' + esc(plural(subidos, 'sticker subido', 'stickers subidos')) + '.</p>' : '')
        + '<ul class="borrado-lista">' + errores.map((m) => '<li>' + esc(m) + '</li>').join('') + '</ul>', [{ texto: 'Entendido' }]);
    } else if (subidos) {
      toast(plural(subidos, 'sticker subido', 'stickers subidos') + (gif ? '. Los GIF quedaron como stickers fijos.' : '.'));
    }
  }

  // Los archivos que se eligen en un paquete: el ícono o los stickers.
  main.addEventListener('change', async (e) => {
    const campo = e.target.closest('input[type=file][data-subir]');
    if (!campo || !campo.files || !campo.files.length) return;
    const archivos = Array.from(campo.files);
    const { id, subir } = campo.dataset;
    const boton = campo.closest('label');
    if (boton) boton.classList.add('ocupado');
    campo.disabled = true;
    try {
      if (subir === 'icono') {
        const r = await subirIcono(id, await prepararIcono(archivos[0]));
        toast(r.mensaje || 'Ícono guardado.');
      } else {
        toast('Subiendo ' + plural(archivos.length, 'sticker', 'stickers') + '…');
        await subirStickers(id, archivos);
      }
    } catch (err) {
      if (err.estado !== 401 && err.estado !== 403) toast(err.message, true);
    } finally {
      if (campo.isConnected) { campo.disabled = false; campo.value = ''; }
      if (boton && boton.isConnected) boton.classList.remove('ocupado');
    }
  });

  /** Bloque de la ficha de una cuenta: darle o no los stickers. */
  function fichaStickers(u) {
    const nombre = esc(u.email || 'esta cuenta de invitado');
    return u.stickersHabilitado
      ? `<div><button class="btn danger" data-accion="stickers-usuario" data-id="${esc(u.id)}" data-valor="false" data-nombre="${nombre}">Quitarle los stickers</button>
          <p class="hint" style="margin:6px 0 0">Está entre las cuentas elegidas para los stickers (sección <button class="link" data-ir="stickers">Stickers</button>).</p></div>`
      : `<div><button class="btn" data-accion="stickers-usuario" data-id="${esc(u.id)}" data-valor="true" data-nombre="${nombre}">Darle los stickers</button>
          <p class="hint" style="margin:6px 0 0">Verá el apartado Stickers en la app mientras en Stickers esté elegido «Administradores y cuentas elegidas».</p></div>`;
  }

  // ---------------------------------------------------------------------------
  // Avisos del equipo
  // ---------------------------------------------------------------------------
  // Lo que el equipo manda a mano: un medio o un canal nuevo, un video
  // retirado, una cuenta bloqueada o un mensaje. El de un creador nuevo no
  // está aquí: se manda desde su ficha, al crearlo.
  const TIPOS_AVISO = {
    nuevo_medio: 'Medio nuevo',
    nuevo_canal: 'Canal nuevo',
    video_retirado: 'Video retirado',
    cuenta_bloqueada: 'Cuenta bloqueada',
    mensaje: 'Mensaje'
  };
  // Para el historial, que también enseña los de creador nuevo.
  const NOMBRES_AVISO = Object.assign({ nuevo_creador: 'Creador nuevo' }, TIPOS_AVISO);
  const PARA_AVISO = { todos: 'Todos', creador: 'Seguidores de un creador', productora: 'Seguidores de un medio', canal: 'Seguidores de un canal' };
  const MAX_TITULO_AVISO = 80;
  const MAX_CUERPO_AVISO = 300;

  // Lo que escribe el panel para un creador nuevo. También lo usa su ficha.
  const textoCreadorNuevo = (nombre) => ({
    titulo: 'Creador nuevo: ' + nombre,
    cuerpo: nombre + ' ya está en el directorio. Toca para conocerlo y seguirlo.'
  });

  const nombreDeCanal = (k) => k.titulo || k.nombre || (k.handle ? '@' + k.handle.replace(/^@/, '') : 'Canal');

  async function vistaAvisos() {
    const [historial, creadores, productoras, pubs] = await Promise.all([
      api('/api/admin/avisos?limite=100'),
      cargarCreadores(true),
      cargarProductoras(true),
      api('/api/admin/publicaciones?limite=200').catch(() => [])
    ]);
    estado.publicaciones = pubs;

    // Si se llegó desde la ficha de un video, el formulario ya viene armado.
    const previo = estado.avisoPreset || {};
    estado.avisoPreset = null;
    estado.aviso = {
      tipo: previo.tipo || 'mensaje',
      para: previo.para || 'todos',
      id: previo.id || '',
      videoId: previo.videoId || '',
      plataforma: 'youtube',
      tocado: { titulo: false, cuerpo: false }
    };

    const sinAnunciar = creadores.filter((c) => c.activo && !c.anunciadoEn).length;

    main.innerHTML = `
      <div class="head"><div><h1>Avisos</h1><p class="sub">Notificaciones que manda el equipo a mano. Los videos nuevos, los directos y los cortos se avisan solos; esto es para todo lo demás. Al tocar el aviso, la app abre la ficha de quien se habla, o el enlace si le pones uno.</p></div></div>
      <div class="stack">
        <section class="panel"><div class="panel-head"><h2>Mandar un aviso</h2></div><div class="panel-body">
          <div class="push">
            <div class="stack" style="gap:14px">
              <div class="form">
                <label class="f">Qué avisas<select class="input" id="avTipo">${Object.entries(TIPOS_AVISO).map(([k, v]) => `<option value="${k}" ${estado.aviso.tipo === k ? 'selected' : ''}>${esc(v)}</option>`).join('')}</select></label>
                <div class="f" id="avParaCaja"></div>
                <div class="full" id="avCampos"></div>
                <label class="f full">Título <span class="opcional" id="avCuentaTitulo"></span><input class="input" id="avTitulo" maxlength="${MAX_TITULO_AVISO}"></label>
                <label class="f full">Texto <span class="opcional" id="avCuentaCuerpo"></span><textarea class="input" id="avCuerpo" rows="3" maxlength="${MAX_CUERPO_AVISO}"></textarea></label>
                <label class="f full" id="avEnlaceCaja">Enlace <span class="opcional">opcional: tocar el aviso lo abre</span><input class="input" id="avEnlace" placeholder="https://"></label>
                <label class="check full" id="avRetirarCaja" hidden><input type="checkbox" id="avRetirar" checked> Quitarlo también de la app</label>
              </div>
              <div class="alcance" id="avAlcance"></div>
              <p class="hint" style="margin:0">Los avisos para todos solo les llegan a quienes tengan la versión de la app que trae esta función; las anteriores no los reciben. Los de seguidores llegan a todas.</p>
              <div><button class="btn primary" data-accion="mandar-aviso">Mandar aviso</button></div>
              <p class="hint" style="margin:0">¿Un creador nuevo? Se avisa desde su ficha al crearlo.${sinAnunciar ? ' Hay ' + esc(plural(sinAnunciar, 'creador visible', 'creadores visibles')) + ' de los que nunca se avisó: abre su ficha en <button class="link" data-ir="creadores">Creadores</button> y marca «Avisar a todos».' : ''}</p>
            </div>
            <div class="telefono" aria-hidden="true"><div class="pantalla">
              <div class="reloj">${esc(new Date().toLocaleTimeString('es-MX', { hour: '2-digit', minute: '2-digit', hour12: false }))}</div>
              <div class="fecha">${esc(new Date().toLocaleDateString('es-MX', { weekday: 'long', day: 'numeric', month: 'long' }))}</div>
              <div class="notif"><div class="top"><span class="ico">V</span>Voces Izquierda · ahora</div><div class="t" id="avPrevTitulo"></div><div class="b" id="avPrevCuerpo"></div></div>
            </div></div>
          </div>
        </div></section>
        <section class="panel"><div class="panel-head"><h2>Historial</h2></div>
          <div class="tablewrap"><table><thead><tr><th>Cuándo</th><th>Tipo</th><th>Aviso</th><th>A quién</th><th>Estado</th></tr></thead><tbody>
          ${historial.length ? historial.map((a) => `<tr>
            <td class="num" title="${esc(fmtFecha(a.creadoEn))}">${esc(relativo(a.creadoEn))}${a.autor ? `<div class="muted" style="font-size:12.5px">${esc(a.autor)}</div>` : ''}</td>
            <td><span class="badge b-mute">${esc(NOMBRES_AVISO[a.tipo] || a.tipo)}</span></td>
            <td><div class="clip" style="max-width:360px"><b>${esc(a.titulo)}</b></div><div class="muted clip" style="max-width:360px;font-size:13px" title="${esc(a.cuerpo)}">${esc(a.cuerpo)}</div>${a.enlace ? `<a class="mono" style="font-size:12.5px" href="${esc(a.enlace)}" target="_blank" rel="noopener">${esc(a.enlace)}</a>` : ''}</td>
            <td>${esc(a.destino)}</td>
            <td>${a.enviado ? '<span class="badge b-ok">Enviado</span>' : `<span class="badge b-bad" title="${esc(a.error || '')}">No salió</span><div class="muted clip" style="max-width:220px;font-size:12.5px" title="${esc(a.error || '')}">${esc(a.error || '')}</div>`}</td>
          </tr>`).join('') : '<tr><td colspan="5"><div class="empty">Todavía no se ha mandado ningún aviso.</div></td></tr>'}
          </tbody></table></div>
        </section>
      </div>`;

    $('#avTipo').addEventListener('change', (e) => {
      estado.aviso.tipo = e.target.value;
      estado.aviso.id = '';
      estado.aviso.videoId = '';
      estado.aviso.para = e.target.value === 'cuenta_bloqueada' ? 'creador' : 'todos';
      estado.aviso.tocado = { titulo: false, cuerpo: false };
      pintarCamposDeAviso();
    });
    $('#avTitulo').addEventListener('input', () => { estado.aviso.tocado.titulo = true; previaDeAviso(); });
    $('#avCuerpo').addEventListener('input', () => { estado.aviso.tocado.cuerpo = true; previaDeAviso(); });
    pintarCamposDeAviso();
  }

  /** Las opciones de "a quién" o "de quién", según lo que se elija. */
  function opcionesDeAviso(para) {
    if (para === 'creador') {
      return (estado.creadores || []).slice().sort((a, b) => a.nombre.localeCompare(b.nombre, 'es'))
        .map((c) => ({ id: c.id, texto: c.nombre + (c.activo ? '' : ' (oculto)') }));
    }
    if (para === 'productora') {
      return (estado.productoras || []).slice().sort((a, b) => a.nombre.localeCompare(b.nombre, 'es'))
        .map((p) => ({ id: p.id, texto: p.nombre + (p.activo ? '' : ' (oculto)') }));
    }
    if (para === 'canal') {
      return vigilados().filter((v) => v.canal.id)
        .map((v) => ({ id: v.canal.id, texto: nombreDeCanal(v.canal) + ' · ' + v.dueno.nombre + (v.dueno.activo ? '' : ' (oculto)') }))
        .sort((a, b) => a.texto.localeCompare(b.texto, 'es'));
    }
    return [];
  }

  const selectorDeAviso = (id, opciones, elegido, vacio) => `<select class="input" id="${id}">
      <option value="">${esc(vacio)}</option>
      ${opciones.map((o) => `<option value="${esc(o.id)}" ${o.id === elegido ? 'selected' : ''}>${esc(o.texto)}</option>`).join('')}
    </select>`;

  /** Pinta los campos que dependen del tipo y vuelve a enganchar sus eventos. */
  function pintarCamposDeAviso() {
    const a = estado.aviso;
    const paraCaja = $('#avParaCaja');
    const campos = $('#avCampos');
    let paraHtml = '';
    let camposHtml = '';

    if (a.tipo === 'nuevo_medio') {
      camposHtml = `<label class="f">Medio${selectorDeAviso('avId', opcionesDeAviso('productora'), a.id, 'Elige el medio')}</label>`;
    } else if (a.tipo === 'nuevo_canal') {
      camposHtml = `<label class="f">Canal${selectorDeAviso('avId', opcionesDeAviso('canal'), a.id, 'Elige el canal')}</label>`;
    } else if (a.tipo === 'video_retirado') {
      const lista = (estado.publicaciones || []).filter((p) => p.estado !== 'removed' || p.videoId === a.videoId);
      if (a.videoId && !lista.some((p) => p.videoId === a.videoId)) lista.unshift({ videoId: a.videoId, titulo: a.videoId });
      camposHtml = `<label class="f full">Video<select class="input" id="avVideo">
          <option value="">Elige el video</option>
          ${lista.map((p) => `<option value="${esc(p.videoId)}" ${p.videoId === a.videoId ? 'selected' : ''}>${esc((p.titulo || p.videoId) + (p.creadorNombre ? ' · ' + p.creadorNombre : ''))}</option>`).join('')}
        </select></label>`;
    } else {
      // Cuenta bloqueada o mensaje: de quién se habla, o para quién es.
      const paras = a.tipo === 'cuenta_bloqueada' ? ['creador', 'productora', 'canal'] : ['todos', 'creador', 'productora', 'canal'];
      paraHtml = `<label class="f" style="margin:0">${a.tipo === 'cuenta_bloqueada' ? 'De quién es la cuenta' : 'Para quién'}<select class="input" id="avPara">${paras.map((k) => `<option value="${k}" ${a.para === k ? 'selected' : ''}>${esc(a.tipo === 'cuenta_bloqueada' ? { creador: 'Un creador', productora: 'Un medio', canal: 'Un canal' }[k] : PARA_AVISO[k])}</option>`).join('')}</select></label>`;
      const quien = a.para !== 'todos'
        ? `<label class="f">${{ creador: 'Creador', productora: 'Medio', canal: 'Canal' }[a.para]}${selectorDeAviso('avId', opcionesDeAviso(a.para), a.id, 'Elige uno')}</label>` : '';
      const plataforma = a.tipo === 'cuenta_bloqueada'
        ? `<label class="f">Dónde la bloquearon<select class="input" id="avPlat">${Object.entries(PLATAFORMAS).filter(([k]) => k !== 'web').map(([k, v]) => `<option value="${k}" ${a.plataforma === k ? 'selected' : ''}>${esc(v)}</option>`).join('')}</select></label>` : '';
      camposHtml = quien || plataforma ? `<div class="form">${quien}${plataforma}</div>` : '';
    }

    paraCaja.innerHTML = paraHtml;
    campos.innerHTML = camposHtml;
    $('#avEnlaceCaja').hidden = !(a.tipo === 'cuenta_bloqueada' || a.tipo === 'mensaje');
    $('#avRetirarCaja').hidden = a.tipo !== 'video_retirado';

    const al = (sel, fn) => { const el = $(sel); if (el) el.addEventListener('change', fn); };
    al('#avPara', (e) => { a.para = e.target.value; a.id = ''; pintarCamposDeAviso(); });
    al('#avId', (e) => { a.id = e.target.value; rellenarAviso(); });
    al('#avVideo', (e) => { a.videoId = e.target.value; rellenarAviso(); });
    al('#avPlat', (e) => { a.plataforma = e.target.value; rellenarAviso(); });
    rellenarAviso();
  }

  /** El nombre de quien se eligió, o null. */
  function elegidoDeAviso() {
    const a = estado.aviso;
    const para = a.tipo === 'nuevo_medio' ? 'productora' : a.tipo === 'nuevo_canal' ? 'canal' : a.para;
    if (!a.id) return null;
    if (para === 'creador') return (estado.creadores || []).find((c) => c.id === a.id) || null;
    if (para === 'productora') return (estado.productoras || []).find((p) => p.id === a.id) || null;
    if (para === 'canal') {
      const v = vigilados().find((x) => x.canal.id === a.id);
      return v ? { nombre: nombreDeCanal(v.canal), dueno: v.dueno } : null;
    }
    return null;
  }

  /** El texto que propone el panel. Lo que la persona ya escribió no se toca. */
  function rellenarAviso() {
    const a = estado.aviso;
    const quien = elegidoDeAviso();
    const video = a.videoId ? (estado.publicaciones || []).find((p) => p.videoId === a.videoId) : null;
    let titulo = '';
    let cuerpo = '';

    if (a.tipo === 'nuevo_medio' && quien) {
      titulo = 'Medio nuevo: ' + quien.nombre;
      cuerpo = quien.nombre + ' ya está en el directorio. Toca para conocerlo y seguirlo.';
    } else if (a.tipo === 'nuevo_canal' && quien) {
      titulo = 'Canal nuevo: ' + quien.nombre;
      cuerpo = quien.dueno.nombre + ' suma un canal al directorio. Toca para verlo y seguirlo.';
    } else if (a.tipo === 'video_retirado' && video) {
      titulo = 'Se retiró un video' + (video.creadorNombre ? ' de ' + video.creadorNombre : '');
      cuerpo = '«' + (video.titulo || 'El video') + '» ya no está disponible.';
    } else if (a.tipo === 'cuenta_bloqueada' && quien) {
      titulo = PLATAFORMAS[a.plataforma] + ' bloqueó la cuenta de ' + quien.nombre;
      cuerpo = 'Sigues pudiendo encontrar a ' + quien.nombre + ' aquí. Te avisaremos de dónde publica ahora.';
    }

    if (!a.tocado.titulo) $('#avTitulo').value = titulo.slice(0, MAX_TITULO_AVISO);
    if (!a.tocado.cuerpo) $('#avCuerpo').value = cuerpo.slice(0, MAX_CUERPO_AVISO);
    alcanceDeAviso();
    previaDeAviso();
  }

  function alcanceDeAviso() {
    const a = estado.aviso;
    const quien = elegidoDeAviso();
    const video = a.videoId ? (estado.publicaciones || []).find((p) => p.videoId === a.videoId) : null;
    let texto = '';

    if (a.tipo === 'nuevo_medio' || a.tipo === 'nuevo_canal' || (a.tipo === 'mensaje' && a.para === 'todos')) {
      texto = 'Le llega a todas las personas con la app.';
    } else if (a.tipo === 'video_retirado') {
      texto = video ? 'Le llega a quienes siguen a ' + (video.creadorNombre || 'su creador') + ' o su canal.' : 'Elige el video.';
    } else if (!quien) {
      texto = 'Elige ' + ({ creador: 'el creador', productora: 'el medio', canal: 'el canal' }[a.para] || 'a quién') + '.';
    } else if (a.para === 'canal') {
      texto = 'Le llega a quienes siguen el canal ' + quien.nombre + ' o a ' + quien.dueno.nombre + '.';
    } else {
      const n = typeof quien.seguidores === 'number' ? ' (' + plural(quien.seguidores, 'cuenta', 'cuentas') + ', más quienes siguen solo alguno de sus canales)' : '';
      texto = 'Le llega a quienes siguen a ' + quien.nombre + n + '.';
    }
    $('#avAlcance').textContent = texto;
  }

  function previaDeAviso() {
    const titulo = $('#avTitulo').value.trim();
    const cuerpo = $('#avCuerpo').value.trim();
    $('#avPrevTitulo').textContent = titulo || 'Título del aviso';
    $('#avPrevCuerpo').textContent = cuerpo || 'Lo que dice el aviso.';
    $('#avCuentaTitulo').textContent = $('#avTitulo').value.length + ' de ' + MAX_TITULO_AVISO;
    $('#avCuentaCuerpo').textContent = $('#avCuerpo').value.length + ' de ' + MAX_CUERPO_AVISO;
  }

  async function mandarAviso(boton) {
    const a = estado.aviso;
    if (!a) return;
    const titulo = $('#avTitulo').value.trim();
    const cuerpo = $('#avCuerpo').value.trim();
    const enlace = $('#avEnlaceCaja').hidden ? '' : $('#avEnlace').value.trim();
    const necesitaId = a.tipo === 'nuevo_medio' || a.tipo === 'nuevo_canal'
      || ((a.tipo === 'cuenta_bloqueada' || a.tipo === 'mensaje') && a.para !== 'todos');

    if (a.tipo === 'video_retirado' && !a.videoId) { toast('Elige el video.', true); return; }
    if (necesitaId && !a.id) { toast('Elige de quién es el aviso.', true); return; }
    if (!titulo) { toast('El aviso necesita un título.', true); return; }
    if (!cuerpo) { toast('El aviso necesita un texto.', true); return; }
    if (enlace && !/^https:\/\//i.test(enlace)) { toast('El enlace debe empezar por https://', true); return; }

    const ok = await confirmar('¿Mandar este aviso?', $('#avAlcance').textContent + ' «' + titulo + '». Una vez enviado no se puede retirar de los teléfonos.', 'Mandar aviso');
    if (!ok) return;

    const para = a.tipo === 'nuevo_medio' ? 'productora' : a.tipo === 'nuevo_canal' ? 'canal' : a.tipo === 'video_retirado' ? null : a.para;
    boton.disabled = true;
    try {
      const r = await api('/api/admin/avisos', { metodo: 'POST', cuerpo: {
        tipo: a.tipo, para, id: a.id || null, videoId: a.videoId || null,
        titulo, cuerpo, enlace: enlace || null,
        retirarVideo: a.tipo === 'video_retirado' ? $('#avRetirar').checked : null
      } });
      if (r.enviado) toast('Aviso enviado.');
      else toast('El aviso no salió: ' + (r.error || 'motivo desconocido'), true);
      await vistaAvisos();
    } catch (e) {
      toast(avisoDeRuta(e, 'mandar avisos'), true);
    } finally {
      boton.disabled = false;
    }
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
  // En la lista, el posible bot tiene su propia columna: ahí no se repite.
  const badgeProveedor = (u, sinBot) => {
    const [t, c] = PROVEEDORES[u.proveedor] || [u.proveedor, 'b-mute'];
    return `<span class="badge ${c}">${esc(t)}</span>`
      + (u.esAdmin ? ' <span class="badge b-warn">Admin</span>' : '')
      + (u.posibleBot && !sinBot ? ' <span class="badge b-bad">Posible bot</span>' : '')
      + (u.chatHabilitado ? ' <span class="badge b-info">Chat</span>' : '')
      + (u.recomendarHabilitado ? ' <span class="badge b-info">Recomienda</span>' : '')
      + (u.youtubeHabilitado ? ' <span class="badge b-info">YouTube</span>' : '')
      + (u.editorHabilitado ? ' <span class="badge b-warn">Editor</span>' : '')
      + (u.stickersHabilitado ? ' <span class="badge b-info">Stickers</span>' : '');
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

  // ---------------------------------------------------------------------------
  // La lista de cuentas: filtros que se combinan y columnas que ordenan
  // ---------------------------------------------------------------------------
  // Tipos: vale cualquiera de los marcados. Marcas: tiene que cumplirlas todas.
  // El servidor filtra, ordena y pagina (ConsultaDeUsuarios); aquí solo se
  // pide y se pinta.
  const TIPOS_USUARIO = [['anonimo', 'Invitados'], ['google', 'Google'], ['apple', 'Apple']];
  const MARCAS_USUARIO = [
    ['admin', 'Administradores'], ['bot', 'Posibles bots'], ['persona', 'Sin indicio de bot'],
    ['siguen', 'Siguen a alguien'], ['nosiguen', 'No siguen a nadie'], ['sinanuncios', 'Sin anuncios'],
    ['activos7', 'Activos en 7 días'], ['nuevos7', 'Nuevas en 7 días']
  ];
  // Las que se contradicen: marcar una desmarca la otra.
  const OPUESTAS = { bot: 'persona', persona: 'bot', siguen: 'nosiguen', nosiguen: 'siguen' };
  const COLUMNAS_USUARIOS = [['cuenta', 'Cuenta'], ['tipo', 'Tipo'], ['pais', 'Conexión'], ['bot', 'Bot'],
    ['sigue', 'Sigue a'], ['alta', 'Alta'], ['visto', 'Abrió la app']];
  const USUARIOS_DE_LA_A = ['cuenta', 'tipo', 'pais'];
  const TAMANOS_USUARIOS = [25, 50, 100, 200];

  /** Los filtros guardados, con la forma de ahora aunque vengan de una versión anterior del panel. */
  function filtroUsuarios() {
    const f = estado.filtroUsuarios;
    if (!Array.isArray(f.tipos)) f.tipos = [];
    if (!Array.isArray(f.marcas)) f.marcas = [];
    if (f.filtro) {
      (TIPOS_USUARIO.some(([k]) => k === f.filtro) ? f.tipos : f.marcas).push(f.filtro);
      delete f.filtro;
    }
    if (f.orden === 'vistos') f.orden = 'visto';
    if (f.orden === 'nuevos') f.orden = 'alta';
    if (!f.dir) f.dir = 'desc';
    if (!TAMANOS_USUARIOS.includes(f.tamano)) f.tamano = 50;
    return f;
  }

  const hayFiltrosUsuarios = (f) => !!(f.q.trim() || f.tipos.length || f.marcas.length || f.pais);

  let pedidoUsuarios = 0;
  /** Trae la página que piden los filtros. Devuelve false si llegó tarde y otra más nueva ya ganó. */
  async function cargarUsuarios() {
    const f = filtroUsuarios();
    const miPedido = ++pedidoUsuarios;
    const p = new URLSearchParams({ q: f.q.trim(), tipos: f.tipos.join(','), marcas: f.marcas.join(','), pais: f.pais,
      orden: f.orden, dir: f.dir, pagina: String(f.pagina), tamano: String(f.tamano) });
    // Un servidor anterior solo entiende un filtro a la vez.
    const elegidos = f.tipos.concat(f.marcas);
    if (elegidos.length === 1) p.set('filtro', elegidos[0]);
    const datos = await api('/api/admin/usuarios?' + p.toString());
    if (miPedido !== pedidoUsuarios) return false;
    estado.usuarios = datos;
    return true;
  }

  function filasUsuarios() {
    const pag = estado.usuarios;
    if (!pag || !pag.usuarios.length) {
      return `<tr><td colspan="${COLUMNAS_USUARIOS.length + 1}"><div class="empty">${hayFiltrosUsuarios(filtroUsuarios())
        ? 'Ninguna cuenta coincide. <button class="link" data-accion="limpiar-usuarios">Quitar los filtros</button>' : 'Todavía no hay cuentas.'}</div></td></tr>`;
    }
    return pag.usuarios.map((u) => `<tr>
      <td><div class="clip"><b>${u.email ? esc(u.email) : '<span class="muted">Sin correo</span>'}</b>${u.sinAnuncios ? ' <span class="badge b-mute" title="No ve anuncios">Sin anuncios</span>' : ''}</div><span class="mono muted">${esc(u.id)}</span></td>
      <td>${badgeProveedor(u, true)}</td>
      <td>${u.pais || u.red ? `<div>${esc(nombrePais(u.pais) || '—')}</div><div class="muted clip" style="font-size:12.5px;max-width:220px" title="${esc(u.red || '')}">${esc(u.red || '')}</div>` : '<span class="muted">—</span>'}</td>
      <td>${u.posibleBot ? `<span class="badge b-bad" title="${esc(u.motivoBot || '')}">Posible bot</span>` : '<span class="muted">—</span>'}</td>
      <td class="num">${num(u.favoritos)}</td>
      <td class="num">${esc(fmtDia(u.creadoEn))}</td>
      <td class="num" title="${esc(fmtFecha(u.vistoEn))}">${esc(relativo(u.vistoEn))}</td>
      <td class="acciones"><button class="btn sm" data-accion="ver-usuario" data-id="${esc(u.id)}">Ver</button></td>
    </tr>`).join('');
  }

  function cabezaUsuarios() {
    const f = filtroUsuarios();
    return '<tr>' + COLUMNAS_USUARIOS.map((c) => encabezado(c, f.orden, f.dir, 'ordenar-usuarios')).join('') + '<th></th></tr>';
  }

  /** Las casillas de tipo y de marcas, y lo que suman. Se repinta con cada cambio. */
  function filtrosUsuarios() {
    const f = filtroUsuarios();
    const pag = estado.usuarios;
    const chip = (grupo, [k, v], activo) => `<button class="chip${activo ? ' activo' : ''}" data-accion="chip-usuarios" data-grupo="${grupo}" data-valor="${k}" aria-pressed="${activo}">${activo ? '✓ ' : ''}${esc(v)}</button>`;
    return `
      <div class="fila-chips"><span class="etiqueta-chips" title="Vale cualquiera de los marcados">Tipo</span>
        ${TIPOS_USUARIO.map((t) => chip('tipos', t, f.tipos.includes(t[0]))).join('')}</div>
      <div class="fila-chips"><span class="etiqueta-chips" title="Tiene que cumplir todas las marcadas">Solo</span>
        ${MARCAS_USUARIO.map((m) => chip('marcas', m, f.marcas.includes(m[0]))).join('')}</div>
      <div class="resumen-filtros"><span>${pag ? `<b>${num(pag.total)}</b> ${pag.total === 1 ? 'cuenta coincide' : 'cuentas coinciden'}` : ''}${f.tipos.length > 1 ? ' · de cualquiera de los tipos marcados' : ''}${f.marcas.length > 1 ? ' · con todas las marcas elegidas' : ''}</span>
        ${hayFiltrosUsuarios(f) ? '<button class="link" data-accion="limpiar-usuarios">Quitar los filtros</button>' : ''}</div>`;
  }

  function pieUsuarios() {
    const pag = estado.usuarios;
    if (!pag) return '';
    const f = filtroUsuarios();
    const desde = pag.total ? pag.pagina * pag.tamano + 1 : 0;
    const hasta = Math.min((pag.pagina + 1) * pag.tamano, pag.total);
    const ir = (n, texto, apagado) => `<button class="btn sm" data-accion="pagina-usuarios" data-pagina="${n}" ${apagado ? 'disabled' : ''}>${texto}</button>`;
    return `<span class="muted">${pag.total ? num(desde) + '–' + num(hasta) + ' de ' + num(pag.total) : '0 cuentas'}</span>
      <label class="hint" for="tamanoUsuarios">Por página</label>
      <select class="input" id="tamanoUsuarios" style="width:auto">${TAMANOS_USUARIOS.map((n) => `<option value="${n}" ${n === f.tamano ? 'selected' : ''}>${n}</option>`).join('')}</select>
      ${ir(0, 'Primera', pag.pagina <= 0)}${ir(pag.pagina - 1, 'Anterior', pag.pagina <= 0)}
      <span class="muted">Página ${num(pag.pagina + 1)} de ${num(pag.paginas)}</span>
      ${ir(pag.pagina + 1, 'Siguiente', pag.pagina >= pag.paginas - 1)}${ir(pag.paginas - 1, 'Última', pag.pagina >= pag.paginas - 1)}`;
  }

  async function repintarUsuarios() {
    let llego;
    try { llego = await cargarUsuarios(); } catch (e) {
      if (e.estado !== 401 && e.estado !== 403) toast(e.message, true);
      return;
    }
    if (!llego || estado.vista !== 'usuarios') return;
    const partes = { '#filasUsuarios': filasUsuarios, '#pieUsuarios': pieUsuarios, '#cabezaUsuarios': cabezaUsuarios, '#filtrosUsuarios': filtrosUsuarios };
    Object.entries(partes).forEach(([sel, pintar]) => { const el = $(sel); if (el) el.innerHTML = pintar(); });
    const t = $('#tamanoUsuarios');
    if (t) t.addEventListener('change', () => { const f = filtroUsuarios(); f.tamano = Number(t.value); f.pagina = 0; repintarUsuarios(); });
  }

  /** Cambia los filtros desde un botón (las cuentas de una IP, los administradores) y deja la tabla a la vista. */
  async function filtrarUsuarios(cambios) {
    const f = Object.assign(filtroUsuarios(), { q: '', tipos: [], marcas: [], pais: '' }, cambios, { pagina: 0 });
    const tabla = $('#filasUsuarios');
    if (estado.vista !== 'usuarios' || !tabla) { ir('usuarios'); return; }
    $('#qUsuarios').value = f.q;
    $('#paisUsuarios').value = f.pais;
    await repintarUsuarios();
    const panel = tabla.closest('.panel');
    if (panel && panel.scrollIntoView) panel.scrollIntoView({ block: 'start' });
  }

  async function vistaUsuarios() {
    const f = filtroUsuarios();
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
        <section class="panel" id="listaUsuarios">
          <div class="panel-head" style="display:block">
            <div class="toolbar">
              <input class="input buscar" id="qUsuarios" type="search" placeholder="Buscar por correo o IP completos, compañía de internet o ID" title="El correo y la IP se guardan cifrados: escríbelos completos" value="${esc(f.q)}" aria-label="Buscar cuentas">
              <select class="input" id="paisUsuarios" aria-label="País">
                <option value="">Todos los países</option>
                ${Object.keys(r.porPais || {}).map((k) => [k, nombrePais(k)]).sort((a, b) => a[1].localeCompare(b[1], 'es')).map(([k, v]) => `<option value="${esc(k)}" ${f.pais === k ? 'selected' : ''}>${esc(v)}</option>`).join('')}
              </select>
            </div>
            <div id="filtrosUsuarios" class="filtros-usuarios">${filtrosUsuarios()}</div>
          </div>
          <div class="tablewrap"><table class="tabla-usuarios"><thead id="cabezaUsuarios">${cabezaUsuarios()}</thead>
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
    $('#paisUsuarios').addEventListener('change', (e) => { f.pais = e.target.value; f.pagina = 0; repintarUsuarios(); });
    const t = $('#tamanoUsuarios');
    if (t) t.addEventListener('change', () => { f.tamano = Number(t.value); f.pagina = 0; repintarUsuarios(); });
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

    // Para un teléfono perdido o una sesión que se sospecha copiada. A un
    // invitado no: sin teléfono ligado, no podría recuperar su cuenta.
    const sesiones = invitado ? '' : `<div><button class="btn" data-accion="cerrar-sesiones-usuario" data-id="${esc(u.id)}" data-nombre="${esc(u.email || u.id)}">Cerrar todas sus sesiones</button>
        <p class="hint" style="margin:6px 0 0">Sale de la app y del panel en todos sus teléfonos y navegadores. Vuelve a entrar con Google o Apple y lo recupera todo.</p></div>`;

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
          <dt>Versión de la app</dt><dd>${u.appVersion ? esc(nombrePlataforma(u.appPlataforma) + ' ' + u.appVersion) : '<span class="muted">Sin identificar</span>'}</dd>
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
        ${sesiones}
        ${u.chatHabilitado === undefined ? '' : fichaChat(u)}
        ${u.recomendarHabilitado === undefined ? '' : fichaRecomendar(u)}
        ${u.youtubeHabilitado === undefined ? '' : fichaYouTube(u)}
        ${u.editorHabilitado === undefined ? '' : fichaEditor(u)}
        ${u.stickersHabilitado === undefined ? '' : fichaStickers(u)}
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

  // ---------------------------------------------------------------------------
  // Versiones de la app
  // ---------------------------------------------------------------------------
  const nombrePlataforma = (p) => (p === 'ios' ? 'iOS' : 'Android');

  // El interruptor de "¿Estoy suscrito en YouTube?". Con un servidor anterior
  // a esto no viene en los ajustes, y el panel no enseña la tarjeta.
  // A quién se ofrece "Conectar con YouTube". Mismo esquema que las
  // recomendaciones: nadie, administradores, cuentas elegidas o todos.
  const ALCANCES_YT = {
    apagado: ['Nadie', 'La app no ofrece conectar YouTube ni consulta nada.'],
    admins: ['Solo administradores', 'Para probarlo con las cuentas que tienen acceso a este panel.'],
    elegidos: ['Administradores y cuentas elegidas', 'Además de los administradores, las cuentas que elijas una por una desde su ficha en Usuarios. Úsalo para el revisor de Google.'],
    todos: ['Todos', 'Cualquier persona que guardó su cuenta con Google. Solo cuando Google haya aprobado la verificación.']
  };

  // Un servidor 1.0.11 o anterior solo manda el interruptor de sí o no.
  const alcanceYouTube = (ajustes) => ajustes.youtubeAlcance || (ajustes.youtube ? 'todos' : 'apagado');

  function tarjetaYouTube(ajustes) {
    const alcance = alcanceYouTube(ajustes);
    const encendido = alcance !== 'apagado';
    const porCuenta = 'youtubeAlcance' in ajustes;
    return `<section class="panel"><div class="panel-head"><h2>Suscripciones de YouTube</h2>
        <span class="badge ${encendido ? 'b-ok' : 'b-mute'}">${encendido ? esc(ALCANCES_YT[alcance][0]) : 'Apagadas'}</span></div>
      <div class="panel-body">
        ${porCuenta
          ? `<div class="opciones-alcance" role="radiogroup" aria-label="A quién se ofrece conectar YouTube">
            ${Object.entries(ALCANCES_YT).map(([k, [ti, d]]) => `<label class="check"><input type="radio" name="alcanceYt" data-accion="alcance-youtube" data-valor="${k}" ${alcance === k ? 'checked' : ''}><span><b>${esc(ti)}</b><span class="hint" style="display:block">${esc(d)}</span></span></label>`).join('')}
          </div>`
          : `<label class="check"><input type="checkbox" data-accion="ajuste-youtube" ${encendido ? 'checked' : ''}> Ofrecer en la app comprobar si la persona está suscrita en YouTube</label>`}
        <p class="hint" style="margin:10px 0 0;font-size:13.5px">A quien le llegue, la app le enseña «Conectar con YouTube» en Ajustes (si guardó su cuenta con Google) y, si da permiso, le marca en qué canales del directorio está suscrito. El cambio vale la próxima vez que cada persona abra la app.</p>
        <p class="hint" style="margin:10px 0 0;font-size:13.5px"><span class="badge b-warn">Importante</span> Hasta que Google apruebe la verificación de OAuth, no elijas «Todos»: Google enseña un aviso de «app no verificada» y solo 100 cuentas en toda la vida del proyecto pueden dar el permiso. Para que el revisor lo pruebe, usa «Administradores y cuentas elegidas» y elige su cuenta en Usuarios. Solo funciona en las apps 1.0.6 o posteriores.</p>
      </div></section>`;
  }

  /** Bloque de la ficha de una cuenta: ofrecerle o no conectar YouTube. */
  function fichaYouTube(u) {
    const nombre = esc(u.email || 'esta cuenta de invitado');
    return u.youtubeHabilitado
      ? `<div><button class="btn danger" data-accion="youtube-usuario" data-id="${esc(u.id)}" data-valor="false" data-nombre="${nombre}">Ya no ofrecerle conectar YouTube</button>
          <p class="hint" style="margin:6px 0 0">Está entre las cuentas elegidas para YouTube (sección Apps).</p></div>`
      : `<div><button class="btn" data-accion="youtube-usuario" data-id="${esc(u.id)}" data-valor="true" data-nombre="${nombre}">Ofrecerle conectar YouTube</button>
          <p class="hint" style="margin:6px 0 0">Verá «Conectar con YouTube» en Ajustes de la app mientras en Apps esté elegido «Administradores y cuentas elegidas», si guardó su cuenta con Google.</p></div>`;
  }

  async function vistaApps() {
    const [a, mant, ajustes] = await Promise.all([
      api('/api/admin/apps'),
      api('/api/admin/apps/mantenimiento'),
      api('/api/admin/ajustes').catch(() => null)
    ]);
    const enMant = !!(mant && mant.activo);
    const versiones = a.versiones || [];
    const suma = (lista, campo) => lista.reduce((s, v) => s + (v[campo] || 0), 0);
    const fuera = versiones.filter((v) => v.baja || v.bajoMinima);
    const sinDecir = versiones.filter((v) => !v.version);
    const minimas = [];
    if (a.minimaAndroid) minimas.push('Android ' + a.minimaAndroid);
    if (a.minimaIos) minimas.push('iOS ' + a.minimaIos);

    main.innerHTML = `
      <div class="head"><div><h1>Apps</h1><p class="sub">El modo mantenimiento, que deja fuera a todas las apps mientras se trabaja en el servidor; con qué versión de la app entra cada cuenta, y cuáles se dejan de atender para obligar a actualizar.</p></div></div>
      <div class="stack">
        <section class="panel"><div class="panel-head"><h2>Mantenimiento</h2>
            <span class="badge ${enMant ? 'b-warn' : 'b-ok'}">${enMant ? 'En mantenimiento' : 'Apagado'}</span></div>
          <div class="panel-body">
            <p class="hint" style="margin:0 0 12px;font-size:13.5px">${enMant
              ? 'Las apps están fuera: en vez del contenido enseñan el mensaje de abajo a pantalla completa y vuelven a preguntar solas cada medio minuto. Al quitarlo, se recuperan sin que nadie tenga que hacer nada.'
              : 'Deja fuera a todas las apps mientras trabajas en el servidor: en vez del contenido enseñan el mensaje de abajo a pantalla completa. Las versiones anteriores a esa pantalla solo muestran el mensaje como un error.'}
              El panel sigue funcionando, y el servidor sigue recibiendo las publicaciones y mandando los avisos. El cambio tarda como mucho medio minuto.</p>
            <label class="hint" for="mantMensaje">Mensaje para la gente</label>
            <textarea class="input" id="mantMensaje" maxlength="300" rows="2" style="margin:6px 0 12px">${esc((mant && mant.mensaje) || '')}</textarea>
            <div class="toolbar">
              ${enMant
                ? '<button class="btn primary" data-accion="mantenimiento" data-valor="false">Quitar el mantenimiento</button><button class="btn" data-accion="mantenimiento" data-valor="true" data-solo-mensaje="1">Guardar el mensaje</button>'
                : '<button class="btn danger" data-accion="mantenimiento" data-valor="true">Poner en mantenimiento</button>'}
            </div>
          </div>
        </section>

        ${ajustes && 'youtube' in ajustes ? tarjetaYouTube(ajustes) : ''}

        <div class="stats">
          <div class="stat"><div class="k">Cuentas</div><div class="v">${num(suma(versiones, 'usuarios'))}</div><div class="n">${num(suma(versiones, 'activos'))} abrieron la app en 30 días</div></div>
          <div class="stat"><div class="k">Versiones distintas</div><div class="v">${num(versiones.filter((v) => v.version && v.usuarios).length)}</div><div class="n">entre las cuentas que dicen cuál tienen</div></div>
          <div class="stat"><div class="k">Sin identificar</div><div class="v">${num(suma(sinDecir, 'usuarios'))}</div><div class="n">apps que no dicen su versión</div></div>
          <div class="stat"><div class="k">Fuera de servicio</div><div class="v">${num(suma(fuera, 'usuarios'))}</div><div class="n">${fuera.length ? 'cuentas en ' + plural(fuera.length, 'versión que ya no se atiende', 'versiones que ya no se atienden') : 'se atiende a todas las versiones'}</div></div>
        </div>

        <section class="panel"><div class="panel-head"><h2>Versiones instaladas</h2></div>
          <div class="panel-body">
            <p class="hint" style="margin:0;font-size:13.5px">Dar de baja una versión hace que el servidor deje de responderle: la app enseña una pantalla que pide actualizar desde Google Play y no deja hacer nada más. Las versiones anteriores a esa pantalla solo muestran un error. El cambio tarda como mucho medio minuto y se puede deshacer aquí mismo; la cuenta y lo que sigue cada persona no se pierden.</p>
            ${minimas.length ? `<p class="hint" style="margin:10px 0 0;font-size:13.5px"><span class="badge b-info">Versión mínima</span> El servidor además tiene puesta una versión mínima (${esc(minimas.join(', '))}) en su configuración: todo lo anterior queda fuera y no se cambia desde el panel.</p>` : ''}
          </div>
          <div class="tablewrap"><table><thead><tr><th>Versión</th><th>Cuentas</th><th>Activas en 30 días</th><th>Última entrada</th><th>Estado</th><th></th></tr></thead><tbody>
          ${versiones.length ? versiones.map((v) => `<tr>
              <td><b>${esc(nombrePlataforma(v.plataforma))}</b> ${v.version ? `<span class="mono">${esc(v.version)}</span>` : 'sin identificar'}</td>
              <td class="num">${num(v.usuarios)}</td>
              <td class="num">${num(v.activos)}</td>
              <td class="num">${v.ultimaVez ? esc(fmtFecha(v.ultimaVez)) : '<span class="muted">—</span>'}</td>
              <td>${v.baja ? '<span class="badge b-warn">Dada de baja</span>' : v.bajoMinima ? '<span class="badge b-warn">Bajo la mínima</span>' : '<span class="badge b-ok">Se atiende</span>'}</td>
              <td class="acciones"><button class="btn sm ${v.baja ? '' : 'danger'}" data-accion="baja-app" data-plataforma="${esc(v.plataforma)}" data-version="${esc(v.version || '')}" data-cuentas="${esc(v.usuarios || 0)}" data-valor="${v.baja ? 'false' : 'true'}">${v.baja ? 'Volver a atender' : 'Dar de baja'}</button></td>
            </tr>`).join('') : '<tr><td colspan="6"><div class="empty">Todavía no ha entrado ninguna cuenta.</div></td></tr>'}
          </tbody></table></div>
          <div class="panel-body">
            <p class="hint" style="margin:0;font-size:13.5px"><b>Sin identificar</b> son las cuentas que entran con una app que todavía no dice su versión (las primeras que se publicaron) y las que no han vuelto a abrir la app desde que esto se empezó a guardar. Darlas de baja deja fuera a toda app que no diga su versión.</p>
          </div>
        </section>

        <section class="panel"><div class="panel-head"><h2>Dar de baja otra versión</h2></div>
          <div class="panel-body">
            <p class="hint" style="margin:0 0 12px;font-size:13.5px">Para una versión que no sale en la lista porque todavía no ha entrado nadie con ella.</p>
            <div class="toolbar">
              <select class="input" id="appPlataforma" aria-label="Plataforma" style="width:130px"><option value="android">Android</option><option value="ios">iOS</option></select>
              <input class="input" id="appVersion" maxlength="20" placeholder="1.0.0" aria-label="Versión" style="width:140px">
              <button class="btn danger" data-accion="baja-app-nueva">Dar de baja</button>
            </div>
          </div>
        </section>
      </div>`;

    $('#appVersion').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('[data-accion="baja-app-nueva"]').click(); });
  }

  async function ponerBajaDeApp(plataforma, version, baja, cuentas) {
    const nombre = nombrePlataforma(plataforma) + ' ' + (version || 'sin identificar');
    const ok = await confirmar(
      baja ? '¿Dar de baja ' + nombre + '?' : '¿Volver a atender ' + nombre + '?',
      baja
        ? (cuentas ? plural(cuentas, 'cuenta entró', 'cuentas entraron') + ' por última vez con esta versión. ' : '')
          + 'La app dejará de funcionarles hasta que la actualicen. Antes de hacerlo, comprueba que la versión nueva ya está publicada en la tienda.'
        : 'El servidor vuelve a responderle y la app funciona otra vez al abrirla.',
      baja ? 'Dar de baja' : 'Volver a atender', baja);
    if (!ok) return;
    await api('/api/admin/apps/baja', { metodo: 'PUT', cuerpo: { plataforma, version: version || null, baja } });
    toast(baja ? nombre + ' ya no se atiende.' : nombre + ' se atiende otra vez.');
    if (estado.vista === 'apps') await vistaApps();
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
      <div class="head"><div><h1>Anuncios</h1><p class="sub">Los anuncios que la app de Android muestra en Novedades y en las fichas de los videos y de los creadores, y las dos maneras de quitarlos: una compra en Google Play o un folio de regalo.</p></div></div>
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
  // Chat con las cuentas
  // ---------------------------------------------------------------------------
  // Una conversación por cuenta, guardada en el servidor y cifrada allí (ver
  // chat/ChatService). Aquí no se guarda nada: lo que está en pantalla vive en
  // `chat` y se suelta al salir de la sección. Con una conversación abierta,
  // el panel espera los mensajes nuevos con consultas de hasta 25 segundos.
  const MAX_TEXTO_CHAT = 2000;
  let chat = null;         // la conversación abierta
  let bandejaChat = [];    // lo último que dijo /api/admin/chat

  const pausa = (ms) => new Promise((r) => setTimeout(r, ms));
  const nombreEnChat = (u) => u.email || 'Invitado ' + String(u.id).slice(0, 8);
  const rutaChat = (id, final) => '/api/admin/chat/' + encodeURIComponent(id) + (final ? '/' + final : '');

  /** El bloque de la ficha de una cuenta: activar, abrir o quitar el chat. */
  function fichaChat(u) {
    const nombre = esc(nombreEnChat(u));
    if (!u.chatHabilitado) {
      return `<div><button class="btn" data-accion="chat-usuario" data-id="${esc(u.id)}" data-valor="true" data-nombre="${nombre}">Activar el chat con esta cuenta</button>
        <p class="hint" style="margin:6px 0 0">Verá «Hablar con el equipo» en Ajustes de la app. Los mensajes se guardan cifrados hasta que borres la conversación o le quites el chat.</p></div>`;
    }
    return `<div class="toolbar">
        <button class="btn primary" data-accion="abrir-chat" data-id="${esc(u.id)}">Abrir el chat</button>
        <button class="btn danger" data-accion="chat-usuario" data-id="${esc(u.id)}" data-valor="false" data-nombre="${nombre}">Quitar el chat</button>
      </div>`;
  }

  /** El número junto a «Chat» en el menú: conversaciones que esperan respuesta. */
  async function contarChat() {
    const n = $('#n-chat');
    if (!n) return;
    try {
      const r = await api('/api/admin/chat/sin-leer');
      n.textContent = r.conversaciones; n.hidden = !r.conversaciones;
    } catch (e) { n.hidden = true; }
  }

  function cuandoCorto(iso) {
    if (!iso) return '';
    const d = new Date(iso);
    const hoy = new Date();
    return d.toDateString() === hoy.toDateString()
      ? d.toLocaleTimeString('es-MX', { hour: '2-digit', minute: '2-digit' })
      : d.toLocaleDateString('es-MX', { day: 'numeric', month: 'short' });
  }

  async function vistaChat() {
    const [lista, cuenta] = await Promise.all([api('/api/admin/chat'), api('/api/admin/chat/sin-leer').catch(() => null)]);
    if (estado.vista !== 'chat') return;
    bandejaChat = lista;

    if (cuenta && cuenta.disponible === false) {
      main.innerHTML = `<div class="head"><div><h1>Chat</h1></div></div>
        <section class="panel"><div class="panel-body"><div class="aviso-mal"><b>El chat no está disponible en este servidor.</b> Falta la llave con la que se cifran los mensajes (CHAT_LLAVES en el .env del VPS). Mientras no esté, la app no enseña el chat.</div></div></section>`;
      return;
    }

    main.innerHTML = `
      <div class="head"><div><h1>Chat</h1><p class="sub">Escribe a las cuentas a las que les activaste el chat y contesta lo que te escriben. Para activarlo a alguien: <button class="link" data-ir="usuarios">Usuarios</button> → «Ver» → «Activar el chat». También puedes contestar desde la app, entrando con tu cuenta de administrador.</p></div></div>
      <div class="chat-pantalla" id="chatPantalla">
        <section class="panel chat-bandeja"><div class="panel-head"><h2>Conversaciones</h2><span class="muted" style="font-size:13px" id="chatTotal"></span></div>
          <div id="chatBandeja"></div></section>
        <section class="panel chat-hilo" id="chatHilo"><div class="empty">Elige una conversación.</div></section>
      </div>
      <p class="hint" style="margin:12px 0 0">Los mensajes se guardan cifrados en el servidor con una llave que no está en la base de datos: una copia de la base no basta para leerlos. A la persona le llega un aviso que no dice lo que escribiste, y solo ve «Equipo de Voces», nunca tu correo.</p>`;
    pintarBandeja();

    const abrir = estado.chatAbierto && lista.find((c) => c.id === estado.chatAbierto);
    if (abrir) await abrirConversacion(abrir.id);
    else if (window.matchMedia('(min-width: 761px)').matches && lista.length && !chat) await abrirConversacion(lista[0].id);
    refrescarBandejaLuego();
  }

  /** La lista de la izquierda se pone al día sola mientras la sección está abierta. */
  function refrescarBandejaLuego() {
    clearTimeout(refrescarBandejaLuego.reloj);
    refrescarBandejaLuego.reloj = setTimeout(async () => {
      if (estado.vista !== 'chat') return;
      try {
        bandejaChat = await api('/api/admin/chat');
        if (estado.vista === 'chat') { pintarBandeja(); contarChat(); }
      } catch (e) { /* se reintenta */ }
      refrescarBandejaLuego();
    }, 15000);
  }

  function pintarBandeja() {
    const caja = $('#chatBandeja');
    if (!caja) return;
    const total = $('#chatTotal');
    if (total) total.textContent = plural(bandejaChat.length, 'cuenta', 'cuentas');
    if (!bandejaChat.length) {
      caja.innerHTML = '<div class="empty">Ninguna cuenta tiene el chat activado todavía.</div>';
      return;
    }
    caja.innerHTML = '<ul class="chat-conversaciones">' + bandejaChat.map((c) => {
      const abierta = chat && chat.usuarioId === c.id;
      // Si la tengo abierta, lo que llegó ya lo estoy viendo.
      const sinLeer = abierta ? 0 : c.sinLeer;
      const vistazo = c.ultimo ? (c.ultimoDeAdmin ? 'Equipo: ' : '') + c.ultimo : 'Sin mensajes todavía';
      return `<li><button class="chat-conv ${abierta ? 'abierta' : ''} ${sinLeer ? 'pendiente' : ''}" data-accion="abrir-chat" data-id="${esc(c.id)}">
          <span class="chat-conv-fila"><b class="clip">${esc(nombreEnChat(c))}</b><span class="muted chat-conv-hora">${esc(cuandoCorto(c.ultimoEn))}</span></span>
          <span class="chat-conv-fila"><span class="muted clip">${esc(vistazo)}</span>${sinLeer ? `<span class="badge b-ok">${num(sinLeer)}</span>` : ''}</span>
        </button></li>`;
    }).join('') + '</ul>';
  }

  async function abrirConversacion(id) {
    if (chat && chat.usuarioId === id && chat.vivo) { $('#chatTexto') && $('#chatTexto').focus(); return; }
    olvidarChat();
    const cuenta = bandejaChat.find((c) => c.id === id) || { id };
    const c = { usuarioId: id, nombre: nombreEnChat(cuenta), mensajes: [], ultimoId: 0, vivo: true, enviando: false, error: null, cargado: false };
    chat = c;
    estado.chatAbierto = id;
    const pantalla = $('#chatPantalla');
    if (pantalla) pantalla.classList.add('con-hilo');
    pintarBandeja();
    pintarHilo();

    try {
      agregarMensajes(c, await api(rutaChat(id, 'mensajes')));
    } catch (e) {
      if (chat === c) terminarChat(c, e.message);
      return;
    }
    if (chat !== c) return;
    c.cargado = true;
    pintarHilo();
    // Abrirla es leer lo que había: los contadores bajan.
    const enLista = bandejaChat.find((x) => x.id === id);
    if (enLista) enLista.sinLeer = 0;
    pintarBandeja();
    contarChat();
    escucharChat(c);
  }

  function agregarMensajes(c, lista) {
    let nuevos = false;
    for (const m of lista || []) {
      if (c.mensajes.some((x) => x.id === m.id)) continue;
      c.mensajes.push(m);
      nuevos = true;
    }
    c.mensajes.sort((a, b) => a.id - b.id);
    if (c.mensajes.length) c.ultimoId = c.mensajes[c.mensajes.length - 1].id;
    return nuevos;
  }

  /** Espera lo que llegue mientras la conversación siga abierta. */
  async function escucharChat(c) {
    let fallos = 0;
    while (c.vivo && chat === c) {
      let lista;
      try {
        lista = await api(rutaChat(c.usuarioId, 'mensajes') + '?esperar=true&despues=' + c.ultimoId);
        fallos = 0;
        if (c.error) { c.error = null; pintarHilo(); }
      } catch (e) {
        if (!c.vivo || chat !== c) return;
        if ([401, 403, 404, 409].includes(e.estado)) { terminarChat(c, e.message); return; }
        // Sin red o el servidor reiniciando: cada vez más despacio.
        fallos++;
        if (fallos === 3) { c.error = 'Se perdió la conexión. Reintentando…'; pintarHilo(); }
        await pausa(Math.min(30000, 2000 * fallos));
        continue;
      }
      if (chat !== c || !c.vivo) return;
      if (agregarMensajes(c, lista)) pintarHilo();
    }
  }

  async function enviarChat() {
    const c = chat;
    const caja = $('#chatTexto');
    if (!c || !caja || c.enviando || !c.vivo) return;
    const texto = caja.value.trim();
    if (!texto) return;
    if (texto.length > MAX_TEXTO_CHAT) { toast('Máximo ' + num(MAX_TEXTO_CHAT) + ' letras por mensaje.', true); return; }

    c.enviando = true;
    pintarPie();
    try {
      const m = await api(rutaChat(c.usuarioId, 'mensajes'), { metodo: 'POST', cuerpo: { texto } });
      if (chat !== c) return;
      agregarMensajes(c, [m]);
      caja.value = '';
      pintarHilo();
    } finally {
      c.enviando = false;
      if (chat === c) { pintarPie(); $('#chatTexto') && $('#chatTexto').focus(); }
    }
  }

  function terminarChat(c, motivo) {
    c.vivo = false;
    c.error = motivo || 'La conversación ya no está disponible.';
    if (chat === c) pintarHilo();
  }

  /** Suelta la conversación abierta y le dice al servidor que ya no se mira. */
  function olvidarChat() {
    const c = chat;
    chat = null;
    if (!c) return;
    const vivo = c.vivo;
    c.vivo = false;
    c.mensajes.length = 0;
    if (vivo && sesion && sesion.token) {
      api(rutaChat(c.usuarioId, 'salir') + '?hasta=' + c.ultimoId, { metodo: 'POST' }).catch(() => {});
    }
  }

  // Al cerrar la pestaña o recargar, la persona vuelve a recibir avisos al
  // momento en vez de esperar a que el servidor note que el panel se fue.
  window.addEventListener('pagehide', () => {
    const c = chat;
    if (!c || !c.vivo || !sesion || !sesion.token) return;
    try {
      fetch(BASE + rutaChat(c.usuarioId, 'salir') + '?hasta=' + c.ultimoId, {
        method: 'POST', keepalive: true,
        headers: { Authorization: 'Bearer ' + sesion.token, 'X-App-Plataforma': 'panel' }
      });
    } catch (e) { /* nada que hacer */ }
  });

  function pintarHilo() {
    const c = chat;
    const hilo = $('#chatHilo');
    if (!hilo || !c) return;

    if (!hilo.querySelector('.chat-lista')) {
      hilo.innerHTML = `
        <div class="panel-head chat-cabeza">
          <button class="btn sm solo-movil-chat" data-accion="chat-volver">‹ Conversaciones</button>
          <h2 class="clip" id="chatNombre"></h2>
          <div class="toolbar">
            <button class="btn sm" data-accion="ver-usuario" id="chatFicha">Ficha</button>
            <button class="btn sm danger" data-accion="chat-borrar" id="chatBorrar">Borrar conversación</button>
          </div>
        </div>
        <div class="chat-lista" id="chatLista" aria-live="polite"></div>
        <div class="chat-pie" id="chatPie"></div>`;
    }
    $('#chatNombre').textContent = c.nombre;
    $('#chatFicha').dataset.id = c.usuarioId;
    $('#chatBorrar').dataset.id = c.usuarioId;
    $('#chatBorrar').dataset.nombre = c.nombre;

    const lista = $('#chatLista');
    const abajo = lista.scrollHeight - lista.scrollTop - lista.clientHeight < 80;
    lista.innerHTML = '';
    if (!c.cargado && c.vivo) {
      lista.innerHTML = '<div class="cargando">Cargando…</div>';
    } else if (!c.mensajes.length && !c.error) {
      lista.innerHTML = '<div class="empty">Todavía no se han escrito. Lo que mandes le llega con un aviso al teléfono.</div>';
    }
    for (const m of c.mensajes) {
      const fila = document.createElement('div');
      fila.className = 'chat-msj ' + (m.deAdmin ? 'mio' : 'suyo');
      if (m.deAdmin && m.autor && m.autor !== 'Tú') {
        const quien = document.createElement('span');
        quien.className = 'chat-autor';
        quien.textContent = m.autor;
        fila.appendChild(quien);
      }
      const texto = document.createElement('div');
      texto.textContent = m.texto; // nunca innerHTML: lo escribió otra persona
      fila.appendChild(texto);
      const pie = document.createElement('span');
      pie.className = 'chat-hora';
      pie.textContent = cuandoCorto(m.creadoEn) + (m.deAdmin ? (m.leidoEn ? ' · Leído' : ' · Enviado') : '');
      pie.title = fmtFecha(m.creadoEn);
      fila.appendChild(pie);
      lista.appendChild(fila);
    }
    if (c.error) {
      const nota = document.createElement('div');
      nota.className = 'chat-nota';
      nota.textContent = c.error;
      lista.appendChild(nota);
    }
    if (abajo || c.mensajes.length < 3) lista.scrollTop = lista.scrollHeight;
    pintarPie();
  }

  function pintarPie() {
    const c = chat;
    const pie = $('#chatPie');
    if (!pie || !c) return;
    if (!c.vivo) {
      pie.innerHTML = '<button class="btn" data-accion="recargar">Volver a cargar</button>';
      return;
    }
    if (!$('#chatTexto')) {
      pie.innerHTML = `<textarea class="input" id="chatTexto" rows="2" maxlength="${MAX_TEXTO_CHAT}" placeholder="Escribe un mensaje (Enter envía, Mayús+Enter salta de línea)" aria-label="Mensaje"></textarea>
        <button class="btn primary" id="chatEnviar" data-accion="chat-enviar">Enviar</button>`;
      $('#chatTexto').addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) { e.preventDefault(); enviarChat().catch((err) => toast(err.message, true)); }
      });
    }
    $('#chatEnviar').disabled = c.enviando || !c.cargado;
    $('#chatEnviar').textContent = c.enviando ? 'Enviando…' : 'Enviar';
  }

  // ---------------------------------------------------------------------------
  // Acciones
  // ---------------------------------------------------------------------------
  document.addEventListener('click', async (e) => {
    const destino = e.target.closest('[data-ir]');
    if (destino) {
      // Desde la ficha de una cuenta: la ventana se cierra para ver la sección.
      if (modal.open && modal.contains(destino)) cerrarModal();
      ir(destino.dataset.ir);
      return;
    }

    const b = e.target.closest('[data-accion]');
    if (!b) return;
    const { accion, id } = b.dataset;

    try {
      switch (accion) {
        case 'recargar': ir(estado.vista); break;

        case 'mandar-aviso': await mandarAviso(b); break;

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
        case 'ver-video': await abrirVideo(b.dataset.video); break;

        case 'actualizar-cifras': {
          const ids = (estado.publicaciones || []).map((p) => p.videoId);
          if (!ids.length) break;
          b.disabled = true;
          const textoAntes = b.textContent;
          b.textContent = 'Pidiendo a YouTube…';
          let r;
          try {
            r = await api('/api/admin/videos/cifras', { metodo: 'POST', cuerpo: { videoIds: ids } });
          } catch (e) {
            b.textContent = textoAntes;
            if (e.estado === 404) throw new Error('Este servidor todavía no tiene la versión con cifras de los videos.');
            throw e;
          }
          if (r.sinApi) toast('YouTube no contestó. Revisa YOUTUBE_API_KEY en el servidor, o si se acabó la cuota del día.', true);
          else if (r.ocupada) toast('Ya se estaban actualizando. Prueba otra vez en un momento.');
          else toast('Cifras al día: ' + plural(r.actualizados, 'video', 'videos') + (r.noExisten ? '. ' + plural(r.noExisten, 'ya no está', 'ya no están') + ' en YouTube.' : '.'));
          if (estado.vista === 'publicaciones') await vistaPublicaciones();
          break;
        }

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
          opcionesPub().limite = 100;
          await vistaPublicaciones();
          break;
        }

        case 'vista-pubs': {
          opcionesPub().vista = b.dataset.valor;
          await vistaPublicaciones();
          break;
        }

        case 'ordenar-pubs': {
          const o = opcionesPub(), clave = b.dataset.valor;
          if (o.orden === clave) o.dir = o.dir === 'asc' ? 'desc' : 'asc';
          else { o.orden = clave; o.dir = DE_LA_A.includes(clave) ? 'asc' : 'desc'; }
          await vistaPublicaciones();
          break;
        }

        case 'ordenar-totales': {
          const o = opcionesPub(), clave = b.dataset.valor;
          if (o.totalOrden === clave) o.totalDir = o.totalDir === 'asc' ? 'desc' : 'asc';
          else { o.totalOrden = clave; o.totalDir = DE_LA_A.includes(clave) ? 'asc' : 'desc'; }
          await vistaPublicaciones();
          break;
        }

        case 'filtrar-pubs': {
          const o = opcionesPub();
          o.filtro = { tipo: b.dataset.tipo, id: b.dataset.id, nombre: b.dataset.nombre };
          o.vista = 'videos';
          o.limite = 100;
          if (b.dataset.orden) { o.orden = b.dataset.orden; o.dir = 'desc'; }
          await vistaPublicaciones();
          window.scrollTo(0, 0);
          break;
        }

        case 'quitar-filtro-pubs': {
          opcionesPub().filtro = null;
          await vistaPublicaciones();
          break;
        }

        case 'mas-pubs': {
          const o = opcionesPub();
          o.limite = Math.min(500, o.limite + 100);
          await vistaPublicaciones();
          break;
        }

        case 'aplicar-fechas': {
          const o = opcionesPub();
          const desde = $('#pubDesde').value, hasta = $('#pubHasta').value;
          if (desde && hasta && desde > hasta) { toast('La fecha inicial va antes que la final.', true); break; }
          o.desde = desde; o.hasta = hasta; o.limite = 100;
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

        case 'alcance-youtube': {
          // El botón ya cambió al pulsarlo. Si algo falla o se cancela, se
          // vuelve a pintar la sección con lo que diga el servidor.
          const nuevo = b.dataset.valor;
          const [titulo, detalle] = ALCANCES_YT[nuevo] || [nuevo, ''];
          try {
            const seguro = await confirmar(
              nuevo === 'apagado' ? 'Apagar las suscripciones de YouTube' : 'Suscripciones de YouTube: ' + titulo.toLowerCase(),
              nuevo === 'apagado'
                ? 'La app deja de ofrecer conectar YouTube y de consultar las suscripciones. Lo que cada cuenta ya autorizó no se borra.'
                : nuevo === 'todos'
                  ? 'Cualquier persona que guardó su cuenta con Google verá «Conectar con YouTube». Hazlo solo si Google ya aprobó la verificación de OAuth: antes de eso aparece el aviso de app no verificada y solo 100 cuentas en toda la vida del proyecto pueden dar el permiso.'
                  : detalle + ' Verán «Conectar con YouTube» en Ajustes de la app.',
              nuevo === 'apagado' ? 'Apagar' : 'Cambiar', nuevo === 'apagado' || nuevo === 'todos');
            if (seguro) {
              await api('/api/admin/ajustes', { metodo: 'PUT', cuerpo: { youtubeAlcance: nuevo } });
              toast(nuevo === 'apagado' ? 'Suscripciones de YouTube apagadas.' : 'YouTube se ofrece a: ' + titulo.toLowerCase() + '.');
            }
          } finally {
            if (estado.vista === 'apps') await vistaApps();
          }
          break;
        }

        case 'youtube-usuario': {
          const dar = b.dataset.valor === 'true';
          if (!dar) {
            const ok = await confirmar('¿Dejar de ofrecerle YouTube a ' + b.dataset.nombre + '?',
              'Si el alcance es «Administradores y cuentas elegidas», deja de ver «Conectar con YouTube» en la app.',
              'Quitar', false);
            if (!ok) break;
          }
          b.disabled = true;
          const r = await api('/api/admin/usuarios/' + encodeURIComponent(id) + '/youtube?valor=' + dar, { metodo: 'POST' });
          toast(r.mensaje || 'Listo.');
          await abrirUsuario(id);
          break;
        }

        case 'ajuste-youtube': {
          // Igual que los cortos: si algo falla o se cancela, se vuelve a
          // pintar la sección con lo que diga el servidor.
          const encender = b.checked;
          try {
            const seguro = await confirmar(
              encender ? 'Encender las suscripciones de YouTube' : 'Apagar las suscripciones de YouTube',
              encender
                ? 'La app ofrecerá «Conectar con YouTube» a quien guardó su cuenta con Google. Hazlo solo si Google ya aprobó la verificación de OAuth: antes de eso aparece el aviso de app no verificada y solo 100 cuentas en toda la vida del proyecto pueden dar el permiso.'
                : 'La app deja de ofrecer conectar YouTube y de consultar las suscripciones. Lo que cada cuenta ya autorizó no se borra.',
              encender ? 'Encender' : 'Apagar', !encender);
            if (seguro) {
              await api('/api/admin/ajustes', { metodo: 'PUT', cuerpo: { youtube: encender } });
              toast(encender ? 'Suscripciones de YouTube encendidas.' : 'Suscripciones de YouTube apagadas.');
            }
          } finally {
            if (estado.vista === 'apps') await vistaApps();
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

        case 'completar-videos': {
          // Va de pocos en pocos para que cada llamada conteste rápido, y se
          // repite mientras queden y haya avance.
          b.disabled = true;
          const antes = b.textContent;
          let completados = 0, aCortos = 0, noExisten = 0, r = null;
          try {
            for (let vuelta = 0; vuelta < 400; vuelta++) {
              r = await api('/api/admin/videos/completar', { metodo: 'POST' });
              completados += r.completados; aCortos += r.aCortos; noExisten += r.noExisten;
              b.textContent = 'Preguntando a YouTube… quedan ' + num(r.quedan);
              if (r.sinApi || r.ocupada || !r.revisados || !r.quedan) break;
              if (!r.completados && !r.noExisten) break;
            }
            if (r && r.sinApi) {
              toast('YouTube no contestó. Revisa la clave de la API (YOUTUBE_API_KEY) y la cuota.');
            } else if (r && r.ocupada) {
              toast('Ya hay una revisión en marcha. Prueba en unos minutos.');
            } else if (!completados && !noExisten) {
              toast(r && r.quedan ? 'YouTube todavía no tiene los datos de ' + plural(r.quedan, 'video', 'videos') + '; se vuelve a intentar sola.' : 'No hay videos sin datos.');
            } else {
              toast(plural(completados, 'video completado', 'videos completados')
                + (aCortos ? ', ' + plural(aCortos, 'pasó', 'pasaron') + ' a cortos' : '')
                + (noExisten ? '; ' + plural(noExisten, 'ya no existe', 'ya no existen') + ' en YouTube' : '')
                + (r && r.quedan ? '; quedan ' + num(r.quedan) + '.' : '.'));
            }
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
                ? 'La app de Android empezará a mostrar anuncios de Google en Novedades y en las fichas de los videos y de los creadores a quien no los haya quitado. Los teléfonos que la tienen abierta se enteran en un minuto como mucho. Antes de encenderlos, la política de privacidad publicada tiene que decir que hay anuncios.'
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

        case 'mantenimiento': {
          const activo = b.dataset.valor === 'true';
          const mensaje = $('#mantMensaje') ? $('#mantMensaje').value.trim() : '';
          if (!b.dataset.soloMensaje) {
            const ok = await confirmar(
              activo ? '¿Poner el servidor en mantenimiento?' : '¿Quitar el mantenimiento?',
              activo ? 'Todas las apps dejan de funcionar y enseñan el mensaje hasta que lo quites. El panel sigue funcionando.'
                : 'Las apps vuelven a funcionar; las que están abiertas se recuperan solas en medio minuto.',
              activo ? 'Poner en mantenimiento' : 'Quitar el mantenimiento', activo);
            if (!ok) break;
          }
          await api('/api/admin/apps/mantenimiento', { metodo: 'PUT', cuerpo: { activo, mensaje } });
          toast(b.dataset.soloMensaje ? 'Mensaje guardado.' : activo ? 'El servidor está en mantenimiento.' : 'Mantenimiento quitado.');
          if (estado.vista === 'apps') await vistaApps();
          break;
        }

        case 'baja-app':
          await ponerBajaDeApp(b.dataset.plataforma, b.dataset.version, b.dataset.valor === 'true', Number(b.dataset.cuentas) || 0);
          break;

        case 'baja-app-nueva': {
          const version = $('#appVersion').value.trim();
          if (!/^v?\d+(\.\d+){0,3}$/.test(version)) { toast('Escribe la versión como 1.0.0.', true); break; }
          await ponerBajaDeApp($('#appPlataforma').value, version.replace(/^v/, ''), true, 0);
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

        case 'chat-usuario': {
          const activar = b.dataset.valor === 'true';
          if (!activar) {
            const ok = await confirmar('¿Quitarle el chat a ' + b.dataset.nombre + '?',
              'Ya no verá el chat en la app y se borra toda su conversación con el equipo. No se puede deshacer.',
              'Quitar el chat y borrar', true);
            if (!ok) break;
          }
          b.disabled = true;
          const r = await api('/api/admin/usuarios/' + encodeURIComponent(id) + '/chat?valor=' + activar, { metodo: 'POST' });
          toast(r.mensaje || 'Listo.');
          if (!activar && chat && chat.usuarioId === id) { olvidarChat(); estado.chatAbierto = null; }
          if (estado.vista === 'chat') { cerrarModal(); await vistaChat(); } else await abrirUsuario(id);
          contarChat();
          break;
        }

        case 'abrir-chat': {
          if (estado.vista !== 'chat') {
            cerrarModal();
            estado.chatAbierto = id;
            ir('chat');
          } else {
            await abrirConversacion(id);
          }
          break;
        }

        case 'chat-enviar': await enviarChat(); break;

        case 'chat-volver': {
          olvidarChat();
          estado.chatAbierto = null;
          const p = $('#chatPantalla');
          if (p) p.classList.remove('con-hilo');
          pintarBandeja();
          break;
        }

        case 'chat-borrar': {
          const ok = await confirmar('¿Borrar la conversación con ' + b.dataset.nombre + '?',
            'Se borran todos los mensajes, los tuyos y los suyos, para los dos lados. La cuenta sigue con el chat activado. No se puede deshacer.',
            'Borrar conversación', true);
          if (!ok) break;
          const r = await api(rutaChat(id), { metodo: 'DELETE' });
          toast(r.mensaje || 'Conversación borrada.');
          olvidarChat();
          await vistaChat();
          contarChat();
          break;
        }

        case 'ver-usuario': {
          b.disabled = true;
          await abrirUsuario(id);
          b.disabled = false;
          break;
        }

        case 'pagina-usuarios': {
          filtroUsuarios().pagina = Math.max(0, Number(b.dataset.pagina) || 0);
          await repintarUsuarios();
          const lista = $('#listaUsuarios');
          if (lista && lista.getBoundingClientRect().top < 0) lista.scrollIntoView({ block: 'start' });
          break;
        }

        case 'ordenar-usuarios': {
          const f = filtroUsuarios(), clave = b.dataset.valor;
          if (f.orden === clave) f.dir = f.dir === 'asc' ? 'desc' : 'asc';
          else { f.orden = clave; f.dir = USUARIOS_DE_LA_A.includes(clave) ? 'asc' : 'desc'; }
          f.pagina = 0;
          await repintarUsuarios();
          break;
        }

        case 'chip-usuarios': {
          const f = filtroUsuarios(), lista = f[b.dataset.grupo], v = b.dataset.valor;
          const i = lista.indexOf(v);
          if (i >= 0) lista.splice(i, 1);
          else {
            lista.push(v);
            const opuesta = OPUESTAS[v];
            if (opuesta && lista.includes(opuesta)) lista.splice(lista.indexOf(opuesta), 1);
          }
          f.pagina = 0;
          await repintarUsuarios();
          break;
        }

        case 'limpiar-usuarios': await filtrarUsuarios({}); break;

        case 'ver-admins': {
          Object.assign(filtroUsuarios(), { q: '', tipos: [], marcas: ['admin'], pais: '', pagina: 0 });
          ir('usuarios');
          break;
        }

        case 'ver-bots': await filtrarUsuarios({ marcas: ['bot'] }); break;

        case 'buscar-ip': {
          cerrarModal();
          await filtrarUsuarios({ q: b.dataset.ip });
          break;
        }

        case 'rol-usuario': {
          const dar = b.dataset.valor === 'true';
          const nombre = b.dataset.nombre;
          const ok = await confirmar(
            dar ? '¿Dar acceso a ' + nombre + '?' : '¿Quitar el acceso a ' + nombre + '?',
            dar ? 'Podrá entrar a este panel, editar el directorio, mover videos, avisar a los seguidores y ver las cuentas de los usuarios.'
                : 'Ya no podrá usar el panel, ni siquiera si lo tiene abierto ahora mismo.',
            dar ? 'Dar acceso' : 'Quitar acceso', !dar || config.ambiente === 'produccion');
          if (!ok) break;
          const r = await api('/api/admin/usuarios/' + encodeURIComponent(id) + '/admin?valor=' + dar, { metodo: 'POST' });
          toast(r.mensaje || 'Listo.');
          if (estado.vista === 'usuarios') repintarUsuarios();
          break;
        }

        case 'cerrar-sesiones-usuario': {
          const ok = await confirmar(
            '¿Cerrar todas las sesiones de ' + b.dataset.nombre + '?',
            'Saldrá de la app y del panel en todos sus teléfonos y navegadores. Para volver a entrar tendrá que usar Google o Apple; no pierde nada de lo que tenía.',
            'Cerrar sesiones', true);
          if (!ok) break;
          const r = await api('/api/admin/usuarios/' + encodeURIComponent(id) + '/cerrar-sesiones', { metodo: 'POST' });
          toast(r.mensaje || 'Sesiones cerradas.');
          if (sesion && sesion.id === id) { location.reload(); break; }
          cerrarModal();
          break;
        }

        case 'filtro-recs': {
          estado.filtroRecs = b.dataset.valor;
          await vistaRecomendaciones();
          break;
        }

        case 'alcance-recs': {
          // El botón ya cambió al pulsarlo. Si algo falla o se cancela, se
          // vuelve a pintar la sección con lo que diga el servidor.
          const nuevo = b.dataset.valor;
          const [titulo] = ALCANCES[nuevo] || [nuevo];
          try {
            const seguro = await confirmar(
              nuevo === 'apagado' ? 'Apagar las recomendaciones' : 'Recomendar canales: ' + titulo.toLowerCase(),
              nuevo === 'apagado'
                ? 'Nadie verá «Recomendar un canal» en la app, tampoco los administradores. Lo que ya llegó no se borra y lo puedes seguir revisando aquí.'
                : nuevo === 'todos'
                  ? 'Cualquier persona que use la app verá «Recomendar un canal» en Ajustes y podrá mandar hasta 5 al día. Cada una cuesta 1 o 2 unidades de la cuota diaria de YouTube.'
                  : ALCANCES[nuevo][1] + ' Verán «Recomendar un canal» en Ajustes de la app.',
              nuevo === 'apagado' ? 'Apagar' : 'Cambiar', nuevo === 'apagado' || (nuevo === 'todos' && config.ambiente === 'produccion'));
            if (seguro) {
              await api('/api/admin/ajustes', { metodo: 'PUT', cuerpo: { recomendaciones: nuevo } });
              toast(nuevo === 'apagado' ? 'Recomendaciones apagadas.' : 'Ahora pueden recomendar: ' + titulo.toLowerCase() + '.');
            }
          } finally {
            if (estado.vista === 'recomendaciones') await vistaRecomendaciones();
          }
          break;
        }

        case 'rec-estado': {
          const ids = b.dataset.ids.split(',').map(Number).filter(Boolean);
          b.disabled = true;
          const r = await api('/api/admin/recomendaciones/estado', { metodo: 'POST', cuerpo: { ids, estado: b.dataset.valor } });
          toast(r.mensaje || 'Listo.');
          await vistaRecomendaciones();
          contarRecomendaciones();
          break;
        }

        case 'rec-borrar': {
          const ids = b.dataset.ids.split(',').map(Number).filter(Boolean);
          const ok = await confirmar('¿Borrar ' + (ids.length === 1 ? 'esta recomendación' : 'estas ' + ids.length + ' recomendaciones') + '?',
            'Desaparece' + (ids.length === 1 ? '' : 'n') + ' de aquí y de «Tus recomendaciones» en la app de quien la mandó. Úsalo para lo que no es un canal o es spam; para decir que no, mejor «Descartar». No se puede deshacer.',
            'Borrar', true);
          if (!ok) break;
          const r = await api('/api/admin/recomendaciones/borrar', { metodo: 'POST', cuerpo: { ids } });
          toast(r.mensaje || 'Listo.');
          await vistaRecomendaciones();
          contarRecomendaciones();
          break;
        }

        case 'rec-agregar': {
          // El formulario de siempre, con el canal ya buscado. Al guardarlo,
          // las recomendaciones de ese canal quedan como agregadas.
          const ids = b.dataset.ids.split(',').map(Number).filter(Boolean);
          await abrirCanal(null, {
            buscar: b.dataset.canal,
            alGuardar: async () => {
              try {
                await api('/api/admin/recomendaciones/estado', { metodo: 'POST', cuerpo: { ids, estado: 'agregado' } });
              } catch (e) {
                toast('El canal quedó guardado, pero no se pudo marcar la recomendación: ' + e.message, true);
              }
              if (estado.vista === 'recomendaciones') await vistaRecomendaciones();
              contarRecomendaciones();
            }
          });
          break;
        }

        case 'recomendar-usuario': {
          const dar = b.dataset.valor === 'true';
          if (!dar) {
            const ok = await confirmar('¿Quitar a ' + b.dataset.nombre + ' de las cuentas que recomiendan?',
              'Si el alcance es «Administradores y cuentas elegidas», deja de ver «Recomendar un canal» en la app. Lo que ya recomendó se queda.',
              'Quitar', false);
            if (!ok) break;
          }
          b.disabled = true;
          const r = await api('/api/admin/usuarios/' + encodeURIComponent(id) + '/recomendar?valor=' + dar, { metodo: 'POST' });
          toast(r.mensaje || 'Listo.');
          if (b.dataset.desde === 'lista') await vistaRecomendaciones();
          else await abrirUsuario(id);
          break;
        }

        case 'editor-usuario': {
          const dar = b.dataset.valor === 'true';
          const ok = await confirmar(
            dar ? '¿Dejar que ' + b.dataset.nombre + ' agregue desde la app?' : '¿Quitarle a ' + b.dataset.nombre + ' el permiso de agregar?',
            dar ? 'Podrá dar de alta creadores, medios y canales de YouTube desde su teléfono. Salen en el directorio en el acto, sin pasar por aquí ni esperar aprobación, y se suscriben a los avisos de sus videos. Lo que agregue lo verás en la sección Editores.'
              : 'Deja de ver «Agregar al directorio» en la app. Lo que ya agregó se queda en el directorio.',
            dar ? 'Dar permiso' : 'Quitar permiso', !dar || config.ambiente === 'produccion');
          if (!ok) break;
          b.disabled = true;
          const r = await api('/api/admin/usuarios/' + encodeURIComponent(id) + '/editor?valor=' + dar, { metodo: 'POST' });
          toast(r.mensaje || 'Listo.');
          if (b.dataset.desde === 'lista') await vistaEditores();
          else await abrirUsuario(id);
          break;
        }

        case 'alcance-stickers': {
          // El botón ya cambió al pulsarlo. Si algo falla o se cancela, se
          // vuelve a pintar la sección con lo que diga el servidor.
          const nuevo = b.dataset.valor;
          const [titulo, detalle] = ALCANCES_STICKERS[nuevo] || [nuevo, ''];
          try {
            const seguro = await confirmar(
              nuevo === 'apagado' ? 'Apagar los stickers' : 'Stickers: ' + titulo.toLowerCase(),
              nuevo === 'apagado'
                ? 'La app deja de enseñar el apartado Stickers y el servidor deja de entregarlos. Lo que cada persona ya agregó a WhatsApp se queda en su WhatsApp.'
                : detalle + ' Verán el apartado Stickers la próxima vez que abran la app, con los paquetes publicados.',
              nuevo === 'apagado' ? 'Apagar' : 'Cambiar', nuevo === 'apagado' || (nuevo === 'todos' && config.ambiente === 'produccion'));
            if (seguro) {
              await api('/api/admin/ajustes', { metodo: 'PUT', cuerpo: { stickers: nuevo } });
              toast(nuevo === 'apagado' ? 'Stickers apagados.' : 'Stickers para: ' + titulo.toLowerCase() + '.');
            }
          } finally {
            if (estado.vista === 'stickers') await vistaStickers();
          }
          break;
        }

        case 'stickers-usuario': {
          const dar = b.dataset.valor === 'true';
          if (!dar) {
            const ok = await confirmar('¿Quitarle los stickers a ' + b.dataset.nombre + '?',
              'Si el alcance es «Administradores y cuentas elegidas», deja de ver el apartado Stickers en la app. Lo que ya agregó a WhatsApp se queda.',
              'Quitar', false);
            if (!ok) break;
          }
          b.disabled = true;
          const r = await api('/api/admin/usuarios/' + encodeURIComponent(id) + '/stickers?valor=' + dar, { metodo: 'POST' });
          toast(r.mensaje || 'Listo.');
          if (b.dataset.desde === 'lista') await vistaStickers();
          else await abrirUsuario(id);
          break;
        }

        case 'crear-paquete': {
          const creadorId = $('#stkCreador').value;
          if (!creadorId) { toast('Elige de qué creador es.', true); break; }
          b.disabled = true;
          const r = await api('/api/admin/stickers/paquetes', { metodo: 'POST', cuerpo: { creadorId, nombre: $('#stkNombre').value.trim() } });
          paqueteActualizado(r.paquete);
          toast(r.mensaje || 'Paquete creado.');
          const nuevo = document.getElementById('paq-' + r.paquete.id);
          if (nuevo) nuevo.scrollIntoView({ behavior: 'smooth', block: 'start' });
          break;
        }

        case 'renombrar-paquete': {
          abrirModal('Renombrar el paquete',
            '<label class="hint" for="stkRenombrar">Nombre</label><input class="input" id="stkRenombrar" maxlength="128" style="margin-top:6px" value="' + esc(b.dataset.nombre) + '">'
            + '<p class="hint" style="margin:8px 0 0">Es el nombre que enseñan la app y WhatsApp. Quien ya lo agregó verá el nuevo cuando la app lo vuelva a mandar.</p>', [
              { texto: 'Cancelar' },
              { texto: 'Guardar', tipo: 'primary', alPulsar: async () => {
                const r = await api('/api/admin/stickers/paquetes/' + encodeURIComponent(id), { metodo: 'PUT', cuerpo: { nombre: $('#stkRenombrar').value } });
                paqueteActualizado(r.paquete);
                toast(r.mensaje || 'Guardado.');
              } }
            ]);
          break;
        }

        case 'publicar-paquete': {
          const publicar = b.dataset.valor === 'true';
          if (!publicar || config.ambiente === 'produccion') {
            const ok = await confirmar(publicar ? '¿Publicar el paquete?' : '¿Despublicar el paquete?',
              publicar ? 'Lo verán en la app quienes tengan los stickers, la próxima vez que entren al apartado.'
                : 'Deja de salir en la app. Quien ya lo agregó a WhatsApp lo conserva.',
              publicar ? 'Publicar' : 'Despublicar', !publicar);
            if (!ok) break;
          }
          b.disabled = true;
          const r = await api('/api/admin/stickers/paquetes/' + encodeURIComponent(id), { metodo: 'PUT', cuerpo: { publicado: publicar } });
          paqueteActualizado(r.paquete);
          toast(r.mensaje || 'Listo.');
          break;
        }

        case 'borrar-paquete': {
          const ok = await confirmar('¿Borrar «' + b.dataset.nombre + '»?',
            'Se borran el paquete y todos sus stickers, y deja de salir en la app. No se puede deshacer. Quien ya lo agregó a WhatsApp lo conserva.',
            'Borrar', true);
          if (!ok) break;
          b.disabled = true;
          const r = await api('/api/admin/stickers/paquetes/' + encodeURIComponent(id), { metodo: 'DELETE' });
          estado.stickers.paquetes = estado.stickers.paquetes.filter((p) => p.id !== id);
          pintarStickers();
          toast(r.mensaje || 'Paquete borrado.');
          break;
        }

        case 'emojis-sticker': {
          abrirModal('Emojis del sticker',
            '<label class="hint" for="stkEmojis">De 1 a 3 emojis, separados por un espacio</label><input class="input" id="stkEmojis" maxlength="40" style="margin-top:6px;font-size:20px" value="' + esc(b.dataset.emojis) + '">'
            + '<p class="hint" style="margin:8px 0 0">WhatsApp los usa para sugerir el sticker cuando alguien escribe ese emoji.</p>', [
              { texto: 'Cancelar' },
              { texto: 'Guardar', tipo: 'primary', alPulsar: async () => {
                const r = await api('/api/admin/stickers/' + encodeURIComponent(id), { metodo: 'PUT', cuerpo: { emojis: $('#stkEmojis').value } });
                paqueteActualizado(r.paquete);
                toast(r.mensaje || 'Guardado.');
              } }
            ]);
          break;
        }

        case 'mover-sticker': {
          const p = estado.stickers.paquetes.find((x) => x.id === b.dataset.paquete);
          if (!p) break;
          const ids = p.stickers.map((s) => s.id);
          const i = ids.indexOf(id), j = i + Number(b.dataset.dir);
          if (i < 0 || j < 0 || j >= ids.length) break;
          [ids[i], ids[j]] = [ids[j], ids[i]];
          b.disabled = true;
          const r = await api('/api/admin/stickers/paquetes/' + encodeURIComponent(p.id) + '/orden', { metodo: 'PUT', cuerpo: { ids } });
          paqueteActualizado(r.paquete);
          break;
        }

        case 'quitar-sticker': {
          const ok = await confirmar('¿Quitar este sticker?', 'Se borra del paquete. Si el paquete está publicado y se queda con menos de 3, se despublica hasta que vuelva a tenerlos.', 'Quitar', true);
          if (!ok) break;
          b.disabled = true;
          const r = await api('/api/admin/stickers/' + encodeURIComponent(id), { metodo: 'DELETE' });
          paqueteActualizado(r.paquete);
          toast(r.mensaje || 'Quitado.');
          break;
        }

        case 'icono-desde-sticker': {
          const p = estado.stickers.paquetes.find((x) => x.id === id);
          if (!p || !p.stickers.length) break;
          b.disabled = true;
          const blob = await blobConSesion('/api/stickers/' + p.stickers[0].id + '/imagen?v=' + p.version);
          const r = await subirIcono(id, await prepararIcono(blob));
          toast(r.mensaje || 'Ícono guardado.');
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
