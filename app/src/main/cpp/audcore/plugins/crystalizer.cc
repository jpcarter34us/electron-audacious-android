// Upstream plugin crystalizer/crystalizer.cc, compiled unchanged inside its own namespace so
// the plugins' file-level names (aud_plugin_instance, buffers) stay apart.

#include <libaudcore/i18n.h>
#include <libaudcore/plugin.h>
#include <libaudcore/preferences.h>
#include <libaudcore/runtime.h>

namespace audkit_crystalizer
{
#include "../upstream-plugins/crystalizer/crystalizer.cc"
}

EffectPlugin * audkit_effect_crystalizer() { return &audkit_crystalizer::aud_plugin_instance; }
