/*
 * AudCore.h
 *
 * C interface to the parts of libaudcore that run on iOS: the effect plugin
 * chain (libaudcore/effect.cc), the 10-band equalizer (equalizer.cc), the
 * visualization FFT (fft.cc) and the configuration store the plugins read
 * their settings from. Swift (AudKit) decodes audio with AVFoundation, runs
 * it through this pipeline and plays the result with AVAudioEngine, in the
 * same order as upstream output.cc: effects, then equalizer, then volume.
 */

#ifndef AUDCORE_H
#define AUDCORE_H

#include <stdbool.h>

#include "AudOutput.h"

#ifdef __cplusplus
extern "C" {
#endif

#define AUDCORE_EQ_NBANDS 10
#define AUDCORE_EQ_MAX_GAIN 12
#define AUDCORE_VIS_PCM 512
#define AUDCORE_VIS_FREQ 256

/* Registers the effect plugins (calling each one's init()) and the
 * equalizer. Load the saved configuration first, so plugins see it. */
void audcore_init(void);

/* ---- configuration (upstream's config file layout) ---- */

bool audcore_config_load(const char * path);
bool audcore_config_save(const char * path);
/* section NULL is the core ("audacious") section. */
void audcore_config_set(const char * section, const char * name,
                        const char * value);
/* Returns a malloc'd string; free() it. */
char * audcore_config_get(const char * section, const char * name);

/* ---- effect plugins, in the order they are applied ---- */

int audcore_effect_count(void);
const char * audcore_effect_id(int index);
const char * audcore_effect_name(int index);
const char * audcore_effect_about(int index);
bool audcore_effect_preserves_format(int index);
bool audcore_effect_has_prefs(int index);
bool audcore_effect_enabled(int index);
/* Takes effect immediately while playing: format-preserving effects are
 * inserted or drained in place, others restart the chain (as upstream). */
void audcore_effect_set_enabled(int index, bool enabled);

/* ---- plugin settings, generated from each plugin's PreferencesWidget
 * declarations (same JSON shape as ElectronPlayer's src/shared/prefs.ts) ----
 *
 * open: calls the plugin's prefs init() and returns
 *   {"name","hasApply","widgets":[...]} with a numeric "id" per widget,
 *   or NULL if the plugin has no settings.
 * values: {"<id>": value, ...} for every widget bound to a setting.
 * All returned strings are malloc'd; free() them. */
char * audcore_prefs_open(int effect);
char * audcore_prefs_values(int effect);
/* value is JSON-ish text: true/false, a number, or a string's raw text. */
void audcore_prefs_set(int effect, int widget, const char * value);
void audcore_prefs_button(int effect, int widget);
void audcore_prefs_apply(int effect);
void audcore_prefs_close(int effect);

/* ---- pipeline: call from one (decoding) thread only ---- */

/* Starts the enabled effects for this input format (effect_start). */
void audcore_pipeline_start(int channels, int rate);
/* Output format after the effects (the equalizer runs at this format). */
int audcore_pipeline_channels(void);
int audcore_pipeline_rate(void);
/* Effects then equalizer on interleaved float samples (samples = frames *
 * channels). *out points into a pipeline buffer, valid until the next call.
 * The output format may change if an effect was toggled; check
 * audcore_pipeline_channels()/rate() after each call. Returns the number of
 * output samples. */
int audcore_pipeline_process(const float * in, int samples, const float ** out);
/* Same, draining the effects' buffers: at the end of a song (and again with
 * end_of_playlist at the end of the last one). */
int audcore_pipeline_finish(const float * in, int samples,
                            bool end_of_playlist, const float ** out);
/* A seek: effects drop their buffers. Returns false if an effect (e.g.
 * crossfade) handles the flush itself. */
bool audcore_pipeline_flush(bool force);
/* Converts an output delay (ms) to input time, plus effects' read-ahead. */
int audcore_pipeline_adjust_delay(int delay_ms);

/* ---- equalizer (core settings equalizer_active/_preamp/_bands) ---- */

void audcore_eq_set_active(bool active);
bool audcore_eq_active(void);
void audcore_eq_set_preamp(double db);
double audcore_eq_preamp(void);
void audcore_eq_set_bands(const double bands[AUDCORE_EQ_NBANDS]);
void audcore_eq_bands(double bands[AUDCORE_EQ_NBANDS]);

/* ---- visualization ---- */

/* 512 mono PCM samples in, intensities of frequencies 1/512 ... 256/512 of
 * the sample rate out (libaudcore's calc_freq). */
void audcore_calc_freq(const float pcm[AUDCORE_VIS_PCM],
                       float freq[AUDCORE_VIS_FREQ]);

#ifdef __cplusplus
}
#endif

#endif /* AUDCORE_H */
