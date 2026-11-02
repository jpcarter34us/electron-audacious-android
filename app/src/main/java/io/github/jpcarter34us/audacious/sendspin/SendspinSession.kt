package io.github.jpcarter34us.audacious.sendspin

import android.util.Log
import io.github.jpcarter34us.audacious.util.Json
import io.github.jpcarter34us.audacious.util.bool
import io.github.jpcarter34us.audacious.util.double
import io.github.jpcarter34us.audacious.util.int
import io.github.jpcarter34us.audacious.util.long
import io.github.jpcarter34us.audacious.util.obj
import io.github.jpcarter34us.audacious.util.str
import io.github.jpcarter34us.audacious.util.strings
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

const val SENDSPIN_TAG = "sendspin"

/** The WebSocket under a session (already signed in to Music Assistant's
 *  proxy, when that is the route). */
interface SendspinSocket {
    fun sendText(text: String)
    fun sendBinary(data: Bytes)
    fun close()
}

data class SendspinFormat(val codec: String, val sampleRate: Int, val channels: Int, val bitDepth: Int) {
    val json: JSONObject
        get() = JSONObject().put("codec", codec).put("sample_rate", sampleRate).put("channels", channels).put("bit_depth", bitDepth)

    companion object {
        /** As sendspin-js codec-support.ts: Opus at 48 kHz, the others at 48
         *  and 44.1 kHz, all stereo 16-bit. */
        fun defaults(codecs: List<String>): List<SendspinFormat> = codecs.flatMap { codec ->
            if (codec == "opus") listOf(SendspinFormat(codec, 48000, 2, 16))
            else listOf(48000, 44100).map { SendspinFormat(codec, it, 2, 16) }
        }

        /** Bytes for 30 s of the most demanding advertised format (the
         *  server's stream-ahead horizon). */
        fun bufferCapacity(formats: List<SendspinFormat>): Int {
            val rates = formats.map { f ->
                val pcm = (f.sampleRate * f.channels * ((f.bitDepth + 7) / 8)).toDouble()
                when (f.codec) {
                    "opus" -> 64000.0
                    "flac" -> pcm * 1.02
                    else -> pcm
                }
            }
            return ((rates.maxOrNull() ?: 192_000.0) * 30).toInt()
        }
    }
}

class SendspinConfig(
    var clientName: String,
    var productName: String = "Audacious for Android",
    var manufacturer: String = "Audacious for Android",
    var softwareVersion: String = "1.0",
    var suite: NoiseSuite = NoiseSuite.CHACHA,
    /** Whether an unpaired server (the Sentinel PSK) may play here. */
    var unpairedAccess: Boolean = true,
    /** player@v1 supported_formats, in order of preference. */
    var formats: List<SendspinFormat> = SendspinFormat.defaults(listOf("pcm")),
    var requiredLeadTimeMs: Int = 250,
    var minBufferMs: Int = 250,
    var staticDelayMs: Int = 0,
    var minPinLength: Int = SendspinPIN.DEFAULT_MIN_DIGITS,
)

/**
 * One Sendspin connection, from client/init to close: the encrypted
 * transport (sendspin-js transport.ts), PIN and PSK pairing (pairing.ts),
 * and the player/controller/metadata protocol with clock sync
 * (protocol-handler.ts, time-sync-manager.ts), ported from the iOS port's
 * SendspinSession.swift. Everything runs on `queue`, a single thread.
 */
class SendspinSession(
    val config: SendspinConfig,
    private val socket: SendspinSocket,
    private val identity: SendspinIdentity,
    private val psks: SendspinPskStore,
    private val storage: SendspinStorage,
    val queue: ScheduledExecutorService,
) {
    open class Events {
        open fun handshake(category: SendspinPskStore.Category) {}
        open fun streamStart(format: JSONObject, isUpdate: Boolean) {}
        open fun streamClear() {}
        open fun streamEnd() {}
        /** Server timestamp (µs, server clock) and the encoded audio. */
        open fun audio(serverTime: Long, payload: Bytes, offset: Int) {}
        open fun volume(volume: Int, muted: Boolean) {}
        open fun staticDelay(ms: Int) {}
        /** server/state (metadata, controller) and group/update, merged. */
        open fun state(server: JSONObject, group: JSONObject) {}
        open fun activated(roles: List<String>) {}
        open fun pin(pin: String?) {}
        open fun pairing(event: String, detail: String?) {}
    }

    var events = Events()
    val timeFilter = SendspinTimeFilter()

    // MARK: Timers

    private val timers = HashMap<String, ScheduledFuture<*>>()
    private var closed = false

    private fun after(seconds: Double, name: String, work: () -> Unit) {
        timers.remove(name)?.cancel(false)
        lateinit var future: ScheduledFuture<*>
        future = queue.schedule({
            if (!closed && timers[name] === future) {
                timers.remove(name)
                work()
            }
        }, (seconds * 1000).toLong(), TimeUnit.MILLISECONDS)
        timers[name] = future
    }

    private fun cancelTimer(name: String) {
        timers.remove(name)?.cancel(false)
    }

    // MARK: - Transport

    private enum class TransportState { IDLE, AWAIT_SERVER_INIT, AWAIT_NOISE1, TRANSPORT }

    private var state = TransportState.IDLE
    private var handshake: NoiseHandshake? = null
    private var session: NoiseSession? = null
    var matched: SendspinPskStore.Entry? = null
        private set
    var serverID = ""
        private set
    private var rawClientInit = ByteArray(0)
    var handshakeHash = ByteArray(0)
        private set
    private var fragmentType = -1
    private var fragmentData = java.io.ByteArrayOutputStream()
    private var quiesced = false
    private var outboundQueue = ArrayList<JSONObject>()
    private var seenActivate = false
    private var effectiveActiveRoles: List<String>? = null

    companion object {
        private const val HANDSHAKE_TIMEOUT = 30.0
        private const val MAX_TRANSPORT_PLAINTEXT = 65519
        private const val MAX_REASSEMBLY = 4 * 1024 * 1024
        private val QUIESCED_TYPES = setOf("client/command", "client/time", "client/state")

        private const val FAILURES_KEY = "sendspin-pair-failures"
        private const val ATTEMPT_TIMEOUT = 120.0
        private const val ESCALATION_THRESHOLD = 10
        private const val SHORT_PIN_LENGTH = 6
        private const val STATE_INTERVAL = 5.0
        private const val BURST_SIZE = 8
        private const val BURST_INTERVAL = 10.0
        private const val PROBE_TIMEOUT = 2.0

        /** activate-authorization.ts: null when allowed, else the goodbye reason. */
        fun authorize(category: SendspinPskStore.Category, activities: List<String>, activeRoles: List<String>?,
                      unpairedAccess: Boolean): String? {
            fun allowed(set: Set<String>, unpaired: Boolean): Boolean = when (category) {
                SendspinPskStore.Category.LONG_TERM ->
                    if ("pairing" in set) set.size == 1 else set.all { it == "playback" || it == "management" }
                SendspinPskStore.Category.PAIRING -> set == setOf("pairing")
                SendspinPskStore.Category.SENTINEL ->
                    set.isEmpty() || set == setOf("pairing") || (set == setOf("playback") && unpaired)
            }
            fun admissible(unpaired: Boolean): Boolean {
                val set = activities.toSet()
                if (!allowed(set, unpaired)) return false
                if (activeRoles != null && activeRoles.isNotEmpty()) return allowed(set + "playback", unpaired)
                return true
            }
            if (admissible(unpairedAccess)) return null
            if (category == SendspinPskStore.Category.SENTINEL && !unpairedAccess && admissible(true)) return "pairing_required"
            return "unauthorized"
        }

        /** state-manager.ts applyDiff: merge, null deletes, objects one level deep. */
        fun applyDiff(existing: JSONObject, diff: JSONObject): JSONObject {
            val result = JSONObject(existing.toString())
            val keys = diff.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = diff.opt(key)
                val old = result.opt(key)
                when {
                    value == null || value == JSONObject.NULL -> result.remove(key)
                    value is JSONObject && old is JSONObject -> result.put(key, applyDiff(old, value))
                    else -> result.put(key, value)
                }
            }
            return result
        }

        /** sid = "sendspin-pair-pake-v1" || h || big-endian uint32 counter. */
        fun sid(handshakeHash: Bytes, index: Int): Bytes =
            utf8("sendspin-pair-pake-v1") + handshakeHash +
                byteArrayOf((index ushr 24).toByte(), (index ushr 16).toByte(), (index ushr 8).toByte(), index.toByte())

        fun json(o: JSONObject): String = o.toString()

        private fun msg(type: String, payload: JSONObject) = JSONObject().put("type", type).put("payload", payload)
    }

    val isReady get() = state == TransportState.TRANSPORT
    val trustLevel get() = if (matched?.category == SendspinPskStore.Category.LONG_TERM) "user" else "none"

    fun start() {
        val text = json(msg("client/init", JSONObject().put("client_id", identity.clientID).put("version", 1)
            .put("suite", config.suite.wireName)))
        rawClientInit = utf8(text)
        socket.sendText(text)
        state = TransportState.AWAIT_SERVER_INIT
        after(HANDSHAKE_TIMEOUT, "handshake") { fail("handshake timed out") }
    }

    /** The socket went away: drop timers and keys. */
    fun socketClosed() {
        closed = true
        for (t in timers.values) t.cancel(false)
        timers.clear()
        state = TransportState.IDLE
        session = null
        handshake = null
        clearAttempt()
    }

    fun close() {
        socketClosed()
        socket.close()
    }

    private fun fail(reason: String) {
        Log.e(SENDSPIN_TAG, "sendspin: $reason")
        close()
    }

    fun receiveText(text: String) {
        if (closed) return
        if (state == TransportState.TRANSPORT) return fail("unexpected cleartext")
        val m = Json.parseObject(text)
        val type = m?.str("type") ?: return fail("malformed handshake")
        val payload = m.obj("payload") ?: JSONObject()
        try {
            if (state == TransportState.AWAIT_SERVER_INIT && type == "server/init") {
                val id = payload.str("server_id")
                val rs = id?.let { Base64URL.decode(it) }
                if (payload.int("version") != 1 || id == null || rs == null || rs.size != 32) return fail("unsupported server/init")
                serverID = id
                handshake = NoiseHandshake(config.suite, NoiseHandshake.Role.RESPONDER, rawClientInit + utf8(text), identity.keyPair, rs)
                state = TransportState.AWAIT_NOISE1
                after(HANDSHAKE_TIMEOUT, "handshake") { fail("handshake timed out") }
            } else if (state == TransportState.AWAIT_NOISE1 && type == "noise/handshake") {
                val data = payload.str("data")?.let { Base64URL.decode(it) } ?: return fail("unexpected $type")
                val hs = handshake ?: return fail("unexpected $type")
                val m2 = respond(hs, data)
                socket.sendText(json(msg("noise/handshake", JSONObject().put("data", Base64URL.encode(m2)))))
                session = hs.split()
                handshakeHash = hs.handshakeHash
                handshake = null
                state = TransportState.TRANSPORT
                cancelTimer("handshake")
                handshakeCompleted(false)
            } else {
                fail("unexpected $type")
            }
        } catch (e: Exception) {
            fail("handshake failed ($e)")
        }
    }

    /** Reads Noise message 1, picks the PSK it names, and writes message 2. */
    private fun respond(hs: NoiseHandshake, message1: Bytes): Bytes {
        val payload = hs.readMessage(NoiseHandshake.MESSAGE1, message1)
        val pskID = Json.parseObject(String(payload, Charsets.UTF_8))?.str("psk_id") ?: throw CPaceException("unknown psk_id")
        val entry = psks.lookup(pskID) ?: throw CPaceException("unknown psk_id")
        if (entry.category == SendspinPskStore.Category.LONG_TERM && entry.serverID != null && entry.serverID != serverID) {
            throw CPaceException("PSK belongs to another server")
        }
        hs.psk = entry.psk
        matched = entry
        return hs.writeMessage(NoiseHandshake.MESSAGE2, utf8("{}"))
    }

    fun receiveBinary(binary: Bytes) {
        if (closed) return
        val s = session
        if (state != TransportState.TRANSPORT || s == null) return fail("unexpected binary frame")
        if (binary.size > MAX_TRANSPORT_PLAINTEXT + 16) return fail("oversized frame")
        val plain = try {
            s.decrypt(binary)
        } catch (e: Exception) {
            return fail("decryption failed")
        }
        if (plain.isEmpty()) return fail("empty frame")
        dispatch(plain)
    }

    private fun dispatch(full: Bytes) {
        when (full[0].toInt()) {
            0 -> control(String(full, 1, full.size - 1, Charsets.UTF_8))
            2, 3 -> {
                if (full[0].toInt() == 2 && fragmentType < 0) {
                    if (full.size < 2 || full[1].toInt() == 2 || full[1].toInt() == 3) return fail("bad fragment")
                    fragmentType = full[1].toInt() and 0xFF
                    fragmentData = java.io.ByteArrayOutputStream()
                    fragmentData.write(full, 2, full.size - 2)
                    return
                }
                if (fragmentType < 0) return fail("stray fragment end")
                fragmentData.write(full, 1, full.size - 1)
                if (fragmentData.size() > MAX_REASSEMBLY) {
                    fragmentType = -1
                    return fail("fragment too large")
                }
                if (full[0].toInt() == 2) return
                val type = fragmentType
                val data = fragmentData.toByteArray()
                fragmentType = -1
                if (type == 0) control(String(data, Charsets.UTF_8)) else binaryMessage(byteArrayOf(type.toByte()) + data)
            }
            else -> binaryMessage(full)
        }
    }

    private fun control(body: String) {
        val m = Json.parseObject(body)
        val type = m?.str("type")
        if (m == null || type == null) {
            Log.w(SENDSPIN_TAG, "sendspin: dropped malformed control message")
            return
        }
        val payload = m.obj("payload") ?: JSONObject()
        when (type) {
            "noise/handshake" -> rehandshake(payload)
            "server/activate" -> activate(payload)
            "server/unpair" -> {
                val entry = matched ?: return
                if (entry.category != SendspinPskStore.Category.LONG_TERM) return
                psks.remove(entry.id)
                goodbyeAndClose("unpaired")
            }
            else -> route(type, payload)
        }
    }

    private fun rehandshake(payload: JSONObject) {
        val rs = Base64URL.decode(serverID)
        val data = payload.str("data")?.let { Base64URL.decode(it) }
        if (rs == null || data == null) return fail("bad re-handshake")
        val hs = NoiseHandshake(config.suite, NoiseHandshake.Role.RESPONDER, handshakeHash, identity.keyPair, rs)
        try {
            val m2 = respond(hs, data)
            quiesced = true
            after(HANDSHAKE_TIMEOUT, "handshake") { fail("re-handshake timed out") }
            // Message 2 goes under the current keys, then the keys change.
            encryptSend(msg("noise/handshake", JSONObject().put("data", Base64URL.encode(m2))))
            session = hs.split()
            handshakeHash = hs.handshakeHash
            seenActivate = false
            effectiveActiveRoles = null
            handshakeCompleted(true)
        } catch (e: Exception) {
            fail("re-handshake failed ($e)")
        }
    }

    private fun activate(payload: JSONObject) {
        val roles = payload.strings("active_roles")
        if (!seenActivate && roles == null) return goodbyeAndClose("unauthorized")
        if (roles != null) effectiveActiveRoles = roles
        seenActivate = true
        val activities = payload.strings("activities") ?: emptyList()
        val refusal = authorize(matched?.category ?: SendspinPskStore.Category.SENTINEL, activities, effectiveActiveRoles,
            config.unpairedAccess)
        if (refusal != null) return goodbyeAndClose(refusal)
        cancelTimer("handshake")
        val queued = if ("pairing" !in activities && effectiveActiveRoles?.contains("controller@v1") == true)
            outboundQueue.filter { it.optString("type") == "client/command" } else emptyList()
        quiesced = false
        outboundQueue = ArrayList()
        routeActivate(payload, activities)
        queued.forEach { encryptSend(it) }
    }

    private fun goodbyeAndClose(reason: String) {
        if (state == TransportState.TRANSPORT) encryptSend(msg("client/goodbye", JSONObject().put("reason", reason)))
        close()
    }

    fun sendControl(m: JSONObject) {
        if (state != TransportState.TRANSPORT || session == null) return
        if (quiesced && m.optString("type") in QUIESCED_TYPES) {
            outboundQueue.add(m)
            return
        }
        encryptSend(m)
    }

    private fun encryptSend(m: JSONObject) {
        val s = session ?: return
        val plain = byteArrayOf(0) + utf8(json(m))
        if (plain.size > MAX_TRANSPORT_PLAINTEXT) return
        val ct = try {
            s.encrypt(plain)
        } catch (e: Exception) {
            return
        }
        socket.sendBinary(ct)
    }

    fun goodbye(reason: String) {
        if (state == TransportState.TRANSPORT) encryptSend(msg("client/goodbye", JSONObject().put("reason", reason)))
    }

    private fun handshakeCompleted(rehandshake: Boolean) {
        resetPairing()
        resetActivation(rehandshake)
        matched?.category?.let { events.handshake(it) }
    }

    private fun binaryMessage(bytes: Bytes) {
        // Type 4: player audio chunk, 8-byte big-endian server timestamp (µs) then the data.
        if (bytes.size < 9 || bytes[0].toInt() != 4 || currentFormat == null) return
        var ts = 0L
        for (i in 1..8) ts = (ts shl 8) or (bytes[i].toLong() and 0xFF)
        events.audio(ts, bytes, 9)
    }

    // MARK: - Routing (core.ts)

    private fun routeActivate(payload: JSONObject, activities: List<String>) {
        if ("pairing" in activities) suspendForPairing()
        if (!pairingActivate(activities, payload.obj("pairing"))) protocolActivate(payload)
    }

    private fun route(type: String, payload: JSONObject) {
        when (type) {
            "server/pair-init" -> pairInit(payload)
            "server/pair-auth" -> pairAuth(payload)
            "server/pair-confirm" -> pairConfirm(payload)
            "server/pair-finalize" -> pairFinalize()
            "pair/abort" -> pairAborted(payload.optString("reason", ""))
            "server/hello" -> sendHello()
            "server/time" -> serverTime(payload)
            "stream/start" -> streamStart(payload)
            "stream/clear" -> if (forPlayer(payload)) events.streamClear()
            "stream/end" -> {
                if (!forPlayer(payload)) return
                currentFormat = null
                events.streamEnd()
                sendState()
            }
            "server/command" -> serverCommand(payload)
            "server/state" -> {
                serverState = applyDiff(serverState, payload)
                events.state(serverState, groupState)
            }
            "group/update" -> {
                groupState = applyDiff(groupState, payload)
                events.state(serverState, groupState)
            }
        }
    }

    private fun forPlayer(payload: JSONObject): Boolean {
        val roles = payload.strings("roles") ?: return true
        return "player" in roles
    }

    // MARK: - Pairing (pairing.ts)

    private enum class PairPhase { IDLE, AWAIT_WINDOW, AWAIT_INIT, AWAIT_AUTH, AWAIT_CONFIRM, AWAIT_FINALIZE }

    private var phase = PairPhase.IDLE
    private var method: String? = null
    private var cpace: CPace.Party? = null
    private var nonceB: Bytes? = null
    private var currentSid: Bytes? = null
    private var pendingPSK: Bytes? = null
    private var pairingActivateCount = 0
    private var attemptIndex = 0
    private var pinLength: Int? = null
    private var failures: Int = storage.get(FAILURES_KEY)?.let { Json.parseObject(it)?.int("dynamic_pin") } ?: 0

    /** The operator's gesture: lets one gesture-gated attempt start. Device
     *  state (the client keeps it across connections). */
    var windowOpen = false

    private val minPinLength get() = minOf(SendspinPIN.MAX_DIGITS, maxOf(SendspinPIN.MIN_DIGITS, config.minPinLength))

    private val pairMethods: JSONArray
        get() = JSONArray()
            .put(JSONObject().put("method", "pairing_psk"))
            .put(JSONObject().put("method", "dynamic_pin").put("out_channels", JSONArray().put("display"))
                .put("min_pin_length", minPinLength))

    private val supportedMethods = setOf("pairing_psk", "dynamic_pin")

    val isPinEscalated get() = failures >= ESCALATION_THRESHOLD

    /** The operator's gesture (returns true if it started a waiting attempt). */
    fun openPairingWindow(): Boolean {
        if (phase == PairPhase.AWAIT_WINDOW) {
            startAttempt()
            return true
        }
        windowOpen = true
        return false
    }

    fun cancelPairing() {
        if (phase != PairPhase.IDLE) abort("user_cancelled")
    }

    private fun pairingActivate(activities: List<String>, pairing: JSONObject?): Boolean {
        if ("pairing" !in activities) {
            abandonAttempt("server_cancelled")
            return false
        }
        abandonAttempt("superseded")
        pairingActivateCount++
        attemptIndex = pairingActivateCount
        val m = pairing?.str("method")
        if (m == null) {
            fail("server/activate carried no pairing method")
            return true
        }
        val fitsPSK = (m == "pairing_psk") == (matched?.category == SendspinPskStore.Category.PAIRING)
        if (!fitsPSK || m !in supportedMethods) {
            abort("method_not_supported")
            return true
        }
        method = m
        if (m == "pairing_psk") {
            sendFinalize()
            phase = PairPhase.AWAIT_FINALIZE
            after(ATTEMPT_TIMEOUT, "attempt") { abort("attempt_timeout") }
            events.pairing("started", null)
            return true
        }
        val length = pairing.int("pin_length")
        if (length == null) {
            fail("dynamic_pin without pin_length")
            return true
        }
        if (length < minPinLength || length > SendspinPIN.MAX_DIGITS) {
            abort("pin_length_unacceptable")
            return true
        }
        pinLength = length
        if ((isPinEscalated || length < SHORT_PIN_LENGTH) && !windowOpen) {
            phase = PairPhase.AWAIT_WINDOW
            sendControl(msg("client/pair-pending", JSONObject().put("pairing_index", attemptIndex)))
            events.pairing("pending", null)
            return true
        }
        startAttempt()
        return true
    }

    private fun startAttempt() {
        windowOpen = false
        after(ATTEMPT_TIMEOUT, "attempt") { abort("attempt_timeout") }
        events.pairing("started", null)
        val nonce = randomBytes(SendspinPIN.NONCE_SIZE)
        nonceB = nonce
        phase = PairPhase.AWAIT_INIT
        sendControl(msg("client/pair-init", JSONObject().put("pairing_index", attemptIndex)
            .put("commit_B", Base64URL.encode(SendspinPIN.commit(nonce)))))
    }

    private fun decode(value: Any?, size: Int): Bytes? {
        val raw = (value as? String)?.let { Base64URL.decode(it) } ?: return null
        return if (raw.size == size) raw else null
    }

    private fun pairInit(payload: JSONObject) {
        if (phase == PairPhase.IDLE) return
        val nonceA = decode(payload.opt("nonce_A"), SendspinPIN.NONCE_SIZE)
        val nb = nonceB
        val length = pinLength
        if (phase != PairPhase.AWAIT_INIT || method != "dynamic_pin" || nonceA == null || nb == null || length == null) return pairingFail()
        val pin = SendspinPIN.derive(handshakeHash, nonceA, nb, length)
        val s = sid(handshakeHash, attemptIndex)
        currentSid = s
        cpace = try {
            CPace.Party(CPace.Party.Role.RESPONDER, utf8(pin), s, utf8("server"), utf8("client"))
        } catch (e: Exception) {
            return pairingFail()
        }
        phase = PairPhase.AWAIT_AUTH
        events.pin(pin)
    }

    private fun pairAuth(payload: JSONObject) {
        if (phase == PairPhase.IDLE) return
        val party = cpace
        val share = decode(payload.opt("pake_msg_1"), CPace.SHARE_SIZE)
        if (phase != PairPhase.AWAIT_AUTH || party == null || share == null) return pairingFail()
        sendControl(msg("client/pair-auth", JSONObject().put("pake_msg_2", Base64URL.encode(party.publicShare))))
        try {
            party.derive(share)
        } catch (e: Exception) {
            return pairingFail()
        }
        phase = PairPhase.AWAIT_CONFIRM
    }

    private fun pairConfirm(payload: JSONObject) {
        if (phase == PairPhase.IDLE) return
        val party = cpace
        val serverKc = decode(payload.opt("server_kc"), CPace.TAG_SIZE)
        if (phase != PairPhase.AWAIT_CONFIRM || party == null || serverKc == null) return pairingFail()
        val verified: Boolean
        val tag: Bytes
        try {
            verified = party.verify(serverKc)
            tag = party.tag()
        } catch (e: Exception) {
            return pairingFail()
        }
        if (!verified) {
            if (method == "dynamic_pin") saveFailures(failures + 1)
            return abort("pin_mismatch")
        }
        if (method == "dynamic_pin") saveFailures(0)
        val confirm = JSONObject().put("client_kc", Base64URL.encode(tag))
        val nb = nonceB
        if (method == "dynamic_pin" && nb != null) confirm.put("nonce_B", Base64URL.encode(nb))
        sendControl(msg("client/pair-confirm", confirm))
        phase = PairPhase.AWAIT_FINALIZE
        sendFinalize()
    }

    private fun pairFinalize() {
        val psk = pendingPSK ?: return
        psks.addLongTerm(psk, serverID)
        clearAttempt()
        events.pairing("finalized", null)
    }

    private fun pairAborted(reason: String) {
        if (phase == PairPhase.IDLE && pendingPSK == null) return
        clearAttempt()
        events.pairing("aborted", reason)
    }

    /** Mints the long-term PSK and sends client/pair-finalize: wrapped
     *  under the CPace key after a PIN, in the clear (inside Noise) after
     *  the Pairing PSK. */
    private fun sendFinalize() {
        val psk = randomBytes(32)
        pendingPSK = psk
        val isk = cpace?.isk
        val s = currentSid
        if (isk != null && s != null) {
            val kWrap = sha256(utf8("sendspin-pair-psk-wrap-v1") + s + isk)
            val wrapped = try {
                config.suite.encrypt(kWrap, 0, ByteArray(0), psk)
            } catch (e: Exception) {
                return pairingFail()
            }
            sendControl(msg("client/pair-finalize", JSONObject().put("wrapped_psk", Base64URL.encode(wrapped))))
        } else {
            sendControl(msg("client/pair-finalize", JSONObject().put("long_term_psk", Base64URL.encode(psk))))
        }
    }

    private fun abort(reason: String) {
        clearAttempt()
        sendControl(msg("pair/abort", JSONObject().put("reason", reason)))
        events.pairing("aborted", reason)
        if (reason == "concurrent_attempt") close()
    }

    private fun pairingFail() {
        clearAttempt()
        close()
    }

    private fun abandonAttempt(detail: String) {
        if (phase == PairPhase.IDLE && pendingPSK == null) return
        clearAttempt()
        events.pairing("aborted", detail)
    }

    private fun clearAttempt() {
        cancelTimer("attempt")
        if (method == "dynamic_pin" || method == "static_pin") events.pin(null)
        pendingPSK = null
        phase = PairPhase.IDLE
        method = null
        cpace = null
        currentSid = null
        nonceB = null
        pinLength = null
    }

    private fun resetPairing() {
        clearAttempt()
        pairingActivateCount = 0
        attemptIndex = 0
    }

    private fun saveFailures(n: Int) {
        failures = n
        storage.set(FAILURES_KEY, json(JSONObject().put("dynamic_pin", n)))
    }

    // MARK: - Protocol (protocol-handler.ts)

    private var activated = false
    var activeRoles: Set<String>? = null
        private set
    private var pairingSuspended = false
    private var lastSentPlayer: Map<String, Int>? = null
    var currentFormat: JSONObject? = null
        private set
    var volume = 100
        private set
    var muted = false
        private set
    private var serverState = JSONObject()
    private var groupState = JSONObject()

    private fun sendHello() {
        val formats = config.formats
        val hello = JSONObject()
            .put("name", config.clientName)
            .put("supported_roles", JSONArray().put("player@v1").put("controller@v1").put("metadata@v1"))
            .put("trust_level", trustLevel)
            .put("supported_pair_methods", pairMethods)
            .put("unpaired_access", JSONObject().put("enabled", config.unpairedAccess))
            .put("device_info", JSONObject().put("product_name", config.productName).put("manufacturer", config.manufacturer)
                .put("software_version", config.softwareVersion))
            .put("player@v1_support", JSONObject()
                .put("supported_formats", Json.array(formats.map { it.json }))
                .put("buffer_capacity", SendspinFormat.bufferCapacity(formats))
                .put("supported_commands", JSONArray().put("volume").put("mute")))
        lastSentPlayer = null
        sendControl(msg("client/hello", hello))
    }

    private fun protocolActivate(payload: JSONObject) {
        pairingSuspended = false
        var rolesChanged = false
        payload.strings("active_roles")?.let { roles ->
            val next = roles.toSet()
            rolesChanged = activeRoles != next
            activeRoles = next
        }
        events.activated(activeRoles?.toList() ?: emptyList())
        if (activated) {
            if (rolesChanged) sendState()
            return
        }
        activated = true
        sendState()
        startTimeSync()
        scheduleState()
    }

    private fun scheduleState() {
        after(STATE_INTERVAL, "state") {
            sendState()
            scheduleState()
        }
    }

    private fun suspendForPairing() {
        pairingSuspended = true
        activated = false
        activeRoles = emptySet()
        stopTimeSync()
        cancelTimer("state")
    }

    private fun resetActivation(preserveRoles: Boolean) {
        pairingSuspended = false
        activated = false
        if (!preserveRoles) activeRoles = null
        stopTimeSync()
        cancelTimer("state")
    }

    private fun streamStart(payload: JSONObject) {
        val format = payload.obj("player") ?: return
        val isUpdate = currentFormat != null
        currentFormat = format
        events.streamStart(format, isUpdate)
    }

    private fun serverCommand(payload: JSONObject) {
        val command = payload.obj("player") ?: return
        when (command.str("command")) {
            "volume" -> command.int("volume")?.let {
                volume = it.coerceIn(0, 100)
                events.volume(volume, muted)
            }
            "mute" -> (command.opt("mute") as? Boolean)?.let {
                muted = it
                events.volume(volume, muted)
            }
            "set_static_delay" -> command.double("static_delay_ms")?.takeIf { !it.isNaN() && !it.isInfinite() }?.let {
                config.staticDelayMs = Math.round(it).toInt().coerceIn(0, 5000)
                events.staticDelay(config.staticDelayMs)
            }
        }
        scheduleState()
        sendState()
    }

    /** Local volume or mute change (reported to the server). */
    fun setVolume(volume: Int, muted: Boolean) {
        this.volume = volume.coerceIn(0, 100)
        this.muted = muted
        sendState()
    }

    /** client/state: full the first time after hello, deltas after. */
    fun sendState() {
        if (pairingSuspended || !isReady) return
        val payload = JSONObject().put("available", true)
        val roles = activeRoles
        if (roles == null || "player@v1" in roles) {
            val current = linkedMapOf("volume" to volume, "muted" to (if (muted) 1 else 0), "static_delay_ms" to config.staticDelayMs,
                "required_lead_time_ms" to config.requiredLeadTimeMs, "min_buffer_ms" to config.minBufferMs)
            val player = JSONObject()
            for ((key, value) in current) {
                if (lastSentPlayer?.get(key) == value) continue
                if (key == "muted") player.put(key, value != 0) else player.put(key, value)
            }
            if (lastSentPlayer == null) player.put("supported_commands", JSONArray().put("set_static_delay"))
            payload.put("player", player)
            lastSentPlayer = current
        }
        sendControl(msg("client/state", payload))
    }

    /** A controller command (play, pause, next, previous, volume, ...). */
    fun sendCommand(command: String, params: JSONObject = JSONObject()) {
        if (pairingSuspended || activeRoles?.contains("controller@v1") != true) return
        val controller = JSONObject(params.toString()).put("command", command)
        sendControl(msg("client/command", JSONObject().put("controller", controller)))
    }

    // MARK: - Clock sync (time-sync-manager.ts)

    private class Sample(val measurement: Double, val maxError: Double, val t4: Double, val rtt: Double)

    private var burstActive = false
    private var burstSent = 0
    private var inFlight: Long? = null
    private var samples = ArrayList<Sample>()

    private fun startTimeSync() {
        stopTimeSync()
        startBurst()
        scheduleBurst()
    }

    private fun scheduleBurst() {
        after(BURST_INTERVAL, "burst") {
            startBurst()
            scheduleBurst()
        }
    }

    private fun stopTimeSync() {
        cancelTimer("burst")
        abortBurst()
    }

    private fun startBurst() {
        if (burstActive) return
        burstActive = true
        burstSent = 0
        samples = ArrayList()
        inFlight = null
        sendProbe()
    }

    private fun sendProbe() {
        if (!burstActive || inFlight != null) return
        if (burstSent >= BURST_SIZE) return finishBurst()
        val t1 = SendspinClock.nowMicros()
        sendControl(msg("client/time", JSONObject().put("client_transmitted", t1)))
        burstSent++
        inFlight = t1
        after(PROBE_TIMEOUT, "probe") {
            if (burstActive && inFlight == t1) abortBurst()
        }
    }

    private fun abortBurst() {
        cancelTimer("probe")
        burstActive = false
        burstSent = 0
        inFlight = null
        samples = ArrayList()
    }

    private fun finishBurst() {
        cancelTimer("probe")
        // The median offset of the three lowest-delay samples.
        val best = samples.sortedBy { it.rtt }.take(3).sortedBy { it.measurement }
        if (best.isNotEmpty()) {
            val pick = best[best.size / 2]
            timeFilter.update(pick.measurement, pick.maxError, pick.t4)
        }
        burstActive = false
        burstSent = 0
        inFlight = null
        samples = ArrayList()
    }

    private fun serverTime(payload: JSONObject) {
        val expected = inFlight
        val t1 = payload.long("client_transmitted")
        val t2 = payload.double("server_received")
        val t3 = payload.double("server_transmitted")
        if (!burstActive || expected == null || t1 == null || t1 != expected || t2 == null || t3 == null) return
        val t4 = SendspinClock.nowMicros().toDouble()
        val t1d = t1.toDouble()
        val measurement = ((t2 - t1d) + (t3 - t4)) / 2
        val rtt = maxOf(0.0, (t4 - t1d) - (t3 - t2))
        samples.add(Sample(measurement, maxOf(1000.0, rtt / 2), t4, rtt))
        cancelTimer("probe")
        inFlight = null
        if (burstSent >= BURST_SIZE) finishBurst() else sendProbe()
    }
}
