/*
 * decoders.h
 *
 * Songs decoded natively, to interleaved float: FLAC (dr_flac), MP3
 * (dr_mp3), WAV/AIFF (dr_wav) and Ogg Vorbis (stb_vorbis). They read from
 * a ByteSource, which is a local file or a file still being downloaded:
 * reads past what has arrived wait for it, so network songs play while
 * they download and seeking works from the cache, as in the iOS port's
 * StreamDecoder. The Java side reports download progress.
 */

#ifndef AUDJNI_DECODERS_H
#define AUDJNI_DECODERS_H

#include <stdint.h>
#include <string>

struct ByteSource;

/* fd is taken over (closed by bytesource_destroy). */
ByteSource * bytesource_create(int fd);
/* available: bytes on disk so far; total: final size or -1; complete: no
 * more will come. */
void bytesource_update(ByteSource * s, int64_t available, int64_t total, bool complete);
/* The download failed: pending and later reads past the data fail. */
void bytesource_fail(ByteSource * s);
/* Unblocks every read for good (the song is being closed). */
void bytesource_cancel(ByteSource * s);
void bytesource_destroy(ByteSource * s);

struct NativeDecoder;

/* hint: file extension, or empty. Returns null if no native decoder reads
 * the data (the Java side then tries MediaCodec); *error says why. */
NativeDecoder * decoder_open(ByteSource * src, const char * hint, std::string * error);
int decoder_channels(NativeDecoder * d);
int decoder_rate(NativeDecoder * d);
/* Total frames, or -1 when unknown (yet). */
int64_t decoder_frames(NativeDecoder * d);
/* Up to max_frames frames; 0 at the end; -1 on a read error. */
int decoder_read(NativeDecoder * d, float * out, int max_frames);
bool decoder_seek(NativeDecoder * d, int64_t frame);
void decoder_close(NativeDecoder * d);

#endif /* AUDJNI_DECODERS_H */
