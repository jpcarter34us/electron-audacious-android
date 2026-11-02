package io.github.jpcarter34us.audacious.library

import io.github.jpcarter34us.audacious.player.MediaLocation
import io.github.jpcarter34us.audacious.player.PlayOption
import io.github.jpcarter34us.audacious.player.Player
import io.github.jpcarter34us.audacious.player.Track
import io.github.jpcarter34us.audacious.player.TrackSource
import io.github.jpcarter34us.audacious.util.Bg
import io.github.jpcarter34us.audacious.util.Http
import io.github.jpcarter34us.audacious.util.Main
import io.github.jpcarter34us.audacious.util.Prefs
import io.github.jpcarter34us.audacious.util.Secrets
import io.github.jpcarter34us.audacious.util.Signal
import io.github.jpcarter34us.audacious.util.bool
import io.github.jpcarter34us.audacious.util.double
import io.github.jpcarter34us.audacious.util.int
import io.github.jpcarter34us.audacious.util.obj
import io.github.jpcarter34us.audacious.util.objects
import io.github.jpcarter34us.audacious.util.str
import io.github.jpcarter34us.audacious.util.text
import okhttp3.HttpUrl
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OpenSubsonic / Subsonic servers (Navidrome, Gonic, Airsonic, ...), ported
 * from ElectronPlayer's src/main/subsonic.ts by way of the iOS port.
 *
 * Auth: an API key (apiKeyAuthentication extension) when the user gives
 * one, otherwise the salted token t = md5(password + salt); servers that
 * reject tokens (error 41) get the hex-encoded password. Playlists store
 * the song id; the stream URL (which carries a credential) is built when
 * it plays.
 */
class SubsonicCredentials(val base: String, val username: String, val password: String?, val apiKey: String?, var auth: Auth) {
    enum class Auth { API_KEY, TOKEN, HEX }

    companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT = "AudaciousAndroid"
        private val random = SecureRandom()

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    fun url(method: String, params: Map<String, String> = emptyMap()): String {
        val b = HttpUrl.parse("$base/rest/$method")!!.newBuilder()
        when (auth) {
            Auth.API_KEY -> b.addQueryParameter("apiKey", apiKey ?: "")
            Auth.TOKEN -> {
                val salt = hex(ByteArray(8).also { random.nextBytes(it) })
                val token = hex(MessageDigest.getInstance("MD5").digest(((password ?: "") + salt).toByteArray()))
                b.addQueryParameter("u", username).addQueryParameter("s", salt).addQueryParameter("t", token)
            }
            Auth.HEX -> b.addQueryParameter("u", username).addQueryParameter("p", "enc:" + hex((password ?: "").toByteArray()))
        }
        b.addQueryParameter("v", API_VERSION).addQueryParameter("c", CLIENT).addQueryParameter("f", "json")
        for ((k, v) in params.toSortedMap()) b.addQueryParameter(k, v)
        return b.build().toString()
    }
}

class SubsonicError(message: String, val code: Int?) : Exception(message)

class SubsonicClient(private val player: Player) : LibrarySource {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    class Status(
        val state: State = State.DISCONNECTED,
        val error: String? = null,
        val serverType: String? = null,
        val serverVersion: String? = null,
        val openSubsonic: Boolean = false,
        val apiKeySupported: Boolean = false,
        val auth: String? = null,
    )

    override val changed = Signal()
    var status = Status()
        private set
    var server = Prefs.string("subsonic.server")
        private set
    var username = Prefs.string("subsonic.username")
        private set
    val hasPassword get() = Secrets.get("subsonic.password") != null
    val hasApiKey get() = Secrets.get("subsonic.apikey") != null

    @Volatile private var credentials: SubsonicCredentials? = null

    companion object {
        /** Formats the engine plays; anything else is transcoded to MP3 by the server. */
        val playable = setOf("mp3", "flac", "wav", "ogg", "oga", "opus", "m4a", "m4b", "mp4", "aac", "aif", "aiff")

        /** http(s)://host[:port][/base] from what the user typed. */
        fun baseURL(server: String): String? {
            var text = server.trim()
            if (text.isEmpty()) return null
            if (!Regex("^[a-zA-Z]+://").containsMatchIn(text)) text = "http://$text"
            val url = HttpUrl.parse(text) ?: return null
            var path = url.encodedPath()
            while (path.endsWith("/")) path = path.dropLast(1)
            if (path.endsWith("/rest")) path = path.dropLast(5)
            val portPart = if (url.port() != HttpUrl.defaultPort(url.scheme())) ":${url.port()}" else ""
            val host = if (url.host().contains(":")) "[${url.host()}]" else url.host()
            return "${url.scheme()}://$host$portPart$path"
        }

        fun stream(creds: SubsonicCredentials, id: String, suffix: String?): MediaLocation {
            val ext = suffix?.lowercase()
            if (ext != null && ext in playable) return MediaLocation.Http(creds.url("stream", mapOf("id" to id)), formatHint = ext)
            // WMA, APE, ...: ask the server for MP3.
            return MediaLocation.Http(creds.url("stream", mapOf("id" to id, "format" to "mp3", "maxBitRate" to "320")), formatHint = "mp3")
        }

        /** Blocks. */
        fun raw(creds: SubsonicCredentials, method: String, params: Map<String, String> = emptyMap()): JSONObject {
            val response = try {
                Http.client.newCall(Http.request(creds.url(method, params))).execute()
            } catch (e: Exception) {
                throw SubsonicError("Cannot reach ${creds.base} (${e.text})", null)
            }
            val text = response.use { it.body()?.string() ?: "" }
            val body = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw SubsonicError("Not a Subsonic server (HTTP ${response.code()})", null)
            }
            val r = body.obj("subsonic-response") ?: throw SubsonicError("Not a Subsonic server", null)
            if (r.str("status") != "ok") {
                val error = r.obj("error")
                val code = error?.int("code")
                throw SubsonicError(error?.str("message") ?: "Subsonic error ${code ?: 0}", code)
            }
            return r
        }
    }

    init {
        player.register("subsonic") { t ->
            val s = t.source as TrackSource.Subsonic
            val creds = credentials ?: throw SubsonicError("Connect to the Subsonic server first", null)
            stream(creds, s.id, s.suffix)
        }
        if (server.isNotEmpty() && (hasPassword || hasApiKey)) connect()
    }

    /** Saves server and credentials (only those given), then connects. */
    fun configure(server: String, username: String, password: String?, apiKey: String?) {
        this.server = server.trim()
        this.username = username.trim()
        Prefs.set("subsonic.server", this.server)
        Prefs.set("subsonic.username", this.username)
        if (!apiKey.isNullOrEmpty()) {
            Secrets.set("subsonic.apikey", apiKey.trim())
            Secrets.set("subsonic.password", null)
        } else if (!password.isNullOrEmpty()) {
            Secrets.set("subsonic.password", password)
            Secrets.set("subsonic.apikey", null)
        }
        connect()
    }

    fun signOut() {
        Secrets.set("subsonic.password", null)
        Secrets.set("subsonic.apikey", null)
        credentials = null
        status = Status()
        changed.emit()
    }

    fun connect() {
        val base = baseURL(server)
        if (base == null) {
            status = Status(error = "Enter the server address first")
            changed.emit()
            return
        }
        val password = Secrets.get("subsonic.password")
        val apiKey = Secrets.get("subsonic.apikey")
        if (apiKey == null && (username.isEmpty() || password == null)) {
            status = Status(error = "Enter your username and password, or an API key")
            changed.emit()
            return
        }
        status = Status(State.CONNECTING)
        changed.emit()
        val creds = SubsonicCredentials(base, username, password, apiKey,
            if (apiKey != null) SubsonicCredentials.Auth.API_KEY else SubsonicCredentials.Auth.TOKEN)
        Bg.load({
            val ping = try {
                raw(creds, "ping")
            } catch (e: SubsonicError) {
                if (e.code != 41 || password == null) throw e
                // Token auth not supported: fall back to the hex-encoded password.
                creds.auth = SubsonicCredentials.Auth.HEX
                raw(creds, "ping")
            }
            val extensions = if (ping.bool("openSubsonic")) {
                try {
                    raw(creds, "getOpenSubsonicExtensions").objects("openSubsonicExtensions").mapNotNull { it.str("name") }
                } catch (_: Exception) {
                    emptyList()
                }
            } else emptyList()
            ping to extensions
        }) { result ->
            result.onSuccess { (ping, extensions) ->
                credentials = creds
                status = Status(State.CONNECTED, null, ping.str("type") ?: "Subsonic", ping.str("serverVersion") ?: ping.str("version"),
                    ping.bool("openSubsonic"), "apiKeyAuthentication" in extensions,
                    when (creds.auth) {
                        SubsonicCredentials.Auth.API_KEY -> "API key"
                        SubsonicCredentials.Auth.TOKEN -> "password (salted token)"
                        SubsonicCredentials.Auth.HEX -> "password"
                    })
            }.onFailure { e ->
                credentials = null
                status = Status(error = when ((e as? SubsonicError)?.code) {
                    40 -> "Wrong username or password"
                    44 -> "The API key is not valid"
                    42 -> "The server does not accept this sign-in method"
                    else -> e.text
                })
            }
            changed.emit()
        }
    }

    /** A read-only API call. Blocks. */
    fun call(method: String, params: Map<String, String> = emptyMap()): JSONObject {
        val c = credentials ?: throw SubsonicError("Not connected to the Subsonic server", null)
        return raw(c, method, params)
    }

    /** Reports a play: "now playing" without submission, a full play with. */
    fun scrobble(id: String, submission: Boolean) {
        val c = credentials ?: return
        Bg.run {
            try {
                raw(c, "scrobble", mapOf("id" to id, "submission" to submission.toString()))
            } catch (_: Exception) {
            }
        }
    }

    fun cover(id: String?, size: Int = 300): ImageRef? {
        val c = credentials ?: return null
        return id?.let { ImageRef.Remote(c.url("getCoverArt", mapOf("id" to it, "size" to size.toString()))) }
    }

    /** Scrobbling as in ElectronPlayer's subsonic-playback.ts: "now playing"
     *  when a song starts, a full play at half the song or 4 minutes. */
    fun trackStarted(track: Track) {
        val s = track.source as? TrackSource.Subsonic ?: return
        scrobble(s.id, false)
        val wait = minOf(240.0, (track.duration ?: 60.0) / 2)
        Main.delay((wait * 1000).toLong(), Runnable {
            if (player.currentID == track.id && player.state != Player.State.STOPPED) scrobble(s.id, true)
        })
    }

    // MARK: Library

    override val id = "subsonic"
    override val label = "Subsonic"
    override val isConnected get() = status.state == State.CONNECTED
    override val statusText: String
        get() = if (status.state == State.CONNECTING) "Connecting to the Subsonic server…"
        else status.error?.let { "Not connected: $it" } ?: "Not connected to a Subsonic server. Set it up in Settings."
    override val targetText = "Plays here"

    private fun coverFn(id: String?): (() -> ImageRef?)? = id?.let { i -> { cover(i) } }

    fun track(s: JSONObject): Track? {
        val id = s.str("id") ?: return null
        return Track(source = TrackSource.Subsonic(id, s.str("suffix")), title = s.str("title") ?: "Untitled", artist = s.str("artist"),
            album = s.str("album"), duration = s.double("duration"), trackNumber = s.int("track"), artworkKey = s.str("coverArt"))
    }

    private fun playTracks(tracks: List<Track>, option: PlayOption): String {
        Main.post { player.add(tracks, option) }
        return "this device"
    }

    private fun songItem(s: JSONObject): LibraryItem? {
        val t = track(s) ?: return null
        return LibraryItem("song:${t.source}", t.title, t.subtitle, "track", t.duration, coverFn(t.artworkKey),
            play = { o -> playTracks(listOf(t), o) }, track = t)
    }

    private fun songs(list: Any?): List<LibraryItem> = io.github.jpcarter34us.audacious.util.Json.objects(list).mapNotNull { songItem(it) }

    private fun albumSongs(id: String) = songs(call("getAlbum", mapOf("id" to id)).obj("album")?.opt("song"))
    private fun playlistSongs(id: String) = songs(call("getPlaylist", mapOf("id" to id)).obj("playlist")?.opt("entry"))

    private fun album(a: JSONObject): LibraryItem? {
        val id = a.str("id") ?: return null
        val name = a.str("name") ?: a.str("title") ?: "Album"
        return LibraryItem("album:$id", name, listOfNotNull(a.str("artist"), a.int("year")?.toString()).joinToString(" · "), "album",
            image = coverFn(a.str("coverArt")),
            open = {
                LibraryView(name, LibraryLayout.LIST, load = { o -> if (o > 0) emptyList() else albumSongs(id) },
                    playAll = { o, items -> playTracks(items.mapNotNull { it.track }, o) })
            },
            play = { o -> playTracks(albumSongs(id).mapNotNull { it.track }, o) })
    }

    private fun artist(a: JSONObject): LibraryItem? {
        val id = a.str("id") ?: return null
        val name = a.str("name") ?: "Artist"
        val count = a.int("albumCount")
        return LibraryItem("artist:$id", name, count?.let { "$it album${if (it == 1) "" else "s"}" }, "artist", image = coverFn(a.str("coverArt")),
            open = {
                LibraryView(name, LibraryLayout.GRID, load = { o ->
                    if (o > 0) emptyList() else call("getArtist", mapOf("id" to id)).obj("artist")?.objects("album")?.mapNotNull { album(it) } ?: emptyList()
                })
            })
    }

    private fun playlist(p: JSONObject): LibraryItem? {
        val id = p.str("id") ?: return null
        val name = p.str("name") ?: "Playlist"
        return LibraryItem("playlist:$id", name, p.int("songCount")?.let { "$it songs" }, "playlist", image = coverFn(p.str("coverArt")),
            open = {
                LibraryView(name, LibraryLayout.LIST, load = { o -> if (o > 0) emptyList() else playlistSongs(id) },
                    playAll = { o, items -> playTracks(items.mapNotNull { it.track }, o) })
            },
            play = { o -> playTracks(playlistSongs(id).mapNotNull { it.track }, o) })
    }

    /** Every song, or those matching `query`, a page at a time: search3 with
     *  only songs. OpenSubsonic servers return everything for an empty query;
     *  older ones want a literal "" instead. */
    private fun songsView(query: String): LibraryView {
        var emptyQuery = ""
        fun page(offset: Int) = songs(call("search3", mapOf("query" to (if (query.isEmpty()) emptyQuery else query),
            "songCount" to LIBRARY_PAGE_SIZE.toString(), "songOffset" to offset.toString(), "artistCount" to "0", "albumCount" to "0"))
            .obj("searchResult3")?.opt("song"))
        return LibraryView(if (query.isEmpty()) "Songs" else "Songs: $query", LibraryLayout.LIST, load = { offset ->
            var items = page(offset)
            if (items.isEmpty() && query.isEmpty() && offset == 0 && emptyQuery.isEmpty()) {
                emptyQuery = "\"\""
                items = page(0)
            }
            items
        }, playAll = { o, items -> playTracks(items.mapNotNull { it.track }, o) })
    }

    private fun albumList(title: String, type: String) = LibraryView(title, LibraryLayout.GRID, load = { offset ->
        call("getAlbumList2", mapOf("type" to type, "size" to LIBRARY_PAGE_SIZE.toString(), "offset" to offset.toString()))
            .obj("albumList2")?.objects("album")?.mapNotNull { album(it) } ?: emptyList()
    })

    override val sections: List<Pair<String, () -> LibraryView>>
        get() = listOf(
            "Artists" to {
                LibraryView("Artists", LibraryLayout.GRID, load = { offset ->
                    if (offset > 0) emptyList() else call("getArtists").obj("artists")?.objects("index")
                        ?.flatMap { it.objects("artist") }?.mapNotNull { artist(it) } ?: emptyList()
                })
            },
            "Albums" to { albumList("Albums", "alphabeticalByName") },
            "Songs" to { songsView("") },
            "Recently Added" to { albumList("Recently Added", "newest") },
            "Playlists" to {
                LibraryView("Playlists", LibraryLayout.GRID, load = { offset ->
                    if (offset > 0) emptyList() else call("getPlaylists").obj("playlists")?.objects("playlist")?.mapNotNull { playlist(it) }
                        ?: emptyList()
                })
            },
            "Starred" to {
                LibraryView("Starred", LibraryLayout.GRID, sections = {
                    val s = call("getStarred2").obj("starred2")
                    listOf(LibrarySection("Artists", s?.objects("artist")?.mapNotNull { artist(it) } ?: emptyList(), LibraryLayout.GRID),
                        LibrarySection("Albums", s?.objects("album")?.mapNotNull { album(it) } ?: emptyList(), LibraryLayout.GRID),
                        LibrarySection("Songs", songs(s?.opt("song")), LibraryLayout.LIST))
                })
            },
            "Random Songs" to {
                LibraryView("Random Songs", LibraryLayout.LIST, load = { offset ->
                    if (offset > 0) emptyList() else songs(call("getRandomSongs", mapOf("size" to "50")).obj("randomSongs")?.opt("song"))
                }, playAll = { o, items -> playTracks(items.mapNotNull { it.track }, o) })
            },
        )

    override fun search(query: String, within: String?): LibraryView {
        if (within == "Songs") return songsView(query)
        return LibraryView("Search: $query", LibraryLayout.GRID, sections = {
            val r = call("search3", mapOf("query" to query, "artistCount" to "20", "albumCount" to "20", "songCount" to "50")).obj("searchResult3")
            listOf(LibrarySection("Artists", r?.objects("artist")?.mapNotNull { artist(it) } ?: emptyList(), LibraryLayout.GRID),
                LibrarySection("Albums", r?.objects("album")?.mapNotNull { album(it) } ?: emptyList(), LibraryLayout.GRID),
                LibrarySection("Songs", songs(r?.opt("song")), LibraryLayout.LIST))
        })
    }
}
