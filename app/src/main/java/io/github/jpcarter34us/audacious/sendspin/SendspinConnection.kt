package io.github.jpcarter34us.audacious.sendspin

import android.util.Log
import io.github.jpcarter34us.audacious.engine.LiveFeed
import io.github.jpcarter34us.audacious.util.Http
import io.github.jpcarter34us.audacious.util.Json
import io.github.jpcarter34us.audacious.util.int
import io.github.jpcarter34us.audacious.util.str
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * One connection to a Sendspin server: the WebSocket, either through Music
 * Assistant's /sendspin proxy (which wants `{"type": "auth", "token",
 * "client_id"}` first and answers `auth_ok`, as ElectronPlayer's
 * sendspin-host.ts does) or straight to a server's own Sendspin port; the
 * session on top; and the audio turned into decoded chunks stamped with
 * when to be heard on SendspinClock. Callbacks run on `queue`.
 */
class SendspinConnection(
    private val url: String,
    private val token: String?,
    private val config: SendspinConfig,
    private val identity: SendspinIdentity,
    private val psks: SendspinPskStore,
    private val storage: SendspinStorage,
) {
    open class Handlers {
        open fun connected() {}
        /** The connection is gone (null: closed normally). */
        open fun closed(error: String?) {}
        open fun handshake(category: SendspinPskStore.Category) {}
        open fun streamStart(isUpdate: Boolean) {}
        open fun streamClear() {}
        open fun streamEnd() {}
        open fun chunk(chunk: LiveFeed.Chunk) {}
        open fun volume(volume: Int, muted: Boolean) {}
        open fun staticDelay(ms: Int) {}
        open fun state(server: JSONObject, group: JSONObject) {}
        open fun activated(roles: List<String>) {}
        open fun pin(pin: String?) {}
        open fun pairing(event: String, detail: String?) {}
    }

    /** One thread; work sent after the connection is gone is dropped. */
    val queue = ScheduledThreadPoolExecutor(1, { r -> Thread(r, "sendspin").apply { priority = Thread.MAX_PRIORITY - 1 } },
        ThreadPoolExecutor.DiscardPolicy())
    var handlers = Handlers()
    private var socket: WebSocket? = null
    var session: SendspinSession? = null
        private set
    private var finished = false
    private var signedIn = false
    private var authTimeout: ScheduledFuture<*>? = null

    /** The pairing gesture outlives connections (sendspin-js keeps it as device state). */
    var pairingWindowOpen = false

    // MARK: Socket

    private inner class Socket(private val ws: WebSocket) : SendspinSocket {
        override fun sendText(text: String) {
            ws.send(text)
        }

        override fun sendBinary(data: Bytes) {
            ws.send(ByteString.of(*data))
        }

        override fun close() {
            ws.close(1000, null)
        }
    }

    fun connect() {
        queue.execute {
            val ws = try {
                Http.streaming.newWebSocket(Http.request(url), Listener())
            } catch (e: Exception) {
                finish("Invalid address ($url)")
                return@execute
            }
            socket = ws
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            queue.execute {
                if (finished) return@execute
                if (token != null) signIn(webSocket, token) else startSession(webSocket)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            queue.execute {
                if (finished) return@execute
                val s = session
                if (s == null) {
                    if (token != null && !signedIn && Json.parseObject(text)?.str("type") == "auth_ok") {
                        signedIn = true
                        authTimeout?.cancel(false)
                        startSession(webSocket)
                    }
                    return@execute
                }
                s.receiveText(text)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val data = bytes.toByteArray()
            queue.execute {
                if (finished) return@execute
                session?.receiveBinary(data)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            queue.execute { finish(describeClose(code, reason)) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            queue.execute { finish(describeClose(code, reason)) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            queue.execute {
                val ready = session?.isReady == true
                finish(if (ready) null else "Cannot reach ${hostOf(url)} (${t.message ?: t.javaClass.simpleName})")
            }
        }
    }

    private fun hostOf(u: String) = try {
        java.net.URI(u).host ?: u
    } catch (_: Exception) {
        u
    }

    private fun describeClose(code: Int, reason: String): String? = when {
        code == 4001 -> "Music Assistant refused the sign-in (${reason.ifEmpty { "invalid token" }})"
        session?.isReady == true && (code == 1000 || code == 1001) -> null
        code == 1000 || code == 1001 -> "The server closed the connection"
        else -> "The server closed the connection ($code)"
    }

    /** Music Assistant's proxy: sign in, then it relays Sendspin unchanged. */
    private fun signIn(ws: WebSocket, token: String) {
        ws.send(JSONObject().put("type", "auth").put("token", token).put("client_id", identity.clientID).toString())
        authTimeout = queue.schedule({ finish("Music Assistant did not answer the sign-in") }, 15, TimeUnit.SECONDS)
    }

    private fun startSession(ws: WebSocket) {
        val s = SendspinSession(config, Socket(ws), identity, psks, storage, queue)
        s.windowOpen = pairingWindowOpen
        session = s
        wire(s)
        handlers.connected()
        s.start()
    }

    /** Says goodbye and closes. */
    fun disconnect() {
        queue.execute {
            session?.goodbye("shutdown")
            // Let the goodbye go out before the close frame.
            queue.schedule({ finish(null) }, 100, TimeUnit.MILLISECONDS)
        }
    }

    private fun finish(error: String?) {
        if (finished) return
        finished = true
        authTimeout?.cancel(false)
        pairingWindowOpen = session?.windowOpen ?: pairingWindowOpen
        session?.socketClosed()
        socket?.close(1000, null)
        decoder?.release()
        decoder = null
        handlers.closed(error)
        queue.schedule({ queue.shutdown() }, 1, TimeUnit.SECONDS)
    }

    // MARK: Session events

    private var decoder: SendspinAudioDecoder? = null
    /** Chunks that arrived before the clock was synchronized. */
    private val early = ArrayList<Pair<Long, ByteArray>>()

    private fun wire(s: SendspinSession) {
        s.events = object : SendspinSession.Events() {
            override fun handshake(category: SendspinPskStore.Category) = handlers.handshake(category)

            override fun streamStart(format: JSONObject, isUpdate: Boolean) {
                val d = decoder
                if (!isUpdate || d == null || d.codec != format.str("codec") || d.sampleRate != format.int("sample_rate") ||
                    d.channels != format.int("channels") || format.has("codec_header")
                ) {
                    d?.release()
                    decoder = SendspinAudioDecoder.create(format)
                    if (decoder == null) Log.e(SENDSPIN_TAG, "sendspin: unsupported format $format")
                }
                if (!isUpdate) early.clear()
                handlers.streamStart(isUpdate)
            }

            override fun streamClear() {
                early.clear()
                handlers.streamClear()
            }

            override fun streamEnd() {
                early.clear()
                decoder?.release()
                decoder = null
                handlers.streamEnd()
            }

            override fun audio(serverTime: Long, payload: Bytes, offset: Int) = audio(serverTime, payload, offset, s)
            override fun volume(volume: Int, muted: Boolean) = handlers.volume(volume, muted)
            override fun staticDelay(ms: Int) = handlers.staticDelay(ms)
            override fun state(server: JSONObject, group: JSONObject) = handlers.state(server, group)
            override fun activated(roles: List<String>) = handlers.activated(roles)
            override fun pin(pin: String?) = handlers.pin(pin)
            override fun pairing(event: String, detail: String?) = handlers.pairing(event, detail)
        }
    }

    private fun audio(serverTime: Long, payload: Bytes, offset: Int, s: SendspinSession) {
        if (decoder == null) return
        if (!s.timeFilter.isSynchronized) {
            early.add(serverTime to payload.copyOfRange(offset, payload.size))
            if (early.size > 2000) early.subList(0, early.size - 2000).clear()
            return
        }
        if (early.isNotEmpty()) {
            val pending = ArrayList(early)
            early.clear()
            for ((t, p) in pending) emit(t, p, 0, s)
        }
        emit(serverTime, payload, offset, s)
    }

    private fun emit(serverTime: Long, payload: Bytes, offset: Int, s: SendspinSession) {
        val d = decoder ?: return
        val samples = d.decode(payload, offset)
        if (samples.size < d.channels) return
        val playAt = s.timeFilter.clientTime(serverTime.toDouble()).toLong() - s.config.staticDelayMs.toLong() * 1000
        handlers.chunk(LiveFeed.Chunk(samples, d.channels, d.sampleRate, playAt))
    }

    // MARK: Commands (any thread)

    fun sendCommand(command: String, params: JSONObject = JSONObject()) {
        if (!queue.isShutdown) queue.execute { session?.sendCommand(command, params) }
    }

    fun setVolume(volume: Int, muted: Boolean) {
        if (!queue.isShutdown) queue.execute { session?.setVolume(volume, muted) }
    }

    /** The operator's gesture: admits one pairing attempt within 5 minutes. */
    fun openPairingWindow() {
        if (queue.isShutdown) return
        queue.execute {
            val s = session
            if (s != null) s.openPairingWindow() else pairingWindowOpen = true
            queue.schedule({
                session?.windowOpen = false
                pairingWindowOpen = false
            }, 300, TimeUnit.SECONDS)
        }
    }

    fun cancelPairing() {
        if (!queue.isShutdown) queue.execute { session?.cancelPairing() }
    }
}
