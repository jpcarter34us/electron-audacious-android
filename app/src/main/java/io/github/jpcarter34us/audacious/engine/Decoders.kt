package io.github.jpcarter34us.audacious.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import io.github.jpcarter34us.audacious.player.MediaLocation
import io.github.jpcarter34us.audacious.util.Http
import io.github.jpcarter34us.audacious.util.UserError
import okhttp3.Call
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteOrder

/** Decodes one song to interleaved float samples. Used only from the
 *  engine's decoding thread, where blocking (on the network) is fine. */
interface AudioDecoder {
    val channels: Int
    val sampleRate: Int
    /** Seconds, when the decoder knows. */
    val duration: Double?
    /** Fills `out` with up to `maxFrames` frames; 0 at the end of the song. */
    fun read(out: FloatArray, maxFrames: Int): Int
    /** Seconds from the start; may block until the data is there. */
    fun seek(seconds: Double)
    /** Unblocks any waiting call and frees the decoder. */
    fun cancel()
    /** True once nothing more needs to be fetched (local file, or download done). */
    val isFullyLoaded: Boolean
}

/**
 * A song's bytes for the native decoders (jni/decoders.cc): a local file,
 * or a download into the cache that reads wait on, so a network song plays
 * while it downloads and seeks within what has arrived (the iOS port's
 * StreamDecoder, with its cache).
 */
class ByteSource private constructor(val handle: Long, val file: File?, private val call: Call?) {
    @Volatile var complete = false
        private set
    @Volatile var failed: String? = null
        private set
    private val done = Object()

    companion object {
        fun local(context: Context, path: String): ByteSource {
            if (path.startsWith("content://")) {
                val pfd = context.contentResolver.openFileDescriptor(Uri.parse(path), "r") ?: throw UserError("Cannot open this song")
                val size = pfd.statSize
                val h = Native.sourceOpenFd(pfd.detachFd())
                return ByteSource(h, null, null).also { it.finish(size) }
            }
            val f = File(path)
            if (!f.exists()) throw UserError("The file is missing (${f.name})")
            val h = Native.sourceOpenPath(path)
            if (h == 0L) throw UserError("Cannot open ${f.name}")
            return ByteSource(h, f, null).also { it.finish(f.length()) }
        }

        /** Starts downloading `location` into `cache` and returns at once. */
        fun download(location: MediaLocation.Http, cache: File): ByteSource {
            cache.parentFile?.mkdirs()
            FileOutputStream(cache).close()
            val pfd = ParcelFileDescriptor.open(cache, ParcelFileDescriptor.MODE_READ_ONLY)
            val h = Native.sourceOpenFd(pfd.detachFd())
            val call = Http.streaming.newCall(Http.request(location.url, location.headers))
            val source = ByteSource(h, cache, call)
            Thread({ source.fetch() }, "download").start()
            return source
        }
    }

    private fun finish(size: Long) {
        Native.sourceUpdate(handle, size, size, true)
        complete = true
    }

    private fun fetch() {
        val c = call ?: return
        try {
            c.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
                val body = response.body() ?: throw IOException("empty response")
                val total = body.contentLength()
                val input = body.byteStream()
                FileOutputStream(file!!).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    var reported = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        // Report every 64 KB or so; the decoder waits on it.
                        if (written - reported >= 64 * 1024) {
                            out.flush()
                            Native.sourceUpdate(handle, written, total, false)
                            reported = written
                        }
                    }
                    out.flush()
                    Native.sourceUpdate(handle, written, written, true)
                }
            }
            complete = true
        } catch (e: Exception) {
            failed = if (c.isCanceled) "Cancelled" else (e.message ?: "Download failed")
            Native.sourceFail(handle)
        }
        synchronized(done) { done.notifyAll() }
    }

    /** Waits for the whole file (MediaCodec needs it); false if it failed. */
    fun awaitComplete(): Boolean {
        synchronized(done) {
            while (!complete && failed == null) done.wait(200)
        }
        return complete
    }

    private var released = false

    fun cancel() {
        call?.cancel()
        Native.sourceCancel(handle)
        synchronized(done) { done.notifyAll() }
    }

    /** After the decoder reading it is closed. */
    fun release() {
        if (released) return
        released = true
        cancel()
        Native.sourceDestroy(handle)
        if (call != null) file?.delete()
    }
}

/** FLAC, MP3, WAV/AIFF, Ogg Vorbis and Ogg Opus, natively (jni/decoders.cc). */
class NativeDecoder private constructor(private val source: ByteSource, private var handle: Long) : AudioDecoder {
    override val channels = Native.decoderChannels(handle)
    override val sampleRate = Native.decoderRate(handle)
    override val duration: Double?
        get() = Native.decoderFrames(handle).takeIf { it > 0 }?.let { it.toDouble() / sampleRate }
    override val isFullyLoaded get() = source.complete

    companion object {
        /** Null (with the reason) when the data is not a format read natively. */
        fun open(source: ByteSource, hint: String?): Pair<NativeDecoder?, String> {
            val h = Native.decoderOpen(source.handle, hint ?: "")
            if (h == 0L) return null to Native.decoderError()
            return NativeDecoder(source, h) to ""
        }
    }

    override fun read(out: FloatArray, maxFrames: Int): Int {
        val n = Native.decoderRead(handle, out, maxFrames)
        if (n < 0) throw UserError(source.failed?.let { "The download stopped ($it)" } ?: "Read error")
        return n
    }

    override fun seek(seconds: Double) {
        Native.decoderSeek(handle, (seconds * sampleRate).toLong())
    }

    override fun cancel() {
        source.cancel()
        synchronized(this) {
            if (handle != 0L) Native.decoderClose(handle)
            handle = 0
        }
        source.release()
    }
}

/**
 * Everything else Android decodes (AAC/M4A, ALAC on newer versions, ...)
 * through MediaExtractor and MediaCodec, with the API 16 buffer arrays.
 * Needs the whole file. The output format is found by decoding the first
 * buffer, since HE-AAC reports its real rate only then.
 */
class MediaCodecDecoder(context: Context, path: String, private val source: ByteSource?) : AudioDecoder {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private var inputBuffers: Array<java.nio.ByteBuffer>
    private var outputBuffers: Array<java.nio.ByteBuffer>
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var pending = FloatArray(0)
    private var pendingPos = 0
    private var pendingLen = 0
    override var channels = 0
        private set
    override var sampleRate = 0
        private set
    override val duration: Double?
    override val isFullyLoaded get() = true

    init {
        if (path.startsWith("content://")) {
            val pfd = context.contentResolver.openFileDescriptor(Uri.parse(path), "r") ?: throw UserError("Cannot open this song")
            pfd.use { extractor.setDataSource(it.fileDescriptor) }
        } else {
            extractor.setDataSource(path)
        }
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                extractor.selectTrack(i)
                format = f
                break
            }
        }
        if (format == null) {
            extractor.release()
            throw UserError("This Android version cannot decode this file")
        }
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        codec = try {
            MediaCodec.createDecoderByType(mime).also {
                it.configure(format, null, null, 0)
                it.start()
            }
        } catch (e: Exception) {
            extractor.release()
            throw UserError("This format is not supported on this device (${mime.removePrefix("audio/")})")
        }
        @Suppress("DEPRECATION")
        inputBuffers = codec.inputBuffers
        @Suppress("DEPRECATION")
        outputBuffers = codec.outputBuffers
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) / 1e6 else null
        // Decode until the first output, so the format is the real one.
        var tries = 0
        while (pendingLen == 0 && !outputDone && tries++ < 400) step()
        if (channels <= 0 || sampleRate <= 0) {
            cancel()
            throw UserError("This file has no audio")
        }
    }

    /** Feeds one input buffer and takes one output buffer, if ready. */
    private fun step() {
        if (!inputDone) {
            val i = codec.dequeueInputBuffer(5000)
            if (i >= 0) {
                val buf = inputBuffers[i]
                buf.clear()
                val n = extractor.readSampleData(buf, 0)
                if (n < 0) {
                    codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val o = codec.dequeueOutputBuffer(info, 5000)
        when {
            o >= 0 -> {
                if (info.size > 0) {
                    val buf = outputBuffers[o]
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val shorts = buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val n = shorts.remaining()
                    if (pending.size < n) pending = FloatArray(n)
                    for (k in 0 until n) pending[k] = shorts.get(k) / 32768f
                    pendingPos = 0
                    pendingLen = n
                }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
            o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                val f = codec.outputFormat
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
            @Suppress("DEPRECATION")
            o == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                @Suppress("DEPRECATION")
                outputBuffers = codec.outputBuffers
            }
        }
    }

    override fun read(out: FloatArray, maxFrames: Int): Int {
        var tries = 0
        while (pendingLen - pendingPos <= 0 && !outputDone) {
            step()
            if (++tries > 2000) throw UserError("The decoder stopped")
        }
        val available = (pendingLen - pendingPos) / channels
        if (available <= 0) return 0
        val frames = minOf(available, maxFrames, out.size / channels)
        System.arraycopy(pending, pendingPos, out, 0, frames * channels)
        pendingPos += frames * channels
        return frames
    }

    override fun seek(seconds: Double) {
        extractor.seekTo((seconds * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        codec.flush()
        inputDone = false
        outputDone = false
        pendingLen = 0
        pendingPos = 0
    }

    override fun cancel() {
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        codec.release()
        extractor.release()
        source?.release()
    }
}

/** Opens the right decoder for a song. */
object Decoders {
    fun open(context: Context, location: MediaLocation, cacheName: String): AudioDecoder = when (location) {
        is MediaLocation.Local -> {
            val hint = location.path.substringAfterLast('.', "").lowercase()
            val source = ByteSource.local(context, location.path)
            val (native, _) = NativeDecoder.open(source, hint)
            native ?: run {
                source.release()
                MediaCodecDecoder(context, location.path, null)
            }
        }
        is MediaLocation.Http -> {
            val ext = location.formatHint ?: "audio"
            val cache = File(File(context.cacheDir, "streams"), "$cacheName.$ext")
            val source = ByteSource.download(location, cache)
            val (native, why) = NativeDecoder.open(source, location.formatHint)
            native ?: run {
                if (source.failed != null) {
                    source.release()
                    throw UserError("Cannot download the song (${source.failed})")
                }
                // MP4 and friends: MediaCodec reads the finished download.
                if (!source.awaitComplete()) {
                    source.release()
                    throw UserError("Cannot download the song (${source.failed ?: why})")
                }
                try {
                    MediaCodecDecoder(context, cache.path, source)
                } catch (e: Exception) {
                    source.release()
                    throw e
                }
            }
        }
    }

    /** Clears streams left from an earlier run. */
    fun clearCache(context: Context) {
        File(context.cacheDir, "streams").listFiles()?.forEach { it.delete() }
    }
}
