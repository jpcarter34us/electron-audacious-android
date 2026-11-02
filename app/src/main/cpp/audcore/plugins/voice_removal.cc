// Upstream plugin voice_removal/voice_removal.cc, compiled unchanged inside its own namespace so
// the plugins' file-level names (aud_plugin_instance, buffers) stay apart.

#include <libaudcore/i18n.h>
#include <libaudcore/plugin.h>
#include <libaudcore/preferences.h>
#include <libaudcore/runtime.h>

namespace audkit_voice_removal
{
#include "../upstream-plugins/voice_removal/voice_removal.cc"
}

EffectPlugin * audkit_effect_voice_removal() { return &audkit_voice_removal::aud_plugin_instance; }
