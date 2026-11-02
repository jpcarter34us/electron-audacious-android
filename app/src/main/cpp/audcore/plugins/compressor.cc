// Upstream plugin compressor/compressor.cc, compiled unchanged inside its own namespace so
// the plugins' file-level names (aud_plugin_instance, buffers) stay apart.

#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <libaudcore/ringbuf.h>
#include <libaudcore/i18n.h>
#include <libaudcore/plugin.h>
#include <libaudcore/preferences.h>
#include <libaudcore/runtime.h>

namespace audkit_compressor
{
#include "../upstream-plugins/compressor/compressor.cc"
}

EffectPlugin * audkit_effect_compressor() { return &audkit_compressor::aud_plugin_instance; }
