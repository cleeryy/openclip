# Pull request

## What and why

<!-- One or two sentences. Link the issue: Refs #N / Closes #N -->

Refs #

## Type of change

- [ ] Bug fix (`fix/`)
- [ ] Feature (`feat/`)
- [ ] Docs (`docs/`)
- [ ] Performance (`perf/`)
- [ ] Test / CI (`test/`, `chore/`)
- [ ] Engine public API or JNI change (documented in `ARCHITECTURE.md` **before** code)

## Gates (paste the exact verdicts)

- [ ] `cd engine && make && make test` — `___/___ tests passed`
- [ ] `bash scripts/prepush.sh` — `PREPUSH OK` (or `--fast` + reason)
- [ ] `bash scripts/check_jni_symbols.sh --source-mode` — passes (JNI/native changes)
- [ ] `bash scripts/emutest.sh` — `OK (___ tests)` (behaviour/UI changes)
- [ ] Not run: <!-- name the gate and why, e.g. "emutest: no KVM on this box" -->

## Clean-room compliance

- [ ] No code, strings, icons, palettes, brushes or assets copied from a proprietary tool
- [ ] No commercial package file, extracted content or screenshot committed (`.gitignore` respected)
- [ ] New third-party code is a single-file vendored header, permissive license, logged in `engine/third_party/README.md`
- [ ] New interchange format is open (ORA, PSD, MyPaint, glTF, GIF/APNG/WebM/PNG/JPEG/TIFF, EPUB, PDF)

## Engine / architecture

- [ ] Public API only grows through `engine/include/openclip/`
- [ ] No JNI or Android include added under `engine/`
- [ ] New or changed JNI symbol documented in `ARCHITECTURE.md` first, shim + `EngineHost` updated
- [ ] New engine header has a matching test file in `engine/tests/`
- [ ] Deterministic behaviour (fixed seeds for procedural effects)

## App

- [ ] User-visible strings added to both `res/values/strings.xml` and `res/values-fr/strings.xml`
- [ ] Persisted JSON stays versioned and tolerant of older files
- [ ] No JDK / SDK path pinned inside the repository
- [ ] Tablet (1920×1200@240) and phone layouts both checked when UI changed

## Docs

- [ ] `docs/TASKS.md` updated with the tranche line, test counts and proof path
- [ ] `docs/ROADMAP.md` / `CHANGELOG.md` updated when user-visible behaviour changed
- [ ] Relative links in touched Markdown resolve to existing files

## Notes for reviewers

<!-- Trade-offs, deferred items, screenshots path under /tmp/opencode (not committed) -->
