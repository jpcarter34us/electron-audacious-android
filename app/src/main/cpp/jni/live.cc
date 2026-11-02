/*
 * live.cc
 *
 * Decoders for Sendspin's compressed streams (sendspin-js decoder.ts):
 * FLAC frames, each chunk decoded behind the stream header the server
 * sends as codec_header, and Opus packets through libopus. Both work on
 * every Android version, unlike MediaCodec's FLAC and Opus (Android 5+).
 */

#include "live.h"

#include "dr_flac.h"
#include <opus.h>

#include <stdlib.h>
#include <string.h>
#include <vector>

struct LiveFlac
{
    std::vector<unsigned char> buffer; // header, then the chunk
    std::vector<float> block;
    size_t header = 0;
    int channels;
};

LiveFlac * liveflac_create(const unsigned char * header, int len, int channels)
{
    if (len < 4 || memcmp(header, "fLaC", 4))
        return nullptr;
    auto f = new LiveFlac;
    f->buffer.assign(header, header + len);
    f->header = (size_t)len;
    f->channels = channels;
    return f;
}

int liveflac_decode(LiveFlac * f, const unsigned char * data, int len, std::vector<float> & out)
{
    out.clear();
    f->buffer.resize(f->header);
    f->buffer.insert(f->buffer.end(), data, data + len);
    drflac * flac = drflac_open_memory(f->buffer.data(), f->buffer.size(), nullptr);
    if (!flac)
        return -1;
    int ch = flac->channels;
    const int per = 4096;
    f->block.resize((size_t)per * ch);
    for (;;)
    {
        drflac_uint64 n = drflac_read_pcm_frames_f32(flac, (drflac_uint64)per, f->block.data());
        if (!n)
            break;
        out.insert(out.end(), f->block.begin(), f->block.begin() + (size_t)n * ch);
    }
    drflac_close(flac);
    return ch;
}

void liveflac_destroy(LiveFlac * f) { delete f; }

struct LiveOpus
{
    OpusDecoder * dec;
    int channels;
};

LiveOpus * liveopus_create(int rate, int channels)
{
    int err = 0;
    OpusDecoder * dec = opus_decoder_create(rate, channels, &err);
    if (!dec || err != OPUS_OK)
        return nullptr;
    auto o = new LiveOpus;
    o->dec = dec;
    o->channels = channels;
    return o;
}

int liveopus_decode(LiveOpus * o, const unsigned char * data, int len, std::vector<float> & out)
{
    // 120 ms at 48 kHz is the longest Opus packet.
    out.resize((size_t)5760 * o->channels);
    int n = opus_decode_float(o->dec, data, len, out.data(), 5760, 0);
    if (n < 0)
    {
        out.clear();
        return n;
    }
    out.resize((size_t)n * o->channels);
    return n;
}

void liveopus_destroy(LiveOpus * o)
{
    if (!o)
        return;
    opus_decoder_destroy(o->dec);
    delete o;
}
