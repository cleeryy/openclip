# AGENTS.md — OpenClip working conventions

- Language: C++17 in `engine/` (no JNI/Android includes there), Kotlin
  2.x + Jetpack Compose in `app/`.
- Engine public API only grows through `engine/include/openclip/` —
  app code must not reach inside engine internals.
- JNI boundary is frozen in `ARCHITECTURE.md`; new engine features are
  exposed as explicit additions documented there first.
- Clean-room rule: never copy strings, binaries, formats or assets from
  Clip Studio Paint / Celsys. Open formats only (OpenRaster, MyPaint,
  PSD interchange, glTF). Commercial `.xapk/.apk` files and extracted
  content stay out of git (see `.gitignore`).
- Tests: engine tests are headerless assert harness in `engine/tests/`
  (`make test` must stay green). Each header comes with its test file.
- No external C++ frameworks; single-file vendored headers only, logged
  in `engine/third_party/README.md` with license and origin URL.
- Commits: concise imperative subject lines; never include binaries.
- CI must pass before pushes: engine Linux build + tests, Android debug
  APK build, JNI symbols check.
