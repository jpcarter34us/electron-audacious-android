package io.github.jpcarter34us.audacious.engine

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

/**
 * Audio from a live source (Sendspin) on its way to the PlaybackEngine:
 * decoded chunks, each stamped with the time its first frame should be
 * heard on SendspinClock. The source pushes from its own thread; the
 * engine's decoding thread takes them and writes each into the output when
 * it is due (see PlaybackEngine.serviceLive).
 */
class LiveFeed {
    class Chunk(
        val samples: FloatArray, // interleaved
        val channels: Int,
        val rate: Int,
        /** SendspinClock µs at which the first frame should be heard. */
        val playAt: Long,
    )

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val chunks = ArrayList<Chunk>()
    private var clearRequested = false
    private var stopped = false

    fun push(chunk: Chunk) = lock.withLock {
        // Arrives in order; keep it sorted anyway.
        var i = chunks.size
        while (i > 0 && chunks[i - 1].playAt > chunk.playAt) i--
        chunks.add(i, chunk)
        cond.signalAll()
    }

    /** Seek, track change or stop on the server: drop everything not yet
     *  heard, including what is in the output. */
    fun clear() = lock.withLock {
        chunks.clear()
        clearRequested = true
        cond.signalAll()
    }

    /** The source went away; the engine leaves live mode. */
    fun stop() = lock.withLock {
        chunks.clear()
        stopped = true
        cond.signalAll()
    }

    val isStopped: Boolean get() = lock.withLock { stopped }

    /** Engine side: whether a clear was asked for since the last call. */
    fun takeClear(): Boolean = lock.withLock {
        val requested = clearRequested
        clearRequested = false
        requested
    }

    /** Engine side: the next chunk, waiting up to `timeoutMs` for one. */
    fun next(timeoutMs: Long): Chunk? = lock.withLock {
        if (chunks.isEmpty() && !stopped && !clearRequested) cond.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (chunks.isEmpty() || clearRequested) null else chunks.removeAt(0)
    }

    val queuedSeconds: Double
        get() = lock.withLock { chunks.sumOf { it.samples.size.toDouble() / maxOf(1, it.channels) / maxOf(1, it.rate) } }
}
