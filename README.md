# OpenClip

Free and open-source painting application for Android tablets, with a
clean-room reimplementation of the feature set users expect from a
professional illustration app (pressure-sensitive brushes, layers and
blend modes, filters, OpenRaster/PSD support). Inspired by workflow
conventions popularized by Clip Studio Paint, Krita, MyPaint and others.

**No proprietary code or assets are included or reverse-engineered.**
Feature conventions are reimplemented from scratch under GPL-3.0.

## Stack

- `engine/` — C++17 raster/painting engine (tiled canvas, layers,
  blend modes, brush engine, undo, OpenRaster codec). No Android
  dependencies; builds and tests on any host with a C++17 compiler.
- `app/` — Android app, Kotlin + Jetpack Compose UI hosting the engine
  through a stable JNI boundary. Stylus/pressure-first input pipeline.
- Native build on Android: NDK + CMake; engine shared core compiled
  into the app. CI builds/releases both.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

## Build

- Engine (host): `cd engine && make && make test`
- App: requires Android SDK 34+ / NDK; see `.github/workflows/`.

## Roadmap

See [docs/ROADMAP.md](docs/ROADMAP.md) for the parity plan and
[docs/csp-analysis.md](docs/csp-analysis.md) for the feature map used to
scope it.
