package io.github.jpcarter34us.audacious.engine

/**
 * The native library (src/main/cpp): the ported libaudcore pieces and
 * Audacious effect plugins, the OpenSL ES output, and the decoders. See
 * jni/bridge.cc; handles are native pointers.
 */
object Native {
    init {
        System.loadLibrary("audjni")
    }

    const val EQ_BANDS = 10
    const val EQ_MAX_GAIN = 12.0
    const val VIS_PCM = 512
    const val VIS_FREQ = 256

    // ---- core and configuration ----
    @JvmStatic external fun init(configPath: String)
    @JvmStatic external fun configSave(path: String): Boolean
    @JvmStatic external fun configGet(section: String?, name: String): String?
    @JvmStatic external fun configSet(section: String?, name: String, value: String)

    // ---- effect plugins, in the order they are applied ----
    @JvmStatic external fun effectCount(): Int
    @JvmStatic external fun effectId(index: Int): String
    @JvmStatic external fun effectName(index: Int): String
    @JvmStatic external fun effectAbout(index: Int): String?
    @JvmStatic external fun effectPreservesFormat(index: Int): Boolean
    @JvmStatic external fun effectHasPrefs(index: Int): Boolean
    @JvmStatic external fun effectEnabled(index: Int): Boolean
    @JvmStatic external fun effectSetEnabled(index: Int, enabled: Boolean)

    // ---- plugin settings (JSON, ElectronPlayer's prefs shape) ----
    @JvmStatic external fun prefsOpen(effect: Int): String?
    @JvmStatic external fun prefsValues(effect: Int): String?
    @JvmStatic external fun prefsSet(effect: Int, widget: Int, value: String)
    @JvmStatic external fun prefsButton(effect: Int, widget: Int)
    @JvmStatic external fun prefsApply(effect: Int)
    @JvmStatic external fun prefsClose(effect: Int)

    // ---- pipeline: decoding thread only ----
    @JvmStatic external fun pipelineStart(channels: Int, rate: Int)
    @JvmStatic external fun pipelineChannels(): Int
    @JvmStatic external fun pipelineRate(): Int
    /** Effects then equalizer; returns the output sample count (see ringWriteOutput). */
    @JvmStatic external fun pipelineProcess(input: FloatArray, samples: Int): Int
    @JvmStatic external fun pipelineFinish(endOfPlaylist: Boolean): Int
    @JvmStatic external fun pipelineFlush(force: Boolean): Boolean
    @JvmStatic external fun pipelineAdjustDelay(ms: Int): Int

    // ---- equalizer ----
    @JvmStatic external fun eqSetActive(active: Boolean)
    @JvmStatic external fun eqActive(): Boolean
    @JvmStatic external fun eqSetPreamp(db: Double)
    @JvmStatic external fun eqPreamp(): Double
    @JvmStatic external fun eqSetBands(bands: DoubleArray)
    @JvmStatic external fun eqBands(): DoubleArray

    // ---- visualization ----
    @JvmStatic external fun calcFreq(pcm: FloatArray, freq: FloatArray)
    @JvmStatic external fun visSnapshot(pcm: FloatArray, peaks: FloatArray): Int
    @JvmStatic external fun visClear()

    // ---- output ----
    @JvmStatic external fun outputCreate(channels: Int, rate: Int, bufferFrames: Int, ringFrames: Int): Long
    @JvmStatic external fun outputDestroy(out: Long)
    @JvmStatic external fun outputStart(out: Long): Boolean
    @JvmStatic external fun outputStop(out: Long)
    @JvmStatic external fun outputSetPaused(out: Long, paused: Boolean)
    @JvmStatic external fun outputSetVolume(out: Long, volume: Float)
    @JvmStatic external fun outputDeviceRate(out: Long): Int
    @JvmStatic external fun outputQueuedSeconds(out: Long): Double
    @JvmStatic external fun ringBuffered(out: Long): Int
    @JvmStatic external fun ringConsumed(out: Long): Long
    @JvmStatic external fun ringWritten(out: Long): Long
    @JvmStatic external fun ringSpace(out: Long): Int
    @JvmStatic external fun ringClear(out: Long)
    /** Writes `frames` frames of the last pipeline output, from sample `from`. */
    @JvmStatic external fun ringWriteOutput(out: Long, from: Int, frames: Int): Int

    // ---- byte sources and decoders ----
    /** Takes over the file descriptor. */
    @JvmStatic external fun sourceOpenFd(fd: Int): Long
    @JvmStatic external fun sourceOpenPath(path: String): Long
    @JvmStatic external fun sourceUpdate(source: Long, available: Long, total: Long, complete: Boolean)
    @JvmStatic external fun sourceFail(source: Long)
    @JvmStatic external fun sourceCancel(source: Long)
    @JvmStatic external fun sourceDestroy(source: Long)
    @JvmStatic external fun decoderOpen(source: Long, hint: String): Long
    @JvmStatic external fun decoderError(): String
    @JvmStatic external fun decoderChannels(decoder: Long): Int
    @JvmStatic external fun decoderRate(decoder: Long): Int
    @JvmStatic external fun decoderFrames(decoder: Long): Long
    @JvmStatic external fun decoderRead(decoder: Long, out: FloatArray, maxFrames: Int): Int
    @JvmStatic external fun decoderSeek(decoder: Long, frame: Long): Boolean
    @JvmStatic external fun decoderClose(decoder: Long)

    // ---- Sendspin chunk decoders ----
    @JvmStatic external fun liveFlacCreate(header: ByteArray, channels: Int): Long
    @JvmStatic external fun liveFlacDecode(decoder: Long, data: ByteArray, offset: Int, length: Int): FloatArray?
    @JvmStatic external fun liveFlacDestroy(decoder: Long)
    @JvmStatic external fun liveOpusCreate(rate: Int, channels: Int): Long
    @JvmStatic external fun liveOpusDecode(decoder: Long, data: ByteArray, offset: Int, length: Int): FloatArray?
    @JvmStatic external fun liveOpusDestroy(decoder: Long)
}
