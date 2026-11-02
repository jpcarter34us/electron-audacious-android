/*
 * engine.cc (AudKit)
 *
 * The effect chain, following upstream libaudcore/effect.cc: enabled
 * plugins are started in order at the input format, each passing its
 * (possibly changed) format to the next; format-preserving plugins can be
 * added or removed while playing (a removed one is drained first), others
 * restart the chain. After the effects comes the equalizer, as in
 * output.cc's write_output().
 *
 * The plugin settings serializer is adapted from ElectronPlayer's N-API
 * addon (native/src/addon.cc), so both ports describe PreferencesWidget
 * trees the same way.
 */

#include "AudCore.h"

#include <libaudcore/equalizer.h>
#include <libaudcore/internal.h>
#include <libaudcore/plugin.h>
#include <libaudcore/preferences.h>
#include <libaudcore/runtime.h>
#include <libaudcore/threads.h>

#include <algorithm>
#include <map>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <vector>

#include "../plugins/registry.h"

/* ---- registry ---- */

namespace
{
struct Registered
{
    const char * id;
    EffectPlugin * header;
    bool enabled;
};

std::vector<Registered> registry;

const char * const CONFIG_SECTION = "audkit";
const char * const CONFIG_ENABLED = "enabled_effects";

void save_enabled()
{
    std::string list;
    for (auto & r : registry)
    {
        if (!r.enabled)
            continue;
        if (!list.empty())
            list += ',';
        list += r.id;
    }
    aud_set_str(CONFIG_SECTION, CONFIG_ENABLED, list.c_str());
}

bool valid(int index) { return index >= 0 && index < (int)registry.size(); }
} // namespace

/* Core defaults from upstream config.cc that the ported code reads. */
static const char * const core_defaults[] = {
    "equalizer_active", "FALSE",
    "equalizer_bands", "0,0,0,0,0,0,0,0,0,0",
    "equalizer_preamp", "0",
    nullptr};

void audcore_init(void)
{
    if (!registry.empty())
        return;

    aud_config_set_defaults(nullptr, core_defaults);

    for (auto & entry : audkit_effect_plugins())
    {
        EffectPlugin * header = entry.get();
        if (header->init())
            registry.push_back({entry.id, header, false});
    }

    // aud_plugin_list(PluginType::Effect) order: by plugin order, then name.
    std::stable_sort(registry.begin(), registry.end(),
                     [](const Registered & a, const Registered & b) {
                         if (a.header->order != b.header->order)
                             return a.header->order < b.header->order;
                         return strcmp(a.header->info.name, b.header->info.name) < 0;
                     });

    String enabled = aud_get_str(CONFIG_SECTION, CONFIG_ENABLED);
    std::string list = enabled ? (const char *)enabled : "";
    for (auto & r : registry)
    {
        std::string id = r.id;
        size_t pos = 0;
        while ((pos = list.find(id, pos)) != std::string::npos)
        {
            bool start = pos == 0 || list[pos - 1] == ',';
            bool end = pos + id.size() == list.size() || list[pos + id.size()] == ',';
            if (start && end)
            {
                r.enabled = true;
                break;
            }
            pos += id.size();
        }
    }

    eq_init();
}

int audcore_effect_count(void) { return registry.size(); }

const char * audcore_effect_id(int index)
{
    return valid(index) ? registry[index].id : nullptr;
}

const char * audcore_effect_name(int index)
{
    return valid(index) ? registry[index].header->info.name : nullptr;
}

const char * audcore_effect_about(int index)
{
    return valid(index) ? registry[index].header->info.about : nullptr;
}

bool audcore_effect_preserves_format(int index)
{
    return valid(index) && registry[index].header->preserves_format;
}

bool audcore_effect_has_prefs(int index)
{
    return valid(index) && registry[index].header->info.prefs;
}

bool audcore_effect_enabled(int index)
{
    return valid(index) && registry[index].enabled;
}

/* ---- effect chain (effect.cc) ---- */

namespace
{
struct Effect
{
    int position; // in registry
    EffectPlugin * header;
    int channels_returned, rate_returned;
    bool remove_flag;
};

aud::mutex mutex;
std::vector<Effect> effects;
bool running;
bool reset_pending;
int input_channels, input_rate;
int output_channels, output_rate;
Index<float> input_buffer;

void start_locked()
{
    effects.clear();

    int channels = input_channels;
    int rate = input_rate;

    for (int i = 0; i < (int)registry.size(); i++)
    {
        if (!registry[i].enabled)
            continue;

        AUDINFO("Starting %s at %d channels, %d Hz.\n",
                registry[i].header->info.name, channels, rate);

        registry[i].header->start(channels, rate);
        effects.push_back({i, registry[i].header, channels, rate, false});
    }

    output_channels = channels;
    output_rate = rate;
    eq_set_format(channels, rate);
    reset_pending = false;
}

// effect_insert(): starts a format-preserving plugin at the format of the
// effect before it.
void insert_locked(int position)
{
    auto it = effects.begin();
    const Effect * prev = nullptr;
    for (; it != effects.end(); ++it)
    {
        if (it->position == position)
        {
            it->remove_flag = false;
            return;
        }
        if (it->position > position)
            break;
        prev = &*it;
    }

    int channels = prev ? prev->channels_returned : input_channels;
    int rate = prev ? prev->rate_returned : input_rate;
    EffectPlugin * header = registry[position].header;
    header->start(channels, rate);
    effects.insert(it, {position, header, channels, rate, false});
}

void remove_locked(int position)
{
    for (auto & e : effects)
    {
        if (e.position == position)
            e.remove_flag = true;
    }
}

Index<float> & process_locked(Index<float> & data, bool finish,
                              bool end_of_playlist)
{
    Index<float> * cur = &data;

    for (auto it = effects.begin(); it != effects.end();)
    {
        if (it->remove_flag)
        {
            cur = &it->header->finish(*cur, false);

            // simulate end-of-playlist call
            // first save the current data
            Index<float> save = std::move(*cur);
            cur = &it->header->finish(*cur, true);

            // combine the saved and new data
            save.move_from(*cur, 0, -1, -1, true, true);
            *cur = std::move(save);

            it = effects.erase(it);
        }
        else
        {
            cur = finish ? &it->header->finish(*cur, end_of_playlist)
                         : &it->header->process(*cur);
            ++it;
        }
    }

    return *cur;
}

int run(const float * in, int samples, bool finish, bool end_of_playlist,
        const float ** out)
{
    auto mh = mutex.take();

    if (reset_pending)
    {
        // aud_output_reset(OutputReset::EffectsOnly): drain, then restart.
        Index<float> empty;
        process_locked(empty, true, false);
        start_locked();
    }

    input_buffer.resize(samples);
    if (samples)
        memcpy(input_buffer.begin(), in, sizeof(float) * samples);

    Index<float> & data = process_locked(input_buffer, finish, end_of_playlist);
    eq_filter(data.begin(), data.len());

    *out = data.begin();
    return data.len();
}
} // namespace

void audcore_effect_set_enabled(int index, bool enabled)
{
    if (!valid(index) || registry[index].enabled == enabled)
        return;

    auto mh = mutex.take();
    registry[index].enabled = enabled;
    save_enabled();

    if (!running)
        return;

    // effect_enable()
    if (registry[index].header->preserves_format)
    {
        if (enabled)
            insert_locked(index);
        else
            remove_locked(index);
    }
    else
        reset_pending = true;
}

void audcore_pipeline_start(int channels, int rate)
{
    auto mh = mutex.take();
    input_channels = channels;
    input_rate = rate;
    running = true;
    start_locked();
}

int audcore_pipeline_channels(void) { return output_channels; }
int audcore_pipeline_rate(void) { return output_rate; }

int audcore_pipeline_process(const float * in, int samples, const float ** out)
{
    return run(in, samples, false, false, out);
}

int audcore_pipeline_finish(const float * in, int samples,
                            bool end_of_playlist, const float ** out)
{
    return run(in, samples, true, end_of_playlist, out);
}

bool audcore_pipeline_flush(bool force)
{
    auto mh = mutex.take();
    bool flushed = true;

    for (auto & e : effects)
    {
        if (!e.header->flush(force) && !force)
        {
            flushed = false;
            break;
        }
    }

    return flushed;
}

int audcore_pipeline_adjust_delay(int delay)
{
    auto mh = mutex.take();

    for (auto it = effects.rbegin(); it != effects.rend(); ++it)
        delay = it->header->adjust_delay(delay);

    return delay;
}

/* ---- equalizer ---- */

void audcore_eq_set_active(bool active)
{
    aud_set_bool(nullptr, "equalizer_active", active);
}

bool audcore_eq_active(void) { return aud_get_bool(nullptr, "equalizer_active"); }

void audcore_eq_set_preamp(double db)
{
    aud_set_double(nullptr, "equalizer_preamp", db);
}

double audcore_eq_preamp(void) { return aud_get_double(nullptr, "equalizer_preamp"); }

void audcore_eq_set_bands(const double bands[AUDCORE_EQ_NBANDS])
{
    aud_eq_set_bands(bands);
}

void audcore_eq_bands(double bands[AUDCORE_EQ_NBANDS]) { aud_eq_get_bands(bands); }

/* ---- visualization ---- */

void audcore_calc_freq(const float pcm[AUDCORE_VIS_PCM], float freq[AUDCORE_VIS_FREQ])
{
    calc_freq(pcm, freq);
}

/* ---- plugin settings (from ElectronPlayer native/src/addon.cc) ---- */

namespace
{
struct PrefsSession
{
    const PluginPreferences * prefs;
    std::vector<const PreferencesWidget *> widgets; // index = widget id
};

std::map<int, PrefsSession> prefs_sessions; // by effect index

void json_str(std::string & out, const char * s)
{
    out += '"';
    for (; s && *s; s++)
    {
        unsigned char c = *s;
        if (c == '"' || c == '\\')
            (out += '\\') += c;
        else if (c == '\n')
            out += "\\n";
        else if (c < 0x20)
        {
            char buf[8];
            snprintf(buf, sizeof buf, "\\u%04x", c);
            out += buf;
        }
        else
            out += c;
    }
    out += '"';
}

std::string json_num(double v)
{
    char buf[32];
    snprintf(buf, sizeof buf, "%.10g", v);
    return buf;
}

const char * cfg_type_name(WidgetConfig::Type type)
{
    switch (type)
    {
    case WidgetConfig::Bool: return "bool";
    case WidgetConfig::Int: return "int";
    case WidgetConfig::Float: return "float";
    case WidgetConfig::String: return "string";
    default: return nullptr;
    }
}

void serialize_widgets(std::string & out, ArrayRef<PreferencesWidget> widgets,
                       PrefsSession & session);

void serialize_widget(std::string & out, const PreferencesWidget & w,
                      PrefsSession & session)
{
    static const char * const kinds[] = {
        "label", "button", "check", "radio", "spin", "entry", "file", "combo",
        "font", "box", "table", "notebook", "separator", "custom", "custom"};

    int id = session.widgets.size();
    session.widgets.push_back(&w);

    out += "{\"id\":" + std::to_string(id) + ",\"type\":\"" + kinds[w.type] + '"';
    if (w.label)
    {
        out += ",\"label\":";
        json_str(out, w.label);
    }
    if (w.child)
        out += ",\"child\":true";
    if (auto cfg = cfg_type_name(w.cfg.type))
        out += std::string(",\"cfg\":\"") + cfg + '"';

    switch (w.type)
    {
    case PreferencesWidget::RadioButton:
        out += ",\"value\":" + std::to_string(w.data.radio_btn.value);
        break;
    case PreferencesWidget::SpinButton:
        out += ",\"min\":" + json_num(w.data.spin_btn.min) +
               ",\"max\":" + json_num(w.data.spin_btn.max) +
               ",\"step\":" + json_num(w.data.spin_btn.step);
        if (w.data.spin_btn.right_label)
        {
            out += ",\"unit\":";
            json_str(out, w.data.spin_btn.right_label);
        }
        break;
    case PreferencesWidget::Entry:
        if (w.data.entry.password)
            out += ",\"password\":true";
        break;
    case PreferencesWidget::FileEntry:
        out += std::string(",\"mode\":\"") +
               (w.data.file_entry.mode == FileSelectMode::Folder ? "folder" : "file") + '"';
        break;
    case PreferencesWidget::ComboBox:
    {
        auto items = w.data.combo.fill ? w.data.combo.fill() : w.data.combo.elems;
        out += ",\"items\":[";
        for (int i = 0; i < items.len; i++)
        {
            if (i)
                out += ',';
            out += "{\"label\":";
            json_str(out, items.data[i].label);
            out += ",\"value\":";
            if (items.data[i].str)
                json_str(out, items.data[i].str);
            else
                out += std::to_string(items.data[i].num);
            out += '}';
        }
        out += ']';
        break;
    }
    case PreferencesWidget::Box:
        out += std::string(",\"horizontal\":") + (w.data.box.horizontal ? "true" : "false") +
               ",\"frame\":" + (w.data.box.frame ? "true" : "false") + ",\"children\":";
        serialize_widgets(out, w.data.box.widgets, session);
        break;
    case PreferencesWidget::Table:
        out += ",\"children\":";
        serialize_widgets(out, w.data.table.widgets, session);
        break;
    case PreferencesWidget::Notebook:
        out += ",\"tabs\":[";
        for (int i = 0; i < w.data.notebook.tabs.len; i++)
        {
            if (i)
                out += ',';
            out += "{\"name\":";
            json_str(out, w.data.notebook.tabs.data[i].name);
            out += ",\"children\":";
            serialize_widgets(out, w.data.notebook.tabs.data[i].widgets, session);
            out += '}';
        }
        out += ']';
        break;
    case PreferencesWidget::Separator:
        out += std::string(",\"horizontal\":") + (w.data.separator.horizontal ? "true" : "false");
        break;
    default:
        break;
    }

    out += '}';
}

void serialize_widgets(std::string & out, ArrayRef<PreferencesWidget> widgets,
                       PrefsSession & session)
{
    out += '[';
    for (int i = 0; i < widgets.len; i++)
    {
        if (i)
            out += ',';
        serialize_widget(out, widgets.data[i], session);
    }
    out += ']';
}

std::string serialize_values(const PrefsSession & session)
{
    std::string out = "{";
    bool first = true;
    for (size_t id = 0; id < session.widgets.size(); id++)
    {
        const WidgetConfig & cfg = session.widgets[id]->cfg;
        if (cfg.type == WidgetConfig::None)
            continue;
        if (!first)
            out += ',';
        first = false;
        out += '"' + std::to_string(id) + "\":";
        switch (cfg.type)
        {
        case WidgetConfig::Bool: out += cfg.get_bool() ? "true" : "false"; break;
        case WidgetConfig::Int: out += std::to_string(cfg.get_int()); break;
        case WidgetConfig::Float: out += json_num(cfg.get_float()); break;
        case WidgetConfig::String: json_str(out, cfg.get_string()); break;
        default: break;
        }
    }
    return out + '}';
}

PrefsSession * session_for(int effect)
{
    auto it = prefs_sessions.find(effect);
    return it == prefs_sessions.end() ? nullptr : &it->second;
}
} // namespace

char * audcore_prefs_open(int effect)
{
    if (!valid(effect) || !registry[effect].header->info.prefs)
        return nullptr;

    audcore_prefs_close(effect);

    PrefsSession & session = prefs_sessions[effect];
    session.prefs = registry[effect].header->info.prefs;
    if (session.prefs->init)
        session.prefs->init();

    std::string json = "{\"name\":";
    json_str(json, registry[effect].header->info.name);
    json += std::string(",\"hasApply\":") + (session.prefs->apply ? "true" : "false") +
            ",\"widgets\":";
    serialize_widgets(json, session.prefs->widgets, session);
    json += '}';
    return strdup(json.c_str());
}

char * audcore_prefs_values(int effect)
{
    PrefsSession * session = session_for(effect);
    return strdup(session ? serialize_values(*session).c_str() : "{}");
}

void audcore_prefs_set(int effect, int widget, const char * value)
{
    PrefsSession * session = session_for(effect);
    if (!session || widget < 0 || widget >= (int)session->widgets.size() || !value)
        return;

    const WidgetConfig & cfg = session->widgets[widget]->cfg;
    switch (cfg.type)
    {
    case WidgetConfig::Bool:
        cfg.set_bool(!strcmp(value, "true") || !strcmp(value, "1"));
        break;
    case WidgetConfig::Int: cfg.set_int((int)strtod(value, nullptr)); break;
    case WidgetConfig::Float: cfg.set_float(strtod(value, nullptr)); break;
    case WidgetConfig::String: cfg.set_string(value); break;
    default: break;
    }
}

void audcore_prefs_button(int effect, int widget)
{
    PrefsSession * session = session_for(effect);
    if (session && widget >= 0 && widget < (int)session->widgets.size() &&
        session->widgets[widget]->type == PreferencesWidget::Button &&
        session->widgets[widget]->data.button.callback)
        session->widgets[widget]->data.button.callback();
}

void audcore_prefs_apply(int effect)
{
    PrefsSession * session = session_for(effect);
    if (session && session->prefs->apply)
        session->prefs->apply();
}

void audcore_prefs_close(int effect)
{
    auto it = prefs_sessions.find(effect);
    if (it == prefs_sessions.end())
        return;
    if (it->second.prefs->cleanup)
        it->second.prefs->cleanup();
    prefs_sessions.erase(it);
}
