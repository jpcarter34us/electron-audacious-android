#include "registry.h"

EffectPlugin * audkit_effect_bitcrusher();
EffectPlugin * audkit_effect_compressor();
EffectPlugin * audkit_effect_crossfade();
EffectPlugin * audkit_effect_crystalizer();
EffectPlugin * audkit_effect_echo();
EffectPlugin * audkit_effect_mixer();
EffectPlugin * audkit_effect_resample();
EffectPlugin * audkit_effect_silence_removal();
EffectPlugin * audkit_effect_speed_pitch();
EffectPlugin * audkit_effect_stereo();
EffectPlugin * audkit_effect_voice_removal();

// Ids are upstream's plugin folder names.
static const AudkitPluginEntry entries[] = {
    {"bitcrusher", audkit_effect_bitcrusher},
    {"compressor", audkit_effect_compressor},
    {"crossfade", audkit_effect_crossfade},
    {"crystalizer", audkit_effect_crystalizer},
    {"echo_plugin", audkit_effect_echo},
    {"mixer", audkit_effect_mixer},
    {"resample", audkit_effect_resample},
    {"silence-removal", audkit_effect_silence_removal},
    {"speedpitch", audkit_effect_speed_pitch},
    {"stereo_plugin", audkit_effect_stereo},
    {"voice_removal", audkit_effect_voice_removal},
};

ArrayRef<AudkitPluginEntry> audkit_effect_plugins() { return entries; }
