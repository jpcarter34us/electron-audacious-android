package io.github.jpcarter34us.audacious.sendspin

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The primitives Sendspin needs, in plain Kotlin so they run on every
 * Android version (the platform has no X25519 or ChaCha20-Poly1305 before
 * Android 9/11): X25519 (RFC 7748), ChaCha20-Poly1305 (RFC 8439) and
 * AES-256-GCM, plus SHA-256/512 and HMAC from the platform. The iOS port
 * gets these from CryptoKit.
 */
class SendspinCryptoException(message: String) : Exception(message)

typealias Bytes = ByteArray

operator fun Bytes.plus(other: Bytes): Bytes {
    val out = ByteArray(size + other.size)
    System.arraycopy(this, 0, out, 0, size)
    System.arraycopy(other, 0, out, size, other.size)
    return out
}

fun utf8(s: String): Bytes = s.toByteArray(Charsets.UTF_8)

private val random = SecureRandom()

fun randomBytes(count: Int): Bytes = ByteArray(count).also { random.nextBytes(it) }

fun sha256(data: Bytes): Bytes = MessageDigest.getInstance("SHA-256").digest(data)
fun sha512(data: Bytes): Bytes = MessageDigest.getInstance("SHA-512").digest(data)

fun hmacSHA256(key: Bytes, data: Bytes): Bytes = hmac("HmacSHA256", key, data)
fun hmacSHA512(key: Bytes, data: Bytes): Bytes = hmac("HmacSHA512", key, data)

/** Keys are always non-empty here (Noise chaining keys, CPace MAC keys). */
private fun hmac(algorithm: String, key: Bytes, data: Bytes): Bytes {
    val mac = Mac.getInstance(algorithm)
    mac.init(SecretKeySpec(key, algorithm))
    return mac.doFinal(data)
}

/** Constant-time comparison. */
fun bytesEqual(a: Bytes, b: Bytes): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

/** base64url (RFC 4648 §5) without padding. */
object Base64URL {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(bytes: Bytes): String {
        val sb = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i + 2 < bytes.size) {
            val v = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8) or (bytes[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[v shr 18 and 63]).append(ALPHABET[v shr 12 and 63]).append(ALPHABET[v shr 6 and 63]).append(ALPHABET[v and 63])
            i += 3
        }
        val rest = bytes.size - i
        if (rest == 1) {
            val v = bytes[i].toInt() and 0xFF shl 16
            sb.append(ALPHABET[v shr 18 and 63]).append(ALPHABET[v shr 12 and 63])
        } else if (rest == 2) {
            val v = (bytes[i].toInt() and 0xFF shl 16) or (bytes[i + 1].toInt() and 0xFF shl 8)
            sb.append(ALPHABET[v shr 18 and 63]).append(ALPHABET[v shr 12 and 63]).append(ALPHABET[v shr 6 and 63])
        }
        return sb.toString()
    }

    /** Also accepts standard base64 and padding. */
    fun decode(text: String): Bytes? {
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4)
        var bits = 0
        var value = 0
        for (c in text) {
            val d = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '-', '+' -> 62
                '_', '/' -> 63
                '=' -> continue
                else -> return null
            }
            value = (value shl 6) or d
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write(value shr bits and 0xFF)
            }
        }
        if (bits >= 6) return null
        return out.toByteArray()
    }
}

// MARK: - X25519

/** RFC 7748 X25519: TweetNaCl's crypto_scalarmult (sixteen 16-bit limbs in
 *  longs), which stays fast on Dalvik before any JIT; BigInteger was
 *  seconds per handshake on a cold start. */
object X25519 {
    val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val BASE = ByteArray(32).also { it[0] = 9 }
    private val K121665 = LongArray(16).also { it[0] = 0xDB41; it[1] = 1 }

    private fun car(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    private fun sel(p: LongArray, q: LongArray, b: Int) {
        val c = (b - 1).toLong().inv()
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack(n: LongArray): Bytes {
        val t = n.copyOf()
        val m = LongArray(16)
        car(t); car(t); car(t)
        for (j in 0 until 2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            sel(t, m, 1 - b)
        }
        val o = ByteArray(32)
        for (i in 0 until 16) {
            o[2 * i] = (t[i] and 0xff).toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
        return o
    }

    private fun unpack(n: Bytes): LongArray {
        val o = LongArray(16)
        for (i in 0 until 16) o[i] = (n[2 * i].toLong() and 0xff) + ((n[2 * i + 1].toLong() and 0xff) shl 8)
        o[15] = o[15] and 0x7fff
        return o
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] + b[i]
    }

    private fun sub(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] - b[i]
    }

    private val t31 = ThreadLocal<LongArray>()

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = t31.get() ?: LongArray(31).also { t31.set(it) }
        java.util.Arrays.fill(t, 0)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        System.arraycopy(t, 0, o, 0, 16)
        car(o)
        car(o)
    }

    private fun inv(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            mul(c, c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        System.arraycopy(c, 0, o, 0, 16)
    }

    /** X25519(scalar, u); throws on an all-zero result (a low-order point). */
    fun scalarMult(scalar: Bytes, point: Bytes): Bytes {
        if (scalar.size != 32 || point.size != 32) throw SendspinCryptoException("bad key")
        val z = scalar.copyOf()
        z[31] = ((z[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()
        val x = unpack(point)
        val a = LongArray(16); val b = x.copyOf(); val c = LongArray(16); val d = LongArray(16)
        val e = LongArray(16); val f = LongArray(16)
        a[0] = 1; d[0] = 1
        for (i in 254 downTo 0) {
            val r = (z[i shr 3].toInt() shr (i and 7)) and 1
            sel(a, b, r); sel(c, d, r)
            add(e, a, c); sub(a, a, c); add(c, b, d); sub(b, b, d)
            mul(d, e, e); mul(f, a, a); mul(a, c, a); mul(c, b, e)
            add(e, a, c); sub(a, a, c); mul(b, a, a); sub(c, d, f)
            mul(a, c, K121665); add(a, a, d); mul(c, c, a); mul(a, d, f)
            mul(d, b, x); mul(b, e, e)
            sel(a, b, r); sel(c, d, r)
        }
        inv(c, c)
        mul(a, a, c)
        val result = pack(a)
        if (result.all { it.toInt() == 0 }) throw SendspinCryptoException("low-order point")
        return result
    }

    fun decodeLE(b: Bytes): BigInteger {
        val be = ByteArray(b.size + 1)
        for (i in b.indices) be[b.size - i] = b[i]
        return BigInteger(be)
    }

    fun encodeLE(n: BigInteger): Bytes {
        val be = n.toByteArray()
        val out = ByteArray(32)
        for (i in 0 until minOf(32, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    fun publicKey(scalar: Bytes): Bytes = scalarMult(scalar, BASE)
}

class KeyPair(val privateKey: Bytes) {
    val publicKey: Bytes = X25519.publicKey(privateKey)

    companion object {
        fun generate() = KeyPair(randomBytes(32))
    }
}

// MARK: - ChaCha20-Poly1305 (RFC 8439)

object ChaCha20Poly1305 {
    private fun rotl(v: Int, n: Int) = (v shl n) or (v ushr (32 - n))

    private fun le32(b: Bytes, o: Int) =
        (b[o].toInt() and 0xFF) or (b[o + 1].toInt() and 0xFF shl 8) or (b[o + 2].toInt() and 0xFF shl 16) or (b[o + 3].toInt() shl 24)

    /** XORs `data` (in place) with the ChaCha20 stream from block `counter`. */
    fun chacha20(key: Bytes, nonce: Bytes, counter: Int, data: Bytes, offset: Int = 0, length: Int = data.size) {
        val state = IntArray(16)
        state[0] = 0x61707865; state[1] = 0x3320646e; state[2] = 0x79622d32; state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = le32(key, i * 4)
        state[12] = counter
        for (i in 0 until 3) state[13 + i] = le32(nonce, i * 4)
        val x = IntArray(16)
        val block = ByteArray(64)
        var pos = 0
        while (pos < length) {
            System.arraycopy(state, 0, x, 0, 16)
            for (round in 0 until 10) {
                quarter(x, 0, 4, 8, 12); quarter(x, 1, 5, 9, 13); quarter(x, 2, 6, 10, 14); quarter(x, 3, 7, 11, 15)
                quarter(x, 0, 5, 10, 15); quarter(x, 1, 6, 11, 12); quarter(x, 2, 7, 8, 13); quarter(x, 3, 4, 9, 14)
            }
            for (i in 0 until 16) {
                val v = x[i] + state[i]
                block[i * 4] = v.toByte(); block[i * 4 + 1] = (v ushr 8).toByte()
                block[i * 4 + 2] = (v ushr 16).toByte(); block[i * 4 + 3] = (v ushr 24).toByte()
            }
            val n = minOf(64, length - pos)
            for (i in 0 until n) data[offset + pos + i] = (data[offset + pos + i].toInt() xor block[i].toInt()).toByte()
            pos += n
            state[12]++
        }
    }

    private fun quarter(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = rotl(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = rotl(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = rotl(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = rotl(x[b] xor x[c], 7)
    }

    fun seal(key: Bytes, nonce: Bytes, ad: Bytes, plaintext: Bytes): Bytes {
        val out = ByteArray(plaintext.size + 16)
        System.arraycopy(plaintext, 0, out, 0, plaintext.size)
        chacha20(key, nonce, 1, out, 0, plaintext.size)
        val tag = tag(key, nonce, ad, out, plaintext.size)
        System.arraycopy(tag, 0, out, plaintext.size, 16)
        return out
    }

    fun open(key: Bytes, nonce: Bytes, ad: Bytes, sealed: Bytes): Bytes {
        if (sealed.size < 16) throw SendspinCryptoException("decrypt")
        val n = sealed.size - 16
        val expected = tag(key, nonce, ad, sealed, n)
        if (!bytesEqual(expected, sealed.copyOfRange(n, sealed.size))) throw SendspinCryptoException("decrypt")
        val out = sealed.copyOf(n)
        chacha20(key, nonce, 1, out)
        return out
    }

    private fun tag(key: Bytes, nonce: Bytes, ad: Bytes, ct: Bytes, ctLength: Int): Bytes {
        val polyKey = ByteArray(64)
        chacha20(key, nonce, 0, polyKey)
        val p = Poly1305(polyKey)
        p.update(ad, 0, ad.size); p.pad(ad.size)
        p.update(ct, 0, ctLength); p.pad(ctLength)
        val lengths = ByteArray(16)
        for (i in 0 until 8) {
            lengths[i] = (ad.size.toLong() ushr (8 * i)).toByte()
            lengths[8 + i] = (ctLength.toLong() ushr (8 * i)).toByte()
        }
        p.update(lengths, 0, 16)
        return p.finish()
    }
}

/** Poly1305 with 26-bit limbs (poly1305-donna-32). */
class Poly1305(key: Bytes) {
    private val r = IntArray(5)
    private val s = IntArray(4)
    private val h = IntArray(5)
    private val buffer = ByteArray(16)
    private var buffered = 0

    init {
        fun le(o: Int) = (key[o].toInt() and 0xFF) or (key[o + 1].toInt() and 0xFF shl 8) or
            (key[o + 2].toInt() and 0xFF shl 16) or (key[o + 3].toInt() and 0xFF shl 24)
        r[0] = le(0) and 0x3ffffff
        r[1] = (le(3) ushr 2) and 0x3ffff03
        r[2] = (le(6) ushr 4) and 0x3ffc0ff
        r[3] = (le(9) ushr 6) and 0x3f03fff
        r[4] = (le(12) ushr 8) and 0x00fffff
        for (i in 0 until 4) s[i] = le(16 + i * 4)
    }

    /** Zero padding to a 16-byte boundary (RFC 8439 AEAD construction). */
    fun pad(length: Int) {
        val rem = length % 16
        if (rem != 0) update(ByteArray(16 - rem), 0, 16 - rem)
    }

    fun update(data: Bytes, offset: Int, length: Int) {
        var o = offset
        var n = length
        if (buffered > 0) {
            val take = minOf(16 - buffered, n)
            System.arraycopy(data, o, buffer, buffered, take)
            buffered += take; o += take; n -= take
            if (buffered < 16) return
            block(buffer, 0, 1 shl 24)
            buffered = 0
        }
        while (n >= 16) {
            block(data, o, 1 shl 24)
            o += 16; n -= 16
        }
        if (n > 0) {
            System.arraycopy(data, o, buffer, 0, n)
            buffered = n
        }
    }

    private fun block(m: Bytes, o: Int, hibit: Int) {
        fun le(i: Int) = ((m[o + i].toInt() and 0xFF) or (m[o + i + 1].toInt() and 0xFF shl 8) or
            (m[o + i + 2].toInt() and 0xFF shl 16) or (m[o + i + 3].toInt() and 0xFF shl 24)).toLong() and 0xFFFFFFFFL
        val r0 = r[0].toLong(); val r1 = r[1].toLong(); val r2 = r[2].toLong(); val r3 = r[3].toLong(); val r4 = r[4].toLong()
        val s1 = r1 * 5; val s2 = r2 * 5; val s3 = r3 * 5; val s4 = r4 * 5
        var h0 = h[0].toLong() + (le(0) and 0x3ffffff)
        var h1 = h[1].toLong() + ((le(3) ushr 2) and 0x3ffffff)
        var h2 = h[2].toLong() + ((le(6) ushr 4) and 0x3ffffff)
        var h3 = h[3].toLong() + ((le(9) ushr 6) and 0x3ffffff)
        var h4 = h[4].toLong() + ((le(12) ushr 8) or hibit.toLong())
        val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
        var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
        var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
        var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
        var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
        var c = d0 ushr 26; h0 = d0 and 0x3ffffff
        d1 += c; c = d1 ushr 26; h1 = d1 and 0x3ffffff
        d2 += c; c = d2 ushr 26; h2 = d2 and 0x3ffffff
        d3 += c; c = d3 ushr 26; h3 = d3 and 0x3ffffff
        d4 += c; c = d4 ushr 26; h4 = d4 and 0x3ffffff
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
        h1 += c
        h[0] = h0.toInt(); h[1] = h1.toInt(); h[2] = h2.toInt(); h[3] = h3.toInt(); h[4] = h4.toInt()
    }

    fun finish(): Bytes {
        if (buffered > 0) {
            buffer[buffered] = 1
            for (i in buffered + 1 until 16) buffer[i] = 0
            block(buffer, 0, 0)
        }
        var h0 = h[0].toLong(); var h1 = h[1].toLong(); var h2 = h[2].toLong(); var h3 = h[3].toLong(); var h4 = h[4].toLong()
        var c = h1 ushr 26; h1 = h1 and 0x3ffffff
        h2 += c; c = h2 ushr 26; h2 = h2 and 0x3ffffff
        h3 += c; c = h3 ushr 26; h3 = h3 and 0x3ffffff
        h4 += c; c = h4 ushr 26; h4 = h4 and 0x3ffffff
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and 0x3ffffff
        h1 += c
        // h - p, selected in constant time.
        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and 0x3ffffff
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and 0x3ffffff
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and 0x3ffffff
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and 0x3ffffff
        val g4 = h4 + c - (1L shl 26)
        val mask = (g4 ushr 63) - 1 // all ones when g4 >= 0 (h >= p)
        h0 = (h0 and mask.inv()) or (g0 and mask)
        h1 = (h1 and mask.inv()) or (g1 and mask)
        h2 = (h2 and mask.inv()) or (g2 and mask)
        h3 = (h3 and mask.inv()) or (g3 and mask)
        h4 = (h4 and mask.inv()) or (g4 and mask and 0x3ffffff)
        // To 4 x 32 bits, plus s.
        val w0 = (h0 or (h1 shl 26)) and 0xFFFFFFFFL
        val w1 = ((h1 ushr 6) or (h2 shl 20)) and 0xFFFFFFFFL
        val w2 = ((h2 ushr 12) or (h3 shl 14)) and 0xFFFFFFFFL
        val w3 = ((h3 ushr 18) or (h4 shl 8)) and 0xFFFFFFFFL
        val out = ByteArray(16)
        var f = w0 + (s[0].toLong() and 0xFFFFFFFFL)
        put(out, 0, f)
        f = w1 + (s[1].toLong() and 0xFFFFFFFFL) + (f ushr 32)
        put(out, 4, f)
        f = w2 + (s[2].toLong() and 0xFFFFFFFFL) + (f ushr 32)
        put(out, 8, f)
        f = w3 + (s[3].toLong() and 0xFFFFFFFFL) + (f ushr 32)
        put(out, 12, f)
        return out
    }

    private fun put(out: Bytes, o: Int, v: Long) {
        out[o] = v.toByte(); out[o + 1] = (v ushr 8).toByte(); out[o + 2] = (v ushr 16).toByte(); out[o + 3] = (v ushr 24).toByte()
    }
}

// MARK: - AES-256-GCM

/** GCM over the platform's AES block cipher (AES/ECB is on every
 *  Android version; AES/GCM only from Android 4.4 with quirks). */
object AesGcm {
    private fun aesBlocks(key: Bytes, input: Bytes): Bytes {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(input)
    }

    private fun gfMul(x: LongArray, y: LongArray): LongArray {
        var z0 = 0L; var z1 = 0L
        var v0 = y[0]; var v1 = y[1]
        for (i in 0 until 128) {
            val bit = if (i < 64) (x[0] ushr (63 - i)) and 1 else (x[1] ushr (127 - i)) and 1
            if (bit == 1L) {
                z0 = z0 xor v0; z1 = z1 xor v1
            }
            val lsb = v1 and 1
            v1 = (v1 ushr 1) or (v0 shl 63)
            v0 = v0 ushr 1
            if (lsb == 1L) v0 = v0 xor (0xE1L shl 56)
        }
        return longArrayOf(z0, z1)
    }

    private fun be64(b: Bytes, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }

    private fun ghash(h: LongArray, ad: Bytes, ct: Bytes, ctLength: Int): Bytes {
        var y = longArrayOf(0, 0)
        fun absorb(data: Bytes, length: Int) {
            var o = 0
            while (o < length) {
                val block = ByteArray(16)
                System.arraycopy(data, o, block, 0, minOf(16, length - o))
                y = gfMul(longArrayOf(y[0] xor be64(block, 0), y[1] xor be64(block, 8)), h)
                o += 16
            }
        }
        absorb(ad, ad.size)
        absorb(ct, ctLength)
        y = gfMul(longArrayOf(y[0] xor (ad.size.toLong() * 8), y[1] xor (ctLength.toLong() * 8)), h)
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[i] = (y[0] ushr (56 - 8 * i)).toByte()
            out[8 + i] = (y[1] ushr (56 - 8 * i)).toByte()
        }
        return out
    }

    /** Keystream counter blocks from J0 + 1 (12-byte nonce). */
    private fun crypt(key: Bytes, nonce: Bytes, data: Bytes, length: Int) {
        val blocks = (length + 15) / 16
        val counters = ByteArray(blocks * 16)
        for (b in 0 until blocks) {
            System.arraycopy(nonce, 0, counters, b * 16, 12)
            val n = b + 2
            counters[b * 16 + 12] = (n ushr 24).toByte(); counters[b * 16 + 13] = (n ushr 16).toByte()
            counters[b * 16 + 14] = (n ushr 8).toByte(); counters[b * 16 + 15] = n.toByte()
        }
        val stream = if (blocks > 0) aesBlocks(key, counters) else ByteArray(0)
        for (i in 0 until length) data[i] = (data[i].toInt() xor stream[i].toInt()).toByte()
    }

    private fun tag(key: Bytes, nonce: Bytes, ad: Bytes, ct: Bytes, ctLength: Int): Bytes {
        val hBytes = aesBlocks(key, ByteArray(16))
        val s = ghash(longArrayOf(be64(hBytes, 0), be64(hBytes, 8)), ad, ct, ctLength)
        val j0 = ByteArray(16)
        System.arraycopy(nonce, 0, j0, 0, 12)
        j0[15] = 1
        val ek = aesBlocks(key, j0)
        for (i in 0 until 16) s[i] = (s[i].toInt() xor ek[i].toInt()).toByte()
        return s
    }

    fun seal(key: Bytes, nonce: Bytes, ad: Bytes, plaintext: Bytes): Bytes {
        val out = ByteArray(plaintext.size + 16)
        System.arraycopy(plaintext, 0, out, 0, plaintext.size)
        crypt(key, nonce, out, plaintext.size)
        System.arraycopy(tag(key, nonce, ad, out, plaintext.size), 0, out, plaintext.size, 16)
        return out
    }

    fun open(key: Bytes, nonce: Bytes, ad: Bytes, sealed: Bytes): Bytes {
        if (sealed.size < 16) throw SendspinCryptoException("decrypt")
        val n = sealed.size - 16
        if (!bytesEqual(tag(key, nonce, ad, sealed, n), sealed.copyOfRange(n, sealed.size))) throw SendspinCryptoException("decrypt")
        val out = sealed.copyOf(n)
        crypt(key, nonce, out, n)
        return out
    }
}
