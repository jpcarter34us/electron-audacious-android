// Upstream plugin speedpitch/speed-pitch.cc, compiled unchanged inside its own namespace so
// the plugins' file-level names (aud_plugin_instance, buffers) stay apart.

#include <math.h>
#include <samplerate.h>
#include <libaudcore/hook.h>
#include <libaudcore/i18n.h>
#include <libaudcore/plugin.h>
#include <libaudcore/preferences.h>
#include <libaudcore/runtime.h>

namespace audkit_speed_pitch
{
#include "../upstream-plugins/speedpitch/speed-pitch.cc"
}

EffectPlugin * audkit_effect_speed_pitch() { return &audkit_speed_pitch::aud_plugin_instance; }
