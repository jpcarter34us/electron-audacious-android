package io.github.jpcarter34us.audacious.library

import io.github.jpcarter34us.audacious.player.PlayOption
import io.github.jpcarter34us.audacious.util.Bg
import io.github.jpcarter34us.audacious.util.Http
import io.github.jpcarter34us.audacious.util.Json
import io.github.jpcarter34us.audacious.util.Main
import io.github.jpcarter34us.audacious.util.Prefs
import io.github.jpcarter34us.audacious.util.Secrets
import io.github.jpcarter34us.audacious.util.Signal
import io.github.jpcarter34us.audacious.util.UserError
import io.github.jpcarter34us.audacious.util.bool
import io.github.jpcarter34us.audacious.util.double
import io.github.jpcarter34us.audacious.util.int
import io.github.jpcarter34us.audacious.util.obj
import io.github.jpcarter34us.audacious.util.objects
import io.github.jpcarter34us.audacious.util.str
import io.github.jpcarter34us.audacious.util.text
import okhttp3.HttpUrl
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Music Assistant, ported from ElectronPlayer's ma-client.ts and
 * music-assistant.ts by way of the iOS port's MusicAssistant.swift.
 *
 * The server's WebSocket API (ws://host:8095/ws): the server sends its
 * ServerInfo first; schema 28 and later require an `auth` command with a
 * token; commands are {message_id, command, args} and replies carry the
 * same message_id with `result` (possibly streamed with `partial: true`)
 * or `error_code` + `details`; everything else is an event.
 *
 * Sign-in follows the Music Assistant mobile app (Apache-2.0): username and
 * password via auth/login, or a provider such as Home Assistant via
 * auth/authorization_url in the browser, which redirects back to a loopback
 * address this app listens on; or a pasted long-lived token.
 */
object MusicAssistantURL {
    /** ws://host:port/ws from what the user typed ("host", "http://host:8095", ...). */
    fun ws(server: String): String? {
        var text = server.trim()
        if (text.isEmpty()) return null
        if (!Regex("^[a-zA-Z]+://").containsMatchIn(text)) text = "http://$text"
        val secure = text.startsWith("https://", true) || text.startsWith("wss://", true)
        val http = text.replaceFirst(Regex("^wss?://", RegexOption.IGNORE_CASE), if (secure) "https://" else "http://")
        val url = HttpUrl.parse(http) ?: return null
        val explicitPort = Regex("^[a-zA-Z]+://(\\[[^]]*]|[^/:]*):(\\d+)").find(http) != null
        val port = if (explicitPort) ":${url.port()}" else if (!secure) ":8095" else ""
        var path = url.encodedPath()
        while (path.endsWith("/")) path = path.dropLast(1)
        if (path.endsWith("/ws")) path = path.dropLast(3)
        val host = if (url.host().contains(":")) "[${url.host()}]" else url.host()
        return "${if (secure) "wss" else "ws"}://$host$port$path/ws"
    }

    /** http(s)://host:port for image URLs. */
    fun httpBase(server: String): String? = ws(server)?.let {
        it.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").removeSuffix("/ws")
    }
}

class MAError(message: String, val authFailed: Boolean = false) : Exception(message)

/** One WebSocket connection to the server. send() blocks (call it off the main thread). */
class MAConnection {
    class ServerInfo(val serverID: String, val version: String, val schema: Int, val name: String?)

    private class Pending(val latch: CountDownLatch = CountDownLatch(1)) {
        var partial: JSONArray? = null
        var result: Any? = null
        var error: Exception? = null
    }

    @Volatile private var socket: WebSocket? = null
    private val pending = HashMap<String, Pending>()
    var onEvent: (String, Any?) -> Unit = { _, _ -> }
    var onClose: (String) -> Unit = {}

    /** Opens the socket and reads the server info. Blocks. */
    fun open(url: String): ServerInfo {
        close()
        val latch = CountDownLatch(1)
        var info: ServerInfo? = null
        var failure: String? = null
        lateinit var ws: WebSocket
        ws = Http.streaming.newWebSocket(Http.request(url), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = Json.parseObject(text)
                if (info == null && latch.count > 0) {
                    val schema = o?.int("schema_version")
                    if (o == null || schema == null) failure = "Not a Music Assistant server" else
                        info = ServerInfo(o.str("server_id") ?: "", o.str("server_version") ?: "", schema, o.str("name"))
                    latch.countDown()
                    return
                }
                if (o != null && socket === webSocket) dispatch(o)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (latch.count > 0) {
                    failure = "Cannot reach $url"
                    latch.countDown()
                } else closed(webSocket)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                closed(webSocket)
            }
        })
        if (!latch.await(20, TimeUnit.SECONDS)) failure = "Cannot reach $url"
        failure?.let {
            ws.cancel()
            throw MAError(it)
        }
        socket = ws
        return info!!
    }

    private fun closed(ws: WebSocket) {
        if (socket !== ws) return
        socket = null
        failPending(MAError("Connection closed"))
        onClose("Connection closed")
    }

    fun close() {
        socket?.close(1000, null)
        socket = null
        failPending(MAError("Disconnected"))
    }

    fun send(command: String, args: JSONObject = JSONObject(), timeoutSeconds: Long = 30): Any? {
        val ws = socket ?: throw MAError("Not connected to Music Assistant")
        val id = UUID.randomUUID().toString().replace("-", "")
        val p = Pending()
        synchronized(pending) { pending[id] = p }
        ws.send(JSONObject().put("message_id", id).put("command", command).put("args", args).toString())
        if (!p.latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            synchronized(pending) { pending.remove(id) }
            throw MAError("$command timed out")
        }
        p.error?.let { throw it }
        return p.result
    }

    private fun failPending(error: Exception) {
        val all = synchronized(pending) { ArrayList(pending.values).also { pending.clear() } }
        for (p in all) {
            p.error = error
            p.latch.countDown()
        }
    }

    private fun dispatch(data: JSONObject) {
        val id = data.str("message_id")
        if (id != null) {
            val p = synchronized(pending) { pending[id] } ?: return
            if (data.has("error_code")) {
                p.error = MAError(data.str("details") ?: "Music Assistant error ${data.str("error_code") ?: ""}")
            } else if (data.bool("partial")) {
                // Chunked results: accumulate until the final message.
                val acc = p.partial ?: JSONArray().also { p.partial = it }
                (data.opt("result") as? JSONArray)?.let { r -> for (i in 0 until r.length()) acc.put(r.opt(i)) }
                return
            } else {
                val acc = p.partial
                p.result = if (acc != null) {
                    (data.opt("result") as? JSONArray)?.let { r -> for (i in 0 until r.length()) acc.put(r.opt(i)) }
                    acc
                } else data.opt("result")
            }
            synchronized(pending) { pending.remove(id) }
            p.latch.countDown()
            return
        }
        data.str("event")?.let { onEvent(it, data.opt("data")) }
    }

    companion object {
        /** One command that needs no authentication on its own short connection. Blocks. */
        fun anonymous(server: String, command: String, args: JSONObject = JSONObject()): Any? {
            val url = MusicAssistantURL.ws(server) ?: throw MAError("Invalid server address")
            val c = MAConnection()
            try {
                c.open(url)
                return c.send(command, args, 15)
            } finally {
                c.close()
            }
        }
    }
}

class MAPlayer(p: JSONObject) {
    val id: String = p.str("player_id") ?: ""
    val name: String = p.str("display_name") ?: p.str("name") ?: id
    val available = p.bool("available")
    val state: String? = p.str("playback_state") ?: p.str("state")
    val volume: Int? = p.int("volume_level")
    val title: String? = p.obj("current_media")?.str("title")
    val artist: String? = p.obj("current_media")?.str("artist")
    val imageURL: String? = p.obj("current_media")?.str("image_url")
    val hidden = p.bool("hide_in_ui") || p.bool("hidden") || (p.has("enabled") && !p.bool("enabled"))
}

class MusicAssistantClient : LibrarySource {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    class Status(
        val state: State = State.DISCONNECTED,
        val error: String? = null,
        val needsSignIn: Boolean = false,
        val user: String? = null,
        val serverName: String? = null,
        val serverVersion: String? = null,
        val schemaVersion: Int? = null,
    )

    class LoginProvider(val id: String, val label: String)

    override val changed = Signal()
    var status = Status()
        private set
    var server = Prefs.string("ma.server")
        private set
    /** "session" (renews itself), "long-lived" (pasted), or "". */
    var tokenKind = Prefs.string("ma.tokenKind")
        private set
    var players: List<MAPlayer> = emptyList()
        private set
    var selectedPlayerID: String? = Prefs.stringOrNull("ma.player")
        set(v) {
            field = v
            Prefs.set("ma.player", v)
            changed.emit()
        }

    val hasToken get() = Secrets.get("ma.token") != null
    val httpBase get() = MusicAssistantURL.httpBase(server)

    private val connection = MAConnection()
    private var reconnectDelay = 1000L
    private var stopped = true
    private val deviceName = "Audacious for Android"
    private val reconnect = Runnable { connect() }

    init {
        connection.onEvent = { event, data -> Main.post { handleEvent(event, data) } }
        connection.onClose = { error ->
            Main.post {
                status = Status(error = error)
                changed.emit()
                scheduleReconnect()
            }
        }
        if (server.isNotEmpty()) connect()
    }

    // MARK: Connection

    fun connect() {
        Main.cancel(reconnect)
        val url = MusicAssistantURL.ws(server)
        if (url == null) {
            status = Status(error = if (server.isEmpty()) null else "Invalid server address")
            changed.emit()
            return
        }
        stopped = false
        status = Status(State.CONNECTING)
        changed.emit()
        Bg.load({
            val info = connection.open(url)
            var user: String? = null
            if (info.schema >= 28) {
                val token = Secrets.get("ma.token") ?: throw MAError("Sign in to this Music Assistant server", authFailed = true)
                val result = try {
                    connection.send("auth", JSONObject().put("token", token).put("device_name", deviceName))
                } catch (e: Exception) {
                    throw MAError("Your sign-in was not accepted (${e.text}). Sign in again.", authFailed = true)
                }
                if (result == false) throw MAError("Your sign-in has expired or was revoked. Sign in again.", authFailed = true)
                val u = (result as? JSONObject)?.obj("user")
                user = u?.str("display_name") ?: u?.str("username")
            }
            val list = try {
                Json.objects(connection.send("players/all")).map { MAPlayer(it) }
            } catch (_: Exception) {
                emptyList()
            }
            Triple(info, user, list)
        }) { result ->
            result.onSuccess { (info, user, list) ->
                reconnectDelay = 1000
                status = Status(State.CONNECTED, user = user, serverName = info.name, serverVersion = info.version, schemaVersion = info.schema)
                setPlayers(list)
            }.onFailure { e ->
                val auth = (e as? MAError)?.authFailed == true
                connection.close()
                status = Status(error = e.text, needsSignIn = auth)
                if (auth) stopped = true else scheduleReconnect()
            }
            changed.emit()
        }
    }

    fun disconnect() {
        stopped = true
        Main.cancel(reconnect)
        Bg.run { connection.close() }
        status = Status()
        changed.emit()
    }

    private fun scheduleReconnect() {
        if (stopped) return
        Main.cancel(reconnect)
        Main.delay(reconnectDelay, reconnect)
        reconnectDelay = minOf(reconnectDelay * 2, 30_000)
    }

    /** Blocks. */
    fun command(command: String, args: JSONObject = JSONObject()): Any? {
        if (status.state != State.CONNECTED) throw MAError("Not connected to Music Assistant")
        return connection.send(command, args)
    }

    // MARK: Sign-in (all block; call off the main thread)

    private fun setServer(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) throw MAError("Enter the server address first")
        Main.post {
            server = trimmed
            Prefs.set("ma.server", trimmed)
        }
        server = trimmed
    }

    /** Sign-in options the server offers besides username and password. */
    fun loginProviders(server: String): List<LoginProvider> {
        setServer(server)
        return Json.objects(MAConnection.anonymous(this.server, "auth/providers")).filter { it.bool("requires_redirect") }.mapNotNull { p ->
            val id = p.str("provider_id") ?: return@mapNotNull null
            LoginProvider(id, if (p.str("provider_type") == "homeassistant" || id == "hass") "Home Assistant" else id)
        }
    }

    /** Username and password: the server returns a session token. */
    fun login(server: String, username: String, password: String) {
        setServer(server)
        val result = MAConnection.anonymous(this.server, "auth/login",
            JSONObject().put("username", username).put("password", password).put("device_name", deviceName)) as? JSONObject
        val token = result?.str("access_token") ?: result?.str("token")
        if (result?.bool("success") != true || token == null) throw MAError(result?.str("error") ?: "Wrong username or password")
        signedIn(token, "session")
    }

    /** Connects with the saved token, if any (servers before schema 28 need none). */
    fun useServerOnly(server: String) {
        setServer(server)
        Main.post { connect() }
    }

    /** A pasted long-lived token. */
    fun useToken(server: String, token: String) {
        setServer(server)
        signedIn(token.trim(), "long-lived")
    }

    /** Sign in with a provider (e.g. Home Assistant) in the browser. Returns
     *  the page to open; `done` runs on the main thread once the server
     *  redirects back here with the token (or it fails or times out). */
    fun beginBrowserLogin(server: String, provider: String, done: (Exception?) -> Unit): String {
        setServer(server)
        val callback = LoopbackCallback()
        val result = MAConnection.anonymous(this.server, "auth/authorization_url",
            JSONObject().put("provider_id", provider).put("return_url", "http://127.0.0.1:${callback.port}/callback")) as? JSONObject
        val url = result?.str("authorization_url")
        if (url == null) {
            callback.stop()
            throw MAError(result?.str("error") ?: "This sign-in method is not available")
        }
        Bg.run {
            try {
                val code = callback.code(300)
                signedIn(code, "session")
                Main.post { done(null) }
            } catch (e: Exception) {
                Main.post { done(e) }
            } finally {
                callback.stop()
            }
        }
        return url
    }

    private fun signedIn(token: String, kind: String) {
        Secrets.set("ma.token", token)
        Main.post {
            tokenKind = kind
            Prefs.set("ma.tokenKind", kind)
            connect()
        }
    }

    /** Signs out: revokes a session token on the server, then forgets it. */
    fun signOut() {
        val revoke = tokenKind == "session" && status.state == State.CONNECTED
        Bg.run {
            if (revoke) try {
                connection.send("auth/logout")
            } catch (_: Exception) {
            }
        }
        Secrets.set("ma.token", null)
        tokenKind = ""
        Prefs.set("ma.tokenKind", "")
        players = emptyList()
        disconnect()
    }

    // MARK: Players

    private fun setPlayers(list: List<MAPlayer>) {
        players = list.filter { it.available && !it.hidden }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        if (selectedPlayerID == null || players.none { it.id == selectedPlayerID }) selectedPlayerID = players.firstOrNull()?.id
        changed.emit()
    }

    val selectedPlayer get() = players.firstOrNull { it.id == selectedPlayerID }

    private fun handleEvent(event: String, data: Any?) {
        when (event) {
            "player_updated", "player_added" -> {
                val p = (data as? JSONObject)?.let { MAPlayer(it) } ?: return
                val list = players.filter { it.id != p.id } + listOfNotNull(p.takeIf { it.available && !it.hidden })
                players = list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                changed.emit()
            }
            "player_removed" -> {
                val id = (data as? JSONObject)?.str("player_id") ?: Json.str(data) ?: return
                players = players.filter { it.id != id }
                changed.emit()
            }
        }
    }

    fun playerCommand(cmd: String, extra: JSONObject = JSONObject()) {
        val id = selectedPlayerID ?: return
        Bg.run {
            try {
                command("players/cmd/$cmd", extra.put("player_id", id))
            } catch (_: Exception) {
            }
        }
    }

    fun setVolume(volume: Int) = playerCommand("volume_set", JSONObject().put("volume_level", volume))

    // MARK: Images

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun image(item: JSONObject, size: Int = 256): ImageRef? {
        val images = item.obj("metadata")?.objects("images") ?: emptyList()
        val image = item.obj("image") ?: images.firstOrNull { it.str("type") == "thumb" } ?: images.firstOrNull() ?: return null
        val path = image.str("path")?.takeIf { it.isNotEmpty() } ?: return null
        if (image.bool("remotely_accessible")) return ImageRef.Remote(path)
        val base = httpBase ?: return null
        image.str("proxy_id")?.let { return ImageRef.Remote("$base/imageproxy/${enc(it)}?size=$size") }
        // The frontend double-encodes the path.
        return ImageRef.Remote("$base/imageproxy?path=${enc(enc(path))}&provider=${enc(image.str("provider") ?: "")}&size=$size")
    }

    // MARK: Library

    override val id = "ma"
    override val label = "Music Assistant"
    override val isConnected get() = status.state == State.CONNECTED
    override val statusText: String
        get() = if (status.state == State.CONNECTING) "Connecting to Music Assistant…"
        else status.error?.let { "Not connected: $it" } ?: "Not connected to Music Assistant. Set it up in Settings."
    override val targetText get() = selectedPlayer?.let { "Plays on ${it.name}" } ?: "Choose a player"

    private fun list(command: String, args: JSONObject) = Json.objects(command(command, args)).map { item(it) }

    private fun item(raw: JSONObject): LibraryItem {
        val itemID = raw.str("item_id") ?: ""
        val provider = raw.str("provider") ?: ""
        val type = raw.str("media_type") ?: "track"
        val name = (raw.str("name") ?: "") + (raw.str("version")?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
        fun ref() = JSONObject().put("item_id", itemID).put("provider_instance_id_or_domain", provider)
        val page = LIBRARY_PAGE_SIZE
        val open: (() -> LibraryView)? = when (type) {
            "artist" -> {
                {
                    LibraryView(name, LibraryLayout.GRID, sections = {
                        val albums = try { list("music/artists/artist_albums", ref()) } catch (_: Exception) { emptyList() }
                        val tracks = try { list("music/artists/artist_tracks", ref()) } catch (_: Exception) { emptyList() }
                        listOf(LibrarySection("Albums", albums, LibraryLayout.GRID), LibrarySection("Tracks", tracks, LibraryLayout.LIST))
                    })
                }
            }
            "album" -> { { LibraryView(name, LibraryLayout.LIST, load = { o -> if (o > 0) emptyList() else list("music/albums/album_tracks", ref()) }) } }
            "playlist" -> {
                { LibraryView(name, LibraryLayout.LIST, load = { o -> list("music/playlists/playlist_tracks", ref().put("offset", o).put("limit", page)) }) }
            }
            "podcast" -> {
                { LibraryView(name, LibraryLayout.LIST, load = { o -> if (o > 0) emptyList() else list("music/podcasts/podcast_episodes", ref()) }) }
            }
            "folder" -> {
                val path = raw.str("path") ?: ""
                {
                    LibraryView(raw.str("label")?.takeIf { it.isNotEmpty() } ?: name, LibraryLayout.LIST, load = { o ->
                        list("music/browse", JSONObject().put("path", path).put("offset", o).put("limit", page))
                    })
                }
            }
            else -> null
        }
        val playable = (if (raw.has("is_playable")) raw.bool("is_playable") else true) && type != "folder"
        val uri = raw.str("uri") ?: "$provider://$type/$itemID"
        val artists = raw.objects("artists").mapNotNull { it.str("name") }.joinToString(", ")
        val album = if (type == "track") raw.obj("album")?.str("name") else null
        val image = image(raw)
        return LibraryItem("ma:$uri", name, listOfNotNull(artists.takeIf { it.isNotEmpty() }, album).joinToString(" · "), type,
            raw.double("duration"), image?.let { i -> { i } }, open,
            play = if (playable) { option: PlayOption ->
                val target = selectedPlayerID ?: throw MAError("Choose a player first")
                command("player_queues/play_media", JSONObject().put("queue_id", target).put("media", JSONArray().put(uri)).put("option", option.wire))
                selectedPlayer?.name ?: "player"
            } else null)
    }

    override val sections: List<Pair<String, () -> LibraryView>>
        get() = listOf(
            Triple("artists", "Artists", LibraryLayout.GRID), Triple("albums", "Albums", LibraryLayout.GRID),
            Triple("tracks", "Tracks", LibraryLayout.LIST), Triple("playlists", "Playlists", LibraryLayout.GRID),
            Triple("radios", "Radio", LibraryLayout.GRID), Triple("podcasts", "Podcasts", LibraryLayout.GRID),
        ).map { (type, title, layout) ->
            title to {
                LibraryView(title, layout, load = { offset ->
                    list("music/$type/library_items", JSONObject().put("limit", LIBRARY_PAGE_SIZE).put("offset", offset))
                })
            }
        } + ("Browse" to {
            LibraryView("Browse", LibraryLayout.LIST, load = { offset ->
                list("music/browse", JSONObject().put("offset", offset).put("limit", LIBRARY_PAGE_SIZE))
            })
        })

    override fun search(query: String, within: String?) = LibraryView("Search: $query", LibraryLayout.GRID, sections = {
        val r = command("music/search", JSONObject().put("search_query", query)
            .put("media_types", JSONArray().put("artist").put("album").put("track").put("playlist").put("radio")).put("limit", 25)) as? JSONObject
        listOf(Triple("Artists", "artists", LibraryLayout.GRID), Triple("Albums", "albums", LibraryLayout.GRID),
            Triple("Tracks", "tracks", LibraryLayout.LIST), Triple("Playlists", "playlists", LibraryLayout.GRID),
            Triple("Radio", "radio", LibraryLayout.GRID)).map { (title, key, layout) ->
            LibrarySection(title, r?.objects(key)?.map { item(it) } ?: emptyList(), layout)
        }
    })
}

/** A one-off HTTP listener on 127.0.0.1 that receives ?code=<token> when
 *  Music Assistant redirects back after a browser sign-in. */
class LoopbackCallback {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    val port get() = server.localPort

    /** Waits for the redirect. Blocks. */
    fun code(timeoutSeconds: Int): String {
        server.soTimeout = timeoutSeconds * 1000
        while (true) {
            val socket = try {
                server.accept()
            } catch (e: java.net.SocketTimeoutException) {
                throw UserError("Sign-in timed out")
            } catch (e: Exception) {
                throw UserError("Sign-in was cancelled")
            }
            // Socket is Closeable only from Android 4.4, so no use {}.
            try {
                val s = socket
                val line = s.getInputStream().bufferedReader().readLine() ?: ""
                val target = line.split(" ").getOrNull(1) ?: ""
                val url = HttpUrl.parse("http://127.0.0.1$target")
                val code = if (url?.encodedPath() == "/callback") url.queryParameter("code") else null
                val body = if (code != null)
                    "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><title>Signed in</title>" +
                        "<body style='font:17px sans-serif;padding:40px;text-align:center'><h2>Signed in to Music Assistant</h2>" +
                        "<p>You can return to Audacious.</p>"
                else "Not found"
                val bytes = body.toByteArray()
                val head = "HTTP/1.1 ${if (code != null) "200 OK" else "404 Not Found"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                s.getOutputStream().apply {
                    write(head.toByteArray())
                    write(bytes)
                    flush()
                }
                if (code != null) return code
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun stop() {
        try {
            server.close()
        } catch (_: Exception) {
        }
    }
}
