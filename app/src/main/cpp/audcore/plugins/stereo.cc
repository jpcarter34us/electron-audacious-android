// Upstream plugin stereo_plugin/stereo.cc, compiled unchanged inside its own namespace so
// the plugins' file-level names (aud_plugin_instance, buffers) stay apart.

#include <libaudcore/i18n.h>
#include <libaudcore/plugin.h>
#include <libaudcore/preferences.h>
#include <libaudcore/runtime.h>

namespace audkit_stereo
{
#include "../upstream-plugins/stereo_plugin/stereo.cc"
}

EffectPlugin * audkit_effect_stereo() { return &audkit_stereo::aud_plugin_instance; }
