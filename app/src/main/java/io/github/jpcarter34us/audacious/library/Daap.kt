package io.github.jpcarter34us.audacious.library

import android.content.Context
import android.util.Base64
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
import io.github.jpcarter34us.audacious.util.text
import okhttp3.HttpUrl
import org.json.JSONObject

/**
 * DAAP, the protocol of iTunes music sharing, ported from ElectronPlayer's
 * src/main/daap.ts by way of the iOS port's DAAP.swift: browse and play
 * libraries shared by OwnTone (forked-daapd), Firefly/mt-daapd,
 * Rhythmbox, Ampache and similar servers, found by mDNS (_daap._tcp) or
 * added by address. iTunes 7+ and Music.app need a proprietary request
 * signature, which is not implemented (as in ElectronPlayer).
 *
 * Song URLs need the session id, so playlists store the share and song id
 * and the stream request is built (signing in again if needed) when the
 * song plays.
 */
class DaapClient(private val context: Context, private val player: Player) : LibrarySource {
    class Share(val id: String, var name: String, val discovered: Boolean, var address: String?, var needsPassword: Boolean,
                var hasPassword: Boolean)

    class Song(val id: Int, val title: String, val artist: String?, val albumArtist: String?, val album: String?, val genre: String?,
               val duration: Double?, val track: Int?, val disc: Int?, val year: Int?, val format: String?)

    class DaapError(message: String, val status: Int?) : Exception(message)

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    private class Session(val id: Int, val revision: Int, val db: Int, val name: String)

    companion object {
        const val DEFAULT_PORT = 3689
        val baseHeaders = mapOf("Client-DAAP-Version" to "3.0", "Viewer-Only-Client" to "1", "Accept" to "*/*",
            "User-Agent" to "AudaciousAndroid")
        val songMeta = listOf("dmap.itemid", "dmap.itemname", "daap.songartist", "daap.songalbumartist", "daap.songalbum",
            "daap.songgenre", "daap.songtime", "daap.songtracknumber", "daap.songdiscnumber", "daap.songyear", "daap.songformat",
            "daap.songdatakind").joinToString(",")
        const val playlistMeta = "dmap.itemid,dmap.itemname,dmap.itemcount,daap.baseplaylist,com.apple.itunes.smart-playlist"

        /** "host:port" from what the user typed ("host", "host:3689", "http://host:3689/", "[::1]:3689"). */
        fun shareID(address: String): String? {
            var a = address.trim()
            if (a.isEmpty()) return null
            if (!Regex("^[a-zA-Z]+://").containsMatchIn(a)) a = "http://$a"
            val url = HttpUrl.parse(a) ?: return null
            val host = url.host()
            val h = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
            val explicit = Regex("^[a-zA-Z]+://[^/]*:(\\d+)").find(a) != null
            return "$h:${if (explicit) url.port() else DEFAULT_PORT}"
        }

        fun song(item: DmapNode): Song? {
            val id = item.num("miid") ?: return null
            if (item.num("asdk") == 1) return null // an internet radio URL, not a file
            val ms = item.num("astm")
            val format = item.text("asfm")?.lowercase()?.filter { it.isLetterOrDigit() }
            fun s(tag: String) = item.text(tag)?.takeIf { it.isNotEmpty() }
            fun n(tag: String) = item.num(tag)?.takeIf { it > 0 }
            return Song(id, s("minm") ?: "Track $id", s("asar"), s("asaa"), s("asal"), s("asgn"),
                ms?.takeIf { it > 0 }?.let { it / 1000.0 }, n("astn"), n("asdn"), n("asyr"), format?.takeIf { it.isNotEmpty() })
        }
    }

    override val changed = Signal()
    var state = State.DISCONNECTED
        private set
    var error: String? = null
        private set
    var needsPassword = false
        private set
    var openShare: Pair<String, String>? = null
        private set
    var shares: List<Share> = emptyList()
        private set
    var songs: List<Song> = emptyList()
        private set
    var discovering = false
        private set
    var servers: List<String> = Prefs.strings("daap.servers")
        private set
    var discover: Boolean = Prefs.bool("daap.discover", true)
        set(v) {
            field = v
            Prefs.set("daap.discover", v)
            if (v) runDiscovery() else {
                discovered.clear()
                rebuildShares()
            }
        }

    private val passwords = HashMap<String, String>()
    private val sessions = HashMap<String, Session>()
    private val discovered = HashMap<String, Pair<String, Boolean>>()
    private val refused = HashSet<String>()
    private var connecting = 0

    init {
        Secrets.get("daap.passwords")?.let { raw ->
            try {
                val o = JSONObject(raw)
                for (k in o.keys()) passwords[k] = o.getString(k)
            } catch (_: Exception) {
            }
        }
        player.register("daap") { t ->
            val s = t.source as TrackSource.Daap
            streamLocation(s.share, s.id, s.format)
        }
        rebuildShares()
        if (discover) runDiscovery()
        Prefs.string("daap.lastShare").takeIf { it.isNotEmpty() }?.let { connect(it) }
    }

    // MARK: Shares

    private fun rebuildShares() {
        val list = LinkedHashMap<String, Share>()
        for ((id, d) in discovered) list[id] = Share(id, d.first, true, null, d.second, false)
        for (address in servers) {
            val id = shareID(address) ?: continue
            val existing = list[id]
            if (existing != null) existing.address = address else list[id] = Share(id, address, false, address, false, false)
        }
        for (s in list.values) {
            s.hasPassword = passwords.containsKey(s.id)
            if (s.id in refused) s.needsPassword = true
            val open = openShare
            if (open != null && open.first == s.id && (s.name == s.id || s.name == s.address)) s.name = open.second
        }
        shares = list.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        changed.emit()
    }

    fun runDiscovery() {
        if (discovering) return
        discovering = true
        changed.emit()
        Bg.load({ Mdns.browse(context, "_daap._tcp") }) { result ->
            discovering = false
            discovered.clear()
            for (s in result.getOrNull() ?: emptyList()) {
                val needs = (s.txt["Password"] ?: "").lowercase() in listOf("true", "1")
                discovered["${s.host}:${s.port}"] = (s.txt["Machine Name"] ?: s.name) to needs
            }
            rebuildShares()
        }
    }

    fun addServer(address: String) {
        val a = address.trim()
        if (a.isEmpty() || a in servers) return
        servers = servers + a
        Prefs.setStrings("daap.servers", servers)
        rebuildShares()
    }

    fun removeServer(address: String) {
        servers = servers - address
        Prefs.setStrings("daap.servers", servers)
        rebuildShares()
    }

    fun forgetPassword(id: String) {
        passwords.remove(id)
        savePasswords()
        rebuildShares()
    }

    private fun savePasswords() {
        Secrets.set("daap.passwords", if (passwords.isEmpty()) null else JSONObject(passwords as Map<*, *>).toString())
    }

    /** Opens a share; `password` (when given) is stored for it. */
    fun connect(address: String, password: String? = null) {
        val id = shareID(address) ?: return
        val attempt = ++connecting
        if (password != null) {
            if (password.isEmpty()) passwords.remove(id) else passwords[id] = password
            savePasswords()
        }
        Prefs.set("daap.lastShare", id)
        synchronized(sessions) { sessions.remove(id) }
        val name = shares.firstOrNull { it.id == id }?.name ?: id
        state = State.CONNECTING
        error = null
        needsPassword = false
        openShare = id to name
        changed.emit()
        Bg.load({
            val session = session(id)
            val items = get(id, "/databases/${session.db}/items", mapOf("type" to "music", "meta" to songMeta))
            session to (items.at("mlcl")?.all("mlit") ?: emptyList()).mapNotNull { song(it) }
        }) { result ->
            if (attempt != connecting) return@load
            result.onSuccess { (session, list) ->
                songs = list
                refused.remove(id)
                state = State.CONNECTED
                openShare = id to session.name
            }.onFailure { e ->
                val status = (e as? DaapError)?.status
                if (status == 401) refused.add(id)
                songs = emptyList()
                state = State.DISCONNECTED
                needsPassword = status == 401
                error = when (status) {
                    401 -> if (passwords.containsKey(id)) "Wrong password" else "This library needs a password"
                    403 -> "The share refused this app (iTunes and Music.app need a signature this app cannot make)"
                    503 -> "The share is busy (too many listeners)"
                    else -> e.text
                }
            }
            rebuildShares()
        }
    }

    fun disconnect() {
        connecting++
        songs = emptyList()
        Prefs.set("daap.lastShare", "")
        state = State.DISCONNECTED
        error = null
        openShare = null
        changed.emit()
    }

    class Playlist(val id: Int, val name: String, val count: Int?, val smart: Boolean)

    /** Blocks. */
    fun playlists(): List<Playlist> {
        val id = openShareID()
        val s = session(id)
        val list = get(id, "/databases/${s.db}/containers", mapOf("meta" to playlistMeta))
        return (list.at("mlcl")?.all("mlit") ?: emptyList())
            .filter { (it.num("abpl") ?: 0) == 0 } // the base playlist is the whole library
            .map { Playlist(it.num("miid") ?: 0, it.text("minm") ?: "", it.num("mimc"), (it.num("aeSP") ?: 0) != 0) }
    }

    /** Blocks. */
    fun playlistSongs(playlist: Int): List<Song> {
        val id = openShareID()
        val s = session(id)
        val list = get(id, "/databases/${s.db}/containers/$playlist/items", mapOf("type" to "music", "meta" to "dmap.itemid,dmap.containeritemid"))
        val byID = songs.associateBy { it.id }
        return (list.at("mlcl")?.all("mlit") ?: emptyList()).mapNotNull { byID[it.num("miid") ?: -1] }
    }

    private fun openShareID(): String {
        val open = openShare
        if (state != State.CONNECTED || open == null) throw DaapError("No shared library is open", null)
        return open.first
    }

    // MARK: HTTP (blocking)

    private fun headers(id: String): Map<String, String> {
        val h = HashMap(baseHeaders)
        passwords[id]?.let {
            h["Authorization"] = "Basic " + Base64.encodeToString("AudaciousAndroid:$it".toByteArray(), Base64.NO_WRAP)
        }
        return h
    }

    private fun url(id: String, path: String, params: Map<String, String>): String {
        val b = HttpUrl.parse("http://$id$path")!!.newBuilder()
        for ((k, v) in params.toSortedMap()) b.addQueryParameter(k, v)
        return b.build().toString()
    }

    private fun raw(id: String, path: String, params: Map<String, String> = emptyMap()): DmapNode {
        val response = try {
            Http.client.newCall(Http.request(url(id, path, params), headers(id))).execute()
        } catch (e: Exception) {
            throw DaapError("Cannot reach $id (${e.text})", null)
        }
        response.use {
            if (!it.isSuccessful) throw DaapError("$path: HTTP ${it.code()}", it.code())
            val body = it.body()?.bytes() ?: ByteArray(0)
            return try {
                DmapNode.parse(body)
            } catch (e: Exception) {
                throw DaapError("Not a DAAP server (${e.text})", null)
            }
        }
    }

    /** A request in the share's session, signing in again once if the session ended. */
    private fun get(id: String, path: String, params: Map<String, String> = emptyMap()): DmapNode {
        for (attempt in 0 until 2) {
            val s = session(id)
            try {
                return raw(id, path, params + mapOf("session-id" to s.id.toString(), "revision-number" to s.revision.toString()))
            } catch (e: DaapError) {
                if (attempt == 0 && e.status in listOf(401, 403)) synchronized(sessions) { sessions.remove(id) } else throw e
            }
        }
        throw DaapError("The share ended the session", null)
    }

    /** The share's session, signing in if there is none. */
    private fun session(id: String): Session {
        synchronized(sessions) { sessions[id]?.let { return it } }
        val s = login(id)
        synchronized(sessions) { sessions[id] = s }
        return s
    }

    private fun login(id: String): Session {
        val info = raw(id, "/server-info")
        if (info.tag != "msrv") throw DaapError("Not a DAAP server", null)
        val login = raw(id, "/login")
        val sessionID = login.num("mlid") ?: throw DaapError("The share did not start a session", null)
        val update = raw(id, "/update", mapOf("session-id" to sessionID.toString(), "revision-number" to "1"))
        val revision = update.num("musr") ?: 1
        val dbs = raw(id, "/databases", mapOf("session-id" to sessionID.toString(), "revision-number" to revision.toString()))
        val db = dbs.at("mlcl", "mlit")
        val dbID = db?.num("miid") ?: throw DaapError("The share has no library", null)
        return Session(sessionID, revision, dbID, info.text("minm")?.takeIf { it.isNotEmpty() } ?: db.text("minm")?.takeIf { it.isNotEmpty() } ?: id)
    }

    /** The stream request for a song, signing in to the share as needed. Blocks. */
    fun streamLocation(share: String, item: Int, format: String?): MediaLocation {
        val s = session(share)
        val ext = format ?: "mp3"
        return MediaLocation.Http(url(share, "/databases/${s.db}/items/$item.$ext", mapOf("session-id" to s.id.toString())), headers(share), ext)
    }

    fun artwork(share: String, item: Int): ImageRef? {
        val s = try {
            session(share)
        } catch (_: Exception) {
            return null
        }
        return ImageRef.Remote(url(share, "/databases/${s.db}/items/$item/extra_data/artwork",
            mapOf("session-id" to s.id.toString(), "mw" to "300", "mh" to "300")), headers(share))
    }

    // MARK: Library

    override val id = "daap"
    override val label = "DAAP"
    override val isConnected get() = state == State.CONNECTED
    override val statusText: String
        get() = when {
            state == State.CONNECTING -> "Opening ${openShare?.second ?: "the shared library"}…"
            error != null -> "Not connected: $error"
            shares.isEmpty() -> "No shared libraries found. Add one by address in Settings."
            else -> "Choose a shared library in Settings."
        }
    override val targetText get() = openShare?.let { "From ${it.second}" } ?: ""

    private fun track(s: Song, share: String) = Track(source = TrackSource.Daap(share, s.id, s.format), title = s.title,
        artist = s.artist ?: s.albumArtist, album = s.album, duration = s.duration, trackNumber = s.track, artworkKey = s.id.toString())

    private fun playTracks(tracks: List<Track>, option: PlayOption): String {
        Main.post { player.add(tracks, option) }
        return "this device"
    }

    private val grouping: SongGrouping
        get() {
            val share = openShare?.first ?: ""
            val list = songs.map { s ->
                val t = track(s, share)
                val item = LibraryItem("daap:${s.id}", s.title, listOfNotNull(s.artist, s.album).joinToString(" · "), "track", s.duration,
                    image = { artwork(share, s.id) }, play = { o -> playTracks(listOf(t), o) }, track = t)
                SongGrouping.Song(item, s.artist, s.albumArtist, s.album, s.genre, s.track, s.disc, s.year)
            }
            return SongGrouping(list) { items, o -> playTracks(items.mapNotNull { it.track }, o) }
        }

    override val sections: List<Pair<String, () -> LibraryView>>
        get() {
            val g = grouping
            return listOf(
                "Songs" to { g.allSongs() },
                "Artists" to { g.artists() },
                "Albums" to { g.albums() },
                "Genres" to { g.genres() },
                "Playlists" to {
                    LibraryView("Playlists", LibraryLayout.LIST, load = { offset ->
                        if (offset > 0) emptyList() else {
                            val share = openShare?.first ?: ""
                            playlists().map { p ->
                                LibraryItem("daap-playlist:${p.id}", p.name, p.count?.let { "$it songs" }, "playlist",
                                    open = {
                                        LibraryView(p.name, LibraryLayout.LIST, load = { o ->
                                            if (o > 0) emptyList() else {
                                                val byID = g.songs.associateBy { it.item.id }
                                                playlistSongs(p.id).mapNotNull { byID["daap:${it.id}"]?.item }
                                            }
                                        }, playAll = { o, items -> playTracks(items.mapNotNull { it.track }, o) })
                                    },
                                    play = { o -> playTracks(playlistSongs(p.id).map { track(it, share) }, o) })
                            }
                        }
                    })
                },
            )
        }

    override fun search(query: String, within: String?) = grouping.search(query)
}
