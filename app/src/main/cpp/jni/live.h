/*
 * live.h
 *
 * Sendspin FLAC and Opus chunk decoders (see live.cc).
 */

#ifndef AUDJNI_LIVE_H
#define AUDJNI_LIVE_H

#include <vector>

struct LiveFlac;
LiveFlac * liveflac_create(const unsigned char * header, int len, int channels);
/* Decodes one chunk of FLAC frames; returns the channel count, or -1. */
int liveflac_decode(LiveFlac * f, const unsigned char * data, int len, std::vector<float> & out);
void liveflac_destroy(LiveFlac * f);

struct LiveOpus;
LiveOpus * liveopus_create(int rate, int channels);
/* Decodes one packet; returns frames, or a negative libopus error. */
int liveopus_decode(LiveOpus * o, const unsigned char * data, int len, std::vector<float> & out);
void liveopus_destroy(LiveOpus * o);

#endif /* AUDJNI_LIVE_H */
