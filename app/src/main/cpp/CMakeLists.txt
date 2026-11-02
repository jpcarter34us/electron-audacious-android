# Native side of Audacious for Android: the ported libaudcore pieces and
# Audacious effect plugins (audcore/, unchanged from the iOS port), the
# libsamplerate they use, the decoders (dr_libs, stb_vorbis, libopus), and
# the JNI bridge with the OpenSL ES output (jni/).

cmake_minimum_required(VERSION 3.18.1)
project(audacious_android C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---- libsamplerate (BSD-2-Clause) ----
add_library(samplerate STATIC
    samplerate/samplerate.c
    samplerate/src_linear.c
    samplerate/src_sinc.c
    samplerate/src_zoh.c)
target_include_directories(samplerate PUBLIC samplerate/include PRIVATE samplerate)
target_compile_definitions(samplerate PRIVATE
    ENABLE_SINC_FAST_CONVERTER ENABLE_SINC_MEDIUM_CONVERTER ENABLE_SINC_BEST_CONVERTER
    HAVE_STDBOOL_H PACKAGE="libsamplerate" VERSION="0.2.2")

# ---- libaudcore pieces and the effect plugins ----
file(GLOB AUDCORE_SOURCES audcore/core/*.cc audcore/plugins/*.cc)
add_library(audcore STATIC ${AUDCORE_SOURCES})
target_include_directories(audcore PUBLIC audcore/include PRIVATE
    audcore audcore/libaudcore audcore/glibshim)
target_link_libraries(audcore PUBLIC samplerate)
target_compile_options(audcore PRIVATE -Wno-unused-parameter -Wno-deprecated-declarations)

# ---- libopus 1.5.2 (BSD-3-Clause), for Sendspin's Opus streams ----
include(opus_sources.cmake)
add_library(opus STATIC ${OPUS_SOURCES})
target_include_directories(opus PUBLIC third_party/opus/include PRIVATE
    third_party/opus third_party/opus/celt third_party/opus/silk
    third_party/opus/silk/float third_party/opus/src)
target_compile_definitions(opus PRIVATE OPUS_BUILD USE_ALLOCA HAVE_LRINTF HAVE_LRINT
    PACKAGE_VERSION="1.5.2")
target_compile_options(opus PRIVATE -O2 -w)

# ---- the app's native library ----
add_library(audjni SHARED
    jni/bridge.cc
    jni/output.cc
    jni/decoders.cc
    jni/live.cc)
target_include_directories(audjni PRIVATE jni third_party/dr_libs third_party/stb)
target_link_libraries(audjni PRIVATE audcore opus OpenSLES log android)
target_compile_options(audjni PRIVATE -Wno-unused-function)
