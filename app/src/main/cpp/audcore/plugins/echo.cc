// Upstream plugin echo_plugin/echo.cc, compiled unchanged inside its own namespace so
// the plugins' file-level names (aud_plugin_instance, buffers) stay apart.

#include <libaudcore/i18n.h>
#include <libaudcore/plugin.h>
#include <libaudcore/preferences.h>
#include <libaudcore/runtime.h>

namespace audkit_echo
{
#include "../upstream-plugins/echo_plugin/echo.cc"
}

EffectPlugin * audkit_effect_echo() { return &audkit_echo::aud_plugin_instance; }
