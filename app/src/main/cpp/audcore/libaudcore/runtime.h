/*
 * runtime.h (AudKit shim)
 *
 * The configuration part of upstream libaudcore's runtime.h, with the same
 * signatures. Values live in an in-memory store (core/config.cc) that the
 * app saves to and loads from an Audacious-style config file.
 */

#ifndef LIBAUDCORE_RUNTIME_H
#define LIBAUDCORE_RUNTIME_H

#include <libaudcore/objects.h>

#include <stdio.h>

#ifdef AUDKIT_VERBOSE
#define AUDDBG(...) fprintf(stderr, "AudCore: " __VA_ARGS__)
#define AUDINFO(...) fprintf(stderr, "AudCore: " __VA_ARGS__)
#else
#define AUDDBG(...) ((void)0)
#define AUDINFO(...) ((void)0)
#endif
#define AUDWARN(...) fprintf(stderr, "AudCore WARNING: " __VA_ARGS__)
#define AUDERR(...) fprintf(stderr, "AudCore ERROR: " __VA_ARGS__)

void aud_config_set_defaults(const char * section,
                             const char * const * entries);

void aud_set_str(const char * section, const char * name, const char * value);
String aud_get_str(const char * section, const char * name);
void aud_set_bool(const char * section, const char * name, bool value);
bool aud_get_bool(const char * section, const char * name);
void aud_toggle_bool(const char * section, const char * name);
void aud_set_int(const char * section, const char * name, int value);
int aud_get_int(const char * section, const char * name);
void aud_set_double(const char * section, const char * name, double value);
double aud_get_double(const char * section, const char * name);

static inline void aud_set_str(const char * name, const char * value)
{
    aud_set_str(nullptr, name, value);
}
static inline String aud_get_str(const char * name)
{
    return aud_get_str(nullptr, name);
}
static inline void aud_set_bool(const char * name, bool value)
{
    aud_set_bool(nullptr, name, value);
}
static inline bool aud_get_bool(const char * name)
{
    return aud_get_bool(nullptr, name);
}
static inline void aud_toggle_bool(const char * name)
{
    aud_toggle_bool(nullptr, name);
}
static inline void aud_set_int(const char * name, int value)
{
    aud_set_int(nullptr, name, value);
}
static inline int aud_get_int(const char * name)
{
    return aud_get_int(nullptr, name);
}
static inline void aud_set_double(const char * name, double value)
{
    aud_set_double(nullptr, name, value);
}
static inline double aud_get_double(const char * name)
{
    return aud_get_double(nullptr, name);
}

#endif /* LIBAUDCORE_RUNTIME_H */
