// Versiones del directorio: comparar dos fotos y migrar de pruebas a producción.
//
// Aquí no hay pantalla ni red: solo las decisiones. Entra el JSON que arma el
// servidor (ver V10__versiones.sql) y sale qué cambia, qué choca y qué hay que
// guardar. La sección Versiones de app.js lo pinta y hace las peticiones.
//
// Migrar es una comparación a tres bandas, ficha por ficha y campo por campo:
//
//   base        la última versión de pruebas que producción ya aplicó
//   pruebas     la versión que se quiere aplicar ahora
//   producción  cómo está producción en este momento
//
//   · cambió solo en pruebas      -> se lleva a producción
//   · cambió solo en producción   -> se deja como está
//   · cambió en los dos, distinto -> conflicto: decide una persona
//
// Sin base (la primera vez, o una ficha que no estaba en ella) no se puede
// saber quién cambió qué, y cada diferencia se pregunta.
//
// Los ids no coinciden entre ambientes. Todo se compara con los ids de
// producción; lo que nació en pruebas y todavía no existe allá lleva un id
// provisional, "nuevo:<id de pruebas>", hasta que producción lo crea.
//
// Pruebas: node admin/versiones.test.js

(function (raiz) {
  'use strict';

  const PLATAFORMAS = { youtube: 'YouTube', tiktok: 'TikTok', twitch: 'Twitch', instagram: 'Instagram', x: 'X', facebook: 'Facebook', threads: 'Threads', telegram: 'Telegram', spotify: 'Spotify', patreon: 'Patreon', web: 'Página web' };

  const CAMPOS = {
    creador: { nombre: 'Nombre', categoria: 'Tema', bio: 'Descripción', foto_url: 'Foto', activo: 'Visible en la app' },
    productora: { nombre: 'Nombre', descripcion: 'Descripción', logo_url: 'Logo', activo: 'Visible en la app', en_directorio: 'Aparece en el directorio', categoria: 'Tema' }
  };
  const LISTA = { creador: 'creadores', productora: 'productoras' };
  const TIPOS = ['productora', 'creador'];     // las productoras primero: los creadores las nombran

  const NUEVO = 'nuevo:';
  const esNuevo = (id) => typeof id === 'string' && id.startsWith(NUEVO);

  // ---------------------------------------------------------------------------
  // Una ficha, campo por campo
  // ---------------------------------------------------------------------------

  const vacio = (v) => v === undefined || v === null || v === '';
  const limpio = (v) => (vacio(v) ? null : v);

  /** Cómo se reconoce un canal en cualquier ambiente: igual que el servidor al guardarlo. */
  const claveDeCanal = (k) => k.plataforma + '|' + (k.channel_id || k.url);

  function canalLimpio(k) {
    return {
      plataforma: k.plataforma,
      nombre: limpio(k.nombre),
      url: k.url,
      handle: limpio(k.handle),
      channel_id: limpio(k.channel_id),
      productora_id: limpio(k.productora_id),
      vinculados: (k.vinculados || []).slice().sort()
    };
  }

  /**
   * La ficha como un mapa plano de campos:
   *   c:nombre, c:bio...   los datos sueltos
   *   p:<id>               figura en esa productora (solo creadores)
   *   k:<clave>            un canal o una red, entero
   * y aparte `orden`, las claves de sus canales en su orden.
   */
  function aplanar(tipo, ficha) {
    const campos = {};
    Object.keys(CAMPOS[tipo]).forEach((c) => { campos['c:' + c] = limpio(ficha[c]); });
    (ficha.productoras || []).forEach((p) => { campos['p:' + p] = true; });
    const orden = [];
    (ficha.canales || []).forEach((k) => {
      const clave = 'k:' + claveDeCanal(k);
      if (campos[clave]) return;                 // repetido: gana el primero, como en el servidor
      campos[clave] = canalLimpio(k);
      orden.push(clave);
    });
    return { campos, orden };
  }

  /** Lo contrario de aplanar: la ficha con sus listas. */
  function armar(tipo, id, campos, orden) {
    const ficha = { id };
    Object.keys(CAMPOS[tipo]).forEach((c) => { ficha[c] = limpio(campos['c:' + c]); });
    if (tipo === 'creador') {
      ficha.productoras = Object.keys(campos).filter((c) => c.startsWith('p:')).map((c) => c.slice(2)).sort();
    }
    ficha.canales = orden.filter((c) => campos[c]).map((c) => campos[c]);
    return ficha;
  }

  // Comparación por contenido, sin depender del orden de las claves.
  function texto(v) {
    if (v === undefined) return '\u0000';
    if (v === null || typeof v !== 'object') return JSON.stringify(v);
    if (Array.isArray(v)) return '[' + v.map(texto).join(',') + ']';
    return '{' + Object.keys(v).sort().map((k) => JSON.stringify(k) + ':' + texto(v[k])).join(',') + '}';
  }
  const igual = (a, b) => texto(a) === texto(b);

  // ---------------------------------------------------------------------------
  // Emparejar las fichas de pruebas con las de producción
  // ---------------------------------------------------------------------------

  const normal = (s) => String(s || '').trim().toLowerCase();
  const deYouTube = (ficha) => (ficha.canales || []).filter((k) => k.plataforma === 'youtube' && k.channel_id).map((k) => k.channel_id);

  /**
   * A qué ficha de producción corresponde cada una de pruebas.
   *
   * Por orden: el origen que pruebas tiene anotado (su copia vino de
   * producción, o ya se enlazó en una migración), lo que producción apuntó en
   * su última migración, y si nada de eso sirve, el parecido: el mismo canal
   * de YouTube o el mismo nombre. Esto último cubre una migración que se
   * quedó a medias, con el creador ya creado pero sin enlazar.
   *
   * @return {{ mapa, idPruebas, avisos, desaparecidos, saltadas }}
   *   mapa[tipo][idDePruebas] = id de producción, o "nuevo:<idDePruebas>"
   */
  function emparejar(pruebas, produccion, base, ids) {
    const mapa = { creador: {}, productora: {} };
    const idPruebas = {};
    const avisos = [];
    const desaparecidos = {};
    const saltadas = {};

    TIPOS.forEach((tipo) => {
      const suyas = pruebas[LISTA[tipo]] || [];
      const deProd = produccion[LISTA[tipo]] || [];
      const enProd = new Set(deProd.map((f) => f.id));
      const enBase = new Set(((base && base[LISTA[tipo]]) || []).map((f) => f.id));
      const anotados = (ids && ids[LISTA[tipo]]) || {};
      const ocupados = new Set();
      const pendientes = [];

      const asignar = (ficha, id) => {
        if (ocupados.has(id)) {
          saltadas[ficha.id] = true;
          avisos.push(`"${ficha.nombre}" está dos veces en pruebas y las dos apuntan a la misma ficha de producción. Solo se toma la primera; revisa el duplicado en pruebas.`);
          return;
        }
        ocupados.add(id);
        mapa[tipo][ficha.id] = id;
        idPruebas[id] = ficha.id;
      };

      suyas.forEach((ficha) => {
        const candidatos = [ficha.origen_id, anotados[ficha.id]].filter(Boolean);
        const vivo = candidatos.find((id) => enProd.has(id));
        if (vivo) { asignar(ficha, vivo); return; }

        // Tenía pareja en producción y ya no está: allá se retiró.
        const ido = candidatos.find((id) => enBase.has(id)) || ficha.origen_id;
        if (ido) {
          asignar(ficha, ido);
          desaparecidos[ido] = true;
          return;
        }
        pendientes.push(ficha);
      });

      pendientes.forEach((ficha) => {
        const libres = deProd.filter((f) => !ocupados.has(f.id));
        const canales = deYouTube(ficha);
        let parecidos = tipo === 'creador' && canales.length
          ? libres.filter((f) => deYouTube(f).some((c) => canales.includes(c)))
          : [];
        let por = 'el mismo canal de YouTube';
        if (!parecidos.length) {
          parecidos = libres.filter((f) => normal(f.nombre) === normal(ficha.nombre));
          por = 'el mismo nombre';
        }
        if (parecidos.length === 1) {
          asignar(ficha, parecidos[0].id);
          avisos.push(`"${ficha.nombre}" no estaba enlazado con producción: se emparejó con la ficha que tiene ${por}.`);
        } else {
          asignar(ficha, NUEVO + ficha.id);
        }
      });
    });

    return { mapa, idPruebas, avisos, desaparecidos, saltadas };
  }

  /** La versión de pruebas con los ids de producción. Las referencias a fichas que no vienen en ella se caen. */
  function traducir(pruebas, par) {
    const aCreador = (id) => par.mapa.creador[id];
    const aProductora = (id) => par.mapa.productora[id];
    const canales = (lista) => (lista || []).map((k) => Object.assign({}, k, {
      productora_id: k.productora_id ? aProductora(k.productora_id) || null : null,
      vinculados: (k.vinculados || []).map(aCreador).filter(Boolean)
    }));

    return {
      creadores: (pruebas.creadores || []).filter((f) => !par.saltadas[f.id]).map((f) => Object.assign({}, f, {
        id: aCreador(f.id),
        productoras: (f.productoras || []).map(aProductora).filter(Boolean),
        canales: canales(f.canales)
      })),
      productoras: (pruebas.productoras || []).filter((f) => !par.saltadas[f.id]).map((f) => Object.assign({}, f, {
        id: aProductora(f.id),
        canales: canales(f.canales)
      }))
    };
  }

  // ---------------------------------------------------------------------------
  // Cómo se lee cada cosa
  // ---------------------------------------------------------------------------

  function lector(nombres) {
    const nombre = (id) => nombres[id] || 'alguien que ya no está';

    function canal(k) {
      if (!k) return 'No lo tiene';
      const partes = [k.handle ? '@' + String(k.handle).replace(/^@/, '') : k.url];
      if (k.handle && k.url) partes.push(k.url);
      if (k.nombre) partes.push('etiqueta «' + k.nombre + '»');
      if (k.productora_id) partes.push('de ' + nombre(k.productora_id));
      if (k.vinculados && k.vinculados.length) partes.push('aparece con ' + k.vinculados.map(nombre).join(', '));
      return partes.join(' · ');
    }

    function valor(campo, v) {
      if (campo.startsWith('k:')) return canal(v);
      if (campo.startsWith('p:')) return v ? 'Sí' : 'No';
      if (v === true) return 'Sí';
      if (v === false) return 'No';
      return vacio(v) ? '(vacío)' : String(v);
    }

    function etiqueta(tipo, campo, muestra) {
      if (campo.startsWith('c:')) return CAMPOS[tipo][campo.slice(2)] || campo.slice(2);
      if (campo.startsWith('p:')) return 'Figura en ' + nombre(campo.slice(2));
      const plataforma = (muestra && muestra.plataforma) || campo.slice(2).split('|')[0];
      return (plataforma === 'youtube' ? 'Canal de ' : 'Red: ') + (PLATAFORMAS[plataforma] || plataforma);
    }

    return { valor, etiqueta, nombre };
  }

  function nombresDe() {
    const nombres = {};
    Array.from(arguments).forEach((foto) => {
      if (!foto) return;
      TIPOS.forEach((tipo) => (foto[LISTA[tipo]] || []).forEach((f) => { if (f.nombre) nombres[f.id] = f.nombre; }));
    });
    return nombres;
  }

  const porId = (foto, tipo) => {
    const mapa = {};
    ((foto && foto[LISTA[tipo]]) || []).forEach((f) => { mapa[f.id] = f; });
    return mapa;
  };

  // ---------------------------------------------------------------------------
  // El plan de una migración
  // ---------------------------------------------------------------------------

  /**
   * Qué pasaría al aplicar en producción esa versión de pruebas.
   *
   * @param entrada.base        la base de la última migración (ya con ids de producción), o null
   * @param entrada.pruebas     el contenido de la versión de pruebas
   * @param entrada.produccion  el directorio de producción ahora
   * @param entrada.ids         { creadores: {idPruebas: idProd}, productoras: {...} } de migraciones anteriores
   *
   * @return un plan: `fichas` (cada una con sus `items`), `avisos`, y lo que
   *         necesitan operaciones() y estadoFinal(). Un item es de tipo
   *           auto       cambió solo en pruebas: se lleva
   *           conflicto  hay que elegir 'pruebas' o 'produccion'
   *           confirmar  quitar un canal en producción: solo si se pide
   */
  function planear(entrada) {
    const base = entrada.base || null;
    const produccion = entrada.produccion;
    const par = emparejar(entrada.pruebas, produccion, base, entrada.ids);
    const suyas = traducir(entrada.pruebas, par);
    const lee = lector(nombresDe(base, produccion, suyas));
    const avisos = par.avisos.slice();
    const fichas = [];
    let serie = 0;

    TIPOS.forEach((tipo) => {
      const enProd = porId(produccion, tipo);
      const enBase = porId(base, tipo);
      const vistas = {};

      (suyas[LISTA[tipo]] || []).forEach((ficha) => {
        vistas[ficha.id] = true;
        const t = aplanar(tipo, ficha);
        const actual = enProd[ficha.id];

        if (!actual) {
          if (esNuevo(ficha.id)) {
            fichas.push({ tipo, id: ficha.id, idPruebas: par.idPruebas[ficha.id], nombre: ficha.nombre, estado: 'nuevo', items: [], suyo: t, nuestro: null, viejo: null });
          } else {
            avisos.push(`"${ficha.nombre}" ya no existe en producción: allá se retiró. No se vuelve a crear; si lo quieres de vuelta, dalo de alta en producción.`);
          }
          return;
        }

        const o = aplanar(tipo, actual);
        const anterior = enBase[ficha.id];
        const b = anterior ? aplanar(tipo, anterior) : null;
        const items = [];

        // Sin base y sin cambios en pruebas: esa ficha es tal cual la copió
        // producción. Si hoy son distintas es que producción siguió
        // cambiando, y no hay nada que llevar.
        const sinNovedad = !b && ficha.cambiado === false;

        if (!sinNovedad) {
          const claves = Array.from(new Set(Object.keys(t.campos).concat(Object.keys(o.campos), b ? Object.keys(b.campos) : []))).sort();
          claves.forEach((campo) => {
            const vt = t.campos[campo], vo = o.campos[campo], vb = b ? b.campos[campo] : undefined;
            if (igual(vt, vo)) return;
            if (b && igual(vt, vb)) return;            // cambió solo en producción

            let clase = 'conflicto';
            if (b && igual(vo, vb)) clase = campo.startsWith('k:') && vt === undefined ? 'confirmar' : 'auto';

            items.push({
              id: 'i' + (++serie), clase, campo,
              etiqueta: lee.etiqueta(tipo, campo, vt || vo || vb),
              pruebas: vt, produccion: vo, base: vb,
              textoPruebas: lee.valor(campo, vt),
              textoProduccion: lee.valor(campo, vo),
              textoBase: b ? lee.valor(campo, vb) : null
            });
          });
        }

        fichas.push({ tipo, id: ficha.id, idPruebas: par.idPruebas[ficha.id], nombre: actual.nombre, estado: items.length ? 'cambia' : 'igual', sinBase: !b, items, suyo: t, nuestro: o, viejo: b });
      });

      // Lo que estaba en la base y pruebas ya no trae: allá se retiró.
      Object.keys(enBase).forEach((id) => {
        if (!vistas[id] && enProd[id]) {
          avisos.push(`"${enProd[id].nombre}" se retiró en pruebas. En producción se conserva: si también debe irse, retíralo desde este panel.`);
        }
      });
    });

    // Una foto guardada en el servidor de pruebas sigue viéndose, pero depende de él.
    fichas.forEach((f) => {
      const campo = f.tipo === 'creador' ? 'c:foto_url' : 'c:logo_url';
      const llega = f.estado === 'nuevo' ? f.suyo.campos[campo] : (f.items.find((i) => i.campo === campo) || {}).pruebas;
      if (typeof llega === 'string' && /\/api\/fotos\//.test(llega)) {
        avisos.push(`La foto de "${f.nombre}" está guardada en el servidor de pruebas. Se verá, pero conviene volver a traerla desde su ficha en producción.`);
      }
    });

    const cuenta = (clase) => fichas.reduce((n, f) => n + f.items.filter((i) => i.clase === clase).length, 0);
    return {
      fichas, avisos, par, suyas, base,
      primeraVez: !base,
      resumen: {
        nuevos: fichas.filter((f) => f.estado === 'nuevo').length,
        cambios: cuenta('auto'),
        conflictos: cuenta('conflicto'),
        confirmar: cuenta('confirmar'),
        iguales: fichas.filter((f) => f.estado === 'igual').length
      }
    };
  }

  /** Los conflictos que todavía no tienen decisión. */
  function sinDecidir(plan, decisiones) {
    const faltan = [];
    plan.fichas.forEach((f) => f.items.forEach((i) => {
      if (i.clase === 'conflicto' && decisiones[i.id] !== 'pruebas' && decisiones[i.id] !== 'produccion') faltan.push(i);
    }));
    return faltan;
  }

  /** ¿Se aplica este item? Lo automático sí; lo demás, solo si se eligió pruebas. */
  const seAplica = (item, decisiones) => item.clase === 'auto' || decisiones[item.id] === 'pruebas';

  // El orden de los canales no es un campo: no vale un conflicto. Si
  // producción no los reordenó desde la base, manda el orden de pruebas.
  function ordenFinal(ficha, campos) {
    const estan = (lista) => lista.filter((c) => campos[c]);
    const nuestro = ficha.nuestro ? ficha.nuestro.orden : [];
    const suyo = ficha.suyo.orden;
    let primero = nuestro, despues = suyo;
    if (ficha.viejo) {
      const comunes = (lista) => lista.filter((c) => nuestro.includes(c) && ficha.viejo.orden.includes(c));
      if (igual(comunes(nuestro), comunes(ficha.viejo.orden))) { primero = suyo; despues = nuestro; }
    }
    if (!ficha.nuestro) primero = suyo;
    const orden = estan(primero);
    estan(despues).forEach((c) => { if (!orden.includes(c)) orden.push(c); });
    return orden;
  }

  /**
   * Lo que hay que guardar en producción, en orden: productoras y después
   * creadores. Una ficha en la que no hay diferencias no aparece.
   *
   * Una ficha en la que todo se decidió a favor de producción sale con
   * `copia: true`: se vuelve a guardar tal como está, sin cambiarle nada, solo
   * para que producción la mande a pruebas y los dos queden iguales. Si no,
   * pruebas se quedaría con un cambio que ya se descartó.
   */
  function operaciones(plan, decisiones) {
    const ops = [];
    plan.fichas.forEach((f) => {
      if (f.estado === 'igual') return;
      const nuevo = f.estado === 'nuevo';
      let campos, copia = false;
      if (nuevo) {
        campos = Object.assign({}, f.suyo.campos);
      } else {
        const aplicados = f.items.filter((i) => seAplica(i, decisiones));
        copia = !aplicados.length;
        campos = Object.assign({}, f.nuestro.campos);
        aplicados.forEach((i) => {
          if (i.pruebas === undefined) delete campos[i.campo]; else campos[i.campo] = i.pruebas;
        });
      }
      ops.push({ tipo: f.tipo, id: f.id, idPruebas: f.idPruebas, nombre: nuevo ? f.nombre : (campos['c:nombre'] || f.nombre), nuevo, copia, campos, orden: copia ? f.nuestro.orden : ordenFinal(f, campos) });
    });
    return ops;
  }

  /**
   * El cuerpo de POST /api/admin/creadores o /productoras para una operación.
   *
   * @param nuevos    { "nuevo:<idPruebas>": idDeProduccion } de lo creado hasta ahora
   * @param existen   ids que hay en producción (un Set)
   * @return { cuerpo, incompleto }: incompleto si nombra a alguien que todavía
   *         no se ha creado; hay que volver a guardarla cuando exista.
   */
  function cuerpoDe(op, nuevos, existen) {
    let incompleto = false;
    const real = (id) => {
      if (!id) return null;
      if (esNuevo(id)) {
        if (nuevos[id]) return nuevos[id];
        incompleto = true;
        return null;
      }
      return existen.has(id) ? id : null;       // lo que ya no existe en producción se cae
    };

    const ficha = armar(op.tipo, op.nuevo ? null : op.id, op.campos, op.orden);
    const canales = ficha.canales.map((k) => ({
      plataforma: k.plataforma, nombre: k.nombre, url: k.url, handle: k.handle, channelId: k.channel_id,
      productoraId: op.tipo === 'creador' ? real(k.productora_id) : null,
      creadores: k.vinculados.map(real).filter(Boolean)
    }));

    const cuerpo = op.tipo === 'creador'
      ? { id: op.nuevo ? (nuevos[op.id] || null) : op.id, nombre: ficha.nombre, categoria: ficha.categoria || 'otros', bio: ficha.bio, fotoUrl: ficha.foto_url, activo: ficha.activo !== false, canales, productoras: ficha.productoras.map(real).filter(Boolean) }
      : { id: op.nuevo ? (nuevos[op.id] || null) : op.id, nombre: ficha.nombre, descripcion: ficha.descripcion, logoUrl: ficha.logo_url, activo: ficha.activo !== false, canales, enDirectorio: ficha.en_directorio === true, categoria: ficha.categoria || 'otros' };

    return { cuerpo, incompleto };
  }

  /**
   * Lo que producción guarda al terminar: la base de la próxima migración y
   * los ids que les tocaron a los que nacieron en pruebas.
   *
   * La base es la versión aplicada, también donde se eligió producción: esa
   * decisión ya está tomada y no se vuelve a preguntar. Lo que falló al
   * guardar se queda como estaba en la base anterior, para que la próxima
   * migración lo vuelva a intentar.
   *
   * @param nuevos    { "nuevo:<idPruebas>": idDeProduccion }
   * @param fallidas  ids (los del plan) de las fichas que no se pudieron guardar
   */
  function estadoFinal(plan, nuevos, fallidas, idsAnteriores) {
    const fallo = new Set(fallidas || []);
    const real = (id) => (esNuevo(id) && nuevos[id]) || id;
    const ids = {
      creadores: Object.assign({}, (idsAnteriores && idsAnteriores.creadores) || {}),
      productoras: Object.assign({}, (idsAnteriores && idsAnteriores.productoras) || {})
    };
    const base = { creadores: [], productoras: [] };

    TIPOS.forEach((tipo) => {
      const antes = porId(plan.base, tipo);
      (plan.suyas[LISTA[tipo]] || []).forEach((ficha) => {
        if (fallo.has(ficha.id)) {
          if (antes[ficha.id]) base[LISTA[tipo]].push(antes[ficha.id]);
          return;
        }
        if (esNuevo(ficha.id) && !nuevos[ficha.id]) return;     // no llegó a crearse

        const copia = Object.assign({}, ficha, {
          id: real(ficha.id),
          canales: (ficha.canales || []).map((k) => Object.assign({}, k, {
            productora_id: k.productora_id ? real(k.productora_id) : null,
            vinculados: (k.vinculados || []).map(real)
          }))
        });
        if (tipo === 'creador') copia.productoras = (ficha.productoras || []).map(real);
        delete copia.origen_id;
        delete copia.cambiado;
        base[LISTA[tipo]].push(copia);

        if (esNuevo(ficha.id)) ids[LISTA[tipo]][ficha.id.slice(NUEVO.length)] = nuevos[ficha.id];
      });
    });

    return { base, ids };
  }

  // ---------------------------------------------------------------------------
  // Comparar dos fotos del mismo ambiente
  // ---------------------------------------------------------------------------

  /**
   * Qué cambió de `antes` a `despues`. Las dos son del mismo ambiente, así
   * que los ids coinciden.
   *
   * @return [{ tipo, nombre, estado: 'nuevo'|'quitado'|'cambia', cambios: [{ etiqueta, antes, despues }] }]
   */
  function comparar(antes, despues) {
    const lee = lector(nombresDe(antes, despues));
    const salida = [];

    TIPOS.forEach((tipo) => {
      const a = porId(antes, tipo), d = porId(despues, tipo);
      Object.keys(d).forEach((id) => {
        if (!a[id]) { salida.push({ tipo, nombre: d[id].nombre, estado: 'nuevo', cambios: [] }); return; }
        const fa = aplanar(tipo, a[id]).campos, fd = aplanar(tipo, d[id]).campos;
        const cambios = [];
        Array.from(new Set(Object.keys(fa).concat(Object.keys(fd)))).sort().forEach((campo) => {
          if (igual(fa[campo], fd[campo])) return;
          cambios.push({ etiqueta: lee.etiqueta(tipo, campo, fd[campo] || fa[campo]), antes: lee.valor(campo, fa[campo]), despues: lee.valor(campo, fd[campo]) });
        });
        if (cambios.length) salida.push({ tipo, nombre: d[id].nombre, estado: 'cambia', cambios });
      });
      Object.keys(a).forEach((id) => {
        if (!d[id]) salida.push({ tipo, nombre: a[id].nombre, estado: 'quitado', cambios: [] });
      });
    });

    const peso = { nuevo: 0, cambia: 1, quitado: 2 };
    return salida.sort((x, y) => peso[x.estado] - peso[y.estado] || normal(x.nombre).localeCompare(normal(y.nombre)));
  }

  const publico = { planear, sinDecidir, operaciones, cuerpoDe, estadoFinal, comparar, emparejar, aplanar, claveDeCanal, esNuevo, NUEVO };
  if (typeof module !== 'undefined' && module.exports) module.exports = publico;
  else raiz.Versiones = publico;
})(typeof window !== 'undefined' ? window : globalThis);
