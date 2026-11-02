// Upstream plugin crossfade/crossfade.cc, compiled unchanged inside its own namespace so
// the plugins' file-level names (aud_plugin_instance, buffers) stay apart.

#include <math.h>
#include <libaudcore/i18n.h>
#include <libaudcore/plugin.h>
#include <libaudcore/preferences.h>
#include <libaudcore/runtime.h>

namespace audkit_crossfade
{
#include "../upstream-plugins/crossfade/crossfade.cc"
}

EffectPlugin * audkit_effect_crossfade() { return &audkit_crossfade::aud_plugin_instance; }
