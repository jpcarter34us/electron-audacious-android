package io.github.jpcarter34us.audacious.player

import android.content.Context
import android.util.Log
import io.github.jpcarter34us.audacious.engine.LiveFeed
import io.github.jpcarter34us.audacious.engine.PlayItem
import io.github.jpcarter34us.audacious.engine.PlaybackEngine
import io.github.jpcarter34us.audacious.library.ImageRef
import io.github.jpcarter34us.audacious.util.Main
import io.github.jpcarter34us.audacious.util.Prefs
import io.github.jpcarter34us.audacious.util.Signal
import io.github.jpcarter34us.audacious.util.UserError
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The app's playback state and playlist: what libaudcore's drct.h and
 * playlist.h give the Audacious interfaces (play, pause, seek, next,
 * volume, shuffle, repeat), driving the PlaybackEngine. Ported from the iOS
 * port's Player.swift. Lives on the main thread; `changed` fires on every
 * state change, `tick` four times a second while playing.
 */
class Player(private val context: Context) {
    enum class State { STOPPED, PLAYING, PAUSED }

    val changed = Signal()
    val tick = Signal()

    var tracks: List<Track> = emptyList()
        private set
    /** The track being heard (or paused, or about to start). */
    var currentID: String? = null
        private set
    var state = State.STOPPED
        private set
    var isBuffering = false
        private set
    var time = 0.0
        private set
    var duration: Double? = null
        private set
    var lastError: String? = null

    var volume: Double = Prefs.float("player.volume", 1f).toDouble()
        set(v) {
            field = v.coerceIn(0.0, 1.0)
            engine.volume = field.toFloat()
            Prefs.set("player.volume", field.toFloat())
            changed.emit()
        }

    var shuffle: Boolean = Prefs.bool("player.shuffle")
        set(v) {
            field = v
            if (v) reshuffle()
            Prefs.set("player.shuffle", v)
            updateUpcoming()
            changed.emit()
        }

    var repeatMode: RepeatMode = try {
        RepeatMode.valueOf(Prefs.string("player.repeat", "OFF"))
    } catch (_: Exception) {
        RepeatMode.OFF
    }
        set(v) {
            field = v
            Prefs.set("player.repeat", v.name)
            updateUpcoming()
            changed.emit()
        }

    /** Builds the media location for a track, by TrackSource.kind (may block). */
    private val resolvers = HashMap<String, (Track) -> MediaLocation>()

    /** Cover art for notifications and Now Playing, when a source provides it. */
    var artworkProvider: ((Track) -> ImageRef?)? = null
    /** Called when a track starts being heard (Subsonic scrobbling). */
    var onTrackStarted: ((Track) -> Unit)? = null
    /** Asks the system side (PlaybackService) to keep playing in the background. */
    var onActivate: () -> Unit = {}

    private val engine = PlaybackEngine(context)
    private var decodingID: String? = null
    private var serial = 0
    private var shuffleOrder = ArrayList<String>()
    private val storeFile = File(context.filesDir, "playlist.json")

    init {
        engine.volume = volume.toFloat()
        resolvers["url"] = { t ->
            val url = (t.source as TrackSource.Url).url
            if (url.startsWith("/") || url.startsWith("content://")) MediaLocation.Local(url)
            else MediaLocation.Http(url, formatHint = url.substringAfterLast('/').substringAfterLast('.', "").takeIf { it.length in 2..4 })
        }
        resolvers["local"] = { t -> MediaLocation.Local((t.source as TrackSource.Local).path) }
        engine.onEvent = { event, s -> Main.later { handle(event, s) } }
        load()
        reshuffle()
        // After construction: the ticker is declared below.
        Main.later { startTicker() }
    }

    fun register(kind: String, resolver: (Track) -> MediaLocation) {
        resolvers[kind] = resolver
    }

    val currentTrack: Track?
        get() = liveTrack ?: currentID?.let { id -> tracks.firstOrNull { it.id == id } }

    val currentIndex: Int? get() = currentID?.let { id -> tracks.indexOfFirst { it.id == id }.takeIf { it >= 0 } }

    // MARK: Transport (aud_drct_*)

    fun play(id: String? = null, start: Double = 0.0) {
        if (live != null) {
            // A song from the playlist takes over from the live source.
            if (id == null) return liveCommand("play")
            leaveLive(pauseSource = true)
        }
        val track = (id ?: currentID ?: tracks.firstOrNull()?.id)?.let { i -> tracks.firstOrNull { it.id == i } } ?: return
        lastError = null
        onActivate()
        currentID = track.id
        decodingID = track.id
        time = start
        duration = track.duration
        state = State.PLAYING
        serial = engine.play(item(track), start)
        updateUpcoming()
        changed.emit()
    }

    fun togglePause() {
        if (live != null) return liveCommand(if (state == State.PLAYING) "pause" else "play")
        when (state) {
            State.STOPPED -> play()
            State.PLAYING -> pause()
            State.PAUSED -> resume()
        }
    }

    fun pause() {
        if (live != null) return liveCommand("pause")
        if (state != State.PLAYING) return
        engine.setPaused(true)
        state = State.PAUSED
        save()
        changed.emit()
    }

    fun resume() {
        if (live != null) return liveCommand("play")
        if (state != State.PAUSED) return play()
        onActivate()
        engine.setPaused(false)
        state = State.PLAYING
        changed.emit()
    }

    fun stop() {
        if (live != null) return liveCommand("stop")
        serial = engine.stop()
        state = State.STOPPED
        time = 0.0
        isBuffering = false
        save()
        changed.emit()
    }

    fun next() {
        if (live != null) return liveCommand("next")
        val id = neighbour(currentID, 1, repeatMode == RepeatMode.ALL || shuffle) ?: return
        if (state == State.STOPPED) {
            currentID = id
            changed.emit()
            return
        }
        play(id)
    }

    /** Back to the start of the song, or the previous one in its first seconds. */
    fun previous() {
        if (live != null) return liveCommand("previous")
        if (time > 3 && state != State.STOPPED) return seek(0.0)
        val id = neighbour(currentID, -1, repeatMode == RepeatMode.ALL || shuffle) ?: return seek(0.0)
        if (state == State.STOPPED) {
            currentID = id
            changed.emit()
            return
        }
        play(id)
    }

    fun seek(seconds: Double) {
        if (state == State.STOPPED || live != null) return
        val d = duration
        val target = maxOf(0.0, if (d != null) minOf(seconds, maxOf(0.0, d - 0.5)) else seconds)
        time = target
        engine.seek(target)
        changed.emit()
    }

    // MARK: Playlist

    fun add(new: List<Track>, option: PlayOption) {
        if (new.isEmpty()) return
        val copies = new.map { it.duplicated() }
        val list = ArrayList(tracks)
        when (option) {
            PlayOption.ADD -> list.addAll(copies)
            PlayOption.NEXT, PlayOption.PLAY -> list.addAll(currentIndex?.plus(1) ?: list.size, copies)
        }
        tracks = list
        if (shuffle) {
            val after = (decodingID?.let { shuffleOrder.indexOf(it) } ?: -1) + 1
            if (option == PlayOption.ADD) shuffleOrder.addAll(copies.map { it.id }.shuffled())
            else shuffleOrder.addAll(minOf(maxOf(after, 0), shuffleOrder.size), copies.map { it.id })
        }
        if (option == PlayOption.PLAY || (currentID == null && state == State.STOPPED)) {
            if (option == PlayOption.PLAY || state == State.STOPPED) play(copies[0].id)
        }
        updateUpcoming()
        save()
        changed.emit()
    }

    fun remove(indices: Collection<Int>) {
        val removed = indices.mapNotNull { tracks.getOrNull(it)?.id }.toSet()
        if (currentID in removed) {
            stop()
            currentID = null
        }
        tracks = tracks.filter { it.id !in removed }
        shuffleOrder.removeAll { it in removed }
        updateUpcoming()
        save()
        changed.emit()
    }

    fun move(from: Int, to: Int) {
        if (from !in tracks.indices || to !in tracks.indices || from == to) return
        val list = ArrayList(tracks)
        val t = list.removeAt(from)
        list.add(to, t)
        tracks = list
        updateUpcoming()
        save()
        changed.emit()
    }

    fun clear() {
        stop()
        tracks = emptyList()
        shuffleOrder.clear()
        currentID = null
        duration = null
        updateUpcoming()
        save()
        changed.emit()
    }

    // MARK: Order

    private fun reshuffle() {
        val order = ArrayList(tracks.map { it.id }.shuffled())
        val i = currentID?.let { order.indexOf(it) } ?: -1
        if (i > 0) java.util.Collections.swap(order, 0, i)
        shuffleOrder = order
    }

    private fun neighbour(id: String?, step: Int, wrap: Boolean): String? {
        val order = if (shuffle) shuffleOrder else tracks.map { it.id }
        if (order.isEmpty()) return null
        val i = id?.let { order.indexOf(it) } ?: -1
        if (i < 0) return order.first()
        val j = i + step
        if (j in order.indices) return order[j]
        if (!wrap) return null
        if (shuffle && step > 0) {
            // A new round in a new order.
            reshuffle()
            return shuffleOrder.firstOrNull { it != id } ?: shuffleOrder.firstOrNull()
        }
        return order[(j + order.size) % order.size]
    }

    /** Tells the engine what follows the song it is decoding. */
    private fun updateUpcoming() {
        val d = decodingID
        if (state == State.STOPPED || d == null) {
            engine.setUpcoming(null)
            return
        }
        val nextID = if (repeatMode == RepeatMode.ONE) d else neighbour(d, 1, repeatMode == RepeatMode.ALL)
        engine.setUpcoming(nextID?.let { n -> tracks.firstOrNull { it.id == n } }?.let { item(it) })
    }

    private fun item(track: Track): PlayItem {
        val resolver = resolvers[track.source.kind]
        return PlayItem(track) {
            resolver?.invoke(track) ?: throw UserError("Connect to the library this song is from first")
        }
    }

    // MARK: Engine events

    private fun handle(event: PlaybackEngine.Event, s: Int) {
        Log.i("player", "engine event $event #$s")
        if (s != serial) return
        when (event) {
            is PlaybackEngine.Event.Advanced -> {
                decodingID = event.id
                updateUpcoming()
            }
            is PlaybackEngine.Event.Started -> {
                if (state == State.STOPPED) return
                if (currentID != event.id) {
                    currentID = event.id
                    time = 0.0
                }
                currentTrack?.let {
                    duration = it.duration
                    onTrackStarted?.invoke(it)
                }
                save()
            }
            PlaybackEngine.Event.Ended -> {
                state = State.STOPPED
                time = 0.0
                isBuffering = false
                save()
            }
            is PlaybackEngine.Event.Failed -> {
                val name = tracks.firstOrNull { it.id == event.id }?.title ?: "song"
                lastError = "$name: ${event.message}"
            }
            is PlaybackEngine.Event.Buffering -> isBuffering = event.on
        }
        changed.emit()
    }

    private val ticker = object : Runnable {
        override fun run() {
            onTick()
            Main.delay(250, this)
        }
    }

    private fun startTicker() = Main.delay(250, ticker)

    private fun onTick() {
        val l = live
        if (l != null) {
            if (!l.playing) return
            val t = l.progress + (System.currentTimeMillis() - l.progressAt) / 1000.0 * l.speed
            time = l.duration?.let { minOf(t, it) } ?: t
            tick.emit()
            return
        }
        if (state == State.STOPPED) return
        val p = engine.position() ?: return
        if (p.trackID == currentID) {
            time = p.time
            p.duration?.takeIf { it > 0 }?.let { duration = it }
            tick.emit()
        }
    }

    // MARK: Live source (Sendspin)

    /** What a live source (Music Assistant through Sendspin) is playing. */
    data class LiveInfo(
        val source: String,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val artworkURL: String? = null,
        /** Seconds into the track at `progressAt` (ms), advancing at `speed`. */
        val progress: Double = 0.0,
        val progressAt: Long = System.currentTimeMillis(),
        val speed: Double = 1.0,
        val duration: Double? = null,
        val playing: Boolean = false,
    )

    /** Set while a live source plays here; transport controls go to it. */
    var live: LiveInfo? = null
        private set
    private var liveTrack: Track? = null
    private var liveFeed: LiveFeed? = null
    private var liveTrackID = UUID.randomUUID().toString()
    /** Sends a transport command (play, pause, next, ...) to the live source. */
    var liveController: ((String) -> Unit)? = null

    val isLive get() = live != null

    private fun liveCommand(command: String) {
        liveController?.invoke(command)
    }

    /** The live source started (or resumed) a stream into `feed`. */
    fun beginLive(feed: LiveFeed, info: LiveInfo) {
        onActivate()
        if (live == null && state != State.STOPPED) {
            // Leave the playlist where it was.
            serial = engine.stop()
        }
        if (liveFeed !== feed) {
            liveFeed?.stop()
            liveFeed = feed
            serial = engine.playLive(feed)
        }
        decodingID = null
        engine.setUpcoming(null)
        isBuffering = false
        lastError = null
        state = State.PLAYING
        live = info
        updateLive(info)
    }

    /** The source paused or stopped its stream; it stays in charge. */
    fun pauseLive() {
        if (live == null) return
        state = State.PAUSED
        changed.emit()
    }

    fun updateLive(info: LiveInfo) {
        if (live == null && liveFeed == null) return
        live = info
        if (liveTrack?.artworkKey != info.artworkURL) liveTrackID = UUID.randomUUID().toString()
        liveTrack = Track(liveTrackID, TrackSource.Url(info.artworkURL ?: ""), info.title ?: info.source, info.artist ?: info.source,
            info.album, info.duration, artworkKey = info.artworkURL)
        duration = info.duration
        time = if (info.playing) info.progress + (System.currentTimeMillis() - info.progressAt) / 1000.0 * info.speed else info.progress
        if (state == State.PAUSED && info.playing) state = State.PLAYING
        changed.emit()
    }

    /** The live source's own volume and mute, on top of the player volume. */
    fun setLiveGain(gain: Float) {
        engine.gain = gain
    }

    /** The live source went away (disconnected or turned off). */
    fun endLive() {
        if (live == null && liveFeed == null) return
        leaveLive(pauseSource = false)
        serial = engine.stop()
        state = State.STOPPED
        time = 0.0
        duration = currentID?.let { id -> tracks.firstOrNull { it.id == id } }?.duration
        changed.emit()
    }

    private fun leaveLive(pauseSource: Boolean) {
        if (pauseSource) liveCommand("pause")
        liveFeed?.stop()
        liveFeed = null
        live = null
        liveTrack = null
        engine.gain = 1f
    }

    // MARK: Persistence

    private fun load() {
        try {
            val o = JSONObject(storeFile.readText())
            val list = o.optJSONArray("tracks") ?: JSONArray()
            tracks = (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let { t -> Track.fromJson(t) } }
            currentID = o.optString("current", "").ifEmpty { null }
            duration = currentTrack?.duration
        } catch (_: Exception) {
        }
    }

    private val saver = Runnable {
        val o = JSONObject().put("tracks", JSONArray().also { a -> tracks.forEach { a.put(it.toJson()) } })
            .put("current", currentID ?: "").put("time", time)
        val text = o.toString()
        Thread {
            try {
                val tmp = File(storeFile.path + ".tmp")
                tmp.writeText(text)
                tmp.renameTo(storeFile)
            } catch (_: Exception) {
            }
        }.start()
    }

    /** Saves the playlist soon (coalescing bursts of changes). */
    fun save() {
        Main.cancel(saver)
        Main.delay(500, saver)
    }
}
