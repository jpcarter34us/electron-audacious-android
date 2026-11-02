package io.github.jpcarter34us.audacious.library

import io.github.jpcarter34us.audacious.player.PlayOption
import io.github.jpcarter34us.audacious.player.Track
import io.github.jpcarter34us.audacious.util.Signal

/**
 * The library browser's model, as in ElectronPlayer's src/renderer/library.ts
 * and the iOS port's Library.swift: each source (this device, Subsonic,
 * DAAP, Music Assistant) offers sections; a section is a view of items
 * loaded a page at a time; items open into further views and can be
 * played. Loading and playing block (they run on a background thread).
 */
enum class LibraryLayout { GRID, LIST }

/** How to fetch a cover: a URL with headers, or a local file's embedded art. */
sealed class ImageRef {
    data class Remote(val url: String, val headers: Map<String, String> = emptyMap()) : ImageRef()
    data class Embedded(val path: String) : ImageRef()

    val key: String get() = when (this) {
        is Remote -> url
        is Embedded -> "embedded:$path"
    }
}

class LibraryItem(
    val id: String,
    val name: String,
    val subtitle: String? = null,
    /** artist, album, track, playlist, radio, folder, ... */
    val kind: String,
    val duration: Double? = null,
    /** Resolves the cover (may block); null for none. */
    val image: (() -> ImageRef?)? = null,
    val open: (() -> LibraryView)? = null,
    /** Plays the item; returns where it plays, for the confirmation. */
    val play: ((PlayOption) -> String)? = null,
    /** The playlist entry, for items that are songs played in this app. */
    val track: Track? = null,
)

class LibrarySection(val title: String, val items: List<LibraryItem>, val layout: LibraryLayout)

class LibraryView(
    val title: String,
    val layout: LibraryLayout,
    /** Items from `offset`; an empty page ends the list. */
    val load: (offset: Int) -> List<LibraryItem> = { emptyList() },
    /** Instead of `load`: several titled groups (an artist's albums and tracks). */
    val sections: (() -> List<LibrarySection>)? = null,
    /** Play every song loaded so far. */
    val playAll: ((PlayOption, List<LibraryItem>) -> String)? = null,
)

/** Page size for paged views. */
const val LIBRARY_PAGE_SIZE = 100

interface LibrarySource {
    val id: String
    val label: String
    val isConnected: Boolean
    /** Shown when not connected. */
    val statusText: String
    val sections: List<Pair<String, () -> LibraryView>>
    /** `within`: the section open while searching (Subsonic searches songs
     *  only while Songs is open). */
    fun search(query: String, within: String?): LibraryView
    /** Where items play, for the browser's header. */
    val targetText: String
    val changed: Signal
}

/**
 * Library views over a flat song list, for sources that cannot browse on
 * the server (DAAP, this device): artists, albums and genres are grouped
 * here, as ElectronPlayer's DAAP browser does.
 */
class SongGrouping(val songs: List<Song>, val playSongs: (List<LibraryItem>, PlayOption) -> String) {
    class Song(
        val item: LibraryItem,
        val artist: String?,
        val albumArtist: String?,
        val album: String?,
        val genre: String?,
        val track: Int?,
        val disc: Int?,
        val year: Int?,
    )

    companion object {
        fun ordered(songs: List<Song>): List<Song> =
            songs.sortedWith(compareBy<Song>({ it.disc ?: 0 }, { it.track ?: 0 }, { it.item.name.lowercase() }))

        private val caseless = String.CASE_INSENSITIVE_ORDER
    }

    private fun page(items: List<LibraryItem>, offset: Int) = items.drop(offset).take(LIBRARY_PAGE_SIZE)

    fun songList(title: String, list: List<Song>, sorted: Boolean = false): LibraryView {
        val items = (if (sorted) ordered(list) else list).map { it.item }
        return LibraryView(title, LibraryLayout.LIST, load = { page(items, it) }, playAll = { option, _ -> playSongs(items, option) })
    }

    fun allSongs() = songList("Songs", songs.sortedWith(compareBy(caseless) { it.item.name }))

    private fun groupItem(name: String, kind: String, subtitle: String?, list: List<Song>, image: (() -> ImageRef?)?,
                          open: () -> LibraryView) =
        LibraryItem("$kind:$name", name, subtitle, kind, image = image, open = open,
            play = { option -> playSongs(ordered(list).map { it.item }, option) })

    fun albums(subset: List<Song>? = null, title: String = "Albums"): LibraryView {
        val groups = (subset ?: songs).groupBy { it.album ?: "Unknown Album" }
        val items = groups.keys.sortedWith(caseless).map { album ->
            val list = groups.getValue(album)
            val artist = list.first().albumArtist ?: list.first().artist
            val year = list.firstNotNullOfOrNull { it.year }
            groupItem(album, "album", listOfNotNull(artist, year?.toString()).joinToString(" · "), list, list.first().item.image) {
                songList(album, list, sorted = true)
            }
        }
        return LibraryView(title, LibraryLayout.GRID, load = { page(items, it) })
    }

    fun artists(): LibraryView {
        val groups = songs.groupBy { it.albumArtist ?: it.artist ?: "Unknown Artist" }
        val items = groups.keys.sortedWith(caseless).map { artist ->
            val list = groups.getValue(artist)
            val albumCount = list.mapNotNull { it.album }.toSet().size
            groupItem(artist, "artist", "$albumCount album${if (albumCount == 1) "" else "s"}", list, list.first().item.image) {
                albums(list, artist)
            }
        }
        return LibraryView("Artists", LibraryLayout.GRID, load = { page(items, it) })
    }

    fun genres(): LibraryView {
        val groups = songs.filter { it.genre != null }.groupBy { it.genre!! }
        val items = groups.keys.sortedWith(caseless).map { genre ->
            val list = groups.getValue(genre)
            groupItem(genre, "genre", "${list.size} songs", list, null) { albums(list, genre) }
        }
        return LibraryView("Genres", LibraryLayout.LIST, load = { page(items, it) })
    }

    fun search(query: String): LibraryView {
        val q = query.lowercase()
        fun match(s: String?) = s?.lowercase()?.contains(q) ?: false
        val found = songs.filter { match(it.item.name) || match(it.artist) || match(it.album) || match(it.albumArtist) }
        return LibraryView("Search: $query", LibraryLayout.GRID, sections = {
            val artistNames = found.mapNotNull { s -> listOfNotNull(s.albumArtist, s.artist).firstOrNull { match(it) } }.toSortedSet()
            val artistItems = artistNames.map { name ->
                val list = songs.filter { it.artist == name || it.albumArtist == name }
                groupItem(name, "artist", null, list, list.firstOrNull()?.item?.image) { albums(list, name) }
            }
            val albumNames = found.mapNotNull { it.album }.filter { match(it) }.toSortedSet()
            val albumItems = albumNames.map { name ->
                val list = songs.filter { it.album == name }
                groupItem(name, "album", list.firstOrNull()?.let { it.albumArtist ?: it.artist }, list, list.firstOrNull()?.item?.image) {
                    songList(name, list, sorted = true)
                }
            }
            listOf(LibrarySection("Artists", artistItems, LibraryLayout.GRID), LibrarySection("Albums", albumItems, LibraryLayout.GRID),
                LibrarySection("Songs", found.take(200).map { it.item }, LibraryLayout.LIST))
        })
    }
}
