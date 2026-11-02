package io.github.jpcarter34us.audacious.sendspin

import java.math.BigInteger

/**
 * CPACE-X25519-SHA512 (draft-irtf-cfrg-cpace) in initiator-responder mode
 * with explicit mutual confirmation, and the Sendspin PIN helpers, ported
 * from the iOS port's CPace.swift (sendspin-js 5 `src/core/pake/`). The
 * server is the initiator (A), this client the responder (B). Field
 * arithmetic for Elligator2 is BigInteger mod 2^255 - 19.
 */
class CPaceException(message: String) : Exception(message)

object CPace {
    const val SHARE_SIZE = 32
    const val TAG_SIZE = 64

    private val DSI = utf8("CPace255")
    private val DSI_ISK = utf8("CPace255_ISK")
    private val MAC_LABEL = utf8("CPaceMac")
    private val P = X25519.P

    /** LEB128 length prefix (the draft's prepend_len). */
    fun prependLen(data: Bytes): Bytes {
        var length = data.size
        val prefix = java.io.ByteArrayOutputStream()
        do {
            prefix.write(if (length < 128) length else (length and 0x7F) or 0x80)
            length = length ushr 7
        } while (length != 0)
        return prefix.toByteArray() + data
    }

    fun lvCat(vararg parts: Bytes): Bytes = parts.fold(ByteArray(0)) { acc, p -> acc + prependLen(p) }

    fun generatorString(prs: Bytes, ci: Bytes, sid: Bytes): Bytes {
        val zpad = maxOf(0, 128 - 1 - prependLen(prs).size - prependLen(DSI).size)
        return lvCat(DSI, prs, ByteArray(zpad), ci, sid)
    }

    /** A field element from 32 little-endian bytes (top bit ignored, reduced). */
    fun fieldFromBytes(bytes: Bytes): BigInteger {
        val b = bytes.copyOf(32)
        b[31] = (b[31].toInt() and 0x7F).toByte()
        return X25519.decodeLE(b).mod(P)
    }

    /** Elligator2 onto Curve25519, returning the u-coordinate. */
    fun elligator2(r: BigInteger): Bytes {
        val a = BigInteger.valueOf(486662)
        val two = BigInteger.valueOf(2)
        val one = BigInteger.ONE
        fun inv(x: BigInteger) = x.modPow(P.subtract(two), P)
        val v = a.multiply(inv(one.add(two.multiply(r).multiply(r)).mod(P))).mod(P).negate().mod(P)
        val rhs = v.multiply(v).multiply(v).add(a.multiply(v).multiply(v)).add(v).mod(P)
        val eps = rhs.modPow(P.subtract(one).shiftRight(1), P)
        val x = eps.multiply(v).subtract(one.subtract(eps).multiply(a).multiply(inv(two))).mod(P)
        return X25519.encodeLE(x)
    }

    fun generator(prs: Bytes, ci: Bytes = ByteArray(0), sid: Bytes): Bytes {
        val hash = sha512(generatorString(prs, ci, sid))
        return elligator2(fieldFromBytes(hash.copyOf(32)))
    }

    /** One side of a CPace exchange. Sendspin uses an empty CI, ADa
     *  "server", ADb "client" and sid "sendspin-pair-pake-v1" || h || counter. */
    class Party(
        val role: Role,
        prs: Bytes,
        val sid: Bytes,
        val ada: Bytes,
        val adb: Bytes,
        scalar: Bytes? = null,
    ) {
        enum class Role { INITIATOR, RESPONDER }

        private var scalar: Bytes? = scalar ?: randomBytes(32)
        val publicShare: Bytes = try {
            X25519.scalarMult(this.scalar!!, generator(prs, sid = sid))
        } catch (e: SendspinCryptoException) {
            throw CPaceException("generator encodes a low-order point")
        }
        private var initiatorShare: Bytes? = null
        private var responderShare: Bytes? = null
        private var macKey: Bytes? = null
        var isk: Bytes? = null
            private set

        fun derive(peerShare: Bytes) {
            val k = scalar ?: throw CPaceException("derive() may only be called once")
            scalar = null
            if (peerShare.size != SHARE_SIZE) throw CPaceException("bad peer share size")
            val shared = try {
                X25519.scalarMult(k, peerShare)
            } catch (e: SendspinCryptoException) {
                throw CPaceException("peer share encodes a low-order point")
            }
            val ya = if (role == Role.INITIATOR) publicShare else peerShare
            val yb = if (role == Role.INITIATOR) peerShare else publicShare
            initiatorShare = ya
            responderShare = yb
            val transcript = lvCat(ya, ada) + lvCat(yb, adb)
            val key = sha512(lvCat(DSI_ISK, sid, shared) + transcript)
            isk = key
            macKey = sha512(MAC_LABEL + sid + key)
        }

        /** This side's confirmation tag (Ta or Tb). */
        fun tag(): Bytes = mac(true)

        fun verify(peerTag: Bytes): Boolean {
            val expected = mac(false)
            // A reflected tag (same share and AD on both sides) is refused.
            if (bytesEqual(expected, mac(true))) return false
            return bytesEqual(peerTag, expected)
        }

        private fun mac(own: Boolean): Bytes {
            val key = macKey ?: throw CPaceException("derive() must be called before confirmation tags")
            val useInitiator = own == (role == Role.INITIATOR)
            return hmacSHA512(key, lvCat(if (useInitiator) initiatorShare!! else responderShare!!, if (useInitiator) ada else adb))
        }
    }
}

object SendspinPIN {
    const val NONCE_SIZE = 32
    const val MIN_DIGITS = 4
    const val MAX_DIGITS = 12
    const val DEFAULT_MIN_DIGITS = 6

    /** commit_B = SHA-256("sendspin-pair-commit-v1" || nonce_B). */
    fun commit(nonce: Bytes): Bytes = sha256(utf8("sendspin-pair-commit-v1") + nonce)

    /** The `length`-digit dynamic PIN: SHA-256("sendspin-pin-derive-v1" ||
     *  h || nonce_A || nonce_B) as a big-endian integer, mod 10^length. */
    fun derive(handshakeHash: Bytes, nonceA: Bytes, nonceB: Bytes, length: Int): String {
        val digest = sha256(utf8("sendspin-pin-derive-v1") + handshakeHash + nonceA + nonceB)
        val modulus = BigInteger.TEN.pow(length)
        val digits = BigInteger(1, digest).mod(modulus).toString()
        return "0".repeat(maxOf(0, length - digits.length)) + digits
    }
}
