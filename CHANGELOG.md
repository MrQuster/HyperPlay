# Changelog

All notable changes to **HyperPlay** are documented in this file.
Format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.2.0] - 2026-10-22

### Added
- **Playback & Dropout Event Logger** (`PlaybackEventLogger`): 300-event ring buffer with millisecond timestamps, automatic dropout incident detection (`AUDIO_FOCUS`, `BECOMING_NOISY`, `BUFFER_UNDERRUN`, `ROUTE_CHANGE`, `MAIN_THREAD_JANK`, `PLAYER_ERROR`), 5-second pre-incident context snapshots, `MainThreadWatchdog`, and a 5-minute continuous dropout stress test in the System tab.
- Deferred background `MediaStore` scanning during active playback and track buffering to prevent storage I/O contention.

### Fixed
- Eliminated 1-second volume dropouts during track transitions by removing artificial single-player crossfade volume dips and using sample-level 4 ms anti-click ramps in `HyperPlayDspAudioProcessor`.
- Eliminated main-thread stalls on large playback queues by rebuilding queue UI lists only on `onTimelineChanged` and persisting lightweight position snapshots (`savePositionOnlySnapshot`).
- Added route and profile deduplication in `AudioEffectsController` to prevent redundant `AudioEffect` JNI calls on spurious `AudioDeviceCallback` events.
- Deferred automatic safe-mode audio output fallback to track boundaries so playback is never interrupted mid-song.

## [1.1.0] - 2026-10-21

### Added
- **Audio Diagnostics (A/B & Real-Time Telemetry)** in the System tab: live Peak/RMS dBFS meters (input and output), Auto-Headroom and Limiter Gain Reduction readouts, clipped sample and underrun counters, 13 live A/B pipeline switches, Pure Mode (Bit-Perfect Bypass), and built-in reference signal generators (1 kHz Sine and Pink Noise at -12 dBFS).
- Automatic headroom gain compensation and 16-bit TPDF dithering in `HyperPlayDspAudioProcessor`.

### Fixed
- Fixed audio crackling and distortion on bass peaks by replacing the discontinuous limiter threshold with a $C^1$-continuous soft-knee curve (`0.75 + 0.25 * tanh((|x| - 0.75) / 0.25)`) and lookahead envelope.
- Removed triple gain stacking across `HyperPlayDspAudioProcessor`, hardware `Equalizer`, and `DynamicsProcessing`.
- Bound audio session effects synchronously before playback start to eliminate startup clicks.

## [1.0.0] - 2026-10-21

### Added
- Initial public release of **HyperPlay** (`io.github.mrquster.hyperplay`) by **MrQuster**.
- Media3 `ExoPlayer` engine inside `MediaSessionService` with gapless playback, audio focus handling, Bluetooth auto-resume, sleep timer, ReplayGain normalization, and queue/position persistence.
- Custom 64-bit `HyperPlayDspAudioProcessor` (Biquad 95 Hz low-shelf bass boost, Mid/Side stereo widener, preamp, L/R balance, mono mix, and soft limiter).
- Room SQLite library index (Tracks, Favorites, Albums, Artists, Folders, Playlists, Genres, Recent & Most Played), diacritic-insensitive search, and M3U import/export.
- Liquid Glass UI with `Haze` real-time backdrop blur, draggable AGSL lens navigation bar, floating mini-player pill, and full-screen Now Playing with dynamic `Palette` colors and synced `.lrc` lyrics.
- Custom `HyperPlayMediaNotificationProvider` supporting Xiaomi HyperOS Super Island (`miui.focus.param`), Android 16+ Live Updates, and standard Media3 notifications.
