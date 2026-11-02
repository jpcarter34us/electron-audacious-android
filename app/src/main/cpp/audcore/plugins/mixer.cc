// Upstream plugin mixer/mixer.cc, compiled unchanged inside its own namespace so
// the plugins' file-level names (aud_plugin_instance, buffers) stay apart.

#include <stdlib.h>
#include <libaudcore/i18n.h>
#include <libaudcore/plugin.h>
#include <libaudcore/preferences.h>
#include <libaudcore/runtime.h>

namespace audkit_mixer
{
#include "../upstream-plugins/mixer/mixer.cc"
}

EffectPlugin * audkit_effect_mixer() { return &audkit_mixer::aud_plugin_instance; }
