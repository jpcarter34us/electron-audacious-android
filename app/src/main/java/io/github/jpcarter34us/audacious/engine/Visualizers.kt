package io.github.jpcarter34us.audacious.engine

/** The visualizations, ported from the GTK/Qt plugins by way of
 *  ElectronPlayer's visualizers.ts and the iOS port's Visualizers.swift. */
enum class VisualizerKind(val title: String) {
    OFF("None"), SPECTRUM("Spectrum Analyzer"), SCOPE("Blur Scope"), VU("VU Meter")
}

/** cairo-spectrum / qt-spectrum: log-spaced bars over a 40 dB range with a
 *  short hold before they fall. */
class SpectrumAnalyzer {
    var bars = FloatArray(0)
        private set
    private var delay = IntArray(0)
    private var xscale = FloatArray(0)

    companion object {
        private const val HOLD_FRAMES = 2
        private const val FALLOFF = 2f // dB per frame
        const val RANGE = 40f

        /** libaudcore's Visualizer::compute_log_xscale. */
        fun logXscale(bands: Int) = FloatArray(bands + 1) { Math.pow(256.0, it.toDouble() / bands).toFloat() - 0.5f }

        /** libaudcore's Visualizer::compute_freq_band. */
        fun freqBand(freq: FloatArray, xscale: FloatArray, band: Int, bands: Int): Float {
            var a = Math.ceil(xscale[band].toDouble()).toInt()
            val b = Math.floor(xscale[band + 1].toDouble()).toInt()
            var n = 0f
            if (b < a) {
                n += freq[b] * (xscale[band + 1] - xscale[band])
            } else {
                if (a > 0) n += freq[a - 1] * (a - xscale[band])
                while (a < b) {
                    n += freq[a]
                    a++
                }
                if (b < 256) n += freq[b] * (xscale[band + 1] - b)
            }
            n *= bands / 12f
            return (20 * Math.log10(n.toDouble())).toFloat()
        }
    }

    fun render(frame: VisFrame, requested: Int) {
        val bands = requested.coerceIn(12, 256)
        if (bands != bars.size) {
            bars = FloatArray(bands)
            delay = IntArray(bands)
            xscale = logXscale(bands)
        }
        for (i in 0 until bands) {
            val x = (RANGE + freqBand(frame.freq, xscale, i, bands)).let { if (it.isNaN()) 0f else it.coerceIn(0f, RANGE) }
            bars[i] -= maxOf(0f, FALLOFF - delay[i])
            if (delay[i] > 0) delay[i]--
            if (x > bars[i]) {
                bars[i] = x
                delay[i] = HOLD_FRAMES
            }
        }
    }

    fun clear() {
        bars.fill(0f)
        delay.fill(0)
    }
}

/** blur_scope: an oscilloscope drawn into a small buffer that is blurred
 *  every frame, so old traces fade. */
class BlurScope(val width: Int = 160, val height: Int = 64) {
    val pixels = IntArray(width * height)

    fun render(frame: VisFrame) {
        blur()
        var prev = -1
        for (x in 0 until width) {
            val sample = frame.pcm[x * frame.pcm.size / width]
            val y = Math.round(height / 2f - sample * height / 2f).coerceIn(0, height - 1)
            val a = if (prev < 0) y else minOf(prev, y)
            val b = if (prev < 0) y else maxOf(prev, y)
            for (yy in a..b) pixels[yy * width + x] = 255
            prev = y
        }
    }

    private fun blur() {
        val src = pixels.copyOf()
        val w = width
        for (i in w until pixels.size - w) {
            val left = if (i % w == 0) 0 else src[i - 1]
            val right = if (i % w == w - 1) 0 else src[i + 1]
            pixels[i] = (src[i - w] + src[i + w] + left + right) shr 2
        }
        for (i in 0 until w) {
            pixels[i] = 0
            pixels[pixels.size - 1 - i] = 0
        }
    }

    fun clear() = pixels.fill(0)
}

/** vumeter: per-channel peak level in dB with a held peak marker. */
class VUMeter {
    var level = FloatArray(0)
        private set
    var peak = FloatArray(0)
        private set
    private var peakAge = IntArray(0)

    companion object {
        const val RANGE = 40f
        private const val FALLOFF = 1f
        private const val HOLD = 30
    }

    fun render(frame: VisFrame) {
        val channels = maxOf(1, frame.levels.size)
        if (level.size != channels) {
            level = FloatArray(channels) { -RANGE }
            peak = FloatArray(channels) { -RANGE }
            peakAge = IntArray(channels)
        }
        for (c in 0 until channels) {
            val raw = if (c < frame.levels.size) frame.levels[c] else 0f
            val db = maxOf(-RANGE, (20 * Math.log10(maxOf(raw, 1e-6f).toDouble())).toFloat())
            level[c] = maxOf(db, level[c] - FALLOFF)
            peakAge[c]++
            if (db >= peak[c] || peakAge[c] > HOLD) {
                peak[c] = db
                peakAge[c] = 0
            }
        }
    }

    fun clear() {
        level.fill(-RANGE)
        peak.fill(-RANGE)
    }
}
