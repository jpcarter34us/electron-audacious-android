/*
 * decoders.cc
 *
 * ByteSource and the native decoders (see decoders.h).
 */

#include "decoders.h"

#include <algorithm>
#include <condition_variable>
#include <mutex>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define DR_FLAC_IMPLEMENTATION
#define DR_FLAC_NO_STDIO
#include "dr_flac.h"
#define DR_MP3_IMPLEMENTATION
#define DR_MP3_NO_STDIO
#include "dr_mp3.h"
#define DR_WAV_IMPLEMENTATION
#define DR_WAV_NO_STDIO
#include "dr_wav.h"

#include <opus_multistream.h>
#include <vector>

#define STB_VORBIS_NO_PUSHDATA_API
#include "stb_vorbis.c"
#undef L
#undef C
#undef R

/* ---- ByteSource ---- */

struct ByteSource
{
    int fd;
    std::mutex lock;
    std::condition_variable cond;
    int64_t available = 0;
    int64_t total = -1;
    bool complete = false;
    bool failed = false;
    bool cancelled = false;
};

ByteSource * bytesource_create(int fd)
{
    auto s = new ByteSource;
    s->fd = fd;
    return s;
}

void bytesource_update(ByteSource * s, int64_t available, int64_t total, bool complete)
{
    std::lock_guard<std::mutex> g(s->lock);
    s->available = available;
    s->total = complete ? available : total;
    s->complete = complete;
    s->cond.notify_all();
}

void bytesource_fail(ByteSource * s)
{
    std::lock_guard<std::mutex> g(s->lock);
    s->failed = true;
    s->cond.notify_all();
}

void bytesource_cancel(ByteSource * s)
{
    std::lock_guard<std::mutex> g(s->lock);
    s->cancelled = true;
    s->cond.notify_all();
}

void bytesource_destroy(ByteSource * s)
{
    if (!s)
        return;
    if (s->fd >= 0)
        close(s->fd);
    delete s;
}

namespace
{
/* A decoder's read position in a ByteSource. */
struct Cursor
{
    ByteSource * src;
    int64_t pos = 0;
    bool error = false;

    /* Waits until data at pos has arrived (or will not). */
    size_t read(void * out, size_t n)
    {
        int64_t avail;
        {
            std::unique_lock<std::mutex> g(src->lock);
            src->cond.wait(g, [&] {
                return src->available > pos || src->complete || src->failed || src->cancelled;
            });
            avail = src->available;
            if (avail <= pos)
            {
                if (src->failed || src->cancelled)
                    error = true;
                return 0;
            }
        }
        size_t want = n < (size_t)(avail - pos) ? n : (size_t)(avail - pos);
        size_t done = 0;
        while (done < want)
        {
            ssize_t r = pread(src->fd, (char *)out + done, want - done, (off_t)(pos + done));
            if (r <= 0)
            {
                error = true;
                break;
            }
            done += (size_t)r;
        }
        pos += done;
        return done;
    }

    /* The final size, waiting for it if the server did not say. */
    int64_t length()
    {
        std::unique_lock<std::mutex> g(src->lock);
        src->cond.wait(g, [&] { return src->total >= 0 || src->failed || src->cancelled; });
        return src->total;
    }

    bool seek(int64_t offset, int origin)
    {
        int64_t target;
        if (origin == SEEK_SET)
            target = offset;
        else if (origin == SEEK_CUR)
            target = pos + offset;
        else
        {
            int64_t len = length();
            if (len < 0)
                return false;
            target = len + offset;
        }
        if (target < 0)
            return false;
        pos = target;
        return true;
    }
};

size_t cursor_read(void * user, void * out, size_t n) { return ((Cursor *)user)->read(out, n); }

drflac_bool32 flac_seek(void * user, int offset, drflac_seek_origin origin)
{
    return ((Cursor *)user)->seek(offset, origin == DRFLAC_SEEK_SET ? SEEK_SET : origin == DRFLAC_SEEK_CUR ? SEEK_CUR : SEEK_END);
}
drflac_bool32 flac_tell(void * user, drflac_int64 * cursor)
{
    *cursor = ((Cursor *)user)->pos;
    return DRFLAC_TRUE;
}
drmp3_bool32 mp3_seek(void * user, int offset, drmp3_seek_origin origin)
{
    return ((Cursor *)user)->seek(offset, origin == DRMP3_SEEK_SET ? SEEK_SET : origin == DRMP3_SEEK_CUR ? SEEK_CUR : SEEK_END);
}
drmp3_bool32 mp3_tell(void * user, drmp3_int64 * cursor)
{
    *cursor = ((Cursor *)user)->pos;
    return DRMP3_TRUE;
}
drwav_bool32 wav_seek(void * user, int offset, drwav_seek_origin origin)
{
    return ((Cursor *)user)->seek(offset, origin == DRWAV_SEEK_SET ? SEEK_SET : origin == DRWAV_SEEK_CUR ? SEEK_CUR : SEEK_END);
}
drwav_bool32 wav_tell(void * user, drwav_int64 * cursor)
{
    *cursor = ((Cursor *)user)->pos;
    return DRWAV_TRUE;
}

/* stb_vorbis reads a FILE*; funopen gives it one over the cursor. */
int file_read(void * user, char * buf, int n) { return (int)((Cursor *)user)->read(buf, (size_t)n); }
fpos_t file_seek(void * user, fpos_t offset, int whence)
{
    auto c = (Cursor *)user;
    return c->seek(offset, whence) ? (fpos_t)c->pos : (fpos_t)-1;
}

enum class Kind
{
    flac,
    mp3,
    wav,
    vorbis,
    opus
};

/* Ogg Opus (RFC 7845) through libopus, which the app carries for Sendspin:
 * Android's MediaCodec decodes Opus only from 5.0. A page reader, the
 * OpusHead header, pre-skip, and seeking by page granule positions. */
struct OggOpus
{
    Cursor * c = nullptr;
    OpusMSDecoder * dec = nullptr;
    int channels = 0;
    int preskip = 0;
    int64_t data_start = 0;   // first audio page
    int64_t granule = 0;      // samples decoded so far, in granule units
    int64_t drop = 0;         // samples still to discard (pre-skip, seeking)
    std::vector<unsigned char> page, packet;
    std::vector<int> lacing;
    size_t seg = 0, body_pos = 0, nsegs = 0;
    int64_t page_granule = -1;
    bool eof = false;
    std::vector<float> pcm;
    size_t pcm_pos = 0;

    /* The next page; false at the end of the stream. */
    bool next_page()
    {
        unsigned char h[27];
        if (c->read(h, 27) != 27 || memcmp(h, "OggS", 4))
            return false;
        nsegs = h[26];
        unsigned char lace[255];
        if (c->read(lace, nsegs) != nsegs)
            return false;
        size_t len = 0;
        lacing.assign(lace, lace + nsegs);
        for (int l : lacing)
            len += (size_t)l;
        page.resize(len);
        if (c->read(page.data(), len) != len)
            return false;
        page_granule = 0;
        for (int i = 7; i >= 0; i--)
            page_granule = (page_granule << 8) | h[6 + i];
        seg = 0;
        body_pos = 0;
        return true;
    }

    /* The next whole packet (packets may span pages). */
    bool next_packet()
    {
        packet.clear();
        for (;;)
        {
            if (seg >= nsegs && !next_page())
                return !packet.empty();
            while (seg < nsegs)
            {
                int l = lacing[seg++];
                packet.insert(packet.end(), page.begin() + body_pos, page.begin() + body_pos + l);
                body_pos += (size_t)l;
                if (l < 255)
                    return true;
            }
        }
    }

    bool open(Cursor * cursor, int * out_channels, int64_t * frames)
    {
        c = cursor;
        if (!next_packet() || packet.size() < 19 || memcmp(packet.data(), "OpusHead", 8))
            return false;
        channels = packet[9];
        preskip = packet[10] | packet[11] << 8;
        int family = packet[18];
        int streams = 1, coupled = channels > 1 ? 1 : 0;
        unsigned char mapping[255] = {0, 1};
        if (family != 0)
        {
            if (packet.size() < 21u + channels)
                return false;
            streams = packet[19];
            coupled = packet[20];
            memcpy(mapping, packet.data() + 21, (size_t)channels);
        }
        else if (channels > 2)
            return false;
        int err = 0;
        dec = opus_multistream_decoder_create(48000, channels, streams, coupled, mapping, &err);
        if (!dec || err != OPUS_OK)
            return false;
        if (!next_packet() || packet.size() < 8 || memcmp(packet.data(), "OpusTags", 8))
            return false;
        // Audio starts on the page after the tags.
        seg = nsegs;
        data_start = c->pos;
        drop = preskip;
        *out_channels = channels;
        *frames = length();
        return true;
    }

    /* Total frames, from the last page's granule (the whole file is read
     * for this only when it is local or complete). */
    int64_t length()
    {
        int64_t len;
        {
            std::lock_guard<std::mutex> g(c->src->lock);
            if (!c->src->complete)
                return -1;
            len = c->src->total;
        }
        int64_t from = len > 65536 ? len - 65536 : 0;
        std::vector<unsigned char> tail((size_t)(len - from));
        int64_t save = c->pos;
        c->pos = from;
        size_t n = c->read(tail.data(), tail.size());
        c->pos = save;
        for (size_t i = n >= 27 ? n - 27 : 0; i + 27 <= n; i--)
        {
            if (!memcmp(tail.data() + i, "OggS", 4))
            {
                int64_t g = 0;
                for (int k = 7; k >= 0; k--)
                    g = (g << 8) | tail[i + 6 + k];
                return g > preskip ? g - preskip : -1;
            }
            if (i == 0)
                break;
        }
        return -1;
    }

    int read(float * out, int max_frames)
    {
        int done = 0;
        while (done < max_frames)
        {
            size_t avail = (pcm.size() - pcm_pos) / (size_t)channels;
            if (avail)
            {
                int take = (int)std::min(avail, (size_t)(max_frames - done));
                memcpy(out + (size_t)done * channels, pcm.data() + pcm_pos, sizeof(float) * (size_t)take * channels);
                pcm_pos += (size_t)take * channels;
                done += take;
                continue;
            }
            if (eof || !next_packet())
            {
                eof = true;
                break;
            }
            pcm.resize((size_t)5760 * channels);
            int n = opus_multistream_decode_float(dec, packet.data(), (opus_int32)packet.size(), pcm.data(), 5760, 0);
            if (n < 0)
                n = 0;
            pcm.resize((size_t)n * channels);
            pcm_pos = 0;
            if (drop > 0)
            {
                int64_t d = std::min<int64_t>(drop, n);
                pcm_pos = (size_t)d * channels;
                drop -= d;
            }
        }
        return done;
    }

    bool seek(int64_t frame)
    {
        int64_t target = frame + preskip;
        c->pos = data_start;
        seg = nsegs = 0;
        int64_t prev = 0;
        for (;;)
        {
            int64_t at = c->pos;
            if (!next_page())
                return false;
            if (page_granule >= target)
            {
                // Decode from this page; drop up to the target sample.
                c->pos = at;
                next_page();
                drop = std::max<int64_t>(0, target - prev);
                break;
            }
            if (page_granule > 0)
                prev = page_granule;
        }
        opus_multistream_decoder_ctl(dec, OPUS_RESET_STATE);
        pcm.clear();
        pcm_pos = 0;
        eof = false;
        return true;
    }

    ~OggOpus()
    {
        if (dec)
            opus_multistream_decoder_destroy(dec);
    }
};

bool has_ext(const char * hint, const char * const * list)
{
    if (!hint)
        return false;
    for (; *list; list++)
        if (!strcasecmp(hint, *list))
            return true;
    return false;
}
} // namespace

struct NativeDecoder
{
    Kind kind;
    Cursor cursor;
    int channels = 0;
    int rate = 0;
    int64_t frames = -1;
    drflac * flac = nullptr;
    drmp3 mp3;
    drwav wav;
    stb_vorbis * vorbis = nullptr;
    FILE * file = nullptr;
    OggOpus * opus = nullptr;
};

/* What the first bytes say the data is; false for formats left to
 * MediaCodec (MP4/M4A, Ogg Opus, ...). */
static bool sniff(Cursor & c, const char * hint, Kind * kind, std::string * error)
{
    unsigned char head[64] = {0};
    size_t n = c.read(head, sizeof head);
    c.pos = 0;
    if (n < 4)
    {
        *error = c.error ? "The download failed" : "The file is empty";
        return false;
    }
    if (!memcmp(head, "fLaC", 4))
        return *kind = Kind::flac, true;
    if (!memcmp(head, "OggS", 4))
    {
        if (n >= 35 && !memcmp(head + 29, "vorbis", 6))
            return *kind = Kind::vorbis, true;
        if (n >= 33 && !memcmp(head + 29, "FLAC", 4))
            return *kind = Kind::flac, true;
        if (n >= 36 && !memcmp(head + 28, "OpusHead", 8))
            return *kind = Kind::opus, true;
        *error = "Ogg stream that is not Vorbis, Opus or FLAC";
        return false;
    }
    if (!memcmp(head, "RIFF", 4) || !memcmp(head, "RF64", 4) || !memcmp(head, "FORM", 4) || !memcmp(head, "riff", 4))
        return *kind = Kind::wav, true;
    if (n >= 8 && !memcmp(head + 4, "ftyp", 4))
    {
        *error = "MP4 container";
        return false;
    }
    if (!memcmp(head, "ID3", 3) || (head[0] == 0xFF && (head[1] & 0xE0) == 0xE0))
    {
        // ID3 tags also precede some FLAC files; trust the hint for those.
        static const char * const flacs[] = {"flac", "fla", nullptr};
        *kind = has_ext(hint, flacs) ? Kind::flac : Kind::mp3;
        return true;
    }
    static const char * const mp3s[] = {"mp3", "mp2", "mpga", nullptr};
    if (has_ext(hint, mp3s))
        return *kind = Kind::mp3, true;
    *error = "Unknown format";
    return false;
}

NativeDecoder * decoder_open(ByteSource * src, const char * hint, std::string * error)
{
    auto d = new NativeDecoder;
    d->cursor.src = src;
    if (!sniff(d->cursor, hint, &d->kind, error))
    {
        delete d;
        return nullptr;
    }
    bool ok = false;
    switch (d->kind)
    {
    case Kind::flac:
        d->flac = drflac_open(cursor_read, flac_seek, flac_tell, &d->cursor, nullptr);
        if ((ok = d->flac != nullptr))
        {
            d->channels = d->flac->channels;
            d->rate = (int)d->flac->sampleRate;
            d->frames = d->flac->totalPCMFrameCount > 0 ? (int64_t)d->flac->totalPCMFrameCount : -1;
        }
        break;
    case Kind::mp3:
        if ((ok = drmp3_init(&d->mp3, cursor_read, mp3_seek, mp3_tell, nullptr, &d->cursor, nullptr)))
        {
            d->channels = (int)d->mp3.channels;
            d->rate = (int)d->mp3.sampleRate;
            // From the Xing/Info header when there is one; scanning the
            // whole file would wait for the download.
            if (d->mp3.totalPCMFrameCount != DRMP3_UINT64_MAX)
                d->frames = (int64_t)(d->mp3.totalPCMFrameCount - d->mp3.delayInPCMFrames - d->mp3.paddingInPCMFrames);
        }
        break;
    case Kind::wav:
        if ((ok = drwav_init(&d->wav, cursor_read, wav_seek, wav_tell, &d->cursor, nullptr)))
        {
            d->channels = (int)d->wav.channels;
            d->rate = (int)d->wav.sampleRate;
            d->frames = (int64_t)d->wav.totalPCMFrameCount;
        }
        break;
    case Kind::opus:
        d->opus = new OggOpus;
        if ((ok = d->opus->open(&d->cursor, &d->channels, &d->frames)))
            d->rate = 48000;
        break;
    case Kind::vorbis:
        d->file = funopen(&d->cursor, file_read, nullptr, file_seek, nullptr);
        if (d->file)
        {
            int err = 0;
            d->vorbis = stb_vorbis_open_file(d->file, 0, &err, nullptr);
            if ((ok = d->vorbis != nullptr))
            {
                stb_vorbis_info info = stb_vorbis_get_info(d->vorbis);
                d->channels = info.channels;
                d->rate = (int)info.sample_rate;
                unsigned int len = stb_vorbis_stream_length_in_samples(d->vorbis);
                d->frames = len ? (int64_t)len : -1;
            }
        }
        break;
    }
    if (!ok || d->channels < 1 || d->rate < 1)
    {
        *error = d->cursor.error ? "The download failed" : "Cannot decode this file";
        decoder_close(d);
        return nullptr;
    }
    return d;
}

int decoder_channels(NativeDecoder * d) { return d->channels; }
int decoder_rate(NativeDecoder * d) { return d->rate; }

/* Unknown for MP3 without a Xing/Info header and FLAC without a length in
 * STREAMINFO; the playlist entry's duration stands in then. */
int64_t decoder_frames(NativeDecoder * d) { return d->frames; }

int decoder_read(NativeDecoder * d, float * out, int max_frames)
{
    uint64_t n = 0;
    switch (d->kind)
    {
    case Kind::flac:
        n = drflac_read_pcm_frames_f32(d->flac, (drflac_uint64)max_frames, out);
        break;
    case Kind::mp3:
        n = drmp3_read_pcm_frames_f32(&d->mp3, (drmp3_uint64)max_frames, out);
        break;
    case Kind::wav:
        n = drwav_read_pcm_frames_f32(&d->wav, (drwav_uint64)max_frames, out);
        break;
    case Kind::opus:
        n = (uint64_t)d->opus->read(out, max_frames);
        break;
    case Kind::vorbis:
        n = (uint64_t)stb_vorbis_get_samples_float_interleaved(d->vorbis, d->channels, out, max_frames * d->channels);
        break;
    }
    if (n == 0 && d->cursor.error)
        return -1;
    return (int)n;
}

bool decoder_seek(NativeDecoder * d, int64_t frame)
{
    if (frame < 0)
        frame = 0;
    switch (d->kind)
    {
    case Kind::flac:
        return drflac_seek_to_pcm_frame(d->flac, (drflac_uint64)frame);
    case Kind::mp3:
        return drmp3_seek_to_pcm_frame(&d->mp3, (drmp3_uint64)frame);
    case Kind::wav:
        return drwav_seek_to_pcm_frame(&d->wav, (drwav_uint64)frame);
    case Kind::opus:
        return d->opus->seek(frame);
    case Kind::vorbis:
        return stb_vorbis_seek(d->vorbis, (unsigned int)frame) != 0;
    }
    return false;
}

void decoder_close(NativeDecoder * d)
{
    if (!d)
        return;
    switch (d->kind)
    {
    case Kind::flac:
        if (d->flac)
            drflac_close(d->flac);
        break;
    case Kind::mp3:
        if (d->channels)
            drmp3_uninit(&d->mp3);
        break;
    case Kind::wav:
        if (d->channels)
            drwav_uninit(&d->wav);
        break;
    case Kind::opus:
        delete d->opus;
        break;
    case Kind::vorbis:
        if (d->vorbis)
            stb_vorbis_close(d->vorbis);
        if (d->file)
            fclose(d->file);
        break;
    }
    delete d;
}
