/*
 * glib.h (AudKit shim)
 *
 * The single GLib function upstream index.cc uses: a stable sort with user
 * data. std::stable_sort cannot move raw elements of runtime size, so this
 * is a merge sort over bytes.
 */

#ifndef AUDKIT_GLIB_SHIM_H
#define AUDKIT_GLIB_SHIM_H

#include <stddef.h>

#define GLIB_CHECK_VERSION(major, minor, micro) 0

typedef int (*GCompareDataFunc)(const void * a, const void * b,
                                void * user_data);

void g_qsort_with_data(void * pbase, int total_elems, size_t size,
                       GCompareDataFunc compare_func, void * user_data);

#endif /* AUDKIT_GLIB_SHIM_H */
