/*
 * bridge.cc
 *
 * JNI for io.github.jpcarter34us.audacious.engine.Native: the AudCore C
 * API (effects, plugin settings, equalizer, pipeline, FFT), the output and
 * its ring, the native decoders and the Sendspin chunk decoders. Handles
 * are native pointers passed as longs.
 */

#include <AudCore.h>
#include <jni.h>

#include "decoders.h"
#include "live.h"
#include "output.h"

#include <fcntl.h>
#include <stdlib.h>
#include <string>
#include <unistd.h>
#include <vector>

#define FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_io_github_jpcarter34us_audacious_engine_Native_##name

namespace
{
std::string str(JNIEnv * env, jstring s)
{
    if (!s)
        return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

jstring jstr(JNIEnv * env, const char * s) { return s ? env->NewStringUTF(s) : nullptr; }

/* Returns and frees a malloc'd string from AudCore. */
jstring take(JNIEnv * env, char * s)
{
    jstring out = jstr(env, s);
    free(s);
    return out;
}

jfloatArray floats(JNIEnv * env, const std::vector<float> & v)
{
    jfloatArray out = env->NewFloatArray((jsize)v.size());
    if (out && !v.empty())
        env->SetFloatArrayRegion(out, 0, (jsize)v.size(), v.data());
    return out;
}

// The decoding thread's last pipeline output (audcore_pipeline_process:
// valid until the next call, which is on the same thread).
const float * last_out = nullptr;
int last_samples = 0;

thread_local std::string decoder_error;

struct Decoder
{
    NativeDecoder * dec;
    std::vector<float> scratch;
};
} // namespace

/* ---- core ---- */

FN(void, init)(JNIEnv * env, jclass, jstring config)
{
    std::string path = str(env, config);
    audcore_config_load(path.c_str());
    audcore_init();
}

FN(jboolean, configSave)(JNIEnv * env, jclass, jstring path)
{
    return audcore_config_save(str(env, path).c_str());
}

FN(jstring, configGet)(JNIEnv * env, jclass, jstring section, jstring name)
{
    std::string s = str(env, section);
    return take(env, audcore_config_get(section ? s.c_str() : nullptr, str(env, name).c_str()));
}

FN(void, configSet)(JNIEnv * env, jclass, jstring section, jstring name, jstring value)
{
    std::string s = str(env, section);
    audcore_config_set(section ? s.c_str() : nullptr, str(env, name).c_str(), str(env, value).c_str());
}

/* ---- effects ---- */

FN(jint, effectCount)(JNIEnv *, jclass) { return audcore_effect_count(); }
FN(jstring, effectId)(JNIEnv * env, jclass, jint i) { return jstr(env, audcore_effect_id(i)); }
FN(jstring, effectName)(JNIEnv * env, jclass, jint i) { return jstr(env, audcore_effect_name(i)); }
FN(jstring, effectAbout)(JNIEnv * env, jclass, jint i) { return jstr(env, audcore_effect_about(i)); }
FN(jboolean, effectPreservesFormat)(JNIEnv *, jclass, jint i) { return audcore_effect_preserves_format(i); }
FN(jboolean, effectHasPrefs)(JNIEnv *, jclass, jint i) { return audcore_effect_has_prefs(i); }
FN(jboolean, effectEnabled)(JNIEnv *, jclass, jint i) { return audcore_effect_enabled(i); }
FN(void, effectSetEnabled)(JNIEnv *, jclass, jint i, jboolean on) { audcore_effect_set_enabled(i, on); }

FN(jstring, prefsOpen)(JNIEnv * env, jclass, jint i) { return take(env, audcore_prefs_open(i)); }
FN(jstring, prefsValues)(JNIEnv * env, jclass, jint i) { return take(env, audcore_prefs_values(i)); }
FN(void, prefsSet)(JNIEnv * env, jclass, jint i, jint widget, jstring value)
{
    audcore_prefs_set(i, widget, str(env, value).c_str());
}
FN(void, prefsButton)(JNIEnv *, jclass, jint i, jint widget) { audcore_prefs_button(i, widget); }
FN(void, prefsApply)(JNIEnv *, jclass, jint i) { audcore_prefs_apply(i); }
FN(void, prefsClose)(JNIEnv *, jclass, jint i) { audcore_prefs_close(i); }

/* ---- pipeline (decoding thread) ---- */

FN(void, pipelineStart)(JNIEnv *, jclass, jint channels, jint rate) { audcore_pipeline_start(channels, rate); }
FN(jint, pipelineChannels)(JNIEnv *, jclass) { return audcore_pipeline_channels(); }
FN(jint, pipelineRate)(JNIEnv *, jclass) { return audcore_pipeline_rate(); }

FN(jint, pipelineProcess)(JNIEnv * env, jclass, jfloatArray input, jint samples)
{
    // Copied rather than pinned: the pipeline takes a lock the settings
    // screens also hold.
    static std::vector<float> in;
    in.resize((size_t)samples);
    env->GetFloatArrayRegion(input, 0, samples, in.data());
    last_samples = audcore_pipeline_process(in.data(), samples, &last_out);
    return last_samples;
}

FN(jint, pipelineFinish)(JNIEnv *, jclass, jboolean end_of_playlist)
{
    last_samples = audcore_pipeline_finish(nullptr, 0, end_of_playlist, &last_out);
    return last_samples;
}

FN(jboolean, pipelineFlush)(JNIEnv *, jclass, jboolean force) { return audcore_pipeline_flush(force); }
FN(jint, pipelineAdjustDelay)(JNIEnv *, jclass, jint ms) { return audcore_pipeline_adjust_delay(ms); }

/* ---- equalizer ---- */

FN(void, eqSetActive)(JNIEnv *, jclass, jboolean on) { audcore_eq_set_active(on); }
FN(jboolean, eqActive)(JNIEnv *, jclass) { return audcore_eq_active(); }
FN(void, eqSetPreamp)(JNIEnv *, jclass, jdouble db) { audcore_eq_set_preamp(db); }
FN(jdouble, eqPreamp)(JNIEnv *, jclass) { return audcore_eq_preamp(); }

FN(void, eqSetBands)(JNIEnv * env, jclass, jdoubleArray bands)
{
    double b[AUDCORE_EQ_NBANDS] = {0};
    jsize n = env->GetArrayLength(bands);
    env->GetDoubleArrayRegion(bands, 0, n < AUDCORE_EQ_NBANDS ? n : AUDCORE_EQ_NBANDS, b);
    audcore_eq_set_bands(b);
}

FN(jdoubleArray, eqBands)(JNIEnv * env, jclass)
{
    double b[AUDCORE_EQ_NBANDS];
    audcore_eq_bands(b);
    jdoubleArray out = env->NewDoubleArray(AUDCORE_EQ_NBANDS);
    env->SetDoubleArrayRegion(out, 0, AUDCORE_EQ_NBANDS, b);
    return out;
}

/* ---- visualization ---- */

FN(void, calcFreq)(JNIEnv * env, jclass, jfloatArray pcm, jfloatArray freq)
{
    float p[AUDCORE_VIS_PCM], f[AUDCORE_VIS_FREQ];
    env->GetFloatArrayRegion(pcm, 0, AUDCORE_VIS_PCM, p);
    audcore_calc_freq(p, f);
    env->SetFloatArrayRegion(freq, 0, AUDCORE_VIS_FREQ, f);
}

FN(jint, visSnapshot)(JNIEnv * env, jclass, jfloatArray pcm, jfloatArray peaks)
{
    float p[512], k[8] = {0};
    int max = env->GetArrayLength(peaks);
    int n = audvis_snapshot(p, k, max < 8 ? max : 8);
    env->SetFloatArrayRegion(pcm, 0, 512, p);
    env->SetFloatArrayRegion(peaks, 0, n, k);
    return n;
}

FN(void, visClear)(JNIEnv *, jclass) { audvis_clear(); }

/* ---- output ---- */

#define OUT(h) ((AudOutput *)(intptr_t)(h))

FN(jlong, outputCreate)(JNIEnv *, jclass, jint channels, jint rate, jint buffer_frames, jint ring_frames)
{
    return (jlong)(intptr_t)audout_create(channels, rate, buffer_frames, ring_frames);
}
FN(void, outputDestroy)(JNIEnv *, jclass, jlong h) { audout_destroy(OUT(h)); }
FN(jboolean, outputStart)(JNIEnv *, jclass, jlong h) { return audout_start(OUT(h)); }
FN(void, outputStop)(JNIEnv *, jclass, jlong h) { audout_stop(OUT(h)); }
FN(void, outputSetPaused)(JNIEnv *, jclass, jlong h, jboolean paused) { audout_set_paused(OUT(h), paused); }
FN(void, outputSetVolume)(JNIEnv *, jclass, jlong h, jfloat v) { audout_set_volume(OUT(h), v); }
FN(jint, outputDeviceRate)(JNIEnv *, jclass, jlong h) { return audout_device_rate(OUT(h)); }
FN(jdouble, outputQueuedSeconds)(JNIEnv *, jclass, jlong h) { return audout_queued_seconds(OUT(h)); }

FN(jint, ringBuffered)(JNIEnv *, jclass, jlong h) { return audring_buffered(audout_ring(OUT(h))); }
FN(jlong, ringConsumed)(JNIEnv *, jclass, jlong h) { return (jlong)audring_consumed(audout_ring(OUT(h))); }
FN(jlong, ringWritten)(JNIEnv *, jclass, jlong h) { return (jlong)audring_written(audout_ring(OUT(h))); }
FN(jint, ringSpace)(JNIEnv *, jclass, jlong h) { return audring_space(audout_ring(OUT(h))); }
FN(void, ringClear)(JNIEnv *, jclass, jlong h) { audring_request_clear(audout_ring(OUT(h))); }

/* Writes frames of the last pipeline output, from sample `from`. */
FN(jint, ringWriteOutput)(JNIEnv *, jclass, jlong h, jint from, jint frames)
{
    AudRing * ring = audout_ring(OUT(h));
    int ch = audring_channels(ring);
    if (!last_out || from < 0 || from + frames * ch > last_samples)
        return 0;
    return audring_write(ring, last_out + from, frames);
}

/* ---- byte sources and decoders ---- */

#define SRC(h) ((ByteSource *)(intptr_t)(h))
#define DEC(h) ((Decoder *)(intptr_t)(h))

FN(jlong, sourceOpenFd)(JNIEnv *, jclass, jint fd) { return (jlong)(intptr_t)bytesource_create(fd); }

FN(jlong, sourceOpenPath)(JNIEnv * env, jclass, jstring path)
{
    int fd = open(str(env, path).c_str(), O_RDONLY);
    return fd < 0 ? 0 : (jlong)(intptr_t)bytesource_create(fd);
}

FN(void, sourceUpdate)(JNIEnv *, jclass, jlong h, jlong available, jlong total, jboolean complete)
{
    bytesource_update(SRC(h), available, total, complete);
}
FN(void, sourceFail)(JNIEnv *, jclass, jlong h) { bytesource_fail(SRC(h)); }
FN(void, sourceCancel)(JNIEnv *, jclass, jlong h) { bytesource_cancel(SRC(h)); }
FN(void, sourceDestroy)(JNIEnv *, jclass, jlong h) { bytesource_destroy(SRC(h)); }

FN(jlong, decoderOpen)(JNIEnv * env, jclass, jlong src, jstring hint)
{
    decoder_error.clear();
    std::string h = str(env, hint);
    NativeDecoder * d = decoder_open(SRC(src), h.c_str(), &decoder_error);
    if (!d)
        return 0;
    auto wrap = new Decoder;
    wrap->dec = d;
    return (jlong)(intptr_t)wrap;
}

FN(jstring, decoderError)(JNIEnv * env, jclass) { return env->NewStringUTF(decoder_error.c_str()); }
FN(jint, decoderChannels)(JNIEnv *, jclass, jlong h) { return decoder_channels(DEC(h)->dec); }
FN(jint, decoderRate)(JNIEnv *, jclass, jlong h) { return decoder_rate(DEC(h)->dec); }
FN(jlong, decoderFrames)(JNIEnv *, jclass, jlong h) { return decoder_frames(DEC(h)->dec); }

FN(jint, decoderRead)(JNIEnv * env, jclass, jlong h, jfloatArray out, jint max_frames)
{
    Decoder * d = DEC(h);
    int ch = decoder_channels(d->dec);
    int cap = env->GetArrayLength(out) / ch;
    if (max_frames > cap)
        max_frames = cap;
    d->scratch.resize((size_t)max_frames * ch);
    int n = decoder_read(d->dec, d->scratch.data(), max_frames);
    if (n > 0)
        env->SetFloatArrayRegion(out, 0, n * ch, d->scratch.data());
    return n;
}

FN(jboolean, decoderSeek)(JNIEnv *, jclass, jlong h, jlong frame) { return decoder_seek(DEC(h)->dec, frame); }

FN(void, decoderClose)(JNIEnv *, jclass, jlong h)
{
    if (!h)
        return;
    decoder_close(DEC(h)->dec);
    delete DEC(h);
}

/* ---- Sendspin chunk decoders ---- */

FN(jlong, liveFlacCreate)(JNIEnv * env, jclass, jbyteArray header, jint channels)
{
    jsize n = env->GetArrayLength(header);
    std::vector<unsigned char> h((size_t)n);
    env->GetByteArrayRegion(header, 0, n, (jbyte *)h.data());
    return (jlong)(intptr_t)liveflac_create(h.data(), n, channels);
}

FN(jfloatArray, liveFlacDecode)(JNIEnv * env, jclass, jlong h, jbyteArray data, jint off, jint len)
{
    std::vector<unsigned char> in((size_t)len);
    env->GetByteArrayRegion(data, off, len, (jbyte *)in.data());
    std::vector<float> out;
    if (liveflac_decode((LiveFlac *)(intptr_t)h, in.data(), len, out) < 0)
        return nullptr;
    return floats(env, out);
}

FN(void, liveFlacDestroy)(JNIEnv *, jclass, jlong h) { liveflac_destroy((LiveFlac *)(intptr_t)h); }

FN(jlong, liveOpusCreate)(JNIEnv *, jclass, jint rate, jint channels)
{
    return (jlong)(intptr_t)liveopus_create(rate, channels);
}

FN(jfloatArray, liveOpusDecode)(JNIEnv * env, jclass, jlong h, jbyteArray data, jint off, jint len)
{
    std::vector<unsigned char> in((size_t)len);
    env->GetByteArrayRegion(data, off, len, (jbyte *)in.data());
    std::vector<float> out;
    if (liveopus_decode((LiveOpus *)(intptr_t)h, in.data(), len, out) < 0)
        return nullptr;
    return floats(env, out);
}

FN(void, liveOpusDestroy)(JNIEnv *, jclass, jlong h) { liveopus_destroy((LiveOpus *)(intptr_t)h); }
