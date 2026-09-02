# RetroTube

An Android video player with real, RetroArch-style CRT shaders — without needing to configure RetroArch or an emulator core just to watch a video with scanlines on it.

Built for the retro handheld crowd: pick a preset, optionally turn on screen curvature, optionally force a low internal resolution so scanlines/mask actually read as chunky and authentic instead of a faint HD-video filter, and play local video files organized in a real folder-tree library.

## Features

- **7 shader presets across 3 device-cost tiers**, each based on a real RetroArch preset family:
  - Low: `zfast-crt`, `phosphor-mono`, `deconverge`
  - Medium: `crt-easymode`, `vhs`
  - High: `crt-guest-advanced`, `ntsc`
- **Curvature** as an independent toggle — not baked into any one preset, so any preset can be combined with or without screen warp.
- **Resolution downscale pre-pass** with integer-scale snapping — forces the CRT math to run against a fixed low resolution (240p/480p/720p) snapped to a clean multiple of your actual screen height, regardless of the source video's native resolution.
- **Aspect ratio modes** — fit (letterbox), stretch, or crop.
- **Folder-tree library** — point it at one or more folders (via Android's Storage Access Framework), browse them like real folders (not a flattened dump), with thumbnails and resume-playback-position tracking.
- **Global default + per-file override** settings — set a default look for the whole library, or override it per file via the ⋮ menu on any video row.
- **A/B compare** — a "SHOW RAW" toggle built into the actual player controls, to instantly flip between the shaded and raw video.

## Building

Requires the Android SDK (compileSdk 36) and JDK 17.

```bash
./gradlew :app:assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Installing

Grab the latest APK from the [Releases](../../releases) page and sideload it — this app is not on the Play Store.

## License

GPL-3.0. See [LICENSE](LICENSE). Contributions and forks are welcome; derivative works distributed publicly must also stay open source under the same license.
