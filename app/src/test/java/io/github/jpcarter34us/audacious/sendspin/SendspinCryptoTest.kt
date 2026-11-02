package io.github.jpcarter34us.audacious.sendspin

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The Kotlin Noise, CPace, PIN and PSK code against values computed by
 *  sendspin-js 5.0.0 (the iOS port's vectors), as its SendspinCryptoTests. */
class SendspinCryptoTest {
    private fun hex(s: String): Bytes = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: Bytes): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    @Test
    fun identifiersAndPairingToken() {
        val client = KeyPair(hex(SendspinVectors.clientSecret))
        assertEquals(SendspinVectors.clientPublic, hex(client.publicKey))
        assertEquals(SendspinVectors.clientID, Base64URL.encode(client.publicKey))
        assertArrayEquals(client.publicKey, Base64URL.decode(SendspinVectors.clientID))
        assertEquals(SendspinVectors.sentinelPSK, hex(sendspinSentinelPSK))
        assertEquals(SendspinVectors.sentinelPskID, sendspinPskID(sendspinSentinelPSK))
        assertEquals(SendspinVectors.pskID, sendspinPskID(hex(SendspinVectors.psk)))
        assertEquals(SendspinVectors.pairingToken, sendspinPairingToken(client.publicKey, hex(SendspinVectors.psk)))
    }

    @Test
    fun noiseHandshakeAndTransportMatchSendspinJS() {
        val client = KeyPair(hex(SendspinVectors.clientSecret))
        val ephemeral = KeyPair(hex(SendspinVectors.clientEphemeral))
        for (v in SendspinVectors.handshakes) {
            val suite = if (v.suite == "chacha") NoiseSuite.CHACHA else NoiseSuite.AESGCM
            assertEquals(v.wire, suite.wireName)
            val hs = NoiseHandshake(suite, NoiseHandshake.Role.RESPONDER, hex(v.prologue), client,
                hex(SendspinVectors.serverPublic), fixedEphemeral = ephemeral)
            val payload = hs.readMessage(NoiseHandshake.MESSAGE1, hex(v.message1))
            assertEquals("{\"psk_id\":\"${SendspinVectors.pskID}\"}", String(payload, Charsets.UTF_8))
            hs.psk = hex(SendspinVectors.psk)
            val m2 = hs.writeMessage(NoiseHandshake.MESSAGE2, utf8("{}"))
            assertEquals(v.suite, v.message2, hex(m2))
            assertEquals(v.suite, v.hash, hex(hs.handshakeHash))
            val session = hs.split()
            for ((plain, cipher) in v.serverToClient) assertEquals(v.suite, plain, hex(session.decrypt(hex(cipher))))
            for ((plain, cipher) in v.clientToServer) assertEquals(v.suite, cipher, hex(session.encrypt(hex(plain))))
        }
    }

    @Test
    fun noiseRejectsWrongPSKAndTampering() {
        val client = KeyPair(hex(SendspinVectors.clientSecret))
        val v = SendspinVectors.handshakes[0]
        val prologue = hex(v.prologue)
        prologue[0] = (prologue[0].toInt() xor 1).toByte()
        val hs = NoiseHandshake(NoiseSuite.CHACHA, NoiseHandshake.Role.RESPONDER, prologue, client, hex(SendspinVectors.serverPublic))
        try {
            hs.readMessage(NoiseHandshake.MESSAGE1, hex(v.message1))
            fail("tampered prologue accepted")
        } catch (_: SendspinCryptoException) {
        }
        val ok = NoiseHandshake(NoiseSuite.CHACHA, NoiseHandshake.Role.RESPONDER, hex(v.prologue), client, hex(SendspinVectors.serverPublic))
        ok.readMessage(NoiseHandshake.MESSAGE1, hex(v.message1))
        ok.psk = sendspinSentinelPSK
        ok.writeMessage(NoiseHandshake.MESSAGE2, utf8("{}"))
        try {
            ok.split().decrypt(hex(v.serverToClient[0].second))
            fail("wrong PSK decrypted")
        } catch (_: SendspinCryptoException) {
        }
    }

    @Test
    fun noiseInitiatorInteroperatesWithResponder() {
        val client = KeyPair.generate()
        val server = KeyPair.generate()
        val psk = randomBytes(32)
        for (suite in NoiseSuite.values()) {
            val i = NoiseHandshake(suite, NoiseHandshake.Role.INITIATOR, byteArrayOf(1, 2, 3), server, client.publicKey, psk)
            val r = NoiseHandshake(suite, NoiseHandshake.Role.RESPONDER, byteArrayOf(1, 2, 3), client, server.publicKey, psk)
            assertArrayEquals(byteArrayOf(9), r.readMessage(NoiseHandshake.MESSAGE1, i.writeMessage(NoiseHandshake.MESSAGE1, byteArrayOf(9))))
            assertArrayEquals(ByteArray(0), i.readMessage(NoiseHandshake.MESSAGE2, r.writeMessage(NoiseHandshake.MESSAGE2, ByteArray(0))))
            assertArrayEquals(i.handshakeHash, r.handshakeHash)
            val si = i.split()
            val sr = r.split()
            assertArrayEquals(byteArrayOf(0, 42), sr.decrypt(si.encrypt(byteArrayOf(0, 42))))
            assertArrayEquals(byteArrayOf(0, 43), si.decrypt(sr.encrypt(byteArrayOf(0, 43))))
            // Long frames cross many ChaCha/GCM blocks and Poly1305 buffers.
            val big = randomBytes(5000)
            assertArrayEquals(big, sr.decrypt(si.encrypt(big)))
        }
    }

    @Test
    fun chachaPolyMatchesRfc8439() {
        // RFC 8439 §2.8.2.
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val ad = hex("50515253c0c1c2c3c4c5c6c7")
        val plain = utf8("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.")
        val sealed = ChaCha20Poly1305.seal(key, nonce, ad, plain)
        assertEquals("1ae10b594f09e26a7e902ecbd0600691", hex(sealed.copyOfRange(sealed.size - 16, sealed.size)))
        assertEquals("d31a8d34648e60db7b86afbc53ef7ec2", hex(sealed.copyOfRange(0, 16)))
        assertArrayEquals(plain, ChaCha20Poly1305.open(key, nonce, ad, sealed))
    }

    @Test
    fun aesGcmMatchesNistVector() {
        // GCM spec test case 16 (AES-256, 60-byte plaintext, 20-byte AD).
        val key = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
        val nonce = hex("cafebabefacedbaddecaf888")
        val plain = hex("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39")
        val ad = hex("feedfacedeadbeeffeedfacedeadbeefabaddad2")
        val sealed = AesGcm.seal(key, nonce, ad, plain)
        assertEquals("522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662",
            hex(sealed.copyOf(plain.size)))
        assertEquals("76fc6ece0f4e1768cddf8853bb2d551b", hex(sealed.copyOfRange(plain.size, sealed.size)))
        assertArrayEquals(plain, AesGcm.open(key, nonce, ad, sealed))
    }

    @Test
    fun x25519MatchesRfc7748() {
        val k = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        assertEquals("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552", hex(X25519.scalarMult(k, u)))
    }

    @Test
    fun elligator2MatchesSendspinJS() {
        for ((input, output) in SendspinVectors.elligator2) {
            assertEquals(input, output, hex(CPace.elligator2(CPace.fieldFromBytes(hex(input)))))
        }
    }

    @Test
    fun cpaceMatchesSendspinJS() {
        val v = SendspinVectors.CPace
        val sid = hex(v.sid)
        assertArrayEquals(sid, SendspinSession.sid(hex(v.handshakeHash), 1))
        assertEquals(v.generator, hex(CPace.generator(utf8(v.prs), sid = sid)))
        val a = CPace.Party(CPace.Party.Role.INITIATOR, utf8(v.prs), sid, utf8("server"), utf8("client"), hex(v.scalarA))
        val b = CPace.Party(CPace.Party.Role.RESPONDER, utf8(v.prs), sid, utf8("server"), utf8("client"), hex(v.scalarB))
        assertEquals(v.shareA, hex(a.publicShare))
        assertEquals(v.shareB, hex(b.publicShare))
        b.derive(hex(v.shareA))
        a.derive(hex(v.shareB))
        assertEquals(v.isk, hex(b.isk!!))
        assertEquals(v.tagB, hex(b.tag()))
        assertEquals(v.tagA, hex(a.tag()))
        assertTrue(b.verify(hex(v.tagA)))
        assertFalse(b.verify(hex(v.tagB)))

        val wrong = CPace.Party(CPace.Party.Role.RESPONDER, utf8("654321"), sid, utf8("server"), utf8("client"))
        wrong.derive(hex(v.shareA))
        assertFalse(wrong.verify(hex(v.tagA)))

        val kWrap = sha256(utf8("sendspin-pair-psk-wrap-v1") + sid + hex(v.isk))
        assertEquals(v.wrappedPSK, hex(NoiseSuite.CHACHA.encrypt(kWrap, 0, ByteArray(0), hex(SendspinVectors.psk))))
    }

    @Test
    fun pinDerivationMatchesSendspinJS() {
        val v = SendspinVectors.PIN
        assertEquals(v.commitB, hex(SendspinPIN.commit(hex(v.nonceB))))
        for ((length, pin) in v.pins) {
            assertEquals(pin, SendspinPIN.derive(hex(SendspinVectors.CPace.handshakeHash), hex(v.nonceA), hex(v.nonceB), length))
        }
    }

    @Test
    fun pskStorePersists() {
        val storage = MemorySendspinStorage()
        val store = SendspinPskStore(storage)
        assertEquals(SendspinPskStore.Category.SENTINEL, store.lookup(SendspinVectors.sentinelPskID)?.category)
        val pairing = store.pairingPSK()
        val psk = hex(SendspinVectors.psk)
        store.addLongTerm(psk, "server-a")
        val reloaded = SendspinPskStore(storage)
        assertEquals("server-a", reloaded.lookup(SendspinVectors.pskID)?.serverID)
        assertEquals(SendspinPskStore.Category.PAIRING, reloaded.lookup(sendspinPskID(pairing))?.category)
        assertEquals(listOf("server-a"), reloaded.pairedServers)
        reloaded.remove(SendspinVectors.pskID)
        assertNull(SendspinPskStore(storage).lookup(SendspinVectors.pskID))

        val identity = SendspinIdentity.loadOrCreate(storage)
        assertEquals(identity.clientID, SendspinIdentity.loadOrCreate(storage).clientID)
    }

    @Test
    fun activateAuthorization() {
        val roles = listOf("player@v1")

        assertNull(SendspinSession.authorize(SendspinPskStore.Category.SENTINEL, listOf("playback"), roles, true))
        assertEquals("pairing_required", SendspinSession.authorize(SendspinPskStore.Category.SENTINEL, listOf("playback"), roles, false))
        assertNull(SendspinSession.authorize(SendspinPskStore.Category.SENTINEL, listOf("pairing"), null, false))
        assertEquals("unauthorized", SendspinSession.authorize(SendspinPskStore.Category.SENTINEL, listOf("management"), null, true))
        assertNull(SendspinSession.authorize(SendspinPskStore.Category.LONG_TERM, listOf("playback", "management"), roles, false))
        assertEquals("unauthorized", SendspinSession.authorize(SendspinPskStore.Category.PAIRING, listOf("playback"), roles, true))
        assertNull(SendspinSession.authorize(SendspinPskStore.Category.PAIRING, listOf("pairing"), null, true))
    }
}
