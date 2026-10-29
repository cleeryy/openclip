# Security policy

## Supported versions

Calque is pre-1.0 software distributed as alpha pre-releases. Security
fixes land on `master` and are shipped in the next tagged alpha.

| Version | Supported |
|---|---|
| `0.2.0-alpha` (next tag) | yes |
| `0.1.0-alpha.3` | no (superseded) |
| `0.1.0-alpha.2` and earlier | no |

Only builds published on the project's GitHub Releases page are
supported; unofficial rebuilds and repackaged APKs are out of scope.

## Reporting a vulnerability

Please do **not** open a public issue for a security problem.

- Email: **clery.arqueferradou@gmail.com** (maintainer).
- Include: affected version or commit, Android version and device/AVD,
  reproduction steps, impact, and any proof-of-concept file you are
  comfortable sharing.
- Encrypt or omit sensitive data; never send private artwork or tokens.
- You should receive an acknowledgement within 7 days. The maintainer will
  confirm the finding, prepare a fix on a private branch when warranted,
  and coordinate disclosure with you. Credit is given in the release notes
  unless you prefer to stay anonymous.

Please allow a reasonable remediation window (target: 90 days) before
publishing details.

## Scope

Calque is a local-first Android application. Useful context for triage:

- **No telemetry, no analytics, no third-party crash reporting, no ads,
  no billing.** There is no account system and no vendor backend.
- **All document data stays on device** unless the user explicitly exports
  it or configures the optional WebDAV sync.
- **WebDAV sync** (`M10`) uses HTTP basic auth against a server the user
  provides. Cleartext HTTP is restricted to `127.0.0.1` / `localhost`
  through `network_security_config`; any remote endpoint must be HTTPS.
  Credentials are held in memory for the operation only.
- **Imports are untrusted input.** OpenRaster, PSD, `.clip` (read-only),
  TIFF, SVG, PDF, glTF, `.myb`, `.grd`, `.abr`, EPUB and image files are
  parsed by in-house code. Crashes, hangs, out-of-bounds reads or memory
  exhaustion triggered by a crafted file are in scope — attach the smallest
  reproducer you can (generated files are preferred over private artwork).
- **ONNX Runtime** is bundled for on-device super-resolution. Model files
  shipped with the app are fixed; loading a model the user supplies is not
  a supported path.
- **Native code** (`libopenclip.so`) is reachable only through the frozen
  JNI surface documented in `ARCHITECTURE.md`; memory-safety issues in the
  engine are in scope.

Out of scope:

- Vulnerabilities that require a rooted device, a malicious ADB host, or a
  compromised OS.
- Denial of service from opening an intentionally huge document on a
  device with insufficient RAM (document parsing limits are bounded, but
  resource exhaustion on unsupported hardware is expected).
- Issues in third-party dependencies that have no exploitable path in this
  app — report them upstream, and tell us if Calque needs a pinned update.
- Social engineering, physical access, and lost/stolen devices.
- The repository's CI configuration or GitHub account (report to GitHub).

## Hardening already in place

- Debug-signed alpha APKs are published with a `.sha256` checksum; verify
  it before installing.
- Release builds enable R8 minification and resource shrinking, with keep
  rules limited to the JNI surface and ONNX Runtime.
- The JNI symbol contract is checked on every CI run and before every push
  (`scripts/check_jni_symbols.sh`).
- File parsers refuse malformed or hostile inputs explicitly rather than
  guessing; `.clip` import is read-only and never writes the format.
