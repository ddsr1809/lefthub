// Panel de administración de TubeHub.
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
  const CONFIG = window.TUBEHUB_CONFIG || {};
  const BASE = (CONFIG.apiBase || '').replace(/\/$/, '');

  const CATEGORIAS = { cine: 'Cine', comida: 'Comida', politica: 'Política', musica: 'Música', salud: 'Salud', noticias: 'Noticias', tecnologia: 'Tecnología', otros: 'Otros' };
  const PLATAFORMAS = { youtube: 'YouTube', tiktok: 'TikTok', twitch: 'Twitch', instagram: 'Instagram', spotify: 'Spotify', patreon: 'Patreon', web: 'Web' };
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
  const CLAVE = 'tubehub_admin';
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
    ambiente: host.startsWith('testhub.') || host.includes('test') ? 'pruebas'
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
    document.title = (config.ambiente === 'produccion' ? '' : '[' + config.ambiente + '] ') + 'Panel de TubeHub';
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
    filtroCreadores: { q: '', categoria: '' },
    publicaciones: null
  };

  const VISTAS = {
    resumen: vistaResumen,
    creadores: vistaCreadores,
    suscripciones: vistaSuscripciones,
    publicaciones: vistaPublicaciones,
    reportes: vistaReportes,
    administradores: vistaAdministradores
  };

  function ir(vista) {
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

  // Los contadores del menú salen de las mismas rutas que usan las vistas.
  async function refrescarContadores() {
    try {
      const [creadores, reportes] = await Promise.all([cargarCreadores(true), api('/api/admin/reportes?limite=200')]);
      const problemas = creadores.filter(tieneProblema).length;
      const ns = $('#n-susc'), nr = $('#n-rep');
      ns.textContent = problemas; ns.hidden = !problemas;
      nr.textContent = reportes.length; nr.hidden = !reportes.length;
      return { creadores, reportes };
    } catch (e) { return null; }
  }

  const tieneYouTube = (c) => (c.conexiones || []).some((x) => x.plataforma === 'youtube' && x.channelId);
  // Un creador visible con canal debería tener la suscripción ACTIVA; si no, no llegan avisos.
  const tieneProblema = (c) => c.activo && tieneYouTube(c) && c.estadoSuscripcion !== 'ACTIVA';
  const vencePronto = (c) => c.estadoSuscripcion === 'ACTIVA' && c.expiraEn && (new Date(c.expiraEn) - Date.now()) < 2 * 864e5;

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
    const { creadores, reportes } = base;
    estado.publicaciones = pubs;

    const activos = creadores.filter((c) => c.activo);
    const conYT = activos.filter(tieneYouTube);
    const activas = conYT.filter((c) => c.estadoSuscripcion === 'ACTIVA').length;
    const problemas = creadores.filter(tieneProblema).length;
    const porVencer = creadores.filter(vencePronto).length;
    const seguidores = creadores.reduce((s, c) => s + (c.seguidores || 0), 0);
    const hace7d = Date.now() - 7 * 864e5;
    const pubs7d = pubs.filter((p) => p.publicadoEn && new Date(p.publicadoEn) > hace7d).length;
    const movidos = pubs.filter((p) => p.estado === 'moved').length;

    const alertas = [];
    if (problemas) alertas.push(['b-warn', (problemas === 1 ? '1 creador visible no tiene la suscripción de YouTube activa, así que no genera avisos.' : num(problemas) + ' creadores visibles no tienen la suscripción de YouTube activa, así que no generan avisos.'), 'suscripciones']);
    if (porVencer) alertas.push(['b-warn', (porVencer === 1 ? '1 suscripción vence' : num(porVencer) + ' suscripciones vencen') + ' en menos de 2 días. El servidor las renueva solo cada 4 días; si no se renuevan, revisa los registros.', 'suscripciones']);
    if (reportes.length) alertas.push(['b-info', plural(reportes.length, 'reporte pendiente', 'reportes pendientes') + ' de enlaces rotos.', 'reportes']);

    main.innerHTML = `
      <div class="head"><div><h1>Resumen</h1><p class="sub">Estado del directorio, de las suscripciones a YouTube y de los reportes.</p></div>
        <div class="toolbar"><button class="btn primary" data-accion="nuevo-creador">Nuevo creador</button></div></div>
      <div class="stack">
        <div class="stats tres">
          <div class="stat"><div class="k">Creadores visibles</div><div class="v">${num(activos.length)}</div><div class="n">de ${num(creadores.length)} en el directorio</div></div>
          <div class="stat"><div class="k">Suscripciones activas</div><div class="v">${num(activas)}</div><div class="n">${problemas ? num(problemas) + ' con problemas' : 'de ' + num(conYT.length) + ' canales, todas bien'}</div></div>
          <div class="stat"><div class="k">Seguimientos</div><div class="v">${num(seguidores)}</div><div class="n">suma de seguidores de todos los creadores</div></div>
          <div class="stat"><div class="k">Reportes pendientes</div><div class="v">${num(reportes.length)}</div><div class="n">enlaces rotos y otras incidencias</div></div>
          <div class="stat"><div class="k">Videos en 7 días</div><div class="v">${num(pubs7d)}</div><div class="n">de los últimos ${num(pubs.length)} detectados</div></div>
          <div class="stat"><div class="k">Videos movidos</div><div class="v">${num(movidos)}</div><div class="n">redirigidos a otro enlace</div></div>
        </div>
        <section class="panel"><div class="panel-head"><h2>Qué revisar</h2></div>
          ${alertas.length ? '<ul class="alertas">' + alertas.map(([c, t, v]) => `<li><span><span class="badge ${c}">${c === 'b-warn' ? 'Atención' : 'Pendiente'}</span> ${esc(t)}</span><button class="link" data-ir="${v}">Revisar</button></li>`).join('') + '</ul>' : '<div class="empty">Todo en orden.</div>'}
        </section>
      </div>`;
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

  function filasCreadores() {
    const { q, categoria } = estado.filtroCreadores;
    const t = q.trim().toLowerCase();
    const lista = (estado.creadores || []).filter((c) =>
      (!t || c.nombre.toLowerCase().includes(t)) && (!categoria || c.categoria === categoria));
    if (!lista.length) {
      return `<tr><td colspan="6"><div class="empty">${estado.creadores && estado.creadores.length ? 'Ningún creador coincide.' : 'Todavía no hay creadores. Agrega el primero con su canal de YouTube.'}</div></td></tr>`;
    }
    return lista.map((c) => `<tr>
      <td><div class="who">${avatar(c.fotoUrl)}<div><b>${esc(c.nombre)}</b><span>${esc(CATEGORIAS[c.categoria] || c.categoria)}</span></div></div></td>
      <td><div class="chips">${(c.conexiones || []).map((x) => `<a class="badge b-mute" href="${esc(x.url)}" target="_blank" rel="noopener">${esc(PLATAFORMAS[x.plataforma] || x.plataforma)}</a>`).join('') || '<span class="muted">—</span>'}</div></td>
      <td>${badgeSusc(c.estadoSuscripcion)}${c.expiraEn ? `<div class="muted" style="font-size:12.5px">vence ${esc(relativo(c.expiraEn))}</div>` : ''}</td>
      <td class="num">${num(c.seguidores)}</td>
      <td>${c.activo ? '<span class="badge b-ok">Visible</span>' : '<span class="badge b-mute">Oculto</span>'}</td>
      <td class="acciones">
        <button class="btn sm" data-accion="editar-creador" data-id="${esc(c.id)}">Editar</button>
        <button class="btn sm danger" data-accion="borrar-creador" data-id="${esc(c.id)}">Eliminar</button>
      </td></tr>`).join('');
  }

  async function vistaCreadores() {
    await cargarCreadores(true);
    const f = estado.filtroCreadores;
    main.innerHTML = `
      <div class="head"><div><h1>Creadores</h1><p class="sub">El directorio que ven las apps. Al guardar un creador con canal de YouTube, el servidor lo suscribe al hub para recibir sus videos nuevos.</p></div>
        <button class="btn primary" data-accion="nuevo-creador">Nuevo creador</button></div>
      <section class="panel">
        <div class="panel-head"><div class="toolbar">
          <input class="input buscar" id="qCreadores" type="search" placeholder="Buscar por nombre" value="${esc(f.q)}" aria-label="Buscar creadores">
          <select class="input" id="catCreadores" aria-label="Categoría"><option value="">Todas las categorías</option>${Object.entries(CATEGORIAS).map(([k, v]) => `<option value="${k}" ${f.categoria === k ? 'selected' : ''}>${v}</option>`).join('')}</select>
        </div><span class="muted" style="font-size:13px">${estado.creadores.length} en total</span></div>
        <div class="tablewrap"><table><thead><tr><th>Creador</th><th>Plataformas</th><th>Suscripción</th><th>Seguidores</th><th>En la app</th><th></th></tr></thead>
        <tbody id="filasCreadores">${filasCreadores()}</tbody></table></div>
      </section>`;
    $('#qCreadores').addEventListener('input', (e) => { f.q = e.target.value; $('#filasCreadores').innerHTML = filasCreadores(); });
    $('#catCreadores').addEventListener('change', (e) => { f.categoria = e.target.value; $('#filasCreadores').innerHTML = filasCreadores(); });
  }

  function formularioCreador(c) {
    c = c || { activo: true, categoria: 'otros', conexiones: [] };
    const cx = {};
    (c.conexiones || []).forEach((x) => { cx[x.plataforma] = x; });
    const yt = cx.youtube || {};
    const otras = Object.keys(PLATAFORMAS).filter((p) => p !== 'youtube');
    return `
      <div class="stack" style="gap:14px">
        <fieldset><legend>Canal de YouTube</legend>
          <div class="toolbar" style="flex-wrap:nowrap">
            <input class="input" id="fBuscarCanal" placeholder="@handle, URL del canal o ID que empieza por UC" value="${esc(yt.handle ? '@' + yt.handle.replace(/^@/, '') : (yt.channelId || ''))}">
            <button class="btn" type="button" id="btnBuscarCanal">Buscar</button>
          </div>
          <div id="fCanal" style="margin-top:10px">${yt.channelId ? `<div class="canal"><div><b>Canal vinculado</b><div class="mono muted">${esc(yt.channelId)}</div></div></div>` : '<span class="hint">Busca el canal para llenar los datos y activar los avisos de videos nuevos.</span>'}</div>
          <input type="hidden" id="fYtUrl" value="${esc(yt.url || '')}">
          <input type="hidden" id="fYtHandle" value="${esc(yt.handle || '')}">
          <input type="hidden" id="fYtId" value="${esc(yt.channelId || '')}">
        </fieldset>
        <div class="form">
          <label class="f">Nombre<input class="input" id="fNombre" maxlength="60" value="${esc(c.nombre)}"></label>
          <label class="f">Categoría<select class="input" id="fCategoria">${Object.entries(CATEGORIAS).map(([k, v]) => `<option value="${k}" ${c.categoria === k ? 'selected' : ''}>${v}</option>`).join('')}</select></label>
          <label class="f full">Descripción<textarea class="input" id="fBio" rows="3" maxlength="600">${esc(c.bio)}</textarea></label>
          <label class="f full">Foto (URL)<input class="input" id="fFoto" value="${esc(c.fotoUrl)}" placeholder="https://"></label>
          <label class="check full"><input type="checkbox" id="fActivo" ${c.activo ? 'checked' : ''}> Visible en la app y suscrito a sus videos</label>
        </div>
        <fieldset><legend>Otras plataformas</legend>
          <div class="form">${otras.map((p) => `<label class="f">${PLATAFORMAS[p]}<input class="input" data-plataforma="${p}" value="${esc(cx[p] ? cx[p].url : '')}" placeholder="https://"></label>`).join('')}</div>
        </fieldset>
      </div>`;
  }

  async function buscarCanal() {
    const q = $('#fBuscarCanal').value.trim();
    if (!q) { toast('Escribe un @handle, una URL o un ID de canal.', true); return; }
    const btn = $('#btnBuscarCanal');
    btn.disabled = true; btn.textContent = 'Buscando…';
    try {
      const d = await api('/api/admin/canal?query=' + encodeURIComponent(q));
      $('#fYtId').value = d.channelId;
      $('#fYtHandle').value = d.handle || '';
      $('#fYtUrl').value = d.handle ? 'https://www.youtube.com/@' + d.handle.replace(/^@/, '') : 'https://www.youtube.com/channel/' + d.channelId;
      if (!$('#fNombre').value.trim()) $('#fNombre').value = (d.titulo || '').slice(0, 60);
      if (!$('#fBio').value.trim() && d.descripcion) $('#fBio').value = d.descripcion.slice(0, 600);
      if (d.fotoUrl) $('#fFoto').value = d.fotoUrl;
      $('#fCanal').innerHTML = `<div class="canal">${d.fotoUrl ? `<img src="${esc(d.fotoUrl)}" alt="" referrerpolicy="no-referrer">` : ''}<div><b>${esc(d.titulo)}</b><div class="muted" style="font-size:13px">${d.handle ? esc('@' + d.handle.replace(/^@/, '')) + ' · ' : ''}${d.suscriptores ? esc(d.suscriptores) + ' suscriptores' : ''}</div><div class="mono muted">${esc(d.channelId)}</div></div></div>`;
    } catch (e) {
      toast(e.message, true);
    } finally {
      btn.disabled = false; btn.textContent = 'Buscar';
    }
  }

  function abrirCreador(id) {
    const c = id ? (estado.creadores || []).find((x) => x.id === id) : null;
    if (id && !c) return;
    abrirModal(c ? 'Editar creador' : 'Nuevo creador', formularioCreador(c), [
      { texto: 'Cancelar' },
      { texto: c ? 'Guardar cambios' : 'Crear creador', tipo: 'primary', alPulsar: async () => {
        const nombre = $('#fNombre').value.trim();
        if (nombre.length < 2) { toast('El nombre necesita al menos 2 letras.', true); return false; }
        const conexiones = {};
        const ytId = $('#fYtId').value.trim();
        if (ytId) {
          conexiones.youtube = { plataforma: 'youtube', url: $('#fYtUrl').value || 'https://www.youtube.com/channel/' + ytId, handle: $('#fYtHandle').value || null, channelId: ytId };
        }
        for (const inp of $$('#mCuerpo [data-plataforma]')) {
          const url = inp.value.trim();
          if (!url) continue;
          if (!/^https?:\/\//i.test(url)) { toast('El enlace de ' + PLATAFORMAS[inp.dataset.plataforma] + ' debe empezar por https://', true); inp.focus(); return false; }
          conexiones[inp.dataset.plataforma] = { plataforma: inp.dataset.plataforma, url, handle: null, channelId: null };
        }
        if (!Object.keys(conexiones).length) { toast('Agrega al menos una plataforma.', true); return false; }
        const r = await api('/api/admin/creadores', { metodo: 'POST', cuerpo: {
          id: c ? c.id : null, nombre, categoria: $('#fCategoria').value, bio: $('#fBio').value.trim() || null,
          fotoUrl: $('#fFoto').value.trim() || null, activo: $('#fActivo').checked, conexiones
        } });
        if (r.avisoSuscripcion) toast('Creador guardado, pero el hub de YouTube respondió: ' + r.avisoSuscripcion, true);
        else toast(c ? 'Cambios guardados.' : 'Creador creado. La suscripción a YouTube queda pendiente hasta que el hub la verifique.');
        estado.creadores = null;
        if (estado.vista === 'creadores') vistaCreadores();
        refrescarContadores();
      } }
    ], () => {
      $('#btnBuscarCanal').addEventListener('click', buscarCanal);
      $('#fBuscarCanal').addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); buscarCanal(); } });
    });
  }

  // ---------------------------------------------------------------------------
  // Suscripciones WebSub
  // ---------------------------------------------------------------------------
  async function vistaSuscripciones() {
    const creadores = await cargarCreadores(true);
    const conYT = creadores.filter(tieneYouTube)
      .sort((a, b) => Number(tieneProblema(b)) - Number(tieneProblema(a)) || String(a.expiraEn || '').localeCompare(String(b.expiraEn || '')));
    const fallidas = creadores.filter(tieneProblema).length;
    main.innerHTML = `
      <div class="head"><div><h1>Suscripciones a YouTube</h1><p class="sub">Cada creador con canal se suscribe al hub de Google, que avisa al servidor cuando sube un video. El hub las corta a los 10 días y el servidor las renueva solo cada 4. Reintentar vuelve a guardar al creador, lo que repite la solicitud al hub.</p></div>
        ${fallidas ? `<button class="btn primary" data-accion="reintentar-fallidas">${fallidas === 1 ? 'Reintentar la que tiene problemas' : 'Reintentar las ' + num(fallidas) + ' con problemas'}</button>` : ''}</div>
      <section class="panel"><div class="tablewrap"><table><thead><tr><th>Creador</th><th>Canal</th><th>Estado</th><th>Vence</th><th>En la app</th><th></th></tr></thead><tbody>
      ${conYT.length ? conYT.map((c) => {
        const canal = c.conexiones.find((x) => x.plataforma === 'youtube');
        return `<tr>
          <td><div class="who">${avatar(c.fotoUrl)}<div><b>${esc(c.nombre)}</b><span>${plural(c.seguidores, 'seguidor', 'seguidores')}</span></div></div></td>
          <td><a class="mono" href="https://www.youtube.com/channel/${esc(canal.channelId)}" target="_blank" rel="noopener">${esc(canal.channelId)}</a></td>
          <td>${c.activo ? badgeSusc(c.estadoSuscripcion || 'PENDIENTE_VERIFICACION') : '<span class="badge b-mute">Sin suscribir (oculto)</span>'}</td>
          <td class="num" title="${esc(fmtFecha(c.expiraEn))}">${c.expiraEn ? esc(relativo(c.expiraEn)) : '—'}${vencePronto(c) ? ' <span class="badge b-warn">pronto</span>' : ''}</td>
          <td>${c.activo ? '<span class="badge b-ok">Visible</span>' : '<span class="badge b-mute">Oculto</span>'}</td>
          <td class="acciones">${c.activo ? `<button class="btn sm" data-accion="reintentar" data-id="${esc(c.id)}">Reintentar</button>` : ''}</td>
        </tr>`;
      }).join('') : '<tr><td colspan="6"><div class="empty">Ningún creador tiene canal de YouTube todavía.</div></td></tr>'}
      </tbody></table></div></section>`;
  }

  /** El mismo cuerpo que manda el formulario, armado desde el listado. */
  function cuerpoDe(c) {
    const conexiones = {};
    (c.conexiones || []).forEach((x) => { conexiones[x.plataforma] = x; });
    return { id: c.id, nombre: c.nombre, categoria: c.categoria, bio: c.bio || null, fotoUrl: c.fotoUrl || null, activo: c.activo, conexiones };
  }

  // ---------------------------------------------------------------------------
  // Publicaciones
  // ---------------------------------------------------------------------------
  const ESTADOS_PUB = { ok: ['Normal', 'b-ok'], moved: ['Movido', 'b-info'], removed: ['Retirado', 'b-bad'] };

  async function vistaPublicaciones() {
    const lista = await api('/api/admin/publicaciones?limite=100');
    estado.publicaciones = lista;
    main.innerHTML = `
      <div class="head"><div><h1>Publicaciones</h1><p class="sub">Los videos que detectó el servidor. Si una plataforma tumba uno, muévelo a otro enlace y avisa a los seguidores del creador.</p></div></div>
      <section class="panel"><div class="tablewrap"><table><thead><tr><th></th><th>Video</th><th>Creador</th><th>Estado</th><th>Publicado</th><th></th></tr></thead><tbody>
      ${lista.length ? lista.map((p) => {
        const [t, c] = ESTADOS_PUB[p.estado] || [p.estado, 'b-mute'];
        const enlace = p.estado === 'moved' && p.destinoUrl ? p.destinoUrl : p.url;
        return `<tr>
          <td>${p.miniaturaUrl ? `<img class="thumb" src="${esc(p.miniaturaUrl)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : ''}</td>
          <td><div class="clip" style="max-width:380px"><b>${esc(p.titulo)}</b></div><span class="muted" style="font-size:13px">${p.enVivo ? 'En vivo' : p.tipo === 'short' ? 'Short' : 'Video'} · <span class="mono">${esc(p.videoId)}</span></span></td>
          <td>${esc(p.creadorNombre || '—')}</td>
          <td><span class="badge ${c}">${esc(t)}</span>${p.estado === 'moved' ? `<div class="muted" style="font-size:12.5px">a ${esc(PLATAFORMAS[p.destinoPlataforma] || p.destinoPlataforma || '')}</div>` : ''}</td>
          <td class="num">${esc(fmtFecha(p.publicadoEn))}</td>
          <td class="acciones">${enlace ? `<a class="btn sm" href="${esc(enlace)}" target="_blank" rel="noopener">Abrir</a>` : ''}<button class="btn sm" data-accion="mover" data-video="${esc(p.videoId)}">Mover</button></td>
        </tr>`;
      }).join('') : '<tr><td colspan="6"><div class="empty">Todavía no se ha detectado ningún video.</div></td></tr>'}
      </tbody></table></div></section>`;
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
          <p class="hint" style="margin:10px 0 0">Para quitarle el rol a alguien, por ahora hace falta hacerlo en la base: <span class="mono">update usuarios set es_admin = false where email = '…';</span></p>
        </div></section>
      </div>`;
    $('#admCorreo').addEventListener('keydown', (e) => { if (e.key === 'Enter') $('[data-accion="nombrar-admin"]').click(); });
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
        case 'nuevo-creador': abrirCreador(null); break;
        case 'editar-creador': abrirCreador(id); break;

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

        case 'reintentar': {
          const c = (estado.creadores || []).find((x) => x.id === id);
          if (!c) break;
          b.disabled = true;
          const r = await api('/api/admin/creadores', { metodo: 'POST', cuerpo: cuerpoDe(c) });
          if (r.avisoSuscripcion) toast('El hub respondió: ' + r.avisoSuscripcion, true);
          else toast('Solicitud enviada. Pasa a Activa cuando el hub la verifique.');
          setTimeout(() => { if (estado.vista === 'suscripciones') vistaSuscripciones(); refrescarContadores(); }, 2000);
          break;
        }

        case 'reintentar-fallidas': {
          const lista = (estado.creadores || []).filter(tieneProblema);
          b.disabled = true;
          let bien = 0, mal = 0;
          for (const c of lista) {
            b.textContent = `Reintentando ${bien + mal + 1} de ${lista.length}…`;
            try {
              const r = await api('/api/admin/creadores', { metodo: 'POST', cuerpo: cuerpoDe(c) });
              if (r.avisoSuscripcion) mal++; else bien++;
            } catch (err) { mal++; }
          }
          toast(mal ? `${bien} enviadas, ${mal} fallaron.` : plural(bien, 'solicitud enviada', 'solicitudes enviadas') + ' al hub.', !!mal);
          setTimeout(() => { if (estado.vista === 'suscripciones') vistaSuscripciones(); refrescarContadores(); }, 2000);
          break;
        }

        case 'mover': abrirMover(b.dataset.video); break;

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
    const v = location.hash.slice(1);
    if (sesion && v !== estado.vista && VISTAS[v]) ir(v);
  });

  arrancar();
})();
