/*
 * AudOutput.h
 *
 * Real-time side of the player, in C so the audio render thread never runs
 * Swift code: a single-producer/single-consumer ring of interleaved float
 * samples (the decoding thread writes processed audio, the render callback
 * reads it), and the visualization tap the render callback feeds with what
 * is actually being heard.
 */

#ifndef AUDOUTPUT_H
#define AUDOUTPUT_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct AudRing AudRing;

AudRing * audring_create(int channels, int capacity_frames);
void audring_destroy(AudRing * ring);
int audring_channels(const AudRing * ring);
int audring_capacity(const AudRing * ring);

/* Producer (decoding thread). Writes up to `frames` frames; returns how
 * many fit. */
int audring_write(AudRing * ring, const float * interleaved, int frames);
int audring_space(const AudRing * ring);
/* Asks the consumer to drop everything buffered so far. Data written after
 * the request is kept. */
void audring_request_clear(AudRing * ring);

/* Consumer side, also safe to read from any thread. */
int audring_buffered(const AudRing * ring);
/* Absolute positions in frames since the ring was created. Cleared frames
 * count as consumed, so the frame written at audring_written() == N is
 * being heard once audring_consumed() passes N. */
uint64_t audring_consumed(const AudRing * ring);
uint64_t audring_written(const AudRing * ring);

/* While paused the render callback outputs silence and keeps the buffer. */
void audring_set_paused(AudRing * ring, bool paused);

/* The render callback: fills `channels` non-interleaved float buffers with
 * `frames` frames from the ring (silence on underrun) and feeds the
 * visualization tap. `buffers` is an array of channel pointers. */
void audring_render(AudRing * ring, float * const * buffers, int channels,
                    int frames);

/* ---- visualization tap ---- */

/* The latest 512 mono samples heard, and the peak level per channel
 * (0..1) since the previous call; returns the number of channels. */
int audvis_snapshot(float pcm[512], float * peaks, int max_channels);
void audvis_clear(void);

#ifdef __cplusplus
}
#endif

#endif /* AUDOUTPUT_H */
