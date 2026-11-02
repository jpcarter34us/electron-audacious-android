package io.github.jpcarter34us.audacious.player

import io.github.jpcarter34us.audacious.util.double
import io.github.jpcarter34us.audacious.util.int
import io.github.jpcarter34us.audacious.util.str
import org.json.JSONObject
import java.util.UUID

/**
 * Where a playlist entry's audio comes from. Network entries store the
 * server's id rather than a URL: Subsonic URLs carry credentials and DAAP
 * URLs a session id, so the URL is built when the song is played (the iOS
 * port's TrackSource, the counterpart of ElectronPlayer's loopback proxy).
 */
sealed class TrackSource {
    /** A song on the device: a file path or a content:// URI. */
    data class Local(val path: String) : TrackSource()
    data class Subsonic(val id: String, val suffix: String?) : TrackSource()
    /** share: "host:port"; format: file extension the server streams. */
    data class Daap(val share: String, val id: Int, val format: String?) : TrackSource()
    data class Url(val url: String) : TrackSource()

    val kind: String
        get() = when (this) {
            is Local -> "local"
            is Subsonic -> "subsonic"
            is Daap -> "daap"
            is Url -> "url"
        }

    fun toJson(): JSONObject = when (this) {
        is Local -> JSONObject().put("kind", kind).put("path", path)
        is Subsonic -> JSONObject().put("kind", kind).put("id", id).put("suffix", suffix)
        is Daap -> JSONObject().put("kind", kind).put("share", share).put("id", id).put("format", format)
        is Url -> JSONObject().put("kind", kind).put("url", url)
    }

    companion object {
        fun fromJson(o: JSONObject): TrackSource? = when (o.str("kind")) {
            "local" -> o.str("path")?.let { Local(it) }
            "subsonic" -> o.str("id")?.let { Subsonic(it, o.str("suffix")) }
            "daap" -> o.str("share")?.let { share -> o.int("id")?.let { Daap(share, it, o.str("format")) } }
            "url" -> o.str("url")?.let { Url(it) }
            else -> null
        }
    }
}

/** A playlist entry (libaudcore's playlist entry and its Tuple, reduced to
 *  what the app shows). */
data class Track(
    val id: String = UUID.randomUUID().toString(),
    val source: TrackSource,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    /** Seconds; null when unknown. */
    val duration: Double? = null,
    val trackNumber: Int? = null,
    /** Source-specific cover art key (Subsonic coverArt id, DAAP item id, a local path). */
    val artworkKey: String? = null,
) {
    /** A copy with a new identity, so the same song can be in a playlist twice. */
    fun duplicated() = copy(id = UUID.randomUUID().toString())

    val subtitle: String get() = listOfNotNull(artist, album).joinToString(" · ")

    fun toJson(): JSONObject = JSONObject().put("id", id).put("source", source.toJson()).put("title", title)
        .put("artist", artist).put("album", album).put("duration", duration).put("trackNumber", trackNumber)
        .put("artworkKey", artworkKey)

    companion object {
        fun fromJson(o: JSONObject): Track? {
            val source = o.optJSONObject("source")?.let { TrackSource.fromJson(it) } ?: return null
            return Track(o.str("id") ?: UUID.randomUUID().toString(), source, o.str("title") ?: "Untitled", o.str("artist"),
                o.str("album"), o.double("duration"), o.int("trackNumber"), o.str("artworkKey"))
        }
    }
}

/** How to fetch a track's audio, resolved just before it plays. */
sealed class MediaLocation {
    /** A file path or content:// URI on the device. */
    data class Local(val path: String) : MediaLocation()
    /** formatHint: file extension (mp3, flac, m4a, ...) when known. */
    data class Http(val url: String, val headers: Map<String, String> = emptyMap(), val formatHint: String? = null) : MediaLocation()
}

enum class RepeatMode { OFF, ALL, ONE }

enum class PlayOption(val verb: String) {
    PLAY("Playing"), NEXT("Playing next"), ADD("Added to the queue");

    val wire get() = name.lowercase()
}
