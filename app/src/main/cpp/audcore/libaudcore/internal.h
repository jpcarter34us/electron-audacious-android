/*
 * internal.h (AudKit shim)
 *
 * Declarations upstream libaudcore keeps private, for the ported sources
 * (equalizer.cc, fft.cc) and the AudKit engine (core/engine.cc).
 */

#ifndef LIBAUDCORE_INTERNAL_H
#define LIBAUDCORE_INTERNAL_H

#include <libaudcore/shim-config.h>

#include <stddef.h>

/* runtime.cc: bytes held by Index and RingBuf (index.cc, ringbuf.cc) */
extern size_t misc_bytes_allocated;

/* equalizer.cc */
void eq_init();
void eq_cleanup();
void eq_set_format(int new_channels, int new_rate);
void eq_filter(float * data, int samples);

/* fft.cc */
void calc_freq(const float data[512], float freq[256]);

#endif /* LIBAUDCORE_INTERNAL_H */
