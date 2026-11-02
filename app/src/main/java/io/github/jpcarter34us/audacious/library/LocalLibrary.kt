package io.github.jpcarter34us.audacious.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.github.jpcarter34us.audacious.player.PlayOption
import io.github.jpcarter34us.audacious.player.Player
import io.github.jpcarter34us.audacious.player.Track
import io.github.jpcarter34us.audacious.player.TrackSource
import io.github.jpcarter34us.audacious.util.Bg
import io.github.jpcarter34us.audacious.util.Main
import io.github.jpcarter34us.audacious.util.Signal
import io.github.jpcarter34us.audacious.util.double
import io.github.jpcarter34us.audacious.util.int
import io.github.jpcarter34us.audacious.util.long
import io.github.jpcarter34us.audacious.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Songs on this device (the iOS port's LocalLibrary): the system's music
 * library (MediaStore, with the storage permission) and the app's own
 * music folder (Android/data/<app>/files/Music, which a computer can fill
 * over USB and which Import copies into). Tags of the app folder's files
 * are read with MediaMetadataRetriever and cached by path and date.
 */
class LocalLibrary(private val context: Context, private val player: Player) : LibrarySource {
    class Song(val path: String, val modified: Long, val title: String, val artist: String?, val albumArtist: String?, val album: String?,
               val genre: String?, val duration: Double?, val track: Int?, val disc: Int?, val year: Int?, val folder: String) {
        fun toJson(): JSONObject = JSONObject().put("path", path).put("modified", modified).put("title", title).put("artist", artist)
            .put("albumArtist", albumArtist).put("album", album).put("genre", genre).put("duration", duration).put("track", track)
            .put("disc", disc).put("year", year).put("folder", folder)

        companion object {
            fun fromJson(o: JSONObject) = Song(o.str("path") ?: "", o.long("modified") ?: 0, o.str("title") ?: "", o.str("artist"),
                o.str("albumArtist"), o.str("album"), o.str("genre"), o.double("duration"), o.int("track"), o.int("disc"), o.int("year"),
                o.str("folder") ?: "")
        }
    }

    companion object {
        val extensions = setOf("mp3", "m4a", "m4b", "mp4", "aac", "flac", "wav", "aif", "aiff", "aifc", "ogg", "oga", "opus", "3gp", "mka", "wma")
    }

    override val changed = Signal()
    var songs: List<Song> = emptyList()
        private set
    var scanning = false
        private set

    /** The app's own music folder. */
    val folder: File = (context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "Music")).also { it.mkdirs() }
    private val cacheFile = File(context.cacheDir, "local-library.json")

    val storagePermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO" else Manifest.permission.READ_EXTERNAL_STORAGE

    val hasPermission: Boolean
        get() = Build.VERSION.SDK_INT < 23 || context.checkSelfPermission(storagePermission) == PackageManager.PERMISSION_GRANTED

    init {
        try {
            val a = JSONArray(cacheFile.readText())
            songs = (0 until a.length()).map { Song.fromJson(a.getJSONObject(it)) }
        } catch (_: Exception) {
        }
        rescan()
    }

    fun rescan() {
        if (scanning) return
        scanning = true
        changed.emit()
        val known = songs.associateBy { it.path }
        Bg.load({ scanFolder(known) + if (hasPermission) scanMediaStore() else emptyList() }) { result ->
            scanning = false
            result.getOrNull()?.let { found ->
                songs = found.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                Bg.run { cacheFile.writeText(JSONArray().also { a -> songs.forEach { a.put(it.toJson()) } }.toString()) }
            }
            changed.emit()
        }
    }

    private fun scanFolder(known: Map<String, Song>): List<Song> {
        val out = ArrayList<Song>()
        folder.walkTopDown().filter { it.isFile && it.extension.lowercase() in extensions }.forEach { f ->
            val cached = known[f.path]
            out.add(if (cached != null && cached.modified == f.lastModified()) cached else readTags(f))
        }
        return out
    }

    private fun readTags(f: File): Song {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(f.path)
            fun m(key: Int) = r.extractMetadata(key)?.takeIf { it.isNotBlank() }
            fun num(key: Int) = m(key)?.substringBefore('/')?.trim()?.toIntOrNull()
            val rel = f.parentFile?.relativeTo(folder)?.path ?: ""
            return Song(f.path, f.lastModified(), m(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: f.nameWithoutExtension,
                m(MediaMetadataRetriever.METADATA_KEY_ARTIST), m(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                m(MediaMetadataRetriever.METADATA_KEY_ALBUM), m(MediaMetadataRetriever.METADATA_KEY_GENRE),
                m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { it / 1000.0 },
                num(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER), num(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                m(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull(),
                if (rel.isEmpty() || rel == ".") "Music folder" else "Music folder/$rel")
        } catch (e: Exception) {
            return Song(f.path, f.lastModified(), f.nameWithoutExtension, null, null, null, null, null, null, null, null, "Music folder")
        } finally {
            try {
                r.release()
            } catch (_: Exception) {
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun scanMediaStore(): List<Song> {
        val out = ArrayList<Song>()
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cols = arrayListOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.DATA)
        if (Build.VERSION.SDK_INT >= 30) cols.add("genre")
        if (Build.VERSION.SDK_INT >= 30) cols.add("album_artist")
        try {
            context.contentResolver.query(uri, cols.toTypedArray(), "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
                fun s(name: String) = c.getColumnIndex(name).takeIf { it >= 0 }?.let { c.getString(it) }?.takeIf { it.isNotBlank() && it != "<unknown>" }
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val data = s(MediaStore.Audio.Media.DATA)
                    // The app folder is scanned on its own.
                    if (data != null && data.startsWith(folder.path)) continue
                    val track = c.getInt(c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK))
                    out.add(Song(ContentUris.withAppendedId(uri, id).toString(), c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)),
                        s(MediaStore.Audio.Media.TITLE) ?: data?.substringAfterLast('/') ?: "Song $id", s(MediaStore.Audio.Media.ARTIST),
                        s("album_artist"), s(MediaStore.Audio.Media.ALBUM), s("genre"),
                        c.getLong(c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)).takeIf { it > 0 }?.let { it / 1000.0 },
                        (track % 1000).takeIf { it > 0 }, (track / 1000).takeIf { it > 0 },
                        c.getInt(c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)).takeIf { it > 0 },
                        data?.substringBeforeLast('/')?.substringAfterLast('/') ?: "Device"))
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("local", "MediaStore query failed", e)
        }
        return out
    }

    /** Copies picked files (content URIs) into the app's music folder. */
    fun importFiles(uris: List<Uri>) {
        Bg.load({
            for (uri in uris) {
                var name = "Imported song"
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { name = it }
                }
                val dir = File(folder, "Imported").also { it.mkdirs() }
                var target = File(dir, name)
                var n = 2
                while (target.exists()) target = File(dir, "${name.substringBeforeLast('.')} ${n++}.${name.substringAfterLast('.', "mp3")}")
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }) { rescan() }
    }

    fun delete(song: Song) {
        if (!song.path.startsWith("content://")) File(song.path).delete()
        rescan()
    }

    fun track(s: Song) = Track(source = TrackSource.Local(s.path), title = s.title, artist = s.artist ?: s.albumArtist, album = s.album,
        duration = s.duration, trackNumber = s.track, artworkKey = s.path)

    // MARK: Library

    override val id = "local"
    override val label = "On This Device"
    override val isConnected = true
    override val statusText = ""
    override val targetText = "Plays here"

    private fun playTracks(tracks: List<Track>, option: PlayOption): String {
        Main.post { player.add(tracks, option) }
        return "this device"
    }

    private val grouping: SongGrouping
        get() {
            val list = songs.map { s ->
                val t = track(s)
                val item = LibraryItem("local:${s.path}", s.title, listOfNotNull(s.artist, s.album).joinToString(" · "), "track", s.duration,
                    image = { ImageRef.Embedded(s.path) }, play = { o -> playTracks(listOf(t), o) }, track = t)
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
                "Folders" to { folders(g) },
            )
        }

    private fun folders(g: SongGrouping): LibraryView {
        val byFolder = songs.zip(g.songs).groupBy { it.first.folder }
        val items = byFolder.keys.sortedWith(String.CASE_INSENSITIVE_ORDER).map { name ->
            val list = byFolder.getValue(name).map { it.second }
            LibraryItem("folder:$name", name, "${list.size} songs", "folder", open = { g.songList(name, list, sorted = true) },
                play = { o -> playTracks(SongGrouping.ordered(list).mapNotNull { it.item.track }, o) })
        }
        return LibraryView("Folders", LibraryLayout.LIST, load = { o -> items.drop(o).take(LIBRARY_PAGE_SIZE) })
    }

    override fun search(query: String, within: String?) = grouping.search(query)
}
