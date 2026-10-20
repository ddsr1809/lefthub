package com.vocesdeizquierda.lefthub.chat

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * El cifrado del chat con el equipo. Es el mismo esquema que el panel
 * (admin/chat-cifrado.js): si se cambia uno, hay que cambiar el otro, o
 * dejarán de entenderse.
 *
 *  1. Al entrar al chat se hace un par de llaves ECDH P-256. Vive solo en
 *     memoria: no va al almacén de llaves ni a disco. Al salir, se pierde.
 *  2. Las públicas se intercambian por el servidor (65 bytes, sin comprimir).
 *  3. Del secreto ECDH sale, con HKDF-SHA256, la clave AES-256-GCM de la
 *     conversación. La sal son las dos públicas.
 *  4. Cada mensaje lleva un IV al azar de 12 bytes y, como datos adicionales,
 *     quién lo manda ("admin" o "usuario"). Dentro va {"n": contador, "t":
 *     texto}; quien lo recibe descarta los contadores que no suben.
 *  5. El código de seguridad son 8 cifras sacadas de las dos públicas: si la
 *     persona y el equipo ven el mismo, nadie cambió las llaves en el camino.
 *
 * El servidor solo ve las públicas y los mensajes cifrados. Sin una de las
 * dos privadas no puede leer nada.
 */
object CifradoChat {

    const val VERSION = "lefthub-chat-v1"
    const val LADO_ADMIN = "admin"
    const val LADO_USUARIO = "usuario"

    private const val LARGO_COORDENADA = 32
    private const val BITS_ETIQUETA = 128

    private val azar = SecureRandom()

    fun nuevoPar(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"), azar)
            generateKeyPair()
        }

    /** La pública como la entiende WebCrypto: 0x04 || X || Y, 65 bytes. */
    fun publicaCruda(par: KeyPair): ByteArray {
        val punto = (par.public as ECPublicKey).w
        return byteArrayOf(0x04) + a32(punto.affineX) + a32(punto.affineY)
    }

    /**
     * La clave AES de la conversación.
     *
     * @param propio       el par propio
     * @param publicaOtro  la pública del otro lado, cruda
     * @param claveAdmin   la pública del equipo, cruda
     * @param claveUsuario la pública de la persona, cruda
     */
    fun clave(propio: KeyPair, publicaOtro: ByteArray, claveAdmin: ByteArray, claveUsuario: ByteArray): SecretKey {
        val acuerdo = KeyAgreement.getInstance("ECDH")
        acuerdo.init(propio.private)
        acuerdo.doPhase(publicaDe(publicaOtro, propio), true)
        val secreto = acuerdo.generateSecret()

        val sal = sha256(claveAdmin + claveUsuario)
        val clave = hkdf(secreto, sal, "$VERSION aes-gcm".toByteArray(Charsets.UTF_8))
        secreto.fill(0)
        return SecretKeySpec(clave, "AES")
    }

    /** "1234 5678". Lo mismo que enseña el panel. */
    fun codigo(claveAdmin: ByteArray, claveUsuario: ByteArray): String {
        val h = sha256("$VERSION codigo".toByteArray(Charsets.UTF_8) + claveAdmin + claveUsuario)
        val n = ((h[0].toLong() and 0xff) shl 24) or
            ((h[1].toLong() and 0xff) shl 16) or
            ((h[2].toLong() and 0xff) shl 8) or
            (h[3].toLong() and 0xff)
        val cifras = (n % 100_000_000L).toString().padStart(8, '0')
        return cifras.substring(0, 4) + " " + cifras.substring(4)
    }

    /** Devuelve el IV y el mensaje cifrado (con su etiqueta al final, como WebCrypto). */
    fun cifrar(clave: SecretKey, lado: String, claro: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(12).also { azar.nextBytes(it) }
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
        cifrador.init(Cipher.ENCRYPT_MODE, clave, GCMParameterSpec(BITS_ETIQUETA, iv))
        cifrador.updateAAD(datosDe(lado))
        return iv to cifrador.doFinal(claro)
    }

    /** Null si no se puede descifrar: otra clave, o alguien lo alteró. */
    fun descifrar(clave: SecretKey, lado: String, iv: ByteArray, cifrado: ByteArray): ByteArray? =
        runCatching {
            val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
            cifrador.init(Cipher.DECRYPT_MODE, clave, GCMParameterSpec(BITS_ETIQUETA, iv))
            cifrador.updateAAD(datosDe(lado))
            cifrador.doFinal(cifrado)
        }.getOrNull()

    // -------------------------------------------------------------------------

    private fun datosDe(lado: String) = "$VERSION|$lado".toByteArray(Charsets.UTF_8)

    private fun publicaDe(cruda: ByteArray, propio: KeyPair): PublicKey {
        require(cruda.size == 1 + 2 * LARGO_COORDENADA && cruda[0] == 0x04.toByte()) {
            "Llave pública con formato inesperado"
        }
        val x = BigInteger(1, cruda.copyOfRange(1, 1 + LARGO_COORDENADA))
        val y = BigInteger(1, cruda.copyOfRange(1 + LARGO_COORDENADA, cruda.size))
        // La curva, de la llave propia: es la misma, P-256.
        val curva = (propio.public as ECPublicKey).params
        // Un punto fuera de la curva lo rechaza el proveedor aquí o en doPhase.
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curva))
    }

    /** BigInteger a 32 bytes justos: sin el cero de signo y con ceros delante si falta. */
    private fun a32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == LARGO_COORDENADA -> b
            b.size > LARGO_COORDENADA -> b.copyOfRange(b.size - LARGO_COORDENADA, b.size)
            else -> ByteArray(LARGO_COORDENADA - b.size) + b
        }
    }

    private fun sha256(datos: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(datos)

    private fun hmac(clave: ByteArray, datos: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(clave, "HmacSHA256"))
            doFinal(datos)
        }

    /** HKDF-SHA256 (RFC 5869) de 32 bytes: un solo bloque, como deriveKey de WebCrypto. */
    private fun hkdf(secreto: ByteArray, sal: ByteArray, info: ByteArray): ByteArray {
        val prk = hmac(sal, secreto)
        return hmac(prk, info + byteArrayOf(1))
    }
}
