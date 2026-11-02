/*
 * shim.cc (AudKit)
 *
 * Implementations behind the shim headers: String/StringBuf storage, hooks,
 * the configuration store, and the GLib sort used by index.cc.
 *
 * The configuration store follows upstream config.cc: values are strings
 * keyed by section (nullptr = "audacious", the core) and name; defaults set
 * by aud_config_set_defaults() are used until a value is set; setting a
 * core value calls the hook "set <name>".
 */

#include <libaudcore/audstrings.h>
#include <libaudcore/hook.h>
#include <libaudcore/internal.h>
#include <libaudcore/objects.h>
#include <libaudcore/runtime.h>

#include "AudCore.h"

#include <glib.h>

#include <atomic>
#include <map>
#include <mutex>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <vector>

size_t misc_bytes_allocated;

/* ---- String ---- */

// Each raw string is preceded by its reference count.
struct RawHeader
{
    std::atomic<int> refs;
};

static RawHeader * header_of(const char * str)
{
    return (RawHeader *)(str - sizeof(RawHeader));
}

char * String::raw_get(const char * str)
{
    if (!str)
        return nullptr;
    size_t len = strlen(str);
    char * mem = (char *)malloc(sizeof(RawHeader) + len + 1);
    new (mem) RawHeader{{1}};
    char * raw = mem + sizeof(RawHeader);
    memcpy(raw, str, len + 1);
    return raw;
}

char * String::raw_ref(const char * str)
{
    if (!str)
        return nullptr;
    header_of(str)->refs++;
    return (char *)str;
}

void String::raw_unref(char * str)
{
    if (str && --header_of(str)->refs == 0)
        free(header_of(str));
}

unsigned String::raw_hash(const char * str)
{
    // FNV-1a
    unsigned h = 2166136261u;
    for (; str && *str; str++)
        h = (h ^ (unsigned char)*str) * 16777619u;
    return h;
}

bool String::raw_equal(const char * a, const char * b)
{
    return a == b || (a && b && !strcmp(a, b));
}

/* ---- StringBuf ---- */

StringBuf::StringBuf(int len) : m_data(nullptr), m_len(0) { resize(len); }

StringBuf::~StringBuf() { free(m_data); }

void StringBuf::resize(int len)
{
    if (len < 0)
        len = m_len;
    m_data = (char *)realloc(m_data, len + 1);
    if (len > m_len)
        memset(m_data + m_len, 0, len - m_len);
    m_data[len] = 0;
    m_len = len;
}

StringBuf & StringBuf::insert(int pos, const char * s, int len)
{
    if (pos < 0)
        pos = m_len;
    if (len < 0)
        len = s ? strlen(s) : 0;
    int old = m_len;
    resize(old + len);
    memmove(m_data + pos + len, m_data + pos, old - pos);
    memcpy(m_data + pos, s, len);
    return *this;
}

StringBuf str_printf(const char * format, ...)
{
    va_list args;
    va_start(args, format);
    va_list copy;
    va_copy(copy, args);
    int len = vsnprintf(nullptr, 0, format, copy);
    va_end(copy);
    StringBuf buf(len);
    vsnprintf(buf, len + 1, format, args);
    va_end(args);
    return buf;
}

/* ---- audstrings ---- */

bool str_to_double_array(const char * string, double * array, int count)
{
    if (!string)
        return false;
    std::vector<double> values;
    const char * p = string;
    while (*p)
    {
        while (*p == ',' || *p == ' ')
            p++;
        if (!*p)
            break;
        char * end;
        values.push_back(strtod(p, &end));
        if (end == p)
            return false;
        p = end;
    }
    if ((int)values.size() != count)
        return false;
    for (int i = 0; i < count; i++)
        array[i] = values[i];
    return true;
}

StringBuf double_array_to_str(const double * array, int count)
{
    StringBuf out;
    for (int i = 0; i < count; i++)
    {
        char buf[32];
        snprintf(buf, sizeof buf, "%s%g", i ? "," : "", array[i]);
        out += buf;
    }
    return out;
}

StringBuf int_to_str(int val)
{
    return str_printf("%d", val);
}

/* ---- hooks ---- */

namespace
{
struct HookItem
{
    HookFunction func;
    void * user;
};

std::recursive_mutex hook_mutex;
std::map<std::string, std::vector<HookItem>> hooks;
} // namespace

void hook_associate(const char * name, HookFunction func, void * user)
{
    std::lock_guard<std::recursive_mutex> lock(hook_mutex);
    hooks[name].push_back({func, user});
}

void hook_dissociate(const char * name, HookFunction func, void * user)
{
    std::lock_guard<std::recursive_mutex> lock(hook_mutex);
    auto it = hooks.find(name);
    if (it == hooks.end())
        return;
    auto & list = it->second;
    for (auto i = list.begin(); i != list.end();)
    {
        if (i->func == func && (!user || i->user == user))
            i = list.erase(i);
        else
            ++i;
    }
}

void hook_call(const char * name, void * data)
{
    std::vector<HookItem> list;
    {
        std::lock_guard<std::recursive_mutex> lock(hook_mutex);
        auto it = hooks.find(name);
        if (it == hooks.end())
            return;
        list = it->second;
    }
    for (auto & item : list)
        item.func(data, item.user);
}

/* ---- configuration ---- */

namespace
{
typedef std::map<std::string, std::map<std::string, std::string>> Sections;

std::mutex config_mutex;
Sections config_values;
Sections config_defaults;

const char * section_name(const char * section)
{
    return section ? section : "audacious";
}

std::string config_get(const char * section, const char * name)
{
    std::lock_guard<std::mutex> lock(config_mutex);
    const char * s = section_name(section);
    auto sec = config_values.find(s);
    if (sec != config_values.end())
    {
        auto v = sec->second.find(name);
        if (v != sec->second.end())
            return v->second;
    }
    sec = config_defaults.find(s);
    if (sec != config_defaults.end())
    {
        auto v = sec->second.find(name);
        if (v != sec->second.end())
            return v->second;
    }
    return std::string();
}

void config_set(const char * section, const char * name, const char * value)
{
    bool changed;
    {
        std::lock_guard<std::mutex> lock(config_mutex);
        std::string & slot = config_values[section_name(section)][name];
        changed = slot != value;
        slot = value;
    }
    // As upstream: changes to core settings are announced.
    if (changed && !section)
        hook_call((std::string("set ") + name).c_str(), nullptr);
}
} // namespace

void aud_config_set_defaults(const char * section, const char * const * entries)
{
    std::lock_guard<std::mutex> lock(config_mutex);
    auto & sec = config_defaults[section_name(section)];
    for (; entries[0] && entries[1]; entries += 2)
        sec[entries[0]] = entries[1];
}

void aud_set_str(const char * section, const char * name, const char * value)
{
    config_set(section, name, value ? value : "");
}

String aud_get_str(const char * section, const char * name)
{
    return String(config_get(section, name).c_str());
}

void aud_set_bool(const char * section, const char * name, bool value)
{
    config_set(section, name, value ? "TRUE" : "FALSE");
}

bool aud_get_bool(const char * section, const char * name)
{
    return config_get(section, name) == "TRUE";
}

void aud_toggle_bool(const char * section, const char * name)
{
    aud_set_bool(section, name, !aud_get_bool(section, name));
}

void aud_set_int(const char * section, const char * name, int value)
{
    config_set(section, name, std::to_string(value).c_str());
}

int aud_get_int(const char * section, const char * name)
{
    return atoi(config_get(section, name).c_str());
}

void aud_set_double(const char * section, const char * name, double value)
{
    char buf[32];
    snprintf(buf, sizeof buf, "%g", value);
    config_set(section, name, buf);
}

double aud_get_double(const char * section, const char * name)
{
    return strtod(config_get(section, name).c_str(), nullptr);
}

/* Saved in the same INI layout as upstream's ~/.config/audacious/config. */

bool audcore_config_save(const char * path)
{
    std::string out;
    {
        std::lock_guard<std::mutex> lock(config_mutex);
        for (auto & sec : config_values)
        {
            out += "[" + sec.first + "]\n";
            for (auto & kv : sec.second)
                out += kv.first + "=" + kv.second + "\n";
            out += "\n";
        }
    }
    std::string tmp = std::string(path) + ".tmp";
    FILE * f = fopen(tmp.c_str(), "w");
    if (!f)
        return false;
    bool ok = fwrite(out.data(), 1, out.size(), f) == out.size();
    ok = !fclose(f) && ok;
    return ok && !rename(tmp.c_str(), path);
}

bool audcore_config_load(const char * path)
{
    FILE * f = fopen(path, "r");
    if (!f)
        return false;
    std::string section = "audacious";
    char line[4096];
    std::vector<std::pair<std::string, std::pair<std::string, std::string>>> entries;
    while (fgets(line, sizeof line, f))
    {
        size_t len = strlen(line);
        while (len && (line[len - 1] == '\n' || line[len - 1] == '\r'))
            line[--len] = 0;
        if (line[0] == '[' && len > 1 && line[len - 1] == ']')
            section.assign(line + 1, len - 2);
        else if (const char * eq = strchr(line, '='))
            entries.push_back({section, {std::string(line, eq - line), eq + 1}});
    }
    fclose(f);
    for (auto & e : entries)
    {
        const char * sec = e.first == "audacious" ? nullptr : e.first.c_str();
        config_set(sec, e.second.first.c_str(), e.second.second.c_str());
    }
    return true;
}

void audcore_config_set(const char * section, const char * name, const char * value)
{
    aud_set_str(section, name, value);
}

char * audcore_config_get(const char * section, const char * name)
{
    return strdup(config_get(section, name).c_str());
}

/* ---- GLib ---- */

static void merge_sort(char * base, char * tmp, int n, size_t size,
                       GCompareDataFunc compare, void * user)
{
    if (n < 2)
        return;
    int half = n / 2;
    merge_sort(base, tmp, half, size, compare, user);
    merge_sort(base + half * size, tmp, n - half, size, compare, user);
    char *a = base, *a_end = base + half * size;
    char *b = a_end, *b_end = base + n * size;
    char * out = tmp;
    while (a < a_end && b < b_end)
    {
        // <= keeps equal elements in order (stable)
        if (compare(a, b, user) <= 0)
            memcpy(out, a, size), a += size;
        else
            memcpy(out, b, size), b += size;
        out += size;
    }
    memcpy(out, a, a_end - a);
    out += a_end - a;
    memcpy(out, b, b_end - b);
    memcpy(base, tmp, n * size);
}

void g_qsort_with_data(void * pbase, int total_elems, size_t size,
                       GCompareDataFunc compare_func, void * user_data)
{
    if (total_elems < 2)
        return;
    std::vector<char> tmp(total_elems * size);
    merge_sort((char *)pbase, tmp.data(), total_elems, size, compare_func,
               user_data);
}
