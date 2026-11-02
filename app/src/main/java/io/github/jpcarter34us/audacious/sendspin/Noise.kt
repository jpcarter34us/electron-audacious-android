package io.github.jpcarter34us.audacious.sendspin

/**
 * The Noise Protocol pieces Sendspin uses, ported from the iOS port's
 * Noise.swift (itself from sendspin-js 5 `src/core/noise/`):
 * Noise_KKpsk2_25519_{ChaChaPoly,AESGCM}_SHA256, the client as responder.
 * The handshake is two messages; the transport is one AEAD message per
 * WebSocket binary frame.
 */
enum class NoiseSuite(val cipherName: String) {
    CHACHA("ChaChaPoly"), AESGCM("AESGCM");

    /** The suite string in client/init. */
    val wireName get() = "25519_${cipherName}_SHA256"

    private fun nonce(n: Long): Bytes {
        val out = ByteArray(12)
        for (i in 0 until 8) {
            val byte = (n ushr (8 * i)).toByte()
            // ChaChaPoly: little-endian counter; AESGCM: big-endian.
            if (this == CHACHA) out[4 + i] = byte else out[11 - i] = byte
        }
        return out
    }

    fun encrypt(key: Bytes, nonce: Long, ad: Bytes, plaintext: Bytes): Bytes =
        if (this == CHACHA) ChaCha20Poly1305.seal(key, nonce(nonce), ad, plaintext) else AesGcm.seal(key, nonce(nonce), ad, plaintext)

    fun decrypt(key: Bytes, nonce: Long, ad: Bytes, ciphertext: Bytes): Bytes =
        if (this == CHACHA) ChaCha20Poly1305.open(key, nonce(nonce), ad, ciphertext) else AesGcm.open(key, nonce(nonce), ad, ciphertext)
}

class CipherState(val suite: NoiseSuite, private var key: Bytes? = null) {
    private var n = 0L

    fun initializeKey(key: Bytes?) {
        this.key = key
        n = 0
    }

    fun encrypt(ad: Bytes, plaintext: Bytes): Bytes {
        val k = key ?: return plaintext
        return suite.encrypt(k, n, ad, plaintext).also { n++ }
    }

    fun decrypt(ad: Bytes, ciphertext: Bytes): Bytes {
        val k = key ?: return ciphertext
        return suite.decrypt(k, n, ad, ciphertext).also { n++ }
    }
}

/** Noise HKDF: two or three 32-byte outputs from (chaining key, input). */
private fun noiseHKDF(ck: Bytes, ikm: Bytes, count: Int): List<Bytes> {
    val temp = hmacSHA256(ck, ikm)
    val o1 = hmacSHA256(temp, byteArrayOf(1))
    val o2 = hmacSHA256(temp, o1 + byteArrayOf(2))
    if (count == 2) return listOf(o1, o2)
    return listOf(o1, o2, hmacSHA256(temp, o2 + byteArrayOf(3)))
}

class SymmetricState(suite: NoiseSuite, protocolName: String) {
    private val cipher = CipherState(suite)
    var h: Bytes
        private set
    private var ck: Bytes

    init {
        val name = utf8(protocolName)
        h = if (name.size <= 32) name.copyOf(32) else sha256(name)
        ck = h
    }

    fun mixHash(data: Bytes) {
        h = sha256(h + data)
    }

    fun mixKey(ikm: Bytes) {
        val out = noiseHKDF(ck, ikm, 2)
        ck = out[0]
        cipher.initializeKey(out[1])
    }

    fun mixKeyAndHash(ikm: Bytes) {
        val out = noiseHKDF(ck, ikm, 3)
        ck = out[0]
        mixHash(out[1])
        cipher.initializeKey(out[2])
    }

    fun encryptAndHash(plaintext: Bytes): Bytes = cipher.encrypt(h, plaintext).also { mixHash(it) }

    fun decryptAndHash(ciphertext: Bytes): Bytes = cipher.decrypt(h, ciphertext).also { mixHash(ciphertext) }

    /** (initiator to responder, responder to initiator). */
    fun split(): Pair<CipherState, CipherState> {
        val out = noiseHKDF(ck, ByteArray(0), 2)
        return CipherState(cipher.suite, out[0]) to CipherState(cipher.suite, out[1])
    }
}

/**
 * Noise_KKpsk2: both static keys are known up front (pre-messages), the
 * PSK is mixed in at the end of message 2.
 *   -> e, es, ss
 *   <- e, ee, se, psk
 */
class NoiseHandshake(
    val suite: NoiseSuite,
    val role: Role,
    prologue: Bytes,
    private val s: KeyPair,
    private val rs: Bytes,
    var psk: Bytes? = null,
    private val fixedEphemeral: KeyPair? = null,
) {
    enum class Role { INITIATOR, RESPONDER }
    enum class Token { E, ES, SS, EE, SE, PSK }

    companion object {
        val MESSAGE1 = listOf(Token.E, Token.ES, Token.SS)
        val MESSAGE2 = listOf(Token.E, Token.EE, Token.SE, Token.PSK)
    }

    private val sym = SymmetricState(suite, "Noise_KKpsk2_25519_${suite.cipherName}_SHA256")
    private var e: KeyPair? = null
    private var re: Bytes? = null

    init {
        sym.mixHash(prologue)
        sym.mixHash(if (role == Role.INITIATOR) s.publicKey else rs)
        sym.mixHash(if (role == Role.RESPONDER) s.publicKey else rs)
    }

    /** The running handshake hash (PIN pairing binds to it; a re-handshake
     *  uses it as its prologue). */
    val handshakeHash: Bytes get() = sym.h

    private fun need(key: Bytes?): Bytes = key ?: throw SendspinCryptoException("malformed")

    private fun mixDH(token: Token) {
        val initiator = role == Role.INITIATOR
        val shared = when (token) {
            Token.EE -> X25519.scalarMult(need(e?.privateKey), need(re))
            Token.SS -> X25519.scalarMult(s.privateKey, rs)
            Token.ES -> if (initiator) X25519.scalarMult(need(e?.privateKey), rs) else X25519.scalarMult(s.privateKey, need(re))
            Token.SE -> if (initiator) X25519.scalarMult(s.privateKey, need(re)) else X25519.scalarMult(need(e?.privateKey), rs)
            else -> throw SendspinCryptoException("malformed")
        }
        sym.mixKey(shared)
    }

    fun writeMessage(tokens: List<Token>, payload: Bytes): Bytes {
        var out = ByteArray(0)
        for (token in tokens) {
            when (token) {
                Token.E -> {
                    val eph = fixedEphemeral ?: KeyPair.generate()
                    e = eph
                    sym.mixHash(eph.publicKey)
                    // PSK mode (Noise §9.2): the ephemeral is also mixed into the key.
                    sym.mixKey(eph.publicKey)
                    out += eph.publicKey
                }
                Token.PSK -> sym.mixKeyAndHash(psk ?: throw SendspinCryptoException("bad key"))
                else -> mixDH(token)
            }
        }
        return out + sym.encryptAndHash(payload)
    }

    fun readMessage(tokens: List<Token>, message: Bytes): Bytes {
        var pos = 0
        for (token in tokens) {
            when (token) {
                Token.E -> {
                    if (message.size - pos < 32) throw SendspinCryptoException("malformed")
                    val key = message.copyOfRange(pos, pos + 32)
                    pos += 32
                    re = key
                    sym.mixHash(key)
                    sym.mixKey(key)
                }
                Token.PSK -> sym.mixKeyAndHash(psk ?: throw SendspinCryptoException("bad key"))
                else -> mixDH(token)
            }
        }
        return sym.decryptAndHash(message.copyOfRange(pos, message.size))
    }

    fun split(): NoiseSession {
        val (c1, c2) = sym.split()
        return if (role == Role.INITIATOR) NoiseSession(c1, c2) else NoiseSession(c2, c1)
    }
}

class NoiseSession(private val sendCipher: CipherState, private val receiveCipher: CipherState) {
    fun encrypt(plaintext: Bytes): Bytes = sendCipher.encrypt(ByteArray(0), plaintext)
    fun decrypt(ciphertext: Bytes): Bytes = receiveCipher.decrypt(ByteArray(0), ciphertext)
}
