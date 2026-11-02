/*
 * objects.h (AudKit shim)
 *
 * Upstream libaudcore's objects.h is built on GLib's string pool. This
 * replacement keeps the same class shapes (ArrayRef, String, StringBuf) with
 * plain heap storage, so upstream headers (preferences.h, index.h,
 * equalizer.h) and plugins compile unchanged on iOS without GLib.
 */

#ifndef LIBAUDCORE_OBJECTS_H
#define LIBAUDCORE_OBJECTS_H

#include <libaudcore/shim-config.h>
#include <libaudcore/templates.h>

#include <stddef.h>

// Stores array pointer together with deduced array length.

template<class T>
struct ArrayRef
{
    const T * data;
    int len;

    constexpr ArrayRef(decltype(nullptr) = nullptr) : data(nullptr), len(0) {}

    template<int N>
    constexpr ArrayRef(const T (&array)[N]) : data(array), len(N)
    {
    }

    constexpr ArrayRef(const T * data, int len) : data(data), len(len) {}

    const T * begin() const { return data; }
    const T * end() const { return data + len; }
};

// Reference-counted immutable string (upstream pools these; here each one
// owns its own refcounted copy).

class String
{
public:
    constexpr String() : raw(nullptr) {}

    ~String()
    {
        if (raw)
            raw_unref(raw);
    }

    String(const String & b) : raw(raw_ref(b.raw)) {}

    String & operator=(const String & b)
    {
        if (this != &b)
        {
            raw_unref(raw);
            raw = raw_ref(b.raw);
        }
        return *this;
    }

    String(String && b) : raw(b.raw) { b.raw = nullptr; }

    String & operator=(String && b)
    {
        return aud::move_assign(*this, std::move(b));
    }

    bool operator==(const String & b) const { return raw_equal(raw, b.raw); }

    explicit String(const char * str) : raw(raw_get(str)) {}

    String(decltype(nullptr)) = delete;

    operator const char *() const { return raw; }

    unsigned hash() const { return raw_hash(raw); }

private:
    static char * raw_get(const char * str);
    static char * raw_ref(const char * str);
    static void raw_unref(char * str);
    static unsigned raw_hash(const char * str);
    static bool raw_equal(const char * str1, const char * str2);

    char * raw;
};

// Mutable string buffer. Upstream allocates these on a per-thread stack;
// a heap buffer is enough for the few places the ported code uses one.

class StringBuf
{
public:
    constexpr StringBuf() : m_data(nullptr), m_len(0) {}
    explicit StringBuf(int len);
    StringBuf(StringBuf && other) : m_data(other.m_data), m_len(other.m_len)
    {
        other.m_data = nullptr;
        other.m_len = 0;
    }
    StringBuf & operator=(StringBuf && other)
    {
        return aud::move_assign(*this, std::move(other));
    }
    ~StringBuf();

    StringBuf(const StringBuf &) = delete;
    StringBuf & operator=(const StringBuf &) = delete;

    void resize(int len);
    int len() const { return m_len; }
    operator char *() { return m_data; }
    operator const char *() const { return m_data; }

    // Appends a string.
    StringBuf & insert(int pos, const char * s, int len = -1);
    StringBuf & operator+=(const char * s) { return insert(-1, s); }

private:
    char * m_data;
    int m_len;
};

StringBuf str_printf(const char * format, ...)
    __attribute__((__format__(__printf__, 1, 2)));

#endif /* LIBAUDCORE_OBJECTS_H */
