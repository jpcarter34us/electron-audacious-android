/*
 * registry.h (AudKit)
 *
 * The effect plugins compiled into the app. Upstream loads plugins as
 * shared modules at run time; iOS apps cannot, so each one is built in
 * (plugins/<id>.cc) and listed here.
 */

#ifndef AUDKIT_PLUGIN_REGISTRY_H
#define AUDKIT_PLUGIN_REGISTRY_H

#include <libaudcore/objects.h>

class EffectPlugin;

struct AudkitPluginEntry
{
    const char * id;
    EffectPlugin * (*get)();
};

ArrayRef<AudkitPluginEntry> audkit_effect_plugins();

#endif /* AUDKIT_PLUGIN_REGISTRY_H */
