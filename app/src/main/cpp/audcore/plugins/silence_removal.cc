// Upstream plugin silence-removal/silence-removal.cc, compiled unchanged inside its own namespace so
// the plugins' file-level names (aud_plugin_instance, buffers) stay apart.

#include <math.h>
#include <libaudcore/ringbuf.h>
#include <libaudcore/i18n.h>
#include <libaudcore/plugin.h>
#include <libaudcore/preferences.h>
#include <libaudcore/runtime.h>

namespace audkit_silence_removal
{
#include "../upstream-plugins/silence-removal/silence-removal.cc"
}

EffectPlugin * audkit_effect_silence_removal() { return &audkit_silence_removal::aud_plugin_instance; }
