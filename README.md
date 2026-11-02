# Audacious for Android

A music player for Android 4.1 (API 16) through Android 15 (API 35), built on the audio core of the [Audacious](https://audacious-media-player.org) media player. It is ported from [Audacious Mobile](https://github.com/jpcarter34us/electron-audacious-ios) (iOS) and [ElectronPlayer](https://github.com/jpcarter34us/electronplayer) (desktop). It plays songs on the device and browses and plays Subsonic/OpenSubsonic (Navidrome), DAAP (iTunes sharing, OwnTone) and Music Assistant libraries. It can also be a Music Assistant speaker through Sendspin, either through Music Assistant's WebSocket proxy or straight to a server's Sendspin port; you choose which in Settings.

Not affiliated with the Audacious developers.

## Features

- **Audacious sound:** the 10-band equalizer (with presets) and all 11 effect plugins from the iOS port: Bitcrusher, Dynamic Range Compressor, Crossfade, Crystalizer, Echo, Channel Mixer, Sample Rate Converter, Silence Removal, Speed and Pitch, Extra Stereo, Voice Removal. Each plugin's settings screen is generated from its own `PreferencesWidget` declarations. Effects can be switched mid-song, including ones that change the sample rate.
- **Playback:** gapless between songs of the same format, crossfade, shuffle and repeat, a persistent playlist, network songs that play while they download, prefetch of the next network song, and Spectrum Analyzer / Blur Scope / VU Meter visualizations of what is being heard.
- **Formats:**
  - Native on every Android version: MP3, FLAC (including hi-res), WAV/AIFF, Ogg Vorbis, Ogg Opus.
  - Through Android's MediaCodec: AAC/M4A, plus whatever else the device decodes.
- **On This Device:** the system music library (after permission) and the app's own music folder (`Android/data/<app>/files/Music`, which a computer can fill over USB). Import copies files in.
- **Subsonic:** API key, salted-token or hex-password sign-in. Artists, Albums, paged Songs, Recently Added, Playlists, Starred, Random Songs, search, and scrobbling.
- **DAAP:** mDNS discovery (its own browser, since Android only exposes TXT records from 5.0) or an address. Password-protected shares, Songs/Artists/Albums/Genres/Playlists, search. iTunes 7+/Music.app shares are not supported (proprietary signature), as in ElectronPlayer.
- **Music Assistant:**
  - Sign-in by username and password, Home Assistant in the browser (returning to a loopback listener), or a long-lived token.
  - Browse and search, Play Now / Play Next / Add to Queue on any player, and that player's transport and volume.
- **Music Assistant speaker (Sendspin):** a Kotlin port of the iOS port's Swift client (itself from sendspin-js 5.0.0):
  - Noise_KKpsk2 transport, dynamic-PIN and Pairing-PSK pairing, re-handshake, Kalman clock sync.
  - PCM, FLAC and Opus streams, played through the Audacious engine so the effects, EQ and visualizations apply.
  - Metadata, volume and mute; the transport controls drive the Music Assistant group.
- **System:** a foreground service with a media notification, lock-screen and media-button controls (MediaSession on 5.0+, RemoteControlClient on 4.x), audio focus, and a pause when headphones are unplugged. Secrets are encrypted with the Android Keystore on 6.0+.

## How it is built

| Part | Where | Notes |
|---|---|---|
| Audacious core and plugins | `app/src/main/cpp/audcore` | The iOS port's `AudCore`, unchanged (libaudcore pieces, GLib shims, upstream plugins) |
| libsamplerate | `app/src/main/cpp/samplerate` | Unchanged |
| Output | `jni/output.cc` | OpenSL ES buffer queue, 16-bit, fed by AudCore's lock-free ring. Falls back to 48 kHz with a resampler when the device refuses a rate (hi-res on old Android) |
| Decoders | `jni/decoders.cc` | dr_flac, dr_mp3, dr_wav, stb_vorbis, and an Ogg Opus reader over libopus. They read a byte source that blocks while a song downloads |
| Sendspin audio | `jni/live.cc` | FLAC chunks behind `codec_header`, Opus packets |
| JNI | `jni/bridge.cc`, `engine/Native.kt` | |
| Engine | `engine/PlaybackEngine.kt`, `Decoders.kt`, `AudioOutput.kt` | Port of `PlaybackEngine.swift`: decoding thread, gapless, live mode with timed writes, prefetch |
| Sendspin | `sendspin/` | X25519 (TweetNaCl arithmetic), ChaCha20-Poly1305 and AES-GCM in Kotlin (the platform has neither before Android 9/11), Noise, CPace/Elligator2, session, connection, client |
| Libraries | `library/` | Subsonic, DAAP/DMAP/mDNS, Music Assistant, this device |
| UI | `ui/` | Framework views written in code; no AndroidX, which no longer supports API 16 |

Toolchain: AGP 8.5.2, Gradle 8.9 (wrapper), Kotlin 2.0.21, NDK r23c (the last NDK that targets API 16), CMake 3.22.1, compileSdk/targetSdk 35. Networking is OkHttp 3.12.13, the last version that runs on Android 4.x. TLS 1.2 is switched on for 4.1–4.4, and Let's Encrypt's roots are bundled for devices before 7.1.

## Build

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # minified; signed with the debug key so it installs as is
./gradlew :app:testDebugUnitTest    # Sendspin crypto against sendspin-js 5.0.0 vectors
```

One APK covers armeabi-v7a, arm64-v8a, x86 and x86_64 (7.4 MB release). For Play Store publishing, set up your own signing config in `app/build.gradle.kts`.

## What was verified

- **Unit tests (12):**
  - X25519, ChaCha20-Poly1305 and AES-GCM against the RFC 7748, RFC 8439 and NIST vectors.
  - The Noise handshake and transport, Elligator2, CPace (shares, ISK, tags, wrapped PSK), PIN derivation, PSK ids and the pairing token, byte for byte against the sendspin-js 5.0.0 values the iOS port generated.
  - PSK persistence and the activation rules.
- **Android 15 (API 35 emulator) and Android 4.1.2 (API 16 emulator)**, against local mock servers:
  - Every local format plays: on 4.1, AAC through the old MediaCodec API, 96 kHz FLAC through the 48 kHz fallback, Opus natively. Seeking works.
  - EQ and effects, including switching the Sample Rate Converter off mid-song (48 → 96 kHz without stopping).
  - Subsonic sign-in, browsing, streaming while downloading, scrobbling and prefetch.
  - DAAP password prompt, sign-in, playlists and streaming.
  - The full Sendspin flow on both versions and both routes: PIN pairing, reconnecting with the minted key, clock sync against a server clock 5 s ahead, a timed PCM stream heard through the engine with metadata, volume, a re-handshake, and a controller command from the media keys. The mock server is an independent Node implementation using Node's own X25519 and ChaCha20-Poly1305.
  - Release (R8) build on both.

The mock servers are in `tools/` (Python and Node, standard library only). From an emulator, the host is `10.0.2.2`:

```bash
python3 tools/mock_subsonic.py <music dir> 4533          # user "test", password "secret"
python3 tools/mock_daap.py <music dir> 3689 sesame       # share password "sesame"
node tools/mock_sendspin.mjs 8927 pin.txt                # write the PIN the app shows to pin.txt
```

For the Sendspin proxy route, sign in to Music Assistant with server `10.0.2.2:8927` and token `test-token`.

**Not verified:** a real Music Assistant server (as with the iOS port: how it drives pairing, its FLAC/Opus framing, and real multi-room sync), a real Navidrome or OwnTone server, the Home Assistant browser sign-in, real hardware (only emulators), and Bluetooth/headset routing.

## Known limitations

- On Android 4.x the tag reader cannot read Opus tags, so those files show their file name.
- ALAC needs a device whose MediaCodec decodes it (Android 10+). Subsonic transcodes formats the app cannot play (WMA, APE, ...) to MP3.
- Not ported, as in the iOS port: Sendspin's static-PIN method, its artwork and visualizer roles, Audacious input/output/transport plugins, LADSPA, bs2b, ReplayGain, cue sheets and playlist files.

## Licenses

- Audacious, its plugins and libsamplerate: BSD-2-Clause (see `app/src/main/cpp/audcore/COPYING.*` and `samplerate/COPYING`).
- libopus: BSD-3-Clause (`third_party/opus/COPYING`).
- dr_libs and stb_vorbis: public domain / MIT-0.
- The Music Assistant sign-in flow and the Sendspin client are adapted (by way of ElectronPlayer and the iOS port) from the Music Assistant mobile app and sendspin-js, both Apache-2.0.
- See `NOTICE.ios-reference` for the iOS port's notice, which this port inherits.
