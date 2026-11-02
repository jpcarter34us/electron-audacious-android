/*
 * plugin.h (AudKit shim)
 *
 * The Plugin and EffectPlugin classes from upstream libaudcore's plugin.h,
 * unchanged. Other plugin types (input, output, transport, ...) are left out:
 * on iOS decoding and output go through AVFoundation, and only effect
 * plugins are compiled in (see plugins/registry.cc).
 */

#ifndef LIBAUDCORE_PLUGIN_H
#define LIBAUDCORE_PLUGIN_H

#include <libaudcore/audio.h>
#include <libaudcore/export.h>
#include <libaudcore/index.h>
#include <libaudcore/objects.h>

enum class PluginType
{
    Transport,
    Playlist,
    Input,
    Effect,
    Output,
    Vis,
    General,
    Iface,
    count
};

struct PluginPreferences;

#define _AUD_PLUGIN_MAGIC ((int)0x8EAC8DE2)
#define _AUD_PLUGIN_VERSION_MIN 49 /* 4.6.1 */
#define _AUD_PLUGIN_VERSION 49     /* 4.6.1 */

/* plugin flags */
enum
{
    PluginGLibOnly = 0x1, // plugin requires GLib main loop
    PluginQtOnly = 0x2    // plugin requires Qt main loop
};

struct PluginInfo
{
    const char * name;
    const char * domain; // for gettext
    const char * about;
    const PluginPreferences * prefs;
    int flags;
};

class LIBAUDCORE_PUBLIC Plugin
{
public:
    constexpr Plugin(PluginType type, PluginInfo info) : type(type), info(info)
    {
    }

    const int magic = _AUD_PLUGIN_MAGIC;
    const int version = _AUD_PLUGIN_VERSION;

    const PluginType type;
    const PluginInfo info;

    virtual bool init() { return true; }
    virtual void cleanup() {}

    virtual int take_message(const char * code, const void * data, int size)
    {
        return -1;
    }
};

class LIBAUDCORE_PUBLIC EffectPlugin : public Plugin
{
public:
    constexpr EffectPlugin(const PluginInfo info, int order,
                           bool preserves_format)
        : Plugin(PluginType::Effect, info), order(order),
          preserves_format(preserves_format)
    {
    }

    /* Effects with lowest order (0 to 9) are applied first. */
    const int order;

    /* If the effect does not change the number of channels or the sampling
     * rate, it can be enabled and disabled more smoothly. */
    const bool preserves_format;

    /* All processing is done in floating point.  If the effect plugin wants to
     * change the channel count or sample rate, it can change the parameters
     * passed to start().  They cannot be changed in the middle of a song. */
    virtual void start(int & channels, int & rate) = 0;

    /* Performs effect processing.  process() may modify the audio samples in
     * place and return a reference to the same buffer, or it may return a
     * reference to an internal working buffer.  The number of output samples
     * need not be the same as the number of input samples. */
    virtual Index<float> & process(Index<float> & data) = 0;

    /* Optional.  A seek is taking place; any buffers should be discarded.
     * Unless the "force" flag is set, the plugin may choose to override the
     * normal flush behavior and handle the flush itself (for example, to
     * perform crossfading).  The flush() function should return false in this
     * case to prevent flush() from being called in downstream effect plugins.
     */
    virtual bool flush(bool force) { return true; }

    /* Exactly like process() except that any buffers should be drained (i.e.
     * the data processed and returned).  finish() will be called a second time
     * at the end of the last song in the playlist. */
    virtual Index<float> & finish(Index<float> & data, bool end_of_playlist)
    {
        return process(data);
    }

    /* Required only for plugins that change the time domain (e.g. a time
     * stretch) or use read-ahead buffering.  adjust_delay() must do two
     * things: first, translate <delay> (which is in milliseconds) from the
     * output time domain back to the input time domain; second, increase
     * <delay> by the size of the read-ahead buffer.  It should return the
     * adjusted delay. */
    virtual int adjust_delay(int delay) { return delay; }
};

#endif /* LIBAUDCORE_PLUGIN_H */
