/*
 * hook.h (AudKit shim)
 *
 * Upstream hooks are queued onto the GLib main loop. Here hook_call() runs
 * the handlers synchronously on the calling thread; the only hooks in the
 * ported code are config-change notifications, which are cheap.
 */

#ifndef LIBAUDCORE_HOOK_H
#define LIBAUDCORE_HOOK_H

#include <libaudcore/shim-config.h>

typedef void (*HookFunction)(void * data, void * user);

void hook_associate(const char * name, HookFunction func, void * user);
void hook_dissociate(const char * name, HookFunction func,
                     void * user = nullptr);
void hook_call(const char * name, void * data);

#endif /* LIBAUDCORE_HOOK_H */
