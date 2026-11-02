// Upstream plugin bitcrusher/bitcrusher.cc, compiled unchanged inside its own namespace so
// the plugins' file-level names (aud_plugin_instance, buffers) stay apart.

#include <cmath>
#include <libaudcore/i18n.h>
#include <libaudcore/plugin.h>
#include <libaudcore/preferences.h>
#include <libaudcore/runtime.h>

namespace audkit_bitcrusher
{
#include "../upstream-plugins/bitcrusher/bitcrusher.cc"
}

EffectPlugin * audkit_effect_bitcrusher() { return &audkit_bitcrusher::aud_plugin_instance; }
