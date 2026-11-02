/*
 * output.cc
 *
 * OpenSL ES output. One engine and output mix for the process; one player
 * per output format. The buffer-queue callback renders the next buffer from
 * the ring into float scratch (audring_render, which also feeds the
 * visualization), applies the volume and converts to 16-bit with a little
 * dither. It runs on OpenSL's own thread and touches nothing but the ring
 * and this object's preallocated buffers.
 */

#include "output.h"

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <android/log.h>

#include <atomic>
#include <math.h>
#include <mutex>
#include <stdlib.h>
#include <string.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "audjni", __VA_ARGS__)

namespace
{
const int NUM_BUFFERS = 3;
const int MAX_CH = 2;

std::mutex engine_lock;
SLObjectItf engine_obj = nullptr;
SLEngineItf engine = nullptr;
SLObjectItf mix_obj = nullptr;

bool ensure_engine()
{
    std::lock_guard<std::mutex> g(engine_lock);
    if (engine)
        return true;
    if (slCreateEngine(&engine_obj, 0, nullptr, 0, nullptr, nullptr) != SL_RESULT_SUCCESS)
        return false;
    if ((*engine_obj)->Realize(engine_obj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS ||
        (*engine_obj)->GetInterface(engine_obj, SL_IID_ENGINE, &engine) != SL_RESULT_SUCCESS)
    {
        (*engine_obj)->Destroy(engine_obj);
        engine_obj = nullptr;
        engine = nullptr;
        return false;
    }
    if ((*engine)->CreateOutputMix(engine, &mix_obj, 0, nullptr, nullptr) != SL_RESULT_SUCCESS ||
        (*mix_obj)->Realize(mix_obj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS)
    {
        LOG("cannot create the OpenSL output mix");
        return false;
    }
    return true;
}
} // namespace

struct AudOutput
{
    AudRing * ring = nullptr;
    int channels = 0;    // ring channels
    int out_ch = 0;      // device channels (1 or 2)
    int rate = 0;        // ring rate
    int device_rate = 0; // what OpenSL plays
    int frames = 0;      // per buffer, device rate

    SLObjectItf player_obj = nullptr;
    SLPlayItf play = nullptr;
    SLAndroidSimpleBufferQueueItf queue = nullptr;

    int16_t * buffers[NUM_BUFFERS] = {};
    int next = 0;
    float * scratch[MAX_CH] = {}; // ring-rate frames for one buffer (+ margin)
    int scratch_frames = 0;

    // Linear resampling state (device_rate != rate).
    double step = 1;      // ring frames per device frame
    double phase = 0;     // position between prev and the next frame
    float prev[MAX_CH] = {};
    float cur[MAX_CH] = {};
    bool have_cur = false;
    int left = 0; // rendered ring frames at the front of scratch

    std::atomic<float> volume{1};
    std::atomic<bool> running{false};
    uint32_t dither = 22222;
};

static void fill(AudOutput * o, int16_t * dst)
{
    int ch = o->out_ch;
    float vol = o->volume.load(std::memory_order_relaxed);
    float * bufs[MAX_CH] = {o->scratch[0], o->scratch[ch > 1 ? 1 : 0]};

    if (o->device_rate == o->rate)
    {
        audring_render(o->ring, bufs, ch, o->frames);
        for (int i = 0; i < o->frames; i++)
            for (int c = 0; c < ch; c++)
            {
                o->dither = o->dither * 1664525u + 1013904223u;
                float d = ((int)(o->dither >> 16) - 32768) * (1.0f / 32768.0f) * 0.5f;
                float v = bufs[c][i] * vol * 32767.0f + d;
                if (v > 32767.0f) v = 32767.0f;
                if (v < -32768.0f) v = -32768.0f;
                dst[i * ch + c] = (int16_t)lrintf(v);
            }
        return;
    }

    // Ring frames this buffer needs; frames rendered but not used yet stay
    // at the front of scratch for the next buffer.
    int need = (int)(o->phase + o->step * o->frames) + 2;
    if (need > o->scratch_frames)
        need = o->scratch_frames;
    if (o->left < need)
    {
        float * at[MAX_CH] = {bufs[0] + o->left, bufs[1] + o->left};
        audring_render(o->ring, at, ch, need - o->left);
        o->left = need;
    }
    int used = 0;
    if (!o->have_cur)
    {
        for (int c = 0; c < ch; c++)
            o->cur[c] = bufs[c][0];
        used = 1;
        o->have_cur = true;
    }
    for (int i = 0; i < o->frames; i++)
    {
        while (o->phase >= 1)
        {
            o->phase -= 1;
            for (int c = 0; c < ch; c++)
            {
                o->prev[c] = o->cur[c];
                if (used < o->left)
                    o->cur[c] = bufs[c][used];
            }
            if (used < o->left)
                used++;
        }
        for (int c = 0; c < ch; c++)
        {
            float s = o->prev[c] + (o->cur[c] - o->prev[c]) * (float)o->phase;
            float v = s * vol * 32767.0f;
            if (v > 32767.0f) v = 32767.0f;
            if (v < -32768.0f) v = -32768.0f;
            dst[i * ch + c] = (int16_t)lrintf(v);
        }
        o->phase += o->step;
    }
    int rest = o->left - used;
    for (int c = 0; c < ch; c++)
        memmove(bufs[c], bufs[c] + used, sizeof(float) * rest);
    o->left = rest;
}

static void callback(SLAndroidSimpleBufferQueueItf queue, void * ctx)
{
    auto o = (AudOutput *)ctx;
    if (!o->running.load(std::memory_order_acquire))
        return;
    int16_t * buf = o->buffers[o->next];
    o->next = (o->next + 1) % NUM_BUFFERS;
    fill(o, buf);
    (*queue)->Enqueue(queue, buf, o->frames * o->out_ch * sizeof(int16_t));
}

static bool create_player(AudOutput * o, int rate)
{
    SLDataLocator_AndroidSimpleBufferQueue loc = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, NUM_BUFFERS};
    SLDataFormat_PCM fmt = {SL_DATAFORMAT_PCM,
                            (SLuint32)o->out_ch,
                            (SLuint32)rate * 1000,
                            SL_PCMSAMPLEFORMAT_FIXED_16,
                            SL_PCMSAMPLEFORMAT_FIXED_16,
                            o->out_ch == 1 ? SL_SPEAKER_FRONT_CENTER
                                           : (SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT),
                            SL_BYTEORDER_LITTLEENDIAN};
    SLDataSource src = {&loc, &fmt};
    SLDataLocator_OutputMix out_loc = {SL_DATALOCATOR_OUTPUTMIX, mix_obj};
    SLDataSink sink = {&out_loc, nullptr};

    const SLInterfaceID ids[] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE};
    const SLboolean req[] = {SL_BOOLEAN_TRUE};
    if ((*engine)->CreateAudioPlayer(engine, &o->player_obj, &src, &sink, 1, ids, req) != SL_RESULT_SUCCESS)
    {
        o->player_obj = nullptr;
        return false;
    }
    if ((*o->player_obj)->Realize(o->player_obj, SL_BOOLEAN_FALSE) != SL_RESULT_SUCCESS)
    {
        (*o->player_obj)->Destroy(o->player_obj);
        o->player_obj = nullptr;
        return false;
    }
    (*o->player_obj)->GetInterface(o->player_obj, SL_IID_PLAY, &o->play);
    (*o->player_obj)->GetInterface(o->player_obj, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &o->queue);
    (*o->queue)->RegisterCallback(o->queue, callback, o);
    o->device_rate = rate;
    return true;
}

AudOutput * audout_create(int channels, int rate, int buffer_frames, int ring_frames)
{
    if (channels < 1 || rate < 1 || !ensure_engine())
        return nullptr;
    auto o = new AudOutput;
    o->channels = channels;
    o->out_ch = channels >= 2 ? 2 : 1;
    o->rate = rate;

    bool ok = create_player(o, rate);
    if (!ok && rate > 48000)
        ok = create_player(o, 48000);
    if (!ok && rate != 44100)
        ok = create_player(o, 44100);
    if (!ok)
    {
        LOG("OpenSL refused %d Hz, %d channels", rate, channels);
        delete o;
        return nullptr;
    }

    // Buffers about 20 ms (or the device's burst size), at the device rate.
    int frames = buffer_frames > 0 ? buffer_frames : o->device_rate / 50;
    while (frames < o->device_rate / 100)
        frames *= 2;
    o->frames = frames;
    o->step = (double)rate / o->device_rate;
    o->scratch_frames = (int)(frames * o->step) + 4;
    for (auto & b : o->buffers)
        b = (int16_t *)calloc((size_t)frames * o->out_ch, sizeof(int16_t));
    for (int c = 0; c < MAX_CH; c++)
        o->scratch[c] = (float *)calloc((size_t)o->scratch_frames, sizeof(float));
    o->ring = audring_create(channels, ring_frames);
    return o;
}

void audout_destroy(AudOutput * o)
{
    if (!o)
        return;
    o->running.store(false);
    if (o->player_obj)
        (*o->player_obj)->Destroy(o->player_obj);
    for (auto b : o->buffers)
        free(b);
    for (auto s : o->scratch)
        free(s);
    audring_destroy(o->ring);
    delete o;
}

AudRing * audout_ring(AudOutput * o) { return o->ring; }
int audout_device_rate(AudOutput * o) { return o->device_rate; }

double audout_queued_seconds(AudOutput * o)
{
    return (double)o->frames * NUM_BUFFERS / o->device_rate;
}

bool audout_start(AudOutput * o)
{
    if (o->running.load())
    {
        SLuint32 state = 0;
        (*o->play)->GetPlayState(o->play, &state);
        if (state != SL_PLAYSTATE_PLAYING)
            (*o->play)->SetPlayState(o->play, SL_PLAYSTATE_PLAYING);
        return true;
    }
    o->running.store(true, std::memory_order_release);
    (*o->queue)->Clear(o->queue);
    // Prime the queue; the callback keeps it full from then on.
    for (int i = 0; i < NUM_BUFFERS; i++)
        callback(o->queue, o);
    return (*o->play)->SetPlayState(o->play, SL_PLAYSTATE_PLAYING) == SL_RESULT_SUCCESS;
}

void audout_stop(AudOutput * o)
{
    if (!o->running.exchange(false))
        return;
    (*o->play)->SetPlayState(o->play, SL_PLAYSTATE_STOPPED);
    (*o->queue)->Clear(o->queue);
    o->have_cur = false;
    o->phase = 0;
    o->left = 0;
}

void audout_set_paused(AudOutput * o, bool paused)
{
    audring_set_paused(o->ring, paused);
    if (!o->running.load())
        return;
    (*o->play)->SetPlayState(o->play, paused ? SL_PLAYSTATE_PAUSED : SL_PLAYSTATE_PLAYING);
}

void audout_set_volume(AudOutput * o, float volume)
{
    o->volume.store(volume < 0 ? 0 : volume, std::memory_order_relaxed);
}
