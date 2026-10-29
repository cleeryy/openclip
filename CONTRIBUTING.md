# Contributing to Calque

Thanks for helping. This project is a clean-room GPL-3.0-or-later painting
studio for Android tablets: the engine is portable C++17, the app is Kotlin
and Jetpack Compose. Read [AGENTS.md](AGENTS.md) for the repository
conventions — this page covers the workflow around them.

## Ways to contribute

- Bug reports and feature requests: use the GitHub issue templates.
- Documentation, translations (EN/FR), test fixtures and engine tests are
  as valuable as new features.
- For anything touching the engine public API or the JNI boundary, open an
  issue first — those changes need a documented contract before code.

## Workflow: issue → branch → pull request

1. **Pick or open an issue.** Describe the behaviour, the expected result
   and how you verified it (device/AVD, steps). For parity work, reference
   the matching tranche spec in `docs/`.
2. **Branch from `master`.** Use a short prefix:
   `feat/`, `fix/`, `docs/`, `perf/`, `test/`, `chore/`
   (e.g. `fix/layer-mask-undo`).
3. **Keep the change focused.** One logical change per pull request; no
   drive-by reformatting of untouched files.
4. **Run the gates locally** (see below) before opening the PR.
5. **Open the pull request** against `master` and fill the checklist in
   `.github/pull_request_template.md`. Link the issue with `Refs #N` or
   `Closes #N`.
6. **Review.** A maintainer reviews for correctness, clean-room hygiene and
   gate evidence. Respond to comments with follow-up commits (no
   force-push during review unless asked).

## Mandatory gates

| Gate | Command | When |
|---|---|---|
| Engine tests + build | `cd engine && make && make test` | every change |
| JNI symbol contract | `bash scripts/check_jni_symbols.sh --source-mode` | every change touching `app/src/main/cpp`, `EngineHost`, or native signatures |
| Kotlin compile (main + androidTest) | `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin` | every app change |
| NDK build | `./gradlew :app:externalNativeBuildDebug` | every native change |
| Everything above in one shot | `bash scripts/prepush.sh` (`--fast` = engine only) | before every push |
| Emulator conformance | `bash scripts/emutest.sh` | before closing a milestone or a behaviour-heavy PR |

Rules:

- `make test` must stay green; the harness is a headerless assert suite in
  `engine/tests/`, one test file per public header. New header ⇒ new tests.
- The **emulator suite is a local gate only**. It is intentionally absent
  from GitHub CI (too slow and flaky there); do not add an emulator job.
  On the shared 8 GB dev box, never run heavy Gradle builds in parallel
  with the emulator.
- `scripts/emutest.sh` must end with `OK (N tests)`; report `N` and the
  screenshot path when a visual change is involved. Screenshots stay in
  `/tmp/opencode/` — never commit them.
- If a gate cannot run (no KVM, no SDK), say so explicitly in the PR and
  list what a maintainer must run.

## Commit conventions

- Imperative subject line, ≤ 72 characters, no trailing period:
  `Add layer flags to the T5 panel`, `Fix mask undo on grouped layers`.
- Optional body: what changed, why, and the evidence (`engine 237/237`,
  `emutest OK (248)`, commit/run references). Keep it factual.
- One logical change per commit; never include build outputs, APKs, `.xapk`
  files, screenshots or extracted third-party content.
- Reference the issue in the body: `Refs #12`.
- Do not rewrite published history on `master`.

## Clean-room rule (non-negotiable)

- Never copy code, strings, icons, brushes, palettes, sample files or
  binary assets from any proprietary painting application. Do not commit
  commercial package files or anything extracted from them; `.gitignore`
  already excludes `*.apk`, `*.xapk`, `extracted/`, `apk-dec/`.
- Implement from the documented behaviour, not from decompiled resources.
  Test fixtures are generated in code, never committed as third-party
  binaries.
- Interchange uses open formats only: OpenRaster (native), PSD
  (interchange), MyPaint `.myb`, glTF, GIF/APNG/WebM/PNG/JPEG/TIFF, EPUB,
  PDF.
- New third-party code: single-file vendored headers only, permissive
  license, logged in `engine/third_party/README.md` with origin URL and
  license text. No new C++ framework dependencies.
- UI vocabulary is ours; do not reproduce a vendor's menu labels.

## Code style

**Engine (C++17, `engine/`)**

- Public API only grows through `engine/include/openclip/`; app code must
  not include engine internals.
- No JNI or Android includes in `engine/` — it must build with plain
  `make` on a Linux host.
- Match the existing style: 4-space indent, `namespace openclip`,
  `#ifndef` guards, `std::` qualification, no exceptions across the public
  API (return `bool` + `std::string& error`), deterministic results
  (fixed seeds for procedural effects).
- Keep the per-header test rule.

**JNI boundary**

- The contract is frozen in [ARCHITECTURE.md](ARCHITECTURE.md). Document
  any new symbol there **before** writing the code, then add it to the
  shim (`app/src/main/cpp/ExternalC.cpp`) and `EngineHost`.
- Prefer explicit, typed entry points over generic operation dispatchers.
- `scripts/check_jni_symbols.sh` must pass in both source and built-library
  modes.

**App (Kotlin, `app/`)**

- Kotlin 2.x, Jetpack Compose, 4-space indent, one top-level UI concern per
  file (`ui/` prefix groups: `T1*`, `V2*`, `V3*`…).
- All user-visible strings live in `res/values/strings.xml` and
  `res/values-fr/strings.xml` — never hardcoded in composables.
- Engine calls stay on the render thread; UI state changes go through the
  existing document/session helpers.
- Persisted formats are versioned JSON with tolerant readers (see
  `color-sets.json`, `project.json` v2).
- Never pin the JDK inside the repository (`org.gradle.java.home` lives in
  `~/.gradle/gradle.properties`).

## Tests and evidence

- Engine: assert-based, fast, deterministic; test the contract, not the
  implementation details.
- App: instrumented tests in `app/src/androidTest/` drive real UI. Name
  them after the behaviour (`twoFingerTapUndoesStroke`). Avoid fixed
  `Thread.sleep`; poll with a bounded timeout.
- Performance changes must include before/after measurements (APK size,
  `dumpsys meminfo`, frame timings) and a revert if the gain is not real.
- A behaviour change without a test is a review blocker unless it is purely
  visual and covered by the emulator suite.

## Review checklist (what maintainers look for)

- Scope matches the linked issue; no unrelated refactors.
- Gates green, with the exact numbers quoted.
- Clean-room compliance: no vendor assets, strings or formats.
- JNI/architecture documents updated before the code that needs them.
- Strings in EN and FR; no locale-specific logic in the engine.
- No secrets, tokens, personal paths or telemetry added.

## License of contributions

By submitting a pull request you agree that your contribution is licensed
under GPL-3.0-or-later, the license of this repository.
