package io.github.jpcarter34us.audacious.sendspin

import io.github.jpcarter34us.audacious.engine.Native
import io.github.jpcarter34us.audacious.util.int
import io.github.jpcarter34us.audacious.util.str
import org.json.JSONObject

/**
 * Decodes Sendspin audio chunks to interleaved float samples, as
 * sendspin-js `decoder.ts` does in the browser: PCM (16, 24 or 32-bit
 * little-endian) here, FLAC (frames behind the stream header the server
 * sends as `codec_header`) and Opus packets natively (jni/live.cc), so
 * every codec works on every Android version.
 */
class SendspinAudioDecoder private constructor(
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    private val bitDepth: Int,
    private var handle: Long,
) {
    companion object {
        /** Null when the format is not one this client decodes. */
        fun create(format: JSONObject): SendspinAudioDecoder? {
            val codec = format.str("codec") ?: return null
            val rate = format.int("sample_rate") ?: return null
            val channels = format.int("channels") ?: return null
            if (rate <= 0 || channels <= 0 || channels > 8) return null
            val bits = format.int("bit_depth") ?: 16
            return when (codec) {
                "pcm" -> if (bits in listOf(16, 24, 32)) SendspinAudioDecoder(codec, rate, channels, bits, 0) else null
                "flac" -> {
                    val header = format.str("codec_header")?.let { Base64URL.decode(it) } ?: return null
                    val h = Native.liveFlacCreate(header, channels)
                    if (h == 0L) null else SendspinAudioDecoder(codec, rate, channels, bits, h)
                }
                "opus" -> {
                    val h = Native.liveOpusCreate(rate, channels)
                    if (h == 0L) null else SendspinAudioDecoder(codec, rate, channels, bits, h)
                }
                else -> null
            }
        }
    }

    fun decode(data: Bytes, offset: Int): FloatArray {
        val length = data.size - offset
        if (length <= 0) return FloatArray(0)
        return when (codec) {
            "pcm" -> decodePCM(data, offset, length)
            "flac" -> Native.liveFlacDecode(handle, data, offset, length) ?: FloatArray(0)
            "opus" -> Native.liveOpusDecode(handle, data, offset, length) ?: FloatArray(0)
            else -> FloatArray(0)
        }
    }

    private fun decodePCM(d: Bytes, o: Int, length: Int): FloatArray {
        val bytes = bitDepth / 8
        val count = length / bytes / channels * channels
        val out = FloatArray(count)
        when (bitDepth) {
            16 -> for (i in 0 until count) {
                val p = o + i * 2
                out[i] = ((d[p].toInt() and 0xFF) or (d[p + 1].toInt() shl 8)).toShort() / 32768f
            }
            24 -> for (i in 0 until count) {
                val p = o + i * 3
                val v = (d[p].toInt() and 0xFF) or (d[p + 1].toInt() and 0xFF shl 8) or (d[p + 2].toInt() shl 16)
                out[i] = v / 8_388_608f
            }
            else -> for (i in 0 until count) {
                val p = o + i * 4
                val v = (d[p].toInt() and 0xFF) or (d[p + 1].toInt() and 0xFF shl 8) or (d[p + 2].toInt() and 0xFF shl 16) or (d[p + 3].toInt() shl 24)
                out[i] = (v / 2_147_483_648.0).toFloat()
            }
        }
        return out
    }

    fun release() {
        when (codec) {
            "flac" -> if (handle != 0L) Native.liveFlacDestroy(handle)
            "opus" -> if (handle != 0L) Native.liveOpusDestroy(handle)
        }
        handle = 0
    }
}
