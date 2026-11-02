/*
 * output.cc (AudKit)
 *
 * Ring buffer and render callback for the AVAudioEngine output. Indices
 * only grow (64-bit), so full and empty are told apart without a spare slot;
 * the producer publishes with release and the consumer reads with acquire.
 * A clear moves the read position forward, so positions stay comparable:
 * the frame written at write position N is heard when the read position
 * passes N.
 */

#include "AudOutput.h"

#include <atomic>
#include <math.h>
#include <stdlib.h>
#include <string.h>

struct AudRing
{
    int channels;
    int capacity; // frames
    float * data;
    std::atomic<uint64_t> write_pos{0}; // frames
    std::atomic<uint64_t> read_pos{0};
    std::atomic<uint64_t> clear_to{0}; // drop everything before this frame
    std::atomic<bool> paused{false};
};

AudRing * audring_create(int channels, int capacity_frames)
{
    auto ring = new AudRing;
    ring->channels = channels;
    ring->capacity = capacity_frames;
    ring->data = (float *)calloc((size_t)channels * capacity_frames, sizeof(float));
    return ring;
}

void audring_destroy(AudRing * ring)
{
    if (!ring)
        return;
    free(ring->data);
    delete ring;
}

int audring_channels(const AudRing * ring) { return ring->channels; }
int audring_capacity(const AudRing * ring) { return ring->capacity; }

int audring_space(const AudRing * ring)
{
    uint64_t w = ring->write_pos.load(std::memory_order_relaxed);
    uint64_t r = ring->read_pos.load(std::memory_order_acquire);
    return ring->capacity - (int)(w - r);
}

int audring_buffered(const AudRing * ring)
{
    uint64_t w = ring->write_pos.load(std::memory_order_acquire);
    uint64_t r = ring->read_pos.load(std::memory_order_acquire);
    uint64_t c = ring->clear_to.load(std::memory_order_acquire);
    if (c > r)
        r = c < w ? c : w;
    return (int)(w - r);
}

uint64_t audring_consumed(const AudRing * ring)
{
    return ring->read_pos.load(std::memory_order_acquire);
}

uint64_t audring_written(const AudRing * ring)
{
    return ring->write_pos.load(std::memory_order_acquire);
}

int audring_write(AudRing * ring, const float * in, int frames)
{
    int space = audring_space(ring);
    if (frames > space)
        frames = space;
    if (frames <= 0)
        return 0;

    uint64_t w = ring->write_pos.load(std::memory_order_relaxed);
    int ch = ring->channels;
    int start = (int)(w % ring->capacity);
    int first = frames < ring->capacity - start ? frames : ring->capacity - start;
    memcpy(ring->data + (size_t)start * ch, in, sizeof(float) * first * ch);
    if (frames > first)
        memcpy(ring->data, in + (size_t)first * ch, sizeof(float) * (frames - first) * ch);

    ring->write_pos.store(w + frames, std::memory_order_release);
    return frames;
}

void audring_request_clear(AudRing * ring)
{
    ring->clear_to.store(ring->write_pos.load(std::memory_order_relaxed),
                         std::memory_order_release);
}

void audring_set_paused(AudRing * ring, bool paused)
{
    ring->paused.store(paused, std::memory_order_release);
}

/* ---- visualization tap ---- */

namespace
{
const int VIS_LEN = 2048;
const int VIS_MAX_CH = 8;
float vis_mono[VIS_LEN];
std::atomic<uint32_t> vis_pos{0};
std::atomic<int> vis_channels{0};
// Peaks as bit patterns so they can be exchanged atomically.
std::atomic<uint32_t> vis_peaks[VIS_MAX_CH];

void raise_peak(int c, float v)
{
    uint32_t bits;
    memcpy(&bits, &v, 4);
    uint32_t old = vis_peaks[c].load(std::memory_order_relaxed);
    float oldf;
    do
    {
        memcpy(&oldf, &old, 4);
        if (oldf >= v)
            return;
    } while (!vis_peaks[c].compare_exchange_weak(old, bits));
}

void vis_feed(float * const * buffers, int channels, int frames)
{
    uint32_t pos = vis_pos.load(std::memory_order_relaxed);
    int nch = channels < VIS_MAX_CH ? channels : VIS_MAX_CH;
    float peak[VIS_MAX_CH] = {0};
    for (int i = 0; i < frames; i++)
    {
        // pcm_to_mono() in visualization.cc: the first two channels
        float m = channels == 1 ? buffers[0][i] : (buffers[0][i] + buffers[1][i]) / 2;
        vis_mono[(pos + i) % VIS_LEN] = m;
        for (int c = 0; c < nch; c++)
        {
            float a = fabsf(buffers[c][i]);
            if (a > peak[c])
                peak[c] = a;
        }
    }
    vis_pos.store(pos + frames, std::memory_order_release);
    vis_channels.store(nch, std::memory_order_relaxed);
    for (int c = 0; c < nch; c++)
        raise_peak(c, peak[c]);
}
} // namespace

int audvis_snapshot(float pcm[512], float * peaks, int max_channels)
{
    uint32_t pos = vis_pos.load(std::memory_order_acquire);
    for (int i = 0; i < 512; i++)
        pcm[i] = vis_mono[(pos + VIS_LEN - 512 + i) % VIS_LEN];
    int nch = vis_channels.load(std::memory_order_relaxed);
    if (nch > max_channels)
        nch = max_channels;
    for (int c = 0; c < nch; c++)
    {
        uint32_t bits = vis_peaks[c].exchange(0);
        memcpy(&peaks[c], &bits, 4);
    }
    return nch;
}

void audvis_clear(void)
{
    memset(vis_mono, 0, sizeof vis_mono);
    for (auto & p : vis_peaks)
        p.store(0);
}

/* ---- render ---- */

void audring_render(AudRing * ring, float * const * buffers, int channels,
                    int frames)
{
    int done = 0;

    if (!ring->paused.load(std::memory_order_acquire))
    {
        uint64_t w = ring->write_pos.load(std::memory_order_acquire);
        uint64_t r = ring->read_pos.load(std::memory_order_relaxed);
        uint64_t c = ring->clear_to.load(std::memory_order_acquire);
        if (c > r)
            r = c < w ? c : w;

        int avail = (int)(w - r);
        int take = frames < avail ? frames : avail;
        int ch = ring->channels;
        for (int i = 0; i < take; i++)
        {
            const float * frame = ring->data + (size_t)((r + i) % ring->capacity) * ch;
            for (int k = 0; k < channels; k++)
                buffers[k][i] = frame[k < ch ? k : ch - 1];
        }
        done = take;
        ring->read_pos.store(r + take, std::memory_order_release);
    }

    for (int k = 0; k < channels; k++)
        memset(buffers[k] + done, 0, sizeof(float) * (frames - done));

    if (done)
        vis_feed(buffers, channels, done);
}
