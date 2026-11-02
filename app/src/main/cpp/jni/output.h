/*
 * output.h
 *
 * The output plugin: an OpenSL ES buffer-queue player (Android 2.3 and
 * later) whose callback plays the ring the decoding thread fills, through
 * audring_render (audcore/core/output.cc), so the visualization tap sees
 * what is heard. The ring runs at the effect chain's output format; when
 * the device refuses that rate (hi-res files on old Android) the callback
 * resamples linearly to 48 kHz. Samples go out as 16-bit PCM, which every
 * Android version plays.
 */

#ifndef AUDJNI_OUTPUT_H
#define AUDJNI_OUTPUT_H

#include <AudOutput.h>

struct AudOutput;

AudOutput * audout_create(int channels, int rate, int buffer_frames,
                          int ring_frames);
void audout_destroy(AudOutput * out);
AudRing * audout_ring(AudOutput * out);
/* Starts (or resumes) the hardware. */
bool audout_start(AudOutput * out);
void audout_stop(AudOutput * out);
void audout_set_paused(AudOutput * out, bool paused);
void audout_set_volume(AudOutput * out, float volume);
/* Rate the device actually plays at. */
int audout_device_rate(AudOutput * out);
/* Seconds of audio queued in OpenSL buffers past the ring. */
double audout_queued_seconds(AudOutput * out);

#endif /* AUDJNI_OUTPUT_H */
