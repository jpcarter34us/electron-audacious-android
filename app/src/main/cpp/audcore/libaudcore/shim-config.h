/*
 * shim-config.h (AudKit shim)
 *
 * What meson's config.h gives upstream sources: the EXPORT keyword and the
 * gettext domain. Everything is linked statically into the app, so EXPORT
 * is empty.
 */

#ifndef LIBAUDCORE_SHIM_CONFIG_H
#define LIBAUDCORE_SHIM_CONFIG_H

#ifndef EXPORT
#define EXPORT
#endif

#ifndef PACKAGE
#define PACKAGE "audacious-plugins"
#endif

#endif /* LIBAUDCORE_SHIM_CONFIG_H */
