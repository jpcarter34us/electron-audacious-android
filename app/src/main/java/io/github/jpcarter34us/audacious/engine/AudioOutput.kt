package io.github.jpcarter34us.audacious.engine

import android.content.Context
import android.media.AudioManager
import android.os.Build

/**
 * The output plugin: an OpenSL ES player (jni/output.cc) whose callback
 * plays the ring the decoding thread fills. The ring runs at the effect
 * chain's output format; a new format means a new player and ring (a new
 * `generation`, so positions are not compared across them).
 */
class AudioOutput(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()

    /** Changes whenever the ring is replaced (a new output format). */
    @Volatile var generation = 0
        private set
    @Volatile var handle = 0L
        private set
    @Volatile var channels = 0
        private set
    @Volatile var sampleRate = 0
        private set
    private var running = false

    companion object {
        /** Seconds of audio buffered ahead of what is heard (upstream's output
         *  buffer, 500 ms by default; shorter so EQ and effect changes are
         *  heard sooner). */
        const val BUFFER_SECONDS = 0.35
    }

    var volume = 1f
        set(value) {
            field = value
            applyVolume()
        }

    /** A second factor on the volume (a live source's own volume). */
    var gain = 1f
        set(value) {
            field = value
            applyVolume()
        }

    private fun applyVolume() = synchronized(lock) {
        if (handle != 0L) Native.outputSetVolume(handle, volume * gain)
    }

    /** The device's preferred buffer size (Android 4.2+), for low latency. */
    private val framesPerBuffer: Int by lazy {
        if (Build.VERSION.SDK_INT >= 17) {
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        } else 0
    }

    /** Milliseconds the mixer and hardware add (AudioManager's hidden
     *  getOutputLatency, which every version has; a guess otherwise). */
    private val mixerLatencyMs: Int by lazy {
        try {
            AudioManager::class.java.getMethod("getOutputLatency", Int::class.javaPrimitiveType)
                .invoke(audioManager, AudioManager.STREAM_MUSIC) as Int
        } catch (e: Exception) {
            80
        }
    }

    /** Seconds from a frame leaving the ring to it being heard. */
    val latency: Double
        get() = synchronized(lock) {
            if (handle == 0L) 0.0 else Native.outputQueuedSeconds(handle) + mixerLatencyMs / 1000.0
        }

    /** Makes the output play `channels` at `rate`. Call only with nothing
     *  left in the ring that should still be heard. Returns false when the
     *  device refuses the format. */
    fun configure(channels: Int, rate: Int): Boolean = synchronized(lock) {
        if (channels == this.channels && rate == sampleRate && handle != 0L) return true
        if (handle != 0L) {
            Native.outputStop(handle)
            Native.outputDestroy(handle)
            handle = 0
        }
        running = false
        val h = Native.outputCreate(channels, rate, framesPerBuffer, (rate * BUFFER_SECONDS).toInt())
        if (h == 0L) {
            this.channels = 0
            sampleRate = 0
            return false
        }
        handle = h
        this.channels = channels
        sampleRate = rate
        generation++
        Native.outputSetVolume(h, volume * gain)
        true
    }

    fun setPaused(paused: Boolean) = synchronized(lock) {
        if (handle == 0L) return
        Native.outputSetPaused(handle, paused)
        if (!paused && !running) {
            running = Native.outputStart(handle)
        }
    }

    /** Stops the hardware (when nothing is playing). */
    fun stopEngine() = synchronized(lock) {
        if (handle != 0L && running) Native.outputStop(handle)
        running = false
    }

    fun startEngine() = synchronized(lock) {
        if (handle != 0L && !running) running = Native.outputStart(handle)
    }

    // Ring access, under the lock so a format change cannot free the ring
    // under a reader on another thread.
    fun buffered(): Int = synchronized(lock) { if (handle == 0L) 0 else Native.ringBuffered(handle) }
    fun consumed(): Long = synchronized(lock) { if (handle == 0L) 0 else Native.ringConsumed(handle) }
    fun written(): Long = synchronized(lock) { if (handle == 0L) 0 else Native.ringWritten(handle) }
    fun clear() = synchronized(lock) { if (handle != 0L) Native.ringClear(handle) }
    /** Frames of the last pipeline output, from sample `from`; returns frames written. */
    fun write(from: Int, frames: Int): Int = synchronized(lock) { if (handle == 0L) 0 else Native.ringWriteOutput(handle, from, frames) }

    fun release() = synchronized(lock) {
        if (handle != 0L) {
            Native.outputStop(handle)
            Native.outputDestroy(handle)
        }
        handle = 0
        running = false
    }
}
