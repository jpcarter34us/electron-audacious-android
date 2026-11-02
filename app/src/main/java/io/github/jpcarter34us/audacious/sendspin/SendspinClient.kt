package io.github.jpcarter34us.audacious.sendspin

import io.github.jpcarter34us.audacious.engine.LiveFeed
import io.github.jpcarter34us.audacious.library.MusicAssistantClient
import io.github.jpcarter34us.audacious.library.MusicAssistantURL
import io.github.jpcarter34us.audacious.player.Player
import io.github.jpcarter34us.audacious.util.Main
import io.github.jpcarter34us.audacious.util.Prefs
import io.github.jpcarter34us.audacious.util.Secrets
import io.github.jpcarter34us.audacious.util.Signal
import io.github.jpcarter34us.audacious.util.double
import io.github.jpcarter34us.audacious.util.obj
import io.github.jpcarter34us.audacious.util.str
import io.github.jpcarter34us.audacious.util.strings
import okhttp3.HttpUrl
import org.json.JSONObject

/** The Sendspin identity and PSKs live in the secret store. */
class SecretsSendspinStorage : SendspinStorage {
    override fun get(key: String) = Secrets.get(key)
    override fun set(key: String, value: String?) = Secrets.set(key, value)
}

/**
 * This device as a Music Assistant speaker (a Sendspin player), ported
 * from the iOS port's SendspinClient.swift (the native counterpart of
 * ElectronPlayer's sendspin-host.ts in "play through the Audacious engine"
 * mode): the server's audio plays through the Player's engine, so effects,
 * equalizer and visualizations apply, and the transport controls (and the
 * notification) control the Music Assistant queue while it plays.
 *
 * Two routes: Music Assistant's /sendspin proxy, encapsulated in the
 * signed-in server's WebSocket (`Route.MUSIC_ASSISTANT`), or a Sendspin
 * server's own port, 8927 by default (`Route.DIRECT`).
 */
class SendspinClient(private val player: Player, private val musicAssistant: MusicAssistantClient?) {
    enum class Route(val title: String) {
        MUSIC_ASSISTANT("Through Music Assistant (WebSocket proxy)"),
        DIRECT("Sendspin port (direct)"),
    }

    enum class Connection { OFF, CONNECTING, CONNECTED, ERROR }

    companion object {
        val codecs = listOf("pcm", "flac", "opus")

        /** The WebSocket URL for the settings. */
        fun url(route: Route, maServer: String, directAddress: String): String? = when (route) {
            Route.MUSIC_ASSISTANT -> MusicAssistantURL.ws(maServer)?.removeSuffix("/ws")?.plus("/sendspin")
            Route.DIRECT -> {
                var text = directAddress.trim()
                if (text.isEmpty()) null else {
                    if (!Regex("^[a-zA-Z]+://").containsMatchIn(text)) text = "ws://$text"
                    val secure = text.startsWith("wss://", true) || text.startsWith("https://", true)
                    val http = text.replaceFirst(Regex("^(wss?|https?)://", RegexOption.IGNORE_CASE), if (secure) "https://" else "http://")
                    HttpUrl.parse(http)?.let { u ->
                        val explicitPort = Regex("^[a-zA-Z]+://(\\[[^]]*]|[^/:]*):(\\d+)").find(http) != null
                        val port = if (explicitPort) ":${u.port()}" else if (!secure) ":8927" else ""
                        val path = u.encodedPath().takeIf { it.isNotEmpty() && it != "/" } ?: "/sendspin"
                        val host = if (u.host().contains(":")) "[${u.host()}]" else u.host()
                        "${if (secure) "wss" else "ws"}://$host$port$path"
                    }
                }
            }
        }
    }

    val changed = Signal()

    // MARK: Settings

    var enabled = Prefs.bool("sendspin.enabled")
        set(v) {
            field = v
            Prefs.set("sendspin.enabled", v)
            restart()
        }
    var route: Route = try {
        Route.valueOf(Prefs.string("sendspin.route", Route.MUSIC_ASSISTANT.name))
    } catch (_: Exception) {
        Route.MUSIC_ASSISTANT
    }
        set(v) {
            field = v
            Prefs.set("sendspin.route", v.name)
            restart()
        }
    /** For the direct route: "host", "host:port" or a ws:// URL. */
    var directAddress = Prefs.string("sendspin.address")
        set(v) {
            field = v
            Prefs.set("sendspin.address", v)
        }
    /** The speaker name Music Assistant shows. */
    var name = Prefs.string("sendspin.name", android.os.Build.MODEL ?: "Audacious")
        set(v) {
            field = v
            Prefs.set("sendspin.name", v)
        }
    /** "pcm", "flac" or "opus": the format asked for first (PCM is always offered). */
    var codec = Prefs.string("sendspin.codec", "pcm")
        set(v) {
            field = v
            Prefs.set("sendspin.codec", v)
        }
    /** Refuse to play for servers this device has not been paired with. */
    var requirePairing = Prefs.bool("sendspin.requirePairing")
        set(v) {
            field = v
            Prefs.set("sendspin.requirePairing", v)
        }

    // MARK: State

    var connection = Connection.OFF
        private set
    var error: String? = null
        private set
    /** The PIN to type into Music Assistant while it pairs with this device. */
    var pairingCode: String? = null
        private set
    var pairingMessage: String? = null
        private set
    var paired = false
        private set
    var volume = 100
        private set
    var muted = false
        private set
    var supportedCommands: List<String> = emptyList()
        private set
    var groupName: String? = null
        private set

    private val storage = SecretsSendspinStorage()
    private val identity = SendspinIdentity.loadOrCreate(storage)
    private val psks = SendspinPskStore(storage)
    val clientID get() = identity.clientID
    /** What to enter in a server's "add player by pairing code" field. */
    val pairingToken get() = sendspinPairingToken(identity.keyPair.publicKey, psks.pairingPSK())
    val pairedServers get() = psks.pairedServers

    private var current: SendspinConnection? = null
    private var feed: LiveFeed? = null
    private var reconnectDelay = 1000L
    private var staticDelayMs = Prefs.int("sendspin.staticDelay")
    private var serverState = JSONObject()
    private var groupState = JSONObject()
    private var streaming = false
    private var progressKey = ""
    private var progressAt = System.currentTimeMillis()
    private val reconnect = Runnable { if (enabled && current == null) connect() }
    private var feedBox: FeedBox? = null

    init {
        psks.pairingPSK()
        player.liveController = { command(it) }
        if (enabled) restart()
    }

    // MARK: Connection

    /** Reconnects with the current settings (or stops when disabled). */
    fun restart() {
        Main.cancel(reconnect)
        stopConnection()
        reconnectDelay = 1000
        if (!enabled) {
            connection = Connection.OFF
            error = null
            changed.emit()
            return
        }
        connect()
    }

    private fun connect() {
        val maServer = musicAssistant?.server ?: Prefs.string("ma.server")
        val u = url(route, maServer, directAddress)
        if (u == null) {
            connection = Connection.ERROR
            error = if (route == Route.MUSIC_ASSISTANT) "Sign in to a Music Assistant server first" else "Enter the Sendspin server's address"
            changed.emit()
            return
        }
        var token: String? = null
        if (route == Route.MUSIC_ASSISTANT) {
            token = Secrets.get("ma.token")
            if (token == null) {
                connection = Connection.ERROR
                error = "Sign in to Music Assistant first"
                changed.emit()
                return
            }
        }
        val formats = SendspinFormat.defaults(if (codec == "pcm") listOf("pcm") else listOf(codec, "pcm"))
        val config = SendspinConfig(clientName = name.ifEmpty { "Audacious" }, unpairedAccess = !requirePairing, formats = formats,
            staticDelayMs = staticDelayMs, softwareVersion = "1.0")
        val c = SendspinConnection(u, token, config, identity, psks, storage)
        current = c
        connection = Connection.CONNECTING
        error = null
        c.handlers = handlers(c)
        c.connect()
        changed.emit()
    }

    private fun stopConnection() {
        current?.disconnect()
        current = null
        endStream()
        paired = false
        pairingCode = null
        supportedCommands = emptyList()
        serverState = JSONObject()
        groupState = JSONObject()
    }

    private fun scheduleReconnect() {
        if (!enabled) return
        Main.delay(reconnectDelay, reconnect)
        reconnectDelay = minOf(reconnectDelay * 2, 15_000)
    }

    /** Callbacks from the connection's thread, hopped to the main thread
     *  (audio chunks go straight to the feed, which is thread-safe). */
    private fun handlers(c: SendspinConnection): SendspinConnection.Handlers {
        fun main(work: () -> Unit) = Main.post { if (current === c) work() }
        val box = FeedBox()
        feedBox = box
        return object : SendspinConnection.Handlers() {
            override fun connected() = main {
                connection = Connection.CONNECTING
                changed.emit()
            }

            override fun handshake(category: SendspinPskStore.Category) = main {
                paired = category == SendspinPskStore.Category.LONG_TERM
                connection = Connection.CONNECTED
                reconnectDelay = 1000
                changed.emit()
            }

            override fun closed(error: String?) = main {
                current = null
                endStream()
                connection = if (error == null) Connection.CONNECTING else Connection.ERROR
                this@SendspinClient.error = error
                pairingCode = null
                changed.emit()
                scheduleReconnect()
            }

            override fun activated(roles: List<String>) = main {
                error = null
                changed.emit()
            }

            override fun streamStart(isUpdate: Boolean) {
                // A feed the Player gave up (it played something else) is
                // replaced, so a new stream from the server takes over again.
                if (box.feed?.isStopped != false) box.feed = LiveFeed()
                val f = box.feed
                main { startStream(f) }
            }

            override fun streamClear() {
                box.feed?.clear()
            }

            override fun streamEnd() {
                box.feed?.clear()
                main { pauseStream() }
            }

            override fun chunk(chunk: LiveFeed.Chunk) {
                box.feed?.push(chunk)
            }

            override fun volume(volume: Int, muted: Boolean) = main { applyVolume(volume, muted) }

            override fun staticDelay(ms: Int) = main {
                staticDelayMs = ms
                Prefs.set("sendspin.staticDelay", ms)
            }

            override fun state(server: JSONObject, group: JSONObject) = main {
                serverState = server
                groupState = group
                publishState()
            }

            override fun pin(pin: String?) = main {
                pairingCode = pin
                changed.emit()
            }

            override fun pairing(event: String, detail: String?) = main {
                pairingMessage = when (event) {
                    "pending" -> "Music Assistant wants to pair. Tap Allow Pairing to continue."
                    "started" -> "Pairing…"
                    "finalized" -> "Paired with Music Assistant."
                    "aborted" -> detail?.let { "Pairing stopped (${it.replace('_', ' ')})." }
                    else -> pairingMessage
                }
                changed.emit()
            }
        }
    }

    /** Holds the current feed for the connection's thread. */
    private class FeedBox {
        @Volatile var feed: LiveFeed? = null
    }

    // MARK: Stream

    private fun startStream(f: LiveFeed?) {
        if (f == null || f.isStopped) return
        streaming = true
        feed = f
        player.beginLive(f, liveInfo())
        applyGain()
    }

    private fun pauseStream() {
        streaming = false
        player.pauseLive()
        publishState()
    }

    private fun endStream() {
        streaming = false
        feedBox?.feed = null
        feed?.stop()
        feed = null
        player.endLive()
    }

    private fun applyVolume(volume: Int, muted: Boolean) {
        this.volume = volume
        this.muted = muted
        applyGain()
        changed.emit()
    }

    private fun applyGain() {
        if (player.isLive) player.setLiveGain(if (muted) 0f else volume / 100f)
    }

    /** Volume of this speaker as Music Assistant sees it (0...100). */
    fun setVolume(v: Int) {
        volume = v.coerceIn(0, 100)
        applyGain()
        current?.setVolume(volume, muted)
        changed.emit()
    }

    fun setMuted(m: Boolean) {
        muted = m
        applyGain()
        current?.setVolume(volume, muted)
        changed.emit()
    }

    /** A controller command for the group this speaker is in. */
    fun command(command: String) {
        current?.sendCommand(command)
    }

    // MARK: Pairing

    /** The operator gesture Music Assistant may ask for before pairing. */
    fun allowPairing() {
        current?.openPairingWindow()
        pairingMessage = "Pairing allowed for 5 minutes."
        changed.emit()
    }

    fun cancelPairing() {
        current?.cancelPairing()
    }

    /** Forgets every pairing, and makes a new pairing code. */
    fun forgetPairings() {
        psks.removeAllLongTerm()
        psks.rotatePairingPSK()
        restart()
    }

    // MARK: Metadata

    private fun publishState() {
        supportedCommands = serverState.obj("controller")?.strings("supported_commands") ?: emptyList()
        groupName = groupState.str("group_name")
        if (player.isLive) player.updateLive(liveInfo())
        changed.emit()
    }

    private fun liveInfo(): Player.LiveInfo {
        val meta = serverState.obj("metadata") ?: JSONObject()
        val progress = meta.obj("progress")
        // group/update may not have come yet: playing unless it says otherwise.
        val playing = streaming && (groupState.str("playback_state")?.let { it == "playing" } ?: true)
        val position = progress?.double("track_progress")?.let { it / 1000 } ?: 0.0
        val duration = progress?.double("track_duration")?.let { it / 1000 }
        // Some servers send the speed x1000 (spec integer), some as a ratio.
        val rawSpeed = progress?.double("playback_speed") ?: 1.0
        val speed = if (rawSpeed > 10) rawSpeed / 1000 else rawSpeed
        val key = "$position|${duration ?: 0}|$speed|${meta.str("timestamp") ?: ""}|$playing"
        if (key != progressKey) {
            progressKey = key
            progressAt = System.currentTimeMillis()
        }
        return Player.LiveInfo(
            source = groupName?.let { "Music Assistant · $it" } ?: "Music Assistant",
            title = meta.str("title"), artist = meta.str("artist"), album = meta.str("album"), artworkURL = meta.str("artwork_url"),
            progress = position, progressAt = progressAt, speed = if (playing) speed else 0.0,
            duration = duration?.takeIf { it > 0 }, playing = playing,
        )
    }
}
