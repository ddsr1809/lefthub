// Pruebas de versiones.js. No necesitan navegador ni servidor:
//
//   node admin/versiones.test.js

'use strict';

const assert = require('assert');
const V = require('./versiones.js');

// --- Datos de ejemplo --------------------------------------------------------
// En producción los ids son P-*, en pruebas T-*. La copia de pruebas apunta a
// su original con origen_id.

const canal = (plataforma, url, extra) => Object.assign({ plataforma, nombre: null, url, handle: null, channel_id: null, productora_id: null, vinculados: [] }, extra || {});
const yt = (id, extra) => canal('youtube', 'https://www.youtube.com/channel/' + id, Object.assign({ channel_id: id, handle: '@' + id.toLowerCase() }, extra || {}));

const creador = (id, nombre, extra) => Object.assign({ id, origen_id: null, nombre, categoria: 'otros', bio: null, foto_url: null, activo: true, productoras: [], canales: [], cambiado: true }, extra || {});
const productora = (id, nombre, extra) => Object.assign({ id, origen_id: null, nombre, descripcion: null, logo_url: null, activo: true, en_directorio: false, categoria: 'otros', canales: [], cambiado: true }, extra || {});

const copia = (x) => JSON.parse(JSON.stringify(x));

/** Producción: Juan (dos canales, en Estudio X), Ana (una red), y Estudio X con su canal. */
function produccion() {
  return {
    productoras: [productora('P-casa', 'Estudio X', { canales: [yt('UCcasa', { vinculados: ['P-ana'] })] })],
    creadores: [
      creador('P-juan', 'Juan Pérez', { categoria: 'comida', bio: 'Recetas', productoras: ['P-casa'], canales: [yt('UCjuan'), yt('UCclips', { nombre: 'Clips', productora_id: 'P-casa' })] }),
      creador('P-ana', 'Ana', { canales: [canal('instagram', 'https://instagram.com/ana', { handle: 'ana' })] })
    ]
  };
}

/** Lo mismo visto desde pruebas: otros ids, y cada ficha apunta a su original. */
function enPruebas(foto) {
  const t = (id) => id.replace(/^P-/, 'T-');
  const canales = (lista) => lista.map((k) => Object.assign({}, k, { productora_id: k.productora_id ? t(k.productora_id) : null, vinculados: k.vinculados.map(t) }));
  return {
    productoras: foto.productoras.map((p) => Object.assign({}, p, { id: t(p.id), origen_id: p.id, canales: canales(p.canales), cambiado: false })),
    creadores: foto.creadores.map((c) => Object.assign({}, c, { id: t(c.id), origen_id: c.id, productoras: c.productoras.map(t), canales: canales(c.canales), cambiado: false }))
  };
}

const de = (foto, tipo, id) => foto[tipo].find((f) => f.id === id);
const ficha = (plan, id) => plan.fichas.find((f) => f.id === id);
const existen = (foto) => new Set(foto.creadores.concat(foto.productoras).map((f) => f.id));

/** La base que deja una migración sin cambios: la foto de producción, que es como queda guardada. */
const baseDe = (foto) => copia(foto);

const pruebas = [];
const prueba = (nombre, fn) => pruebas.push([nombre, fn]);

// --- Con base: la comparación a tres bandas ----------------------------------

prueba('sin cambios en ningún lado no hay nada que hacer', () => {
  const prod = produccion();
  const plan = V.planear({ base: baseDe(prod), pruebas: enPruebas(prod), produccion: prod });
  assert.deepStrictEqual(plan.resumen, { nuevos: 0, cambios: 0, conflictos: 0, confirmar: 0, iguales: 3 });
  assert.deepStrictEqual(V.operaciones(plan, {}), []);
  assert.deepStrictEqual(plan.avisos, []);
});

prueba('lo que cambió solo en pruebas se lleva, completo', () => {
  const prod = produccion();
  const t = enPruebas(prod);
  de(t, 'creadores', 'T-juan').bio = 'Recetas de siempre';

  const plan = V.planear({ base: baseDe(prod), pruebas: t, produccion: prod });
  assert.strictEqual(plan.resumen.cambios, 1);
  assert.strictEqual(plan.resumen.conflictos, 0);
  const item = ficha(plan, 'P-juan').items[0];
  assert.deepStrictEqual([item.clase, item.etiqueta, item.textoProduccion, item.textoPruebas], ['auto', 'Descripción', 'Recetas', 'Recetas de siempre']);

  const ops = V.operaciones(plan, {});
  assert.strictEqual(ops.length, 1);
  const { cuerpo, incompleto } = V.cuerpoDe(ops[0], {}, existen(prod));
  assert.strictEqual(incompleto, false);
  assert.strictEqual(cuerpo.id, 'P-juan');
  assert.strictEqual(cuerpo.bio, 'Recetas de siempre');
  // Lo demás va como está en producción: el servidor sustituye la lista entera.
  assert.deepStrictEqual(cuerpo.productoras, ['P-casa']);
  assert.deepStrictEqual(cuerpo.canales.map((k) => [k.channelId, k.nombre, k.productoraId]), [['UCjuan', null, null], ['UCclips', 'Clips', 'P-casa']]);
});

prueba('lo que cambió solo en producción se deja como está', () => {
  const prod = produccion();
  const base = baseDe(prod);
  const t = enPruebas(prod);
  de(prod, 'creadores', 'P-juan').bio = 'Cambiado en producción';

  const plan = V.planear({ base, pruebas: t, produccion: prod });
  assert.strictEqual(ficha(plan, 'P-juan').estado, 'igual');
  assert.deepStrictEqual(V.operaciones(plan, {}), []);
});

prueba('cambios en campos distintos se juntan solos', () => {
  const prod = produccion();
  const base = baseDe(prod);
  const t = enPruebas(prod);
  de(t, 'creadores', 'T-juan').nombre = 'Juan P. Cocina';
  de(prod, 'creadores', 'P-juan').bio = 'Bio de producción';

  const plan = V.planear({ base, pruebas: t, produccion: prod });
  assert.deepStrictEqual([plan.resumen.cambios, plan.resumen.conflictos], [1, 0]);
  const { cuerpo } = V.cuerpoDe(V.operaciones(plan, {})[0], {}, existen(prod));
  assert.deepStrictEqual([cuerpo.nombre, cuerpo.bio], ['Juan P. Cocina', 'Bio de producción']);
});

prueba('el mismo campo cambiado en los dos lados es un conflicto, y se decide', () => {
  const prod = produccion();
  const base = baseDe(prod);
  const t = enPruebas(prod);
  de(t, 'creadores', 'T-juan').bio = 'La de pruebas';
  de(t, 'creadores', 'T-juan').categoria = 'cine';
  de(prod, 'creadores', 'P-juan').bio = 'La de producción';

  const plan = V.planear({ base, pruebas: t, produccion: prod });
  assert.deepStrictEqual([plan.resumen.cambios, plan.resumen.conflictos], [1, 1]);
  const choque = ficha(plan, 'P-juan').items.find((i) => i.clase === 'conflicto');
  assert.deepStrictEqual([choque.textoBase, choque.textoPruebas, choque.textoProduccion], ['Recetas', 'La de pruebas', 'La de producción']);
  assert.strictEqual(V.sinDecidir(plan, {}).length, 1);

  const gana = (quien) => V.cuerpoDe(V.operaciones(plan, { [choque.id]: quien })[0], {}, existen(prod)).cuerpo;
  assert.strictEqual(V.sinDecidir(plan, { [choque.id]: 'pruebas' }).length, 0);
  assert.deepStrictEqual([gana('pruebas').bio, gana('pruebas').categoria], ['La de pruebas', 'cine']);
  // Elegir producción no impide que lo demás (el tema) se lleve.
  assert.deepStrictEqual([gana('produccion').bio, gana('produccion').categoria], ['La de producción', 'cine']);
  assert.strictEqual(V.operaciones(plan, { [choque.id]: 'produccion' })[0].copia, false);
});

prueba('si en una ficha todo queda como en producción, se guarda igual para copiarla a pruebas', () => {
  const prod = produccion();
  const base = baseDe(prod);
  const t = enPruebas(prod);
  de(t, 'creadores', 'T-ana').bio = 'La de pruebas';
  de(prod, 'creadores', 'P-ana').bio = 'La de producción';

  const plan = V.planear({ base, pruebas: t, produccion: prod });
  const choque = ficha(plan, 'P-ana').items[0];
  const ops = V.operaciones(plan, { [choque.id]: 'produccion' });
  assert.deepStrictEqual(ops.map((o) => [o.id, o.copia]), [['P-ana', true]]);
  const { cuerpo } = V.cuerpoDe(ops[0], {}, existen(prod));
  assert.deepStrictEqual([cuerpo.id, cuerpo.bio, cuerpo.canales.length], ['P-ana', 'La de producción', 1]);
});

prueba('si los dos lados llegaron al mismo valor no hay conflicto', () => {
  const prod = produccion();
  const base = baseDe(prod);
  const t = enPruebas(prod);
  de(t, 'creadores', 'T-juan').bio = 'Igual';
  de(prod, 'creadores', 'P-juan').bio = 'Igual';
  assert.deepStrictEqual(V.operaciones(V.planear({ base, pruebas: t, produccion: prod }), {}), []);
});

// --- Canales y productoras ---------------------------------------------------

prueba('un canal nuevo en cada lado: se quedan los dos', () => {
  const prod = produccion();
  const base = baseDe(prod);
  const t = enPruebas(prod);
  de(t, 'creadores', 'T-juan').canales.push(canal('x', 'https://x.com/juan', { handle: 'juan' }));
  de(prod, 'creadores', 'P-juan').canales.push(canal('tiktok', 'https://tiktok.com/@juan'));

  const plan = V.planear({ base, pruebas: t, produccion: prod });
  assert.deepStrictEqual([plan.resumen.cambios, plan.resumen.conflictos], [1, 0]);
  assert.strictEqual(ficha(plan, 'P-juan').items[0].etiqueta, 'Red: X');
  const { cuerpo } = V.cuerpoDe(V.operaciones(plan, {})[0], {}, existen(prod));
  assert.deepStrictEqual(cuerpo.canales.map((k) => k.plataforma), ['youtube', 'youtube', 'x', 'tiktok']);
});

prueba('quitar un canal en pruebas no lo quita en producción si no se confirma', () => {
  const prod = produccion();
  const base = baseDe(prod);
  const t = enPruebas(prod);
  de(t, 'creadores', 'T-juan').canales.pop();           // se va Clips

  const plan = V.planear({ base, pruebas: t, produccion: prod });
  assert.deepStrictEqual([plan.resumen.cambios, plan.resumen.confirmar, plan.resumen.conflictos], [0, 1, 0]);
  const item = ficha(plan, 'P-juan').items[0];
  assert.deepStrictEqual([item.clase, item.textoPruebas], ['confirmar', 'No lo tiene']);
  assert.strictEqual(V.sinDecidir(plan, {}).length, 0, 'no bloquea la migración');
  const sinTocar = V.operaciones(plan, {});
  assert.deepStrictEqual(sinTocar.map((o) => [o.id, o.copia]), [['P-juan', true]], 'sin confirmar, no se le cambia nada');
  assert.deepStrictEqual(V.cuerpoDe(sinTocar[0], {}, existen(prod)).cuerpo.canales.map((k) => k.channelId), ['UCjuan', 'UCclips'], 'se vuelve a guardar tal cual, para copiarla a pruebas');

  const { cuerpo } = V.cuerpoDe(V.operaciones(plan, { [item.id]: 'pruebas' })[0], {}, existen(prod));
  assert.deepStrictEqual(cuerpo.canales.map((k) => k.channelId), ['UCjuan']);
});

prueba('un canal editado en los dos lados choca entero', () => {
  const prod = produccion();
  const base = baseDe(prod);
  const t = enPruebas(prod);
  de(t, 'creadores', 'T-juan').canales[1].nombre = 'Cortes';
  de(prod, 'creadores', 'P-juan').canales[1].productora_id = null;

  const plan = V.planear({ base, pruebas: t, produccion: prod });
  const item = ficha(plan, 'P-juan').items[0];
  assert.deepStrictEqual([item.clase, item.etiqueta], ['conflicto', 'Canal de YouTube']);
  assert.ok(item.textoPruebas.includes('«Cortes»') && item.textoPruebas.includes('de Estudio X'), item.textoPruebas);
  assert.ok(!item.textoProduccion.includes('Estudio X'), item.textoProduccion);
});

prueba('entrar y salir de una productora', () => {
  const prod = produccion();
  const base = baseDe(prod);
  const t = enPruebas(prod);
  de(t, 'creadores', 'T-juan').productoras = [];
  de(t, 'creadores', 'T-ana').productoras = ['T-casa'];

  const plan = V.planear({ base, pruebas: t, produccion: prod });
  assert.strictEqual(plan.resumen.cambios, 2);
  assert.strictEqual(ficha(plan, 'P-ana').items[0].etiqueta, 'Figura en Estudio X');
  const cuerpos = V.operaciones(plan, {}).map((op) => V.cuerpoDe(op, {}, existen(prod)).cuerpo);
  assert.deepStrictEqual(cuerpos.map((c) => [c.id, c.productoras]), [['P-juan', []], ['P-ana', ['P-casa']]]);
});

prueba('si producción no reordenó los canales, manda el orden de pruebas', () => {
  const prod = produccion();
  const base = baseDe(prod);
  const t = enPruebas(prod);
  de(t, 'creadores', 'T-juan').canales.reverse();
  de(t, 'creadores', 'T-juan').bio = 'x';

  const { cuerpo } = V.cuerpoDe(V.operaciones(V.planear({ base, pruebas: t, produccion: prod }), {})[0], {}, existen(prod));
  assert.deepStrictEqual(cuerpo.canales.map((k) => k.channelId), ['UCclips', 'UCjuan']);
});

// --- Lo que nace en pruebas --------------------------------------------------

prueba('creador y productora nuevos: primero la productora, y los ids se resuelven al crear', () => {
  const prod = produccion();
  const t = enPruebas(prod);
  t.productoras.push(productora('T-nueva', 'Casa Nueva', { canales: [yt('UCnueva', { vinculados: ['T-luis'] })] }));
  t.creadores.push(creador('T-luis', 'Luis', { productoras: ['T-nueva'], canales: [canal('x', 'https://x.com/luis', { productora_id: 'T-nueva' })] }));

  const plan = V.planear({ base: baseDe(prod), pruebas: t, produccion: prod });
  assert.strictEqual(plan.resumen.nuevos, 2);
  const ops = V.operaciones(plan, {});
  assert.deepStrictEqual(ops.map((o) => [o.tipo, o.nombre, o.nuevo]), [['productora', 'Casa Nueva', true], ['creador', 'Luis', true]]);

  // La productora nombra a Luis, que todavía no existe: se guarda sin él y queda pendiente.
  const nuevos = {};
  let r = V.cuerpoDe(ops[0], nuevos, existen(prod));
  assert.strictEqual(r.incompleto, true);
  assert.deepStrictEqual([r.cuerpo.id, r.cuerpo.canales[0].creadores], [null, []]);
  nuevos[ops[0].id] = 'P-nueva';

  r = V.cuerpoDe(ops[1], nuevos, existen(prod));
  assert.strictEqual(r.incompleto, false);
  assert.deepStrictEqual([r.cuerpo.id, r.cuerpo.productoras, r.cuerpo.canales[0].productoraId], [null, ['P-nueva'], 'P-nueva']);
  nuevos[ops[1].id] = 'P-luis';

  // Segunda vuelta: ahora sí, con su id y con Luis.
  r = V.cuerpoDe(ops[0], nuevos, existen(prod));
  assert.strictEqual(r.incompleto, false);
  assert.deepStrictEqual([r.cuerpo.id, r.cuerpo.canales[0].creadores], ['P-nueva', ['P-luis']]);

  const fin = V.estadoFinal(plan, nuevos, [], null);
  assert.deepStrictEqual(fin.ids, { creadores: { 'T-luis': 'P-luis' }, productoras: { 'T-nueva': 'P-nueva' } });
  const luis = de(fin.base, 'creadores', 'P-luis');
  assert.deepStrictEqual([luis.productoras, luis.canales[0].productora_id, 'origen_id' in luis, 'cambiado' in luis], [['P-nueva'], 'P-nueva', false, false]);

  // La siguiente migración, aunque pruebas no se haya enterado del enlace.
  prod.productoras.push(productora('P-nueva', 'Casa Nueva', { canales: [yt('UCnueva', { vinculados: ['P-luis'] })] }));
  prod.creadores.push(creador('P-luis', 'Luis', { productoras: ['P-nueva'], canales: [canal('x', 'https://x.com/luis', { productora_id: 'P-nueva' })] }));
  const otra = V.planear({ base: fin.base, pruebas: t, produccion: prod, ids: fin.ids });
  assert.deepStrictEqual([otra.resumen.nuevos, otra.resumen.cambios, otra.resumen.conflictos], [0, 0, 0]);
});

prueba('una migración a medias no duplica: se empareja por canal o por nombre', () => {
  const prod = produccion();
  const t = enPruebas(prod);
  t.creadores.push(creador('T-luis', 'Luis', { canales: [yt('UCluis')] }));
  t.productoras.push(productora('T-nueva', 'Casa Nueva'));
  // Producción ya los creó, pero no quedó apuntado en ningún sitio.
  prod.creadores.push(creador('P-luis', 'Luis Miguel', { canales: [yt('UCluis')] }));
  prod.productoras.push(productora('P-nueva', ' casa nueva '));

  const plan = V.planear({ base: null, pruebas: t, produccion: prod });
  assert.strictEqual(plan.resumen.nuevos, 0);
  assert.strictEqual(ficha(plan, 'P-luis').idPruebas, 'T-luis');
  assert.ok(plan.avisos.some((a) => a.includes('Luis') && a.includes('el mismo canal de YouTube')), plan.avisos.join('\n'));
  assert.ok(plan.avisos.some((a) => a.includes('Casa Nueva') && a.includes('el mismo nombre')));
});

prueba('lo que no se pudo guardar se vuelve a intentar la próxima vez', () => {
  const prod = produccion();
  const base = baseDe(prod);
  const t = enPruebas(prod);
  de(t, 'creadores', 'T-juan').bio = 'Nueva';
  t.creadores.push(creador('T-luis', 'Luis'));

  const plan = V.planear({ base, pruebas: t, produccion: prod });
  const fin = V.estadoFinal(plan, {}, ['P-juan'], null);         // Juan falló y Luis no llegó a crearse
  assert.strictEqual(de(fin.base, 'creadores', 'P-juan').bio, 'Recetas', 'Juan queda como en la base anterior');
  assert.strictEqual(fin.base.creadores.some((c) => c.nombre === 'Luis'), false);

  const otra = V.planear({ base: fin.base, pruebas: t, produccion: prod, ids: fin.ids });
  assert.deepStrictEqual([otra.resumen.nuevos, otra.resumen.cambios], [1, 1]);
});

// --- Retiros -----------------------------------------------------------------

prueba('retirar en pruebas solo avisa; lo retirado en producción no vuelve', () => {
  const prod = produccion();
  const base = baseDe(prod);
  const t = enPruebas(prod);
  t.creadores = t.creadores.filter((c) => c.id !== 'T-ana');           // Ana se retiró en pruebas
  t.productoras[0].canales[0].vinculados = [];
  prod.creadores = prod.creadores.filter((c) => c.id !== 'P-juan');     // Juan se retiró en producción

  const plan = V.planear({ base, pruebas: t, produccion: prod });
  assert.strictEqual(plan.resumen.nuevos, 0);
  assert.ok(plan.avisos.some((a) => a.includes('"Ana" se retiró en pruebas')), plan.avisos.join('\n'));
  assert.ok(plan.avisos.some((a) => a.includes('"Juan Pérez" ya no existe en producción')));
  assert.strictEqual(V.operaciones(plan, {}).some((o) => o.tipo === 'creador'), false);
});

// --- La primera vez ----------------------------------------------------------

prueba('sin base: lo que no cambió en pruebas se salta, y lo que cambió se pregunta', () => {
  const prod = produccion();
  const t = enPruebas(prod);
  // Producción siguió cambiando a Ana y pruebas no se enteró: no es un cambio de pruebas.
  de(prod, 'creadores', 'P-ana').bio = 'Solo en producción';
  // Juan sí se tocó en pruebas.
  Object.assign(de(t, 'creadores', 'T-juan'), { bio: 'Tocado en pruebas', cambiado: true });

  const plan = V.planear({ base: null, pruebas: t, produccion: prod });
  assert.strictEqual(plan.primeraVez, true);
  assert.strictEqual(ficha(plan, 'P-ana').estado, 'igual');
  assert.deepStrictEqual([plan.resumen.cambios, plan.resumen.conflictos], [0, 1]);
  const item = ficha(plan, 'P-juan').items[0];
  assert.deepStrictEqual([item.clase, item.textoBase], ['conflicto', null]);
});

prueba('dos fichas de pruebas para la misma de producción: solo la primera', () => {
  const prod = produccion();
  const t = enPruebas(prod);
  t.creadores.push(creador('T-doble', 'Ana (otra)', { origen_id: 'P-ana' }));
  const plan = V.planear({ base: baseDe(prod), pruebas: t, produccion: prod });
  assert.strictEqual(plan.fichas.filter((f) => f.id === 'P-ana').length, 1);
  assert.ok(plan.avisos.some((a) => a.includes('está dos veces en pruebas')));
});

prueba('la foto guardada en el servidor de pruebas se avisa', () => {
  const prod = produccion();
  const t = enPruebas(prod);
  de(t, 'creadores', 'T-ana').foto_url = 'https://testapp.vocesdeizquierda.com/api/fotos/123';
  const plan = V.planear({ base: baseDe(prod), pruebas: t, produccion: prod });
  assert.ok(plan.avisos.some((a) => a.includes('La foto de "Ana"')));
});

// --- Comparar dos versiones --------------------------------------------------

prueba('comparar dos fotos del mismo ambiente', () => {
  const antes = produccion();
  const despues = produccion();
  de(despues, 'creadores', 'P-juan').activo = false;
  de(despues, 'creadores', 'P-juan').canales.pop();
  despues.creadores = despues.creadores.filter((c) => c.id !== 'P-ana');
  despues.productoras[0].canales[0].vinculados = [];      // al irse Ana, deja de aparecer en el canal de la casa
  despues.productoras.push(productora('P-otra', 'Otra Casa'));

  const dif = V.comparar(antes, despues);
  assert.deepStrictEqual(dif.map((d) => [d.estado, d.nombre]), [['nuevo', 'Otra Casa'], ['cambia', 'Estudio X'], ['cambia', 'Juan Pérez'], ['quitado', 'Ana']]);
  const juan = dif.find((d) => d.nombre === 'Juan Pérez');
  assert.deepStrictEqual(juan.cambios.map((c) => [c.etiqueta, c.antes.split(' · ')[0], c.despues]), [['Visible en la app', 'Sí', 'No'], ['Canal de YouTube', '@ucclips', 'No lo tiene']]);
  assert.deepStrictEqual(V.comparar(antes, antes), []);
});

// -----------------------------------------------------------------------------
let fallos = 0;
pruebas.forEach(([nombre, fn]) => {
  try { fn(); console.log('  ok   ' + nombre); } catch (e) { fallos++; console.log('  MAL  ' + nombre + '\n       ' + String(e.message).split('\n').join('\n       ')); }
});
console.log(fallos ? `\n${fallos} de ${pruebas.length} fallaron` : `\n${pruebas.length} pruebas, todas bien`);
process.exit(fallos ? 1 : 0);
