package io.github.jpcarter34us.audacious.engine

import android.content.Context
import android.os.Process
import io.github.jpcarter34us.audacious.player.MediaLocation
import io.github.jpcarter34us.audacious.player.Track
import io.github.jpcarter34us.audacious.sendspin.SendspinClock
import io.github.jpcarter34us.audacious.util.text
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A song as the engine plays it: the playlist entry and how to fetch it (may block). */
class PlayItem(val track: Track, val resolve: () -> MediaLocation)

/**
 * What libaudcore's playback.cc and output.cc do, on one decoding thread,
 * ported from the iOS port's PlaybackEngine.swift: open the song, decode
 * it, run it through the effect chain and the equalizer (AudCore), and
 * write it to the output ring. Songs follow each other without a gap when
 * the format allows; the effects see the end of each song (finish) as
 * upstream, so crossfade works.
 *
 * Live mode (playLive) takes timestamped audio from a LiveFeed (Sendspin)
 * instead of a decoder and writes each chunk into the output when it is
 * due, so it is heard at the time the server asked for, through the same
 * effects, equalizer and visualization.
 */
class PlaybackEngine(private val context: Context) {
    sealed class Event {
        /** The decoder moved on to this track (it is not heard yet). */
        data class Advanced(val id: String) : Event()
        /** This track is now being heard. */
        data class Started(val id: String) : Event()
        /** The last song finished playing. */
        object Ended : Event()
        data class Failed(val id: String, val message: String) : Event()
        data class Buffering(val on: Boolean) : Event()
    }

    private sealed class Command {
        class Play(val item: PlayItem, val start: Double, val paused: Boolean, val serial: Int) : Command()
        class Seek(val seconds: Double) : Command()
        class Stop(val serial: Int) : Command()
        class Live(val feed: LiveFeed, val serial: Int) : Command()
    }

    /** Called on the decoding thread, with the serial of the play or stop
     *  command the event follows from, so the receiver can drop stale ones. */
    var onEvent: (Event, Int) -> Unit = { _, _ -> }

    private val output = AudioOutput(context)
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val commands = ArrayList<Command>()

    // Guarded by lock.
    private var upcoming: PlayItem? = null
    private var interrupt = 0 // bumped by commands so blocking work gives up
    private var paused = false
    private val markers = ArrayList<Marker>()
    private var requested = 0 // serial of the latest play or stop

    private class Marker(val generation: Int, val frame: Long, val trackID: String, val start: Double, val rate: Int,
                         val duration: Double?, var announced: Boolean = false)

    // Decoding thread only.
    private var decoder: AudioDecoder? = null
    private var current: PlayItem? = null
    private var inputChannels = 0
    private var inputRate = 0
    private var serial = 0
    private var live: LiveFeed? = null
    private var liveIdleSince = 0L
    private var latency = 0.0
    private var latencyAt = 0L

    // MARK: Commands (any thread)

    var volume: Float
        get() = output.volume
        set(v) {
            output.volume = v
        }

    /** Gain on top of `volume` (the live source's own volume and mute). */
    var gain: Float
        get() = output.gain
        set(v) {
            output.gain = v
        }

    /** Returns the serial its events will carry. */
    fun play(item: PlayItem, start: Double = 0.0, paused: Boolean = false) = send { Command.Play(item, start, paused, it) }

    /** Plays a live feed until another play or stop (or the feed stops). */
    fun playLive(feed: LiveFeed) = send { Command.Live(feed, it) }

    fun seek(seconds: Double) {
        send { Command.Seek(seconds) }
    }

    fun stop() = send { Command.Stop(it) }

    fun setPaused(paused: Boolean) {
        lock.withLock {
            this.paused = paused
            cond.signalAll()
        }
        output.setPaused(paused)
    }

    /** The song to continue with when the current one ends (null: stop). */
    fun setUpcoming(item: PlayItem?) = lock.withLock {
        upcoming = item
        cond.signalAll()
    }

    class Position(val trackID: String, val time: Double, val duration: Double?)

    /** Track being heard and the position in it, in seconds. */
    fun position(): Position? = lock.withLock {
        val heard = output.consumed()
        val gen = output.generation
        val m = markers.lastOrNull { it.generation == gen && it.frame <= heard } ?: return null
        Position(m.trackID, m.start + (heard - m.frame).toDouble() / m.rate, m.duration)
    }

    private fun send(make: (Int) -> Command): Int = lock.withLock {
        val probe = make(0)
        val command = if (probe is Command.Seek) probe else {
            // A new play or stop makes earlier queued commands pointless.
            requested++
            commands.clear()
            make(requested)
        }
        commands.add(command)
        interrupt++
        cond.signalAll()
        requested
    }

    private fun emit(event: Event) = onEvent(event, serial)

    // MARK: Decoding thread

    private fun run() {
        val maxFrames = 4096
        val scratch = FloatArray(maxFrames * 16)
        while (true) {
            val pending: List<Command>
            lock.withLock {
                while (commands.isEmpty() && decoder == null && live == null) {
                    announce()
                    cond.await(100, TimeUnit.MILLISECONDS)
                }
                pending = ArrayList(commands)
                commands.clear()
            }
            for (c in pending) {
                try {
                    handle(c)
                } catch (e: Throwable) {
                    android.util.Log.e("engine", "command failed", e)
                }
            }

            val feed = live
            if (feed != null) {
                serviceLive(feed, scratch, maxFrames)
                continue
            }

            val d = decoder ?: continue
            val item = current ?: continue
            try {
                val frames = d.read(scratch, maxFrames)
                if (frames > 0) process(scratch, frames * d.channels) else songEnded()
            } catch (e: Throwable) {
                if (hasCommands()) continue
                emit(Event.Failed(item.track.id, e.text))
                decoder?.cancel()
                decoder = null
                // Carry on with the next song, as upstream does.
                songEnded(failed = true)
            }
            maybePrefetch()
        }
    }

    private fun hasCommands() = lock.withLock { commands.isNotEmpty() }
    private fun interruptCount() = lock.withLock { interrupt }
    private fun isPaused() = lock.withLock { paused }

    private fun handle(command: Command) {
        when (command) {
            is Command.Play -> {
                serial = command.serial
                live = null
                closeDecoder()
                clearOutput()
                Native.pipelineFlush(false)
                lock.withLock { paused = command.paused }
                output.setPaused(command.paused)
                open(command.item, command.start, gapless = false)
            }
            is Command.Seek -> {
                val d = decoder ?: return
                val item = current ?: return
                emit(Event.Buffering(true))
                try {
                    d.seek(command.seconds)
                    Native.pipelineFlush(true)
                    clearOutput()
                    mark(item.track, command.seconds, d.duration ?: item.track.duration)
                } catch (e: Throwable) {
                    if (!hasCommands()) emit(Event.Failed(item.track.id, e.text))
                }
                emit(Event.Buffering(false))
            }
            is Command.Stop -> {
                serial = command.serial
                live = null
                closeDecoder()
                clearOutput()
                Native.pipelineFlush(true)
                current = null
                lock.withLock { markers.clear() }
                output.stopEngine()
            }
            is Command.Live -> {
                serial = command.serial
                closeDecoder()
                current = null
                clearOutput()
                Native.pipelineFlush(true)
                lock.withLock {
                    markers.clear()
                    paused = false
                }
                output.setPaused(false)
                inputChannels = 0
                inputRate = 0
                live = command.feed
                liveIdleSince = 0
                command.feed.takeClear()
            }
        }
    }

    private fun closeDecoder() {
        decoder?.cancel()
        decoder = null
    }

    private fun clearOutput() {
        output.clear()
        Native.visClear()
    }

    /** Opens a song. Gapless: the previous one just ended and its tail is
     *  still in the output, so the chain keeps running if the format matches. */
    private fun open(item: PlayItem, start: Double, gapless: Boolean): Boolean {
        current = item
        emit(Event.Advanced(item.track.id))
        val attempt = interruptCount()
        val opened: AudioDecoder = takePrefetched(item.track.id) ?: run {
            emit(Event.Buffering(true))
            try {
                makeDecoder(item)
            } catch (e: Throwable) {
                emit(Event.Buffering(false))
                if (interruptCount() != attempt) return false
                emit(Event.Failed(item.track.id, e.text))
                current = null
                // Try the next song.
                takeUpcoming()?.let { return open(it, 0.0, gapless) }
                drainAndEnd()
                return false
            }.also { emit(Event.Buffering(false)) }
        }
        if (interruptCount() != attempt) {
            opened.cancel()
            return false
        }
        if (start > 0) {
            try {
                opened.seek(start)
            } catch (_: Throwable) {
            }
        }
        decoder = opened

        val ch = opened.channels
        val rate = opened.sampleRate
        if (ch != inputChannels || rate != inputRate || !gapless) {
            if (gapless && inputChannels != 0) {
                // Format change between songs: drain the effects first.
                finishChain(endOfPlaylist = false)
            }
            Native.pipelineStart(ch, rate)
            inputChannels = ch
            inputRate = rate
            reconfigureOutputIfNeeded()
        }
        mark(item.track, start, opened.duration ?: item.track.duration)
        if (!isPaused()) output.startEngine()
        return true
    }

    private fun makeDecoder(item: PlayItem): AudioDecoder = Decoders.open(context, item.resolve(), item.track.id)

    private fun takeUpcoming(): PlayItem? = lock.withLock {
        val next = upcoming
        upcoming = null
        next
    }

    private fun songEnded(failed: Boolean = false) {
        closeDecoder()
        val next = takeUpcoming()
        if (next != null) {
            if (!failed) finishSong()
            open(next, 0.0, gapless = !failed)
        } else {
            finishChain(endOfPlaylist = true)
            drainAndEnd()
        }
    }

    /** End of a song with another to follow: effects drain (finish). */
    private fun finishSong() {
        val n = Native.pipelineFinish(false)
        if (n > 0) write(n)
    }

    /** End of the last song: finish twice, as upstream. */
    private fun finishChain(endOfPlaylist: Boolean) {
        var n = Native.pipelineFinish(false)
        if (n > 0) write(n)
        if (endOfPlaylist) {
            n = Native.pipelineFinish(true)
            if (n > 0) write(n)
        }
    }

    /** Waits until everything written has been heard, then reports the end. */
    private fun drainAndEnd() {
        current = null
        val attempt = interruptCount()
        while (output.buffered() > 0 && interruptCount() == attempt) {
            lock.withLock {
                announce()
                cond.await(50, TimeUnit.MILLISECONDS)
            }
        }
        if (interruptCount() != attempt) return
        lock.withLock { markers.clear() }
        output.stopEngine()
        emit(Event.Ended)
    }

    private fun process(samples: FloatArray, count: Int) {
        val n = Native.pipelineProcess(samples, count)
        // An effect was switched on or off and changed the format.
        reconfigureOutputIfNeeded()
        if (n > 0) write(n)
    }

    private fun reconfigureOutputIfNeeded() {
        val ch = Native.pipelineChannels()
        val rate = Native.pipelineRate()
        if (ch <= 0 || rate <= 0 || (ch == output.channels && rate == output.sampleRate)) return
        // Let what is buffered at the old format play out first.
        val attempt = interruptCount()
        while (output.buffered() > 0 && interruptCount() == attempt && !isPaused()) Thread.sleep(10)
        val heard = position()
        if (!output.configure(ch, rate)) {
            current?.let { emit(Event.Failed(it.track.id, "This device cannot play $rate Hz, $ch-channel audio")) }
        } else if (isPaused()) {
            output.setPaused(true)
        } else if (current != null || live != null) {
            // A new player starts stopped; carry on playing through it.
            output.startEngine()
        }
        // Keep the clock running for the song being decoded.
        val c = current
        if (c != null && heard != null && heard.trackID == c.track.id) mark(c.track, heard.time, heard.duration)
    }

    /** Writes the last pipeline output (`samples` samples), waiting for room. */
    private fun write(samples: Int) {
        val ch = output.channels
        if (ch <= 0) return
        var frames = samples / ch
        var pos = 0
        val attempt = interruptCount()
        while (frames > 0) {
            val n = output.write(pos, frames)
            frames -= n
            pos += n * ch
            if (frames > 0) {
                lock.withLock {
                    announce()
                    if (interrupt != attempt) return
                    cond.await(20, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    /** Records that the frames written from now on belong to `track`. */
    private fun mark(track: Track, start: Double, duration: Double?) {
        if (output.handle == 0L) return
        lock.withLock {
            val m = Marker(output.generation, output.written(), track.id, start, output.sampleRate, duration)
            markers.removeAll { it.generation != output.generation || it.frame >= m.frame }
            markers.add(m)
            while (markers.size > 8) markers.removeAt(0)
        }
    }

    /** Reports tracks that have started to be heard. Call with the lock held. */
    private fun announce() {
        if (output.handle == 0L) return
        val heard = output.consumed()
        val i = markers.indexOfLast { it.generation == output.generation && it.frame <= heard }
        if (i >= 0 && !markers[i].announced) {
            markers[i].announced = true
            val id = markers[i].trackID
            lock.unlock()
            try {
                emit(Event.Started(id))
            } finally {
                lock.lock()
            }
        }
    }

    // MARK: Live

    private companion object {
        /** A chunk late by more than this (µs) is cut down to what is still on time. */
        const val LIVE_LATE_TOLERANCE = 20_000L
        /** Writing waits while the chunk would be heard earlier than this (µs). */
        const val LIVE_EARLY_TOLERANCE = 8_000L
    }

    private fun serviceLive(feed: LiveFeed, scratch: FloatArray, maxFrames: Int) {
        if (feed.isStopped) {
            live = null
            clearOutput()
            Native.pipelineFlush(true)
            output.stopEngine()
            emit(Event.Ended)
            return
        }
        if (feed.takeClear()) {
            clearOutput()
            Native.pipelineFlush(true)
        }
        val chunk = feed.next(50)
        if (chunk == null) {
            // Nothing coming: once what was written is heard, let the hardware sleep.
            if (output.buffered() == 0) {
                val now = System.currentTimeMillis()
                if (liveIdleSince == 0L) liveIdleSince = now else if (now - liveIdleSince > 2000) output.stopEngine()
            }
            return
        }
        liveIdleSince = 0
        if (chunk.channels != inputChannels || chunk.rate != inputRate) {
            Native.pipelineStart(chunk.channels, chunk.rate)
            inputChannels = chunk.channels
            inputRate = chunk.rate
            reconfigureOutputIfNeeded()
        }
        output.startEngine()

        // Through the chain in pieces the effects are used to.
        var offset = 0
        val total = chunk.samples.size / chunk.channels
        while (offset < total) {
            val frames = minOf(maxFrames, total - offset)
            System.arraycopy(chunk.samples, offset * chunk.channels, scratch, 0, frames * chunk.channels)
            val playAt = chunk.playAt + (offset.toDouble() / chunk.rate * 1_000_000).toLong()
            val n = Native.pipelineProcess(scratch, frames * chunk.channels)
            reconfigureOutputIfNeeded()
            if (n > 0 && !writeLive(n, playAt, feed)) return
            offset += frames
        }
    }

    /** Seconds from a frame entering the ring to it being heard, beyond what
     *  is buffered in the ring. */
    private fun outputLatency(): Double {
        val now = System.currentTimeMillis()
        if (now - latencyAt > 1000) {
            latency = output.latency
            latencyAt = now
        }
        return latency
    }

    /** Writes the last pipeline output so its first frame is heard at
     *  `start`: waits while that is still ahead, drops what is already too
     *  late. False when a command or a clear interrupted it. */
    private fun writeLive(samples: Int, start: Long, feed: LiveFeed): Boolean {
        val ch = output.channels
        val rate = output.sampleRate
        if (ch <= 0 || rate <= 0) return false
        var frames = samples / ch
        var pos = 0
        var playAt = start
        val attempt = interruptCount()
        while (frames > 0) {
            if (interruptCount() != attempt || feed.isStopped) return false
            val buffered = output.buffered().toDouble() / rate
            val heardAt = SendspinClock.nowMicros() + ((buffered + outputLatency()) * 1_000_000).toLong()
            val early = playAt - heardAt
            if (early > LIVE_EARLY_TOLERANCE) {
                lock.withLock {
                    if (interrupt == attempt) cond.await(minOf(early / 1000, 20L), TimeUnit.MILLISECONDS)
                }
                if (feed.takeClear()) {
                    clearOutput()
                    Native.pipelineFlush(true)
                    return false
                }
                continue
            }
            if (early < -LIVE_LATE_TOLERANCE) {
                val drop = minOf(frames, (-early / 1_000_000.0 * rate).toInt().coerceAtLeast(1))
                frames -= drop
                pos += drop * ch
                playAt += (drop.toDouble() / rate * 1_000_000).toLong()
                continue
            }
            val n = output.write(pos, frames)
            frames -= n
            pos += n * ch
            playAt += (n.toDouble() / rate * 1_000_000).toLong()
            if (frames > 0) {
                lock.withLock { if (interrupt == attempt) cond.await(10, TimeUnit.MILLISECONDS) }
            }
        }
        return true
    }

    // MARK: Prefetch

    private val prefetchLock = Any()
    private var prefetching: String? = null
    private var prefetched: Pair<String, AudioDecoder>? = null

    /** Starts opening the next song early, once the current one is fully
     *  downloaded, so a network song follows without a gap. */
    private fun maybePrefetch() {
        val d = decoder ?: return
        if (!d.isFullyLoaded) return
        val next = lock.withLock { upcoming } ?: return
        synchronized(prefetchLock) {
            if (prefetching == next.track.id) return
            prefetched?.second?.cancel()
            prefetched = null
            prefetching = next.track.id
        }
        Thread({
            val opened = try {
                makeDecoder(next)
            } catch (_: Throwable) {
                null
            } ?: return@Thread
            synchronized(prefetchLock) {
                if (prefetching == next.track.id) prefetched = next.track.id to opened else opened.cancel()
            }
        }, "prefetch").start()
    }

    /** The prefetched decoder for this track, if ready; any other is dropped. */
    private fun takePrefetched(id: String): AudioDecoder? = synchronized(prefetchLock) {
        val ready = if (prefetched?.first == id) prefetched?.second else null
        if (ready == null) prefetched?.second?.cancel()
        prefetched = null
        prefetching = null
        ready
    }

    // Last, so every field above is set before the thread runs.
    init {
        Decoders.clearCache(context)
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            run()
        }, "decoding").start()
    }
}
