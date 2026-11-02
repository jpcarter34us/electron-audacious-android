/*
 * audstrings.h (AudKit shim)
 *
 * The helpers the ported sources need from upstream audstrings.h
 * (equalizer.cc, the resample plugin).
 */

#ifndef LIBAUDCORE_STRINGS_H
#define LIBAUDCORE_STRINGS_H

#include <libaudcore/objects.h>

bool str_to_double_array(const char * string, double * array, int count);
StringBuf double_array_to_str(const double * array, int count);
StringBuf int_to_str(int val);

#endif /* LIBAUDCORE_STRINGS_H */
