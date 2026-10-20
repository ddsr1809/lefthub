// Cifrado del chat interno. El mismo esquema que la app de Android
// (chat/CifradoChat.kt); si se cambia uno, hay que cambiar el otro.
//
//   1. Cada lado hace un par de llaves ECDH P-256 al entrar al chat. Viven
//      solo en memoria y no se pueden exportar: al cerrar el chat se pierden.
//   2. Se intercambian las públicas por el servidor (65 bytes, sin comprimir).
//   3. Con la privada propia y la pública del otro sale el secreto ECDH, y de
//      ahí, con HKDF-SHA256, la clave AES-256-GCM de la conversación. La sal
//      son las dos públicas, así que la clave queda atada a esas dos llaves.
//   4. Cada mensaje: AES-GCM con un IV al azar de 12 bytes y, como datos
//      adicionales, quién lo manda ("admin" o "usuario"). Así el servidor no
//      puede devolverle a alguien su propio mensaje como si fuera del otro.
//      Dentro va {"n": contador, "t": texto}; un contador que no sube es un
//      mensaje repetido y se descarta.
//   5. El código de seguridad: 8 cifras sacadas de las dos públicas. Si los
//      dos lados ven el mismo, nadie cambió las llaves por el camino.
//
// Funciona en el navegador (panel) y en Node 20+ (pruebas): los dos traen
// WebCrypto en globalThis.crypto.

(function (raiz, fabrica) {
  if (typeof module === 'object' && module.exports) module.exports = fabrica();
  else raiz.ChatCifrado = fabrica();
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const VERSION = 'lefthub-chat-v1';
  const sutil = () => {
    const c = globalThis.crypto;
    if (!c || !c.subtle) throw new Error('Este navegador no permite cifrar (hace falta HTTPS).');
    return c.subtle;
  };
  const utf8 = (t) => new TextEncoder().encode(t);

  function aBase64(bytes) {
    let s = '';
    const b = new Uint8Array(bytes);
    for (let i = 0; i < b.length; i++) s += String.fromCharCode(b[i]);
    return btoa(s);
  }

  function deBase64(texto) {
    const s = atob(texto);
    const b = new Uint8Array(s.length);
    for (let i = 0; i < s.length; i++) b[i] = s.charCodeAt(i);
    return b;
  }

  function unir(...partes) {
    const total = partes.reduce((n, p) => n + p.length, 0);
    const todo = new Uint8Array(total);
    let i = 0;
    for (const p of partes) { todo.set(p, i); i += p.length; }
    return todo;
  }

  /** Un par nuevo. La privada no se puede exportar ni guardar. */
  async function nuevoPar() {
    const par = await sutil().generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
    const publica = new Uint8Array(await sutil().exportKey('raw', par.publicKey));
    return { privada: par.privateKey, publica, publicaBase64: aBase64(publica) };
  }

  /**
   * La clave de la conversación.
   *
   * @param privada     la CryptoKey privada propia
   * @param publicaOtro la pública del otro, en base64
   * @param claveAdmin  la pública del administrador (bytes)
   * @param claveUsuario la pública de la persona (bytes)
   */
  async function clave(privada, publicaOtro, claveAdmin, claveUsuario) {
    const otra = await sutil().importKey('raw', deBase64(publicaOtro), { name: 'ECDH', namedCurve: 'P-256' }, false, []);
    const secreto = await sutil().deriveBits({ name: 'ECDH', public: otra }, privada, 256);
    const sal = new Uint8Array(await sutil().digest('SHA-256', unir(claveAdmin, claveUsuario)));
    const base = await sutil().importKey('raw', secreto, 'HKDF', false, ['deriveKey']);
    return sutil().deriveKey(
      { name: 'HKDF', hash: 'SHA-256', salt: sal, info: utf8(VERSION + ' aes-gcm') },
      base, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  }

  /** "1234 5678": lo que cada lado lee en voz alta para comprobar. */
  async function codigo(claveAdmin, claveUsuario) {
    const h = new Uint8Array(await sutil().digest('SHA-256', unir(utf8(VERSION + ' codigo'), claveAdmin, claveUsuario)));
    const n = ((h[0] << 24) >>> 0) + (h[1] << 16) + (h[2] << 8) + h[3];
    const s = String(n % 100000000).padStart(8, '0');
    return s.slice(0, 4) + ' ' + s.slice(4);
  }

  const datosDe = (lado) => utf8(VERSION + '|' + lado);

  /** @param lado quien lo manda: "admin" o "usuario" */
  async function cifrar(claveAes, lado, n, texto) {
    const iv = globalThis.crypto.getRandomValues(new Uint8Array(12));
    const claro = utf8(JSON.stringify({ n, t: texto }));
    const cifrado = await sutil().encrypt({ name: 'AES-GCM', iv, additionalData: datosDe(lado), tagLength: 128 }, claveAes, claro);
    return { iv: aBase64(iv), cifrado: aBase64(cifrado) };
  }

  /**
   * @param lado quien lo mandó
   * @return {n, t}, o null si no se puede descifrar (otra clave, alterado)
   */
  async function descifrar(claveAes, lado, iv, cifrado) {
    try {
      const claro = await sutil().decrypt({ name: 'AES-GCM', iv: deBase64(iv), additionalData: datosDe(lado), tagLength: 128 }, claveAes, deBase64(cifrado));
      const m = JSON.parse(new TextDecoder().decode(claro));
      if (typeof m.n !== 'number' || typeof m.t !== 'string') return null;
      return m;
    } catch (e) {
      return null;
    }
  }

  return { VERSION, nuevoPar, clave, codigo, cifrar, descifrar, aBase64, deBase64 };
}));
