// Pruebas de chat-cifrado.js. No necesitan navegador ni servidor:
//
//   node admin/chat-cifrado.test.js
//
// La última usa un mensaje cifrado por el mismo esquema en Java (las mismas
// llamadas que hace chat/CifradoChat.kt en Android): si alguien cambia un
// lado y no el otro, falla aquí.

'use strict';

const assert = require('assert');
const C = require('./chat-cifrado.js');

// Hecho en Java como lo hace Android: la persona cifró para el equipo.
const DE_ANDROID = {
  adminPkcs8: 'MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCCNkjwWNIlM8tyPhRAxrSALtSdJot8AR/3B/1r93fABZQ==',
  claveAdmin: 'BFYzX+iXFb1SunVwodfWh+yTsfAnoEGml16L/VGrEWq3IlHvDHg4iUl9Yqovj2Sp3VepZ3ylxQ5SNGLOmlJHpJA=',
  claveUsuario: 'BKVvbnBHeznMVlBKUhM2QMXYlM4b79B+R/J98Db12x1qV+dArhlC1rRgCQ2YJYOtL9knyxDZGKDifYEjyUsGSOY=',
  codigo: '6959 0299',
  iv: 'qu59gGCGP1JB341f',
  cifrado: 'udCoex4naQUtEfMYAohHzIA6uZ1z6CYb4djz5jXcFdWealHqB0xVeY/CvYHF5SOaYj5QakZsgWIuVDAPnFnM'
};

async function conversacion() {
  const admin = await C.nuevoPar();
  const persona = await C.nuevoPar();
  assert.strictEqual(admin.publica.length, 65);
  assert.strictEqual(admin.publica[0], 4);

  const kAdmin = await C.clave(admin.privada, persona.publicaBase64, admin.publica, persona.publica);
  const kPersona = await C.clave(persona.privada, admin.publicaBase64, admin.publica, persona.publica);
  return { admin, persona, kAdmin, kPersona };
}

const pruebas = {
  async 'los dos lados llegan a la misma clave y se leen'() {
    const { kAdmin, kPersona } = await conversacion();
    const m = await C.cifrar(kAdmin, 'admin', 1, 'Hola, ¿cómo va todo? ñ 👋');
    assert.deepStrictEqual(await C.descifrar(kPersona, 'admin', m.iv, m.cifrado), { n: 1, t: 'Hola, ¿cómo va todo? ñ 👋' });
    const r = await C.cifrar(kPersona, 'usuario', 1, 'Bien');
    assert.strictEqual((await C.descifrar(kAdmin, 'usuario', r.iv, r.cifrado)).t, 'Bien');
  },

  async 'un mensaje devuelto a quien lo mandó no pasa por del otro'() {
    const { kAdmin } = await conversacion();
    const m = await C.cifrar(kAdmin, 'admin', 1, 'mío');
    assert.strictEqual(await C.descifrar(kAdmin, 'usuario', m.iv, m.cifrado), null);
  },

  async 'alterado o con otra clave, no se lee'() {
    const { kAdmin, kPersona } = await conversacion();
    const otra = await conversacion();
    const m = await C.cifrar(kAdmin, 'admin', 1, 'secreto');
    const bytes = C.deBase64(m.cifrado);
    bytes[3] ^= 1;
    assert.strictEqual(await C.descifrar(kPersona, 'admin', m.iv, C.aBase64(bytes)), null);
    assert.strictEqual(await C.descifrar(otra.kPersona, 'admin', m.iv, m.cifrado), null);
  },

  async 'cada mensaje lleva un IV distinto'() {
    const { kAdmin } = await conversacion();
    const a = await C.cifrar(kAdmin, 'admin', 1, 'igual');
    const b = await C.cifrar(kAdmin, 'admin', 2, 'igual');
    assert.notStrictEqual(a.iv, b.iv);
    assert.notStrictEqual(a.cifrado, b.cifrado);
  },

  async 'el código de seguridad es el mismo en los dos lados y cambia con las llaves'() {
    const { admin, persona } = await conversacion();
    const otra = await C.nuevoPar();
    const codigo = await C.codigo(admin.publica, persona.publica);
    assert.match(codigo, /^\d{4} \d{4}$/);
    assert.strictEqual(codigo, await C.codigo(admin.publica, persona.publica));
    assert.notStrictEqual(codigo, await C.codigo(otra.publica, persona.publica));
  },

  async 'la privada no se puede sacar de la memoria'() {
    const { privada } = await C.nuevoPar();
    assert.strictEqual(privada.extractable, false);
    await assert.rejects(crypto.subtle.exportKey('pkcs8', privada));
  },

  async 'lee lo que cifra Android'() {
    const v = DE_ANDROID;
    const privada = await crypto.subtle.importKey('pkcs8', C.deBase64(v.adminPkcs8), { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
    const claveAdmin = C.deBase64(v.claveAdmin);
    const claveUsuario = C.deBase64(v.claveUsuario);
    const k = await C.clave(privada, v.claveUsuario, claveAdmin, claveUsuario);
    assert.deepStrictEqual(await C.descifrar(k, 'usuario', v.iv, v.cifrado), { n: 7, t: 'Hola desde Android: ¿me lees? ñ' });
    assert.strictEqual(await C.codigo(claveAdmin, claveUsuario), v.codigo);
  }
};

(async () => {
  let fallos = 0;
  for (const [nombre, prueba] of Object.entries(pruebas)) {
    try {
      await prueba();
      console.log('ok  ', nombre);
    } catch (e) {
      fallos++;
      console.log('FALLA', nombre, '\n     ', e.message);
    }
  }
  console.log(fallos ? fallos + ' fallaron' : 'todas bien');
  process.exit(fallos ? 1 : 0);
})();
