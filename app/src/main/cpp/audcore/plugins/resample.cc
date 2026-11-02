// Upstream plugin resample/resample.cc, compiled unchanged inside its own namespace so
// the plugins' file-level names (aud_plugin_instance, buffers) stay apart.

#include <samplerate.h>
#include <libaudcore/audstrings.h>
#include <libaudcore/i18n.h>
#include <libaudcore/plugin.h>
#include <libaudcore/preferences.h>
#include <libaudcore/runtime.h>

namespace audkit_resample
{
#include "../upstream-plugins/resample/resample.cc"
}

EffectPlugin * audkit_effect_resample() { return &audkit_resample::aud_plugin_instance; }
